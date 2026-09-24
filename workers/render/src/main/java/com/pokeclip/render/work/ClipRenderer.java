package com.pokeclip.render.work;

import com.pokeclip.render.job.ErrorCode;
import com.pokeclip.render.job.JobEnvelope;
import com.pokeclip.render.job.RenderFailure;
import com.pokeclip.render.job.SourceSegment;
import com.pokeclip.render.media.CropGeometry;
import com.pokeclip.render.media.Loudness;
import com.pokeclip.render.media.MediaInfo;
import com.pokeclip.render.media.MediaProbe;
import com.pokeclip.render.media.ProcessRunner;
import com.pokeclip.render.media.RenderCommands;
import com.pokeclip.render.media.SrtWriter;
import com.pokeclip.render.recipe.Recipe;
import com.pokeclip.render.recipe.Recipe.AudioTrack;
import com.pokeclip.render.recipe.Recipe.Output;
import com.pokeclip.render.recipe.Recipe.SubtitleSegment;
import com.pokeclip.render.storage.S3Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 주문서 하나로 산출물 파일을 만든다. 받기 → 소스 검사 → 잇기 → 소리 재기 → output마다 렌더.
 * 올리기·보고는 부른 쪽({@link JobProcessor}) 몫이다. 여기는 파일만 만든다.
 */
public class ClipRenderer {

    private static final Logger log = LoggerFactory.getLogger(ClipRenderer.class);

    /** 계약1 3절: 구간·길이 대조의 허용 오차. 조각 길이 실측이 ms 단위로 흔들리는 것을 받아 준다. */
    static final long TOLERANCE_MS = 100;

    private final S3Store store;
    private final MediaProbe probe;
    private final ProcessRunner runner;
    private final ObjectMapper mapper;
    private final String ffmpeg;
    private final String fontsDir;

    public ClipRenderer(S3Store store, MediaProbe probe, ProcessRunner runner, ObjectMapper mapper, String ffmpeg,
                        String fontsDir) {
        this.store = store;
        this.probe = probe;
        this.runner = runner;
        this.mapper = mapper;
        this.ffmpeg = ffmpeg;
        this.fontsDir = fontsDir;
    }

    /** 만든 파일 하나. {@code kind}는 계약1 result의 video|srt. */
    public record Produced(String outputId, String kind, Path file) {
    }

    /** 진행 알림. 부른 쪽이 5초 간격으로 걸러 보고한다. */
    @FunctionalInterface
    public interface Progress {
        void report(int percent, String stage);
    }

    public List<Produced> render(JobEnvelope job, Path dir, Instant deadline, Progress progress) {
        Recipe recipe = job.recipe();
        List<SourceSegment> sources = job.sources();
        checkRange(sources, recipe.cut());

        List<String> names = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            SourceSegment s = sources.get(i);
            String name = String.format("seg_%06d.m4s", i);
            store.download(s.bucket(), s.s3Key(), dir.resolve(name));
            names.add(name);
        }
        progress.report(10, "download");

        List<MediaInfo> infos = checkSources(sources, names, dir, deadline, recipe.tracks());
        MediaInfo info = infos.getFirst();
        write(dir.resolve(RenderCommands.CONCAT_LIST), RenderCommands.concatList(names, spacing(infos)));
        runner.run(RenderCommands.concat(ffmpeg), dir, deadline);
        progress.report(20, "concat");

        long offsetMs = recipe.cut().inAtMs() - sources.getFirst().sourceStartAtMs();
        long durationMs = recipe.cut().durationMs();
        ProcessRunner.Result measured = runner.run(
                RenderCommands.measureLoudness(ffmpeg, offsetMs, durationMs, recipe.tracks()), dir, deadline);
        String loudnessFix = Loudness.correctFilter(measured.stderr(), mapper);
        if (loudnessFix == null) {
            log.info("render.loudness_skipped jobId={} reason=silent", job.jobId());
        }
        progress.report(30, "loudness");

        List<SubtitleSegment> subtitles = recipe.subtitles() == null ? List.of()
                : SrtWriter.clipped(recipe.subtitles().segments(), recipe.cut());
        boolean burn = recipe.subtitles() != null && recipe.subtitles().mode().burns() && !subtitles.isEmpty();
        boolean cc = recipe.subtitles() != null && recipe.subtitles().mode().cc() && !subtitles.isEmpty();
        String srt = SrtWriter.render(subtitles);
        if (burn) {
            write(dir.resolve(RenderCommands.BURN_SRT), srt);
        }

        List<Produced> produced = new ArrayList<>();
        List<Output> outputs = recipe.outputs();
        for (int i = 0; i < outputs.size(); i++) {
            Output output = outputs.get(i);
            CropGeometry.Pixels crop = CropGeometry.toPixels(output.crop(), output.aspect(), info.width(),
                    info.height());
            String file = output.outputId() + ".mp4";
            runner.run(RenderCommands.render(ffmpeg, offsetMs, durationMs, crop, output.aspect(), recipe.tracks(),
                    loudnessFix, burn, fontsDir, file), dir, deadline);
            produced.add(new Produced(output.outputId(), "video", dir.resolve(file)));
            if (cc) {
                String srtFile = output.outputId() + ".srt";
                write(dir.resolve(srtFile), srt);
                produced.add(new Produced(output.outputId(), "srt", dir.resolve(srtFile)));
            }
            progress.report(30 + 60 * (i + 1) / outputs.size(), "encode");
        }
        return produced;
    }

    /**
     * 계약1 3절 {@code SOURCE_RANGE}: 조각들이 {@code [in, out)}을 다 덮어야 한다(±100ms). 모자라면 조용히 짧은
     * 클립을 내지 않고 거부한다. 조각 사이가 비어도 거부한다. 잇는 순간 뒤쪽 화면이 앞으로 당겨져 컷이 어긋난다.
     */
    static void checkRange(List<SourceSegment> sources, Recipe.Cut cut) {
        if (sources.isEmpty()) {
            throw RenderFailure.permanent(ErrorCode.SOURCE_MISSING, "영상 조각이 주문서에 없다");
        }
        if (sources.getFirst().sourceStartAtMs() > cut.inAtMs() + TOLERANCE_MS
                || sources.getLast().endAtMs() < cut.outAtMs() - TOLERANCE_MS) {
            throw RenderFailure.permanent(ErrorCode.SOURCE_RANGE, "영상 조각이 자를 구간을 다 덮지 않는다");
        }
        for (int i = 1; i < sources.size(); i++) {
            long gap = sources.get(i).sourceStartAtMs() - sources.get(i - 1).endAtMs();
            if (Math.abs(gap) > TOLERANCE_MS * 5) {
                throw RenderFailure.permanent(ErrorCode.SOURCE_RANGE, "영상 조각 사이가 끊겨 있다");
            }
        }
    }

    /**
     * 계약1 3절 {@code SOURCE_MISMATCH}·{@code SOURCE_MISSING}: 조각마다 실측이 주문서와 맞는지 본다.
     * 해상도가 조각마다 다르면 한 파일로 못 잇는다. 고른 트랙 번호가 소리 스트림 수를 넘으면 그 트랙이 없는 것이다. 
     * 조용히 빼고 섞지 않는다(계약6 2절).
     */
    private List<MediaInfo> checkSources(List<SourceSegment> sources, List<String> names, Path dir, Instant deadline,
                                   List<AudioTrack> tracks) {
        List<MediaInfo> infos = new ArrayList<>();
        MediaInfo first = null;
        for (int i = 0; i < sources.size(); i++) {
            MediaInfo info = probe.probe(dir.resolve(names.get(i)), dir, deadline);
            infos.add(info);
            if (Math.abs(info.videoDurationMs() - sources.get(i).durationMs()) > TOLERANCE_MS) {
                log.warn("render.source_mismatch seq={} expectedMs={} probedMs={}", sources.get(i).seq(),
                        sources.get(i).durationMs(), info.videoDurationMs());
                throw RenderFailure.permanent(ErrorCode.SOURCE_MISMATCH, "영상 조각 길이가 기록과 다르다");
            }
            if (first == null) {
                first = info;
            } else if (info.width() != first.width() || info.height() != first.height()) {
                throw RenderFailure.permanent(ErrorCode.SOURCE_MISMATCH, "방송 중에 화면 크기가 바뀌었다");
            }
        }
        if (first.width() <= 0 || first.height() <= 0) {
            throw RenderFailure.permanent(ErrorCode.SOURCE_MISMATCH, "영상 조각에 화면이 없다");
        }
        int needed = tracks.stream().mapToInt(AudioTrack::trackId).max().orElse(0) + 1;
        if (first.audioStreams() < needed) {
            throw RenderFailure.permanent(ErrorCode.SOURCE_MISSING,
                    "고른 오디오 트랙이 방송에 없다(방송 소리 트랙 " + first.audioStreams() + "개)");
        }
        return infos;
    }

    /**
     * 조각을 잇는 간격. 앞 조각의 소리가 끝나는 자리에 다음 조각의 소리 시작을 맞춘다({@link RenderCommands#concatList}).
     * 간격 = 앞 조각 소리 끝 − 다음 조각 소리 시작(둘 다 각 파일 첫 시각 기준).
     */
    static List<Long> spacing(List<MediaInfo> infos) {
        List<Long> spacing = new ArrayList<>();
        for (int i = 0; i < infos.size() - 1; i++) {
            spacing.add(infos.get(i).audioEndUs() - infos.get(i + 1).audioLeadUs());
        }
        return spacing;
    }

    private static void write(Path file, String text) {
        try {
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
