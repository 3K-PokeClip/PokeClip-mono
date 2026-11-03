package com.pokeclip.render.work;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pokeclip.render.job.EnvelopeParser;
import com.pokeclip.render.job.JobEnvelope;
import com.pokeclip.render.media.MediaProbe;
import com.pokeclip.render.media.ProcessRunner;
import com.pokeclip.render.media.RenderCommands;
import com.pokeclip.render.media.SegmentTiming;
import com.pokeclip.render.recipe.Recipe.AudioTrack;
import com.pokeclip.render.recipe.Recipe.SubtitleSegment;
import com.pokeclip.render.storage.S3Store;
import com.pokeclip.render.support.DirectSegments;
import com.pokeclip.render.support.DirectSegments.Recording;
import com.pokeclip.render.support.Ffmpeg;
import com.pokeclip.render.support.Fixtures;
import com.pokeclip.render.support.PlanOrder;
import com.pokeclip.render.support.RecordingRunner;
import com.pokeclip.render.work.ClipRenderer.Produced;
import com.pokeclip.render.work.RenderTimeline.VideoCut;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * 새 꼴 주문의 자막(PDT 설계 r7 7.6 · 16.2 P18 · P20): 계획 자막 끝점 → {@link RenderTimeline} 결과 µs → SRT(CC) · 번인 파일.
 * 이 기계의 실제 ffmpeg 로 A′ 전 경로를 돌린다. S3 만 가짜다.
 *
 * <p>번인은 libass 가 있을 때만 실제로 태운다(기존 관례 — 없으면 CC 만 내거나, 번인 파일과 명령만 보고 최종 단계는 돌리지 않는다).
 *
 * <p>자료는 설계 5.1 의 꼴이다: A = 11(녹화 sa) · B = 20(끊긴 이웃 — 새 녹화 sb) · C = 21(B 에 이어짐). 자막 0 은 A 에서 B 로
 * 틈을 걸치고, 자막 1 은 A 뒤 틈 안에 통째로 있어 결과 길이가 0 이다.
 */
class JoinedSubtitleTest {

    /** 자막 0 시작 — 시작점(1.0초)에서 16프레임 뒤(결과 533.33ms · ms 경계가 아닌 프레임). */
    private static final long START_US = 1_533_333;
    /** 자막 0 끝 — B 조각 머리에서 16프레임 뒤(결과 3,533.33ms). */
    private static final long END_US = 533_333;
    /** ms 안쪽 자막 시작 — 조각 11 머리에서 2,000,100µs(300µs 길이라 결과에서도 한 ms 안에 든다). */
    private static final long SUB_MS_START_US = 2_000_100;

    @TempDir
    static Path fixtures;
    private static final Map<Long, Path> FILES = new HashMap<>();
    private static boolean burn;

    @TempDir
    Path dir;

    private final RecordingRunner runner = new RecordingRunner();
    private final S3Store store = mock(S3Store.class);
    private ListAppender<ILoggingEvent> logs;

    @BeforeAll
    static void segments() {
        assumeThat(Ffmpeg.available()).as("ffmpeg 필요").isTrue();
        burn = Ffmpeg.canBurnSubtitles();
        Recording sa = DirectSegments.write(fixtures, "sa", "testsrc2=size=160x90:rate=30", 8, -1, 11, 4);
        Recording sb = DirectSegments.write(fixtures, "sb", "testsrc=size=160x90:rate=30", 8, -1, 31, 4);
        FILES.put(10L, sa.pieces().get(0));
        FILES.put(11L, sa.pieces().get(1));
        FILES.put(20L, sb.pieces().get(0));
        FILES.put(21L, sb.pieces().get(1));
    }

    @BeforeEach
    void setUp() {
        doAnswer(inv -> {
            String key = inv.getArgument(1);
            long seq = Long.parseLong(key.substring(key.lastIndexOf('_') + 1, key.lastIndexOf('.')));
            Files.copy(FILES.get(seq), inv.<Path>getArgument(2));
            return null;
        }).when(store).download(anyString(), anyString(), any(), any());
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(JoinedRenderer.class)).addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(JoinedRenderer.class)).detachAppender(logs);
    }

    /**
     * 설계 5.1 자막 예(P18): 틈을 걸친 자막은 남고, 틈 안 통째 자막은 결과 길이 0 이라 그것만 빠진다(주문은 성공). 남은 자막이
     * 덮는 결과 프레임(번인이 보는 ms 로 내린 프레임 시각이 [시작, 끝) 안)은 계획이 고른 프레임 그대로다(P20 — 배치표와 그래프가
     * 같은 값).
     */
    @Test
    void 설계_5_1_자막_예는_틈_안_자막만_빼고_계획_프레임을_덮는다() throws IOException {
        ObjectNode envelope = example().json();
        if (!burn) {
            ((ObjectNode) envelope.get("recipe").get("subtitles")).put("mode", "CC_ONLY");
        }

        Run run = render(envelope, false);

        assertThat(run.files()).extracting(Produced::kind).containsExactly("video", "srt");
        List<SubtitleSegment> cues = cues(Files.readString(run.files().get(1).file()));
        assertThat(cues).extracting(SubtitleSegment::text).containsExactly("컷에 걸친 자막");
        assertThat(messages()).contains("render.subtitle_dropped jobId=" + run.job().jobId() + " index=1 reason=empty");

        List<Long> frames = frameTicks(run.files().getFirst().file());
        assertThat(Arrays.stream(JoinedRenderer.resultFrameTicks(run.timeline(), run.timings())).boxed().toList())
                .as("ASS 격자를 고르는 프레임 시각 = 실제 결과 프레임").isEqualTo(frames);
        long first = run.resultTick(11, START_US);
        long last = run.resultTick(20, END_US);
        assertThat(frames).as("계획 프레임이 결과에 있다").contains(first, last);
        SubtitleSegment cue = cues.getFirst();
        assertThat(frames.stream().filter(t -> covers(cue, t)).toList()).as("자막이 덮는 결과 프레임")
                .containsExactlyElementsOf(frames.subList(frames.indexOf(first), frames.indexOf(last)));
    }

    /** 자리가 없는 자막(v1)은 결과 축 SRT 를 예전 모양으로 태운다. 태우는 파일 = CC 와 같은 목록(설계 7.6 끝). */
    @Test
    void 자리가_없는_번인_자막은_SRT_를_예전_모양으로_최종_단계에_건다() throws IOException {
        Run run = render(example().json(), true);

        String graph = finalGraph();
        assertThat(graph).contains(",subtitles=" + RenderCommands.BURN_SRT + ":force_style='");
        assertThat(Files.readString(dir.resolve(RenderCommands.BURN_SRT)))
                .isEqualTo(Files.readString(run.srt()));
        assertThat(cues(Files.readString(run.srt()))).hasSize(1);
    }

    /**
     * 자리가 있는 자막(v2)은 출력 해상도에 맞춘 ASS 를 출력마다 태운다. 쓴 ASS 를 다시 읽어 libass 판정(시작 ≤ 프레임 ms < 끝 ·
     * 프레임 ms 는 실수 곱셈 뒤 버림)으로 그리는 결과 프레임을 구하면, 계획이 고른 프레임 그대로다(P20 — 독립 기대값은 판독과
     * 계획 Δ 에서). 옛 꼴 규칙(끝 10ms 올림)이면 끝 다음 프레임(3,533.33ms)까지 그린다.
     */
    @Test
    void 자리가_있는_번인_자막은_출력마다_ASS_로_건다_계획_프레임만_덮는다() throws IOException {
        ObjectNode envelope = example().json();
        envelope.set("recipe", Fixtures.recipeV2("s1"));

        Run run = render(envelope, true);

        assertThat(finalGraph()).contains(",subtitles=o1.ass").doesNotContain("force_style");
        String ass = Files.readString(dir.resolve("o1.ass"));
        assertThat(ass).contains("PlayResX: 1080\nPlayResY: 1920\n");
        List<Long> frames = run.plannedFrameTicks();
        long first = run.resultTick(11, START_US);
        long last = run.resultTick(20, END_US);
        assertThat(drawn(ass, frames)).containsExactly(frames.subList(frames.indexOf(first), frames.indexOf(last)));
    }

    /** 계획에 자막이 없으면(clip 이 컷 밖이라 뺌) 레시피에 자막이 있어도 SRT · 번인을 내지 않는다(빈 파일 금지 · 계약1 3절). */
    @Test
    void 계획에_자막이_없으면_자막을_내지_않는다() {
        String body = order().segment(11, false, false).in(11, 1_000_000).out(11, 3_000_000).body();

        Run run = render((ObjectNode) Fixtures.MAPPER.readTree(body), true);

        assertThat(run.files()).extracting(Produced::kind).containsExactly("video");
        assertThat(finalGraph()).doesNotContain("subtitles=");
        assertThat(dir.resolve(RenderCommands.BURN_SRT)).doesNotExist();
    }

    /** 글자가 비었거나 공백뿐인 자막은 뺀다(옛 꼴과 같은 규칙 — 빈 줄을 태우거나 SRT 에 넣지 않는다). */
    @Test
    void 빈_글자_자막은_뺀다() {
        ObjectNode envelope = example().json();
        ((ObjectNode) envelope.get("recipe").get("subtitles").get("segments").get(0)).put("text", "  ");

        Run run = render(envelope, true);

        assertThat(run.files()).extracting(Produced::kind).containsExactly("video");
        assertThat(finalGraph()).doesNotContain("subtitles=");
    }

    /**
     * 결과 µs 길이는 있지만 ms 로 내리면 길이 0 인 자막은 CC 에서 빠지고, 빠진 것을 신호로 남긴다(설계 15절
     * {@code render.subtitle_dropped} — µs 단계의 {@code reason=empty} 와 가른다). 주문은 성공한다.
     */
    @Test
    void ms_로_내려_길이가_0_인_자막은_빼고_신호를_남긴다() throws IOException {
        ObjectNode envelope = order().segment(11, false, false).segment(20, true, true).segment(21, false, true)
                .in(11, 1_000_000).out(21, 2_000_000)
                .subtitleAtMedia(0, 11, START_US, 20, END_US)
                .subtitleAtMedia(1, 11, SUB_MS_START_US, 11, SUB_MS_START_US + 300)
                .lengthMs(9_000).json();
        ((ObjectNode) envelope.get("recipe").get("subtitles")).put("mode", "CC_ONLY");

        Run run = render(envelope, false);

        RenderTimeline.PlacedSubtitle placed = run.timeline().subtitles().stream().filter(p -> p.index() == 1)
                .findFirst().orElseThrow();
        assertThat(placed.endUs()).as("µs 단계는 통과한다").isGreaterThan(placed.startUs());
        assertThat(Math.floorDiv(placed.endUs(), 1_000)).as("ms 로 내리면 같은 값")
                .isEqualTo(Math.floorDiv(placed.startUs(), 1_000));
        assertThat(cues(Files.readString(run.srt()))).extracting(SubtitleSegment::text)
                .containsExactly("컷에 걸친 자막");
        assertThat(messages())
                .contains("render.subtitle_dropped jobId=" + run.job().jobId() + " index=1 reason=empty_ms");
    }

    // ---------------------------------------------------------------- 실행

    record Run(JobEnvelope job, List<Produced> files, RenderTimeline timeline, Map<Long, SegmentTiming> timings) {

        /** 계획이 결과에 남기는 프레임 시각(판독 PTS ∈ [startPts, endPts) + Δ · 오름차순). */
        List<Long> plannedFrameTicks() {
            List<Long> ticks = new ArrayList<>();
            for (VideoCut c : timeline.videoCuts()) {
                for (long p : timings.get(c.seq()).videoPtsSorted()) {
                    if (p >= c.startPts() && p < c.endPts()) {
                        ticks.add(p + c.deltaTicks());
                    }
                }
            }
            return ticks.stream().sorted().toList();
        }

        Path srt() {
            return files.stream().filter(p -> p.kind().equals("srt")).findFirst().orElseThrow().file();
        }

        /**
         * 조각 {@code seq} 의 파일 머리 오프셋이 고르는 프레임(그 자리 또는 그 뒤 첫 프레임 · 정확값 비교)의 결과 틱 = PTS + Δ.
         */
        long resultTick(long seq, long offsetUs) {
            SegmentTiming t = timings.get(seq);
            long xPrime = offsetUs - t.headLeadUs();
            long ts = t.videoTimescale();
            long pts = -1;
            for (long p : t.videoPtsSorted()) {
                if ((p - t.videoFirstDts()) * 1_000_000 >= xPrime * ts) {
                    pts = p;
                    break;
                }
            }
            assertThat(pts).as("조각 %d 오프셋 %d 의 프레임", seq, offsetUs).isNotNegative();
            VideoCut cut = timeline.videoCuts().stream().filter(c -> c.seq() == seq).findFirst().orElseThrow();
            return pts + cut.deltaTicks();
        }
    }

    /** 설계 5.1 꼴 주문(자막 둘 · 결과 9초). */
    private PlanOrder example() {
        return order().segment(11, false, false).segment(20, true, true).segment(21, false, true)
                .in(11, 1_000_000).out(21, 2_000_000)
                .subtitleAtMedia(0, 11, START_US, 20, END_US)
                .subtitleInGap(1, 11, 500, 11, 1_500)
                .lengthMs(9_000);
    }

    private PlanOrder order() {
        return PlanOrder.of(UUID.randomUUID());
    }

    /**
     * @param burnOnly 번인 꼴만 보는 시험인가. 그렇고 libass 가 없으면 최종 단계를 돌리지 않는다(명령 · 파일만 본다).
     */
    private Run render(ObjectNode envelope, boolean burnOnly) {
        if (burnOnly && !burn) {
            runner.intercept((cmd, workDir, deadline, r) ->
                    RecordingRunner.isFinalStage(cmd) ? new ProcessRunner.Result("", "") : null);
        }
        JobEnvelope job = new EnvelopeParser(Fixtures.MAPPER).parse(Fixtures.MAPPER.writeValueAsString(envelope));
        MediaProbe probe = new MediaProbe(runner, Fixtures.MAPPER, "ffprobe");
        List<Produced> files = new ClipRenderer(store, probe, runner, Fixtures.MAPPER, "ffmpeg", null)
                .render(job, dir, deadline(), (p, s) -> { }).files();
        Map<Long, SegmentTiming> timings = new LinkedHashMap<>();
        MediaProbe reader = new MediaProbe(new RecordingRunner(), Fixtures.MAPPER, "ffprobe");
        for (long seq : job.plan().mediaSeqs()) {
            timings.put(seq, reader.timing(dir.resolve(JoinedRenderer.sourceFile(seq)), dir, deadline()));
        }
        List<Integer> trackIds = job.recipe().tracks().stream().map(AudioTrack::trackId).toList();
        RenderTimeline timeline = RenderTimeline.of(job.plan(), job.sources(), timings, trackIds);
        return new Run(job, files, timeline, timings);
    }

    private static Instant deadline() {
        return Instant.now().plus(Duration.ofMinutes(3));
    }

    private List<String> messages() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private String finalGraph() {
        List<String> last = runner.commands().stream().filter(RecordingRunner::isFinalStage).reduce((a, b) -> b)
                .orElseThrow();
        return last.get(last.indexOf("-filter_complex") + 1);
    }

    /** 결과 영상 프레임 시각(90000 틱 · 차례대로). */
    private static List<Long> frameTicks(Path video) {
        return Ffmpeg.probe("-v", "error", "-select_streams", "v:0", "-show_entries", "frame=pts", "-of", "csv=p=0",
                        video.toString()).lines().filter(l -> !l.isBlank())
                .map(l -> Long.parseLong(l.split(",")[0].strip())).sorted().toList();
    }

    /**
     * 번인이 그 프레임에 자막을 그리나 — 프레임 시각을 ms 로 내려 {@code [start, end)} 안인지 본다(ffmpeg n8.0.1
     * {@code libavfilter/vf_subtitles.c:239-241} 가 프레임 시각 ms 를 libass 의 정수 ms 로 넘긴다).
     */
    private static boolean covers(SubtitleSegment cue, long ticks) {
        long ms = Math.floorDiv(ticks * 1_000, DirectSegments.TIMESCALE);
        return ms >= cue.startAtMs() && ms < cue.endAtMs();
    }

    private static final Pattern DIALOGUE = Pattern.compile(
            "^Dialogue: 0,(\\d+):(\\d{2}):(\\d{2})\\.(\\d{2}),(\\d+):(\\d{2}):(\\d{2})\\.(\\d{2}),", Pattern.MULTILINE);

    /**
     * ASS 자막마다 libass 가 그리는 프레임(시각 틱) — ffmpeg n8.0.1 {@code vf_subtitles.c:239-241}(실수 ms 를 정수로 버려 넘김) ·
     * libass 0.17.4 {@code ass_render.c:3151-3154}(시작 ≤ ms < 끝).
     */
    private static List<List<Long>> drawn(String ass, List<Long> frames) {
        List<List<Long>> out = new ArrayList<>();
        Matcher m = DIALOGUE.matcher(ass);
        while (m.find()) {
            long start = assMs(m, 1);
            long end = assMs(m, 5);
            out.add(frames.stream().filter(t -> {
                long now = (long) (t * (1.0 / DirectSegments.TIMESCALE) * 1000);
                return now >= start && now < end;
            }).toList());
        }
        return out;
    }

    private static long assMs(Matcher m, int from) {
        return Long.parseLong(m.group(from)) * 3_600_000 + Long.parseLong(m.group(from + 1)) * 60_000
                + Long.parseLong(m.group(from + 2)) * 1_000 + Long.parseLong(m.group(from + 3)) * 10;
    }

    private static final Pattern CUE = Pattern.compile(
            "\\d+\\n(\\d{2}):(\\d{2}):(\\d{2}),(\\d{3}) --> (\\d{2}):(\\d{2}):(\\d{2}),(\\d{3})\\n(.*?)\\n\\n",
            Pattern.DOTALL);

    /** SRT 를 자막 목록으로 다시 읽는다(시각 ms · 본문). */
    private static List<SubtitleSegment> cues(String srt) {
        Matcher m = CUE.matcher(srt);
        List<SubtitleSegment> out = new ArrayList<>();
        int end = 0;
        while (m.find()) {
            assertThat(m.start()).as("SRT 사이에 다른 글이 없다").isEqualTo(end);
            out.add(new SubtitleSegment(millis(m, 1), millis(m, 5), m.group(9)));
            end = m.end();
        }
        assertThat(end).as("SRT 끝까지 읽음").isEqualTo(srt.length());
        return out;
    }

    private static long millis(Matcher m, int from) {
        return Long.parseLong(m.group(from)) * 3_600_000 + Long.parseLong(m.group(from + 1)) * 60_000
                + Long.parseLong(m.group(from + 2)) * 1_000 + Long.parseLong(m.group(from + 3));
    }
}
