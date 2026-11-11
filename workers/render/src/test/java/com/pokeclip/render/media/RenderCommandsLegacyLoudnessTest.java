package com.pokeclip.render.media;

import com.pokeclip.render.recipe.Recipe.AudioTrack;
import com.pokeclip.render.support.DirectSegments;
import com.pokeclip.render.support.Ffmpeg;
import com.pokeclip.render.support.Fixtures;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * 옛 꼴 출력 명령(지금 운영 경로)을 이 기계의 실제 ffmpeg 로 끝까지 돌려, 평준화가 동적 모드로 떨어져도 결과 소리 시각이 뛰지 않음을
 * 본다. 옛 경로는 출력 {@code -t}가 길이를 자르므로 loudnorm 이 클립 끝 뒤 원본 소리도 받는다. 끝 여유(원본 끝 − 클립 끝)가 2.9초보다
 * 작으면 시각 뜀이 클립 안 {@code (클립 길이 + 여유) − 2.9초} 자리에 떨어져 그 뒤 소리가 영상보다 늦다(운영 판 8.0.1 실측 —
 * 여유 0 · 1 · 2.5초에서 50ms).
 *
 * <p>자료: 정수 초마다 흰 프레임 한 장과 4ms 1kHz 비프가 같이 놓인 원본. 비프가 잡음보다 훨씬 높아 최고점 대 크기 비가 12.5dB 를
 * 넘는다 — 렌더 상수(I −14 · TP −1.5)에서 linear 가 성립하지 않아 2단계가 동적 모드다.
 */
class RenderCommandsLegacyLoudnessTest {

    private static final int SOURCE_SECONDS = 12;
    /** 클립 시작 0.95초 · 길이 10.25초 → 끝 여유 0.8초. loudnorm 입력 11.05초라 뜀 자리 8.15초 · 크기 50ms. */
    private static final long OFFSET_MS = 950;
    private static final long DURATION_MS = 10_250;
    private static final double JUMP_AT_SECONDS = (SOURCE_SECONDS * 1000 - OFFSET_MS) / 1000.0 - 2.9;
    private static final int RATE = 48_000;
    private static final long AAC_FRAME = 1_024;
    /** 표식 싱크가 첫 표식 기준선에서 움직여도 되는 폭(ms). 뜀은 50ms 다. */
    private static final double SYNC_TOLERANCE_MS = 1.0;
    private static final List<AudioTrack> TRACK = List.of(new AudioTrack(0, 1.0));

    @TempDir
    static Path dir;

    @BeforeAll
    static void source() {
        assumeThat(Ffmpeg.available()).as("ffmpeg 필요").isTrue();
        String len = String.valueOf(SOURCE_SECONDS);
        String beep = "aevalsrc='0.95*sin(2*PI*1000*t)*lt(mod(t\\,1)\\,0.004)|0.95*sin(2*PI*1000*t)*lt(mod(t\\,1)\\,0.004)'"
                + ":s=48000:d=" + len + ":c=stereo";
        String noise = "anoisesrc=color=pink:sample_rate=48000:duration=" + len + ":amplitude=0.03:seed=12,"
                + "aformat=channel_layouts=stereo";
        String graph = "color=black:s=320x180:r=30:d=" + len + ",drawbox=x=0:y=0:w=iw:h=ih:t=fill:color=white:"
                + "enable='eq(mod(n\\,30)\\,0)',format=yuv420p[v];" + noise + "[n];" + beep + "[b];"
                + "[n][b]amix=inputs=2:normalize=0[a]";
        new ProcessRunner().run(List.of("ffmpeg", "-hide_banner", "-nostdin", "-y", "-loglevel", "error",
                "-filter_complex", graph, "-map", "[v]", "-map", "[a]", "-c:v", "libx264", "-preset", "ultrafast",
                "-g", "120", "-keyint_min", "120", "-sc_threshold", "0", "-c:a", "aac", "-b:a", "160k", "-ar", "48000",
                "-movflags", "+faststart", RenderCommands.SOURCE), dir, deadline());
    }

    @Test
    void 동적_평준화_옛_꼴_결과도_48kHz_패킷_균일_표식_싱크가_기준선_그대로다() {
        String fix = measure();
        assertThat(peakToLoudness(fix)).as("픽스처는 동적 모드 조건(최고점 대 크기 비 > 12.5dB): %s", fix)
                .isGreaterThan(12.5);
        assertThat(DURATION_MS / 1000.0).as("뜀 자리가 클립 안이다(끝 여유 < 2.9초)").isGreaterThan(JUMP_AT_SECONDS);

        new ProcessRunner().run(RenderCommands.render("ffmpeg", OFFSET_MS, DURATION_MS,
                new Composition.Plan("[0:v:0]setsar=1", List.of()), TRACK, fix, null, null, "o1.mp4"), dir, deadline());
        Path out = dir.resolve("o1.mp4");

        assertThat(Ffmpeg.probe("-v", "error", "-select_streams", "a:0", "-show_entries", "stream=sample_rate",
                "-of", "csv=p=0", out.toString()).strip()).as("소리 표본율").isEqualTo(String.valueOf(RATE));
        List<long[]> packets = audioPackets(out);
        for (int i = 1; i < packets.size(); i++) {
            assertThat(packets.get(i - 1)[1]).as("소리 패킷 %d 길이", i - 1).isEqualTo(AAC_FRAME);
            assertThat(packets.get(i)[0] - packets.get(i - 1)[0]).as("소리 패킷 %d 시각 차", i).isEqualTo(AAC_FRAME);
        }

        List<Double> flashes = flashes(out);
        double[] audio = timelineAudio(out);
        List<double[]> syncs = new ArrayList<>();
        for (double flash : flashes) {
            syncs.add(new double[] {flash, (onset(audio, flash) - flash) * 1000});
        }
        assertThat(syncs).as("표식 수").hasSizeGreaterThanOrEqualTo(10);
        assertThat(flashes.getLast()).as("뜀 자리 뒤 표식이 있다").isGreaterThan(JUMP_AT_SECONDS);
        double baseline = syncs.getFirst()[1];
        for (double[] s : syncs) {
            assertThat(s[1]).as("표식 %.3f초 싱크(ms) · 기준선 %.2f", s[0], baseline)
                    .isCloseTo(baseline, org.assertj.core.data.Offset.offset(SYNC_TOLERANCE_MS));
        }
    }

    private static String measure() {
        ProcessRunner.Result measured = new ProcessRunner().run(
                RenderCommands.measureLoudness("ffmpeg", OFFSET_MS, DURATION_MS, TRACK), dir, deadline());
        String fix = Loudness.correctFilter(measured.stderr(), Fixtures.MAPPER);
        assertThat(fix).as("평준화 필터").isNotNull();
        return fix;
    }

    /** 잰 값의 최고점 − 크기(dB). */
    private static double peakToLoudness(String fix) {
        return number(fix, "measured_TP") - number(fix, "measured_I");
    }

    private static double number(String filter, String key) {
        Matcher m = Pattern.compile(key + "=(-?[0-9.]+)").matcher(filter);
        assertThat(m.find()).as(key).isTrue();
        return Double.parseDouble(m.group(1));
    }

    /** 소리 패킷 {pts, duration}(1/48000). */
    private static List<long[]> audioPackets(Path file) {
        return Ffmpeg.probe("-v", "error", "-select_streams", "a:0", "-show_entries", "packet=pts,duration",
                        "-of", "csv=p=0", file.toString()).lines().filter(l -> !l.isBlank())
                .map(l -> l.split(","))
                .map(f -> new long[] {Long.parseLong(f[0].strip()), Long.parseLong(f[1].strip())}).toList();
    }

    /** 흰 프레임 시각(초) — 밝기 평균이 절반을 넘는 프레임. */
    private static List<Double> flashes(Path file) {
        String rows = Ffmpeg.probe("-v", "error", "-f", "lavfi", "-i", "movie=" + file + ",signalstats",
                "-show_entries", "frame=pts_time:frame_tags=lavfi.signalstats.YAVG", "-of", "csv=p=0");
        assertThat(rows).as("영상 밝기 판독").isNotNull();
        List<Double> out = new ArrayList<>();
        for (String line : rows.lines().filter(l -> !l.isBlank()).toList()) {
            String[] f = line.split(",");
            if (Double.parseDouble(f[1].strip()) > 128) {
                out.add(Double.parseDouble(f[0].strip()));
            }
        }
        return out;
    }

    /**
     * 소리를 시각대로 펼친 PCM(왼쪽 · 48kHz · 0 초 = 표본 0). 재생기처럼 시각 틈은 무음으로 채우므로 시각 뜀이 있으면 그 뒤 표본이
     * 늦게 놓인다.
     */
    private static double[] timelineAudio(Path file) {
        Path raw = dir.resolve("timeline.raw");
        new ProcessRunner().run(List.of("ffmpeg", "-hide_banner", "-nostdin", "-y", "-i", file.toString(),
                "-map", "0:a:0", "-af", "aresample=48000:async=1:min_hard_comp=0.0001:first_pts=0",
                "-c:a", "pcm_f32le", "-f", "f32le", raw.toString()), dir, deadline());
        float[] stereo = DirectSegments.pcm(raw);
        double[] left = new double[stereo.length / 2];
        for (int i = 0; i < left.length; i++) {
            left[i] = stereo[2 * i];
        }
        return left;
    }

    /** 표식 자리 ±200ms 안에서 진폭이 그 창 최고점의 절반을 처음 넘는 시각(초). */
    private static double onset(double[] audio, double around) {
        int from = Math.max(0, (int) Math.round((around - 0.2) * RATE));
        int to = Math.min(audio.length, (int) Math.round((around + 0.2) * RATE));
        double peak = 0;
        for (int i = from; i < to; i++) {
            peak = Math.max(peak, Math.abs(audio[i]));
        }
        for (int i = from; i < to; i++) {
            if (Math.abs(audio[i]) >= peak / 2) {
                return (double) i / RATE;
            }
        }
        throw new AssertionError("표식 " + around + "초 근처 비프가 없다");
    }

    private static Instant deadline() {
        return Instant.now().plus(Duration.ofMinutes(2));
    }
}
