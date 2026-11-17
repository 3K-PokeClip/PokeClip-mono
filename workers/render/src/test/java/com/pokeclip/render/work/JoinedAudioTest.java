package com.pokeclip.render.work;

import com.pokeclip.render.job.EnvelopeParser;
import com.pokeclip.render.job.ErrorCode;
import com.pokeclip.render.job.JobEnvelope;
import com.pokeclip.render.job.RenderFailure;
import com.pokeclip.render.media.MediaProbe;
import com.pokeclip.render.media.ProcessRunner;
import com.pokeclip.render.media.RenderCommands;
import com.pokeclip.render.media.SegmentTiming;
import com.pokeclip.render.recipe.Recipe.AudioTrack;
import com.pokeclip.render.storage.S3Store;
import com.pokeclip.render.support.DirectSegments;
import com.pokeclip.render.support.DirectSegments.Recording;
import com.pokeclip.render.support.Ffmpeg;
import com.pokeclip.render.support.Fixtures;
import com.pokeclip.render.support.PlanOrder;
import com.pokeclip.render.support.RecordingRunner;
import com.pokeclip.render.support.StereoSegments;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * 새 꼴 소리 줄이 흐름 시각 틈과 늦게 시작하는 트랙을 계획 자리에 놓는다(PDT 설계 r7 7.4 소리 묶음 5 · 6 · 7.5 · 16.2 P23 · P28).
 * 이 기계의 실제 ffmpeg 로 A′ 전 경로를 돌리고, 최종 단계 그래프 그대로에서 트랙별 묶음 이음 {@code [acatN]} 을 PCM 으로 뽑아(AAC
 * 재인코딩 전) 녹화 연속 디코드 기준과 채널마다 맞댄다.
 *
 * <p>자료는 좌우 채널에 서로 다른 무늬를 넣은 스테레오 트랙 넷의 12초 녹화 하나({@link StereoSegments})와 그 조각 사본, 그리고
 * 따로 만든 녹화 둘이다.
 *
 * <ul>
 *   <li>30 · 31 · 32 — 손대지 않은 조각.</li>
 *   <li>52 — 32 사본에서 고른 트랙(1 · 3)의 첫 3패킷(3072표본 · 64ms)을 지움(녹화기가 버린 표본 E9). 50 · 51 은 30 · 31 과 같다.</li>
 *   <li>61 — 31 사본에서 트랙 1 패킷을 모두 뺌(한 조각만 패킷 0). 60 · 62 는 30 · 32 와 같다.</li>
 *   <li>71 — 31 사본에서 트랙 1 의 첫 3패킷을 지움(가운데 조각의 64ms 틈). 70 · 72 는 30 · 32 와 같다.</li>
 *   <li>49 — 소리 트랙 둘뿐인 4초 녹화 {@code r2} 의 조각(고른 트랙 3 이 없는 준비 조각).</li>
 *   <li>80 · 81 — 44.1kHz 8초 녹화 {@code rq} 의 두 조각. 81 은 트랙 1 의 첫 3패킷을 지운 사본(늦게 시작하는 트랙).</li>
 * </ul>
 *
 * <p>판정: 결과 표본 n ↔ 기준 표본 n + lag 이고 lag 는 영상 계획(묶음 Δ)에서 낸다 — 기대 lag ±48 안에서 차가 가장 작은 자리가
 * 기대 lag 와 4표본(0.083ms) 안 · 그 자리에서 PCM 최대 차 1e-4 · 채워야 할 자리는 무음. 디코더가 앞 패킷 없이 이어 받는 패킷
 * 하나(1024표본)는 일그러지므로 그 뒤부터 맞댄다.
 */
class JoinedAudioTest {

    private static final int OUT_RATE = 48_000;
    private static final int AAC_FRAME = 1_024;
    /** E9 사본에서 지운 패킷 수(3 × 1024 = 3072표본 = 64ms). */
    private static final int DROPPED_FRAMES = 3;
    private static final int DROPPED = DROPPED_FRAMES * AAC_FRAME;
    /** 맞댈 때 건너뛰는 일그러짐 몫(디코더가 앞 패킷 없이 받은 패킷 하나 + 여유). */
    private static final int SETTLE = 2 * AAC_FRAME;
    private static final int LAG_TOLERANCE = 4;
    /** 44.1kHz 녹화 {@code rq} 의 표본율. */
    private static final int Q_RATE = 44_100;
    private static final Pattern RESAMPLE = Pattern.compile("aresample=48000:async=1:min_hard_comp=[0-9.]+");

    @TempDir
    static Path fixtures;
    private static Recording rs;
    private static Recording rq;
    private static final Map<Long, Path> FILES = new HashMap<>();

    @TempDir
    Path dir;

    private final RecordingRunner runner = new RecordingRunner();
    private final S3Store store = mock(S3Store.class);

    @BeforeAll
    static void segments() {
        assumeThat(Ffmpeg.available()).as("ffmpeg 필요").isTrue();
        rs = StereoSegments.write(fixtures, "rs", 12, 71, 4);
        List<Path> p = rs.pieces();
        for (int i = 0; i < p.size(); i++) {
            FILES.put(30L + i, p.get(i));
            FILES.put(50L + i, p.get(i));
            FILES.put(60L + i, p.get(i));
        }
        Path dropped = StereoSegments.dropHead(p.get(2), fixtures.resolve("e9_a1.m4s"), 1, DROPPED_FRAMES);
        FILES.put(52L, StereoSegments.dropHead(dropped, fixtures.resolve("e9.m4s"), 3, DROPPED_FRAMES));
        FILES.put(61L, StereoSegments.dropTrack(p.get(1), fixtures.resolve("hole.m4s"), 1));
        FILES.put(70L, p.get(0));
        FILES.put(71L, StereoSegments.dropHead(p.get(1), fixtures.resolve("mid.m4s"), 1, DROPPED_FRAMES));
        FILES.put(72L, p.get(2));
        FILES.put(49L, StereoSegments.write(fixtures, "r2", 4, 91, 2).pieces().getFirst());
        rq = StereoSegments.write(fixtures, "rq", 8, 81, 4, Q_RATE);
        FILES.put(80L, rq.pieces().get(0));
        FILES.put(81L, StereoSegments.dropHead(rq.pieces().get(1), fixtures.resolve("q_late.m4s"), 1, DROPPED_FRAMES));
    }

    @BeforeEach
    void setUp() {
        doAnswer(inv -> {
            String key = inv.getArgument(1);
            long seq = Long.parseLong(key.substring(key.lastIndexOf('_') + 1, key.lastIndexOf('.')));
            Files.copy(FILES.get(seq), inv.<Path>getArgument(2));
            return null;
        }).when(store).download(anyString(), anyString(), any(), any());
    }

    /** 손질한 조각이 뜻한 사건인지(시험 자료의 유효성). */
    @Test
    void 손질한_조각은_뜻한_사건이다() {
        assertThat(audioPts(FILES.get(32L), 1).getFirst()).as("8초 조각 첫 소리 = 영상 첫 프레임 자리").isEqualTo(384_000);
        assertThat(audioPts(FILES.get(52L), 1).getFirst()).isEqualTo(384_000 + DROPPED);
        assertThat(audioPts(FILES.get(52L), 3).getFirst()).isEqualTo(384_000 + DROPPED);
        assertThat(audioPts(FILES.get(52L), 2).getFirst()).as("고르지 않은 트랙은 그대로").isEqualTo(384_000);
        assertThat(audioPts(FILES.get(61L), 1)).as("한 조각만 패킷 0").isEmpty();
        assertThat(audioPts(FILES.get(61L), 3).getFirst()).as("4초 조각 첫 소리 = 영상 첫 프레임 + 512표본")
                .isEqualTo(192_512);
        assertThat(audioPts(FILES.get(31L), 1)).isNotEmpty();
        assertThat(audioPts(FILES.get(71L), 1).getFirst()).as("가운데 조각 트랙 1 = 4초 조각 첫 소리 + 64ms")
                .isEqualTo(192_512 + DROPPED);
        assertThat(audioPts(FILES.get(71L), 3).getFirst()).isEqualTo(192_512);
        assertThat(audioStreams(FILES.get(49L))).as("준비 조각에 트랙 3 이 없다").isEqualTo(2);
        assertThat(audioStreams(FILES.get(80L))).isEqualTo(4);
        assertThat(audioRate(FILES.get(81L))).isEqualTo(Q_RATE);
        assertThat(audioPts(FILES.get(81L), 1).getFirst()).as("44.1kHz 트랙 1 은 3패킷 늦게 시작")
                .isEqualTo(audioPts(FILES.get(81L), 3).getFirst() + DROPPED);
    }

    /**
     * 같은 녹화로 이어진 두 조각 사이에서 녹화기가 뒤 조각 머리 64ms 를 버렸다(E9). 흐름 시각 틈을 {@code aresample} 이 그 길이의
     * 무음으로 채워 틈 뒤 소리가 계획 자리에 남는다 — 기본 문턱(0.1초)이면 채우지 않아 틈 뒤가 64ms 앞당겨진다(설계 7.4 소리 묶음 5).
     */
    @Test
    void 흐름_안_버려진_64ms_는_무음으로_채워_뒤_소리가_제자리다() {
        String body = order().segment(51, false, false).segment(52, false, true)
                .in(51, 1_000_000).out(52, 2_000_000).lengthMs(5_000).body();

        Rendered r = render(body);

        assertThat(r.timeline().audioRuns()).hasSize(1);
        int lag = r.expectedLag(0);
        // 틈 = 결과 (8.0 − 5.0)초 자리부터 64ms
        int gap = 3 * OUT_RATE;
        for (int track : List.of(1, 3)) {
            float[][] out = r.pcm(track);
            float[][] ref = reference(track);
            for (int ch = 0; ch < 2; ch++) {
                assertOnPlan(out[ch], ref[ch], AAC_FRAME, gap, lag, "트랙 " + track + " 채널 " + ch + " 틈 앞");
                assertSilent(out[ch], gap + 16, gap + DROPPED - 16, "트랙 " + track + " 채널 " + ch + " 틈");
                assertOnPlan(out[ch], ref[ch], gap + DROPPED + SETTLE, r.end(0) - AAC_FRAME, lag,
                        "트랙 " + track + " 채널 " + ch + " 틈 뒤");
            }
        }
    }

    /**
     * 묶음 머리 조각의 고른 트랙이 자를 구간 시작보다 64ms 늦게 시작한다. 구간을 0 에서 시작시키면 그만큼 앞당겨지므로 {@code adelay}
     * 가 되돌리고, {@code all=1} 이라 왼쪽 · 오른쪽 채널이 같이 밀린다(설계 16.2 P23 — 빠지면 오른쪽만 64ms 이르다).
     */
    @Test
    void 늦게_시작하는_트랙의_앞_무음은_좌우_채널_모두에_든다() {
        String body = order().segment(52, false, false).in(52, 0).out(52, 3_000_000).lengthMs(3_000).body();

        Rendered r = render(body);

        assertThat(r.timeline().audioRuns().getFirst().tracks()).extracting(AudioRun.TrackStart::frontSilenceSamples)
                .containsExactly((long) DROPPED, (long) DROPPED);
        int lag = r.expectedLag(0);
        for (int track : List.of(1, 3)) {
            float[][] out = r.pcm(track);
            float[][] ref = reference(track);
            for (int ch = 0; ch < 2; ch++) {
                assertSilent(out[ch], 0, DROPPED - 16, "트랙 " + track + " 채널 " + ch + " 앞 무음");
                assertOnPlan(out[ch], ref[ch], DROPPED + SETTLE, r.end(0) - AAC_FRAME, lag,
                        "트랙 " + track + " 채널 " + ch);
            }
        }
    }

    /**
     * 같은 녹화로 이어진 세 조각 중 가운데 조각에만 트랙 1 패킷이 없다. 묶음 전체가 아니라서 {@code anullsrc} 대체가 아니고, 흐름
     * 시각 틈을 {@code aresample} 이 채운다 — 뒤 조각 소리가 계획 자리 · 다른 트랙은 그대로(설계 7.4 소리 묶음 6 · 16.2 P28).
     */
    @Test
    void 가운데_조각만_패킷이_없는_트랙은_그_자리만_무음이다() {
        String body = order().segment(60, false, false).segment(61, false, true).segment(62, false, true)
                .in(60, 1_000_000).out(62, 2_000_000).lengthMs(9_000).body();

        Rendered r = render(body);

        assertThat(r.timeline().audioRuns()).hasSize(1);
        assertThat(r.timeline().audioRuns().getFirst().tracks()).noneMatch(AudioRun.TrackStart::silent);
        int lag = r.expectedLag(0);
        // 트랙 1 틈 = 조각 60 마지막 소리 끝(4.010667초) → 조각 62 첫 소리(8.0초) · 결과는 1.0초 앞으로
        int holeFrom = 192_512 - OUT_RATE;
        int holeTo = 384_000 - OUT_RATE;
        float[][] one = r.pcm(1);
        float[][] three = r.pcm(3);
        for (int ch = 0; ch < 2; ch++) {
            assertOnPlan(one[ch], reference(1)[ch], AAC_FRAME, holeFrom, lag, "트랙 1 채널 " + ch + " 틈 앞");
            assertSilent(one[ch], holeFrom + 16, holeTo - 16, "트랙 1 채널 " + ch + " 틈");
            assertOnPlan(one[ch], reference(1)[ch], holeTo + SETTLE, r.end(0) - AAC_FRAME, lag,
                    "트랙 1 채널 " + ch + " 틈 뒤");
            assertOnPlan(three[ch], reference(3)[ch], AAC_FRAME, r.end(0) - AAC_FRAME, lag, "트랙 3 채널 " + ch);
        }
    }

    /**
     * 묶음 머리 조각에만 트랙 1 패킷이 없다. 그 트랙의 첫 표본은 다음 조각 머리라 앞 무음이 조각 하나(4초)만큼이고, 트랙 3 은 4초
     * 조각 첫 소리가 영상 첫 프레임보다 512표본 늦은 만큼이다 — 둘 다 {@code asetpts} 0 시작이 앞당긴 것을 {@code adelay} 가
     * 되돌린다(늦게 시작하는 트랙의 싱크).
     */
    @Test
    void 머리_조각만_패킷이_없는_트랙은_다음_조각부터_제자리다() {
        String body = order().segment(61, false, false).segment(62, false, true)
                .in(61, 0).out(62, 2_000_000).lengthMs(6_000).body();

        Rendered r = render(body);

        assertThat(r.timeline().audioRuns().getFirst().tracks()).extracting(AudioRun.TrackStart::frontSilenceSamples)
                .containsExactly((long) 4 * OUT_RATE, 512L);
        int lag = r.expectedLag(0);
        float[][] one = r.pcm(1);
        float[][] three = r.pcm(3);
        for (int ch = 0; ch < 2; ch++) {
            assertSilent(one[ch], 0, 4 * OUT_RATE - 16, "트랙 1 채널 " + ch + " 앞 무음");
            assertOnPlan(one[ch], reference(1)[ch], 4 * OUT_RATE + SETTLE, r.end(0) - AAC_FRAME, lag,
                    "트랙 1 채널 " + ch);
            assertSilent(three[ch], 0, 512 - 16, "트랙 3 채널 " + ch + " 앞 무음");
            assertOnPlan(three[ch], reference(3)[ch], 512 + SETTLE, r.end(0) - AAC_FRAME, lag, "트랙 3 채널 " + ch);
        }
    }

    /**
     * 같은 녹화로 이어진 세 조각 중 가운데 조각 머리에서 트랙 1 의 64ms 를 버렸다. 틈이 흐름 한가운데라 앞뒤로 소리가 이어진다 —
     * 기본 문턱(0.1초)이면 채우지 않아 틈 뒤 두 조각 소리가 64ms 앞당겨진다(설계 7.4 소리 묶음 5 · 문턱 방어 둘째 자리).
     */
    @Test
    void 가운데_조각의_64ms_틈도_무음으로_채워_뒤_두_조각이_제자리다() {
        String body = order().segment(70, false, false).segment(71, false, true).segment(72, false, true)
                .in(70, 1_000_000).out(72, 2_000_000).lengthMs(9_000).body();

        Rendered r = render(body);

        assertThat(r.timeline().audioRuns()).hasSize(1);
        int lag = r.expectedLag(0);
        // 틈 = 조각 70 마지막 소리 끝(4.010667초)부터 64ms · 결과는 1.0초 앞으로
        int gapFrom = 192_512 - OUT_RATE;
        int gapTo = gapFrom + DROPPED;
        float[][] one = r.pcm(1);
        float[][] three = r.pcm(3);
        for (int ch = 0; ch < 2; ch++) {
            assertOnPlan(one[ch], reference(1)[ch], AAC_FRAME, gapFrom, lag, "트랙 1 채널 " + ch + " 틈 앞");
            assertSilent(one[ch], gapFrom + 16, gapTo - 16, "트랙 1 채널 " + ch + " 틈");
            assertOnPlan(one[ch], reference(1)[ch], gapTo + SETTLE, r.end(0) - AAC_FRAME, lag,
                    "트랙 1 채널 " + ch + " 틈 뒤");
            assertOnPlan(three[ch], reference(3)[ch], AAC_FRAME, r.end(0) - AAC_FRAME, lag, "트랙 3 채널 " + ch);
        }
    }

    /**
     * 고른 트랙(3)이 없는 준비 조각은 준비 조각 대조가 뺀다(설계 7.4 소리 묶음 7). 그래서 묶음 첫 파일은 결과 조각이고, 흐름 안
     * 채움 문턱은 그 조각에서 읽은 표본율(48kHz → 0.010667초)로 정한다 — 트랙 없는 파일의 표본율(0)을 읽는 일이 없다.
     */
    @Test
    void 고른_트랙이_없는_준비_조각은_빠지고_묶음_첫_조각의_표본율을_쓴다() {
        String body = order().prime(49, 51).segment(51, false, false).in(51, 1_000_000).out(51, 3_000_000)
                .lengthMs(2_000).body();

        renderJob(body);

        String list = read(dir.resolve(RenderCommands.runListFile(0)));
        assertThat(list).contains(JoinedRenderer.sourceFile(51)).doesNotContain(JoinedRenderer.sourceFile(49));
        assertThat(finalGraph()).contains("min_hard_comp=0.010667,").doesNotContain("min_hard_comp=0.000000");
    }

    /** 묶음 첫 파일의 소리 표본율을 읽지 못하면(판독 0) 채움 문턱을 정할 수 없어 {@code SOURCE_MISMATCH} 로 끝낸다. */
    @Test
    void 묶음_첫_파일의_표본율을_못_읽으면_SOURCE_MISMATCH() {
        String body = order().segment(30, false, false).segment(31, false, true)
                .in(30, 1_000_000).out(31, 2_000_000).lengthMs(5_000).body();
        runner.intercept((cmd, wd, dl, rr) -> cmd.contains("-show_data_hash") ? unreadableRate(rr.proceed(cmd, wd, dl))
                : null);

        assertThatThrownBy(() -> renderJob(body)).isInstanceOfSatisfying(RenderFailure.class, f -> {
            assertThat(f.code()).isEqualTo(ErrorCode.SOURCE_MISMATCH);
            assertThat(f.getMessage()).contains("표본율을 읽지 못했다");
        });
        assertThat(runner.commands()).noneMatch(RecordingRunner::isFinalStage);
    }

    /**
     * 44.1kHz 입력 · 둘째 묶음 머리의 트랙이 자를 구간 시작보다 늦게 시작(앞 무음 0 아님). 둘째 묶음은 제 흐름 첫 표본부터 48kHz 로
     * 옮겨지므로, 기준도 같은 표본에서 같은 {@code aresample} 로 옮겨 맞댄다 — 트랙 1 · 3 의 앞 무음이 서로 다르고 좌우 채널이 같이
     * 밀린다(설계 7.4 소리 묶음 4 · 16.2 P23 · P25).
     */
    @Test
    void 사십사_kHz_둘째_묶음의_늦게_시작하는_트랙도_좌우_채널_모두_제자리다() {
        String body = order().segment(80, false, false).segment(81, true, true)
                .in(80, 1_000_000).out(81, 3_000_000).lengthMs(6_000).body();

        Rendered r = render(body);

        assertThat(r.timeline().audioRuns()).hasSize(2);
        String resample = resampleClause();
        assertThat(resample).as("44.1kHz 문턱 = 512 ÷ 44100").endsWith("min_hard_comp=0.011610");
        int second = Math.toIntExact(r.timeline().audioRuns().get(1).resultStartSample());
        assertThat(second).isEqualTo(3 * OUT_RATE);
        int firstLag = r.expectedLag(0);
        for (int track : List.of(1, 3)) {
            long head = audioPts(FILES.get(81L), track).getFirst();
            // 계획 앞 무음 = (트랙 첫 표본 − 조각 81 첫 프레임 4.0초) × 48000
            long silence = Math.round(head * (double) OUT_RATE / Q_RATE) - 4L * OUT_RATE;
            assertThat(silence).as("트랙 %d 계획 앞 무음", track).isPositive();
            AudioRun.TrackStart start = r.timeline().audioRuns().get(1).tracks().stream()
                    .filter(t -> t.trackId() == track).findFirst().orElseThrow();
            assertThat(start.frontSilenceSamples()).as("트랙 %d 앞 무음", track).isCloseTo(silence, within(1L));
            float[][] out = r.pcm(track);
            float[][] whole = resampledReference(track, 0, resample);
            float[][] tail = resampledReference(track, head, resample);
            int lag = Math.toIntExact(-(second + silence));
            for (int ch = 0; ch < 2; ch++) {
                String what = "트랙 " + track + " 채널 " + ch;
                assertOnPlan(out[ch], whole[ch], AAC_FRAME, second - AAC_FRAME, firstLag, what + " 첫 묶음");
                assertSilent(out[ch], second + 16, Math.toIntExact(second + silence - 16), what + " 둘째 묶음 앞 무음");
                assertOnPlan(out[ch], tail[ch], Math.toIntExact(second + silence + SETTLE), r.end(1) - AAC_FRAME, lag,
                        what + " 둘째 묶음");
            }
        }
    }

    // ---------------------------------------------------------------- 실행

    private record Rendered(RenderTimeline timeline, Map<Integer, float[][]> pcmByTrack) {

        float[][] pcm(int track) {
            return pcmByTrack.get(track);
        }

        /** 묶음 r 의 기대 lag — 결과 표본 n ↔ 기준 표본 n − Δ·48000/90000(Δ = 묶음 첫 영상 조각 이동량 · 이 자료는 정수). */
        int expectedLag(int run) {
            AudioRun a = timeline.audioRuns().get(run);
            long delta = timeline.videoCuts().stream().filter(c -> a.seqs().contains(c.seq())).findFirst()
                    .orElseThrow().deltaTicks();
            assertThat(delta * OUT_RATE % DirectSegments.TIMESCALE).isZero();
            return Math.toIntExact(-delta * OUT_RATE / DirectSegments.TIMESCALE);
        }

        int end(int run) {
            return Math.toIntExact(timeline.audioRuns().get(run).resultEndSample());
        }
    }

    private PlanOrder order() {
        return PlanOrder.of(UUID.randomUUID());
    }

    private Rendered render(String body) {
        JobEnvelope job = renderJob(body);
        Map<Long, SegmentTiming> timings = new LinkedHashMap<>();
        MediaProbe again = new MediaProbe(new RecordingRunner(), Fixtures.MAPPER, "ffprobe");
        for (var s : job.sources()) {
            timings.put(s.seq(), again.timing(dir.resolve(JoinedRenderer.sourceFile(s.seq())), dir, deadline()));
        }
        List<Integer> trackIds = job.recipe().tracks().stream().map(AudioTrack::trackId).toList();
        return new Rendered(RenderTimeline.of(job.plan(), job.sources(), timings, trackIds), graphPcm(trackIds));
    }

    /** 자막을 뺀 주문을 실제 ffmpeg 로 렌더한다(시간표를 다시 짜지 않음). */
    private JobEnvelope renderJob(String body) {
        ObjectNode envelope = (ObjectNode) Fixtures.MAPPER.readTree(body);
        ((ObjectNode) envelope.get("recipe")).putNull("subtitles");
        JobEnvelope job = new EnvelopeParser(Fixtures.MAPPER).parse(Fixtures.MAPPER.writeValueAsString(envelope));
        MediaProbe probe = new MediaProbe(runner, Fixtures.MAPPER, "ffprobe");
        new ClipRenderer(store, probe, runner, Fixtures.MAPPER, "ffmpeg", null).render(job, dir, deadline(),
                (p, s) -> { });
        return job;
    }

    private String finalGraph() {
        List<String> last = runner.commands().stream().filter(RecordingRunner::isFinalStage).reduce((a, b) -> b)
                .orElseThrow();
        return last.get(last.indexOf("-filter_complex") + 1);
    }

    /** 최종 단계 그래프의 흐름 옮기기 구절({@code aresample=48000:async=1:min_hard_comp=…}). 묶음 · 트랙 모두 같아야 한다. */
    private String resampleClause() {
        Matcher m = RESAMPLE.matcher(finalGraph());
        List<String> found = new ArrayList<>();
        while (m.find()) {
            found.add(m.group());
        }
        assertThat(found).as("흐름 옮기기 구절").isNotEmpty().containsOnly(found.getFirst());
        return found.getFirst();
    }

    /**
     * 녹화 {@code rq} 연속 디코드의 트랙 N 을 표본 {@code fromSample}(입력 표본율 · 시각 그대로)부터 잘라 최종 단계와 같은
     * {@code resample} 구절로 48kHz 로 옮긴 기준(두 채널). 묶음 흐름도 제 첫 표본부터 옮겨지므로 같은 첫 표본에서 시작하면 옮긴
     * 표본 자리가 같다.
     */
    private float[][] resampledReference(int track, long fromSample, String resample) {
        Path raw = dir.resolve("qref_" + track + "_" + fromSample + ".raw");
        runner.proceed(List.of("ffmpeg", "-hide_banner", "-nostdin", "-y", "-i", rq.continuous().toString(),
                "-map", "0:a:" + track, "-af", "atrim=start_sample=" + fromSample + "," + resample,
                "-c:a", "pcm_f32le", "-f", "f32le", raw.toString()), dir, deadline());
        return channels(DirectSegments.pcm(raw));
    }

    /** 판독 출력의 소리 표본율을 모두 0 으로 — ffprobe 가 표본율을 못 낸 꼴. */
    private static ProcessRunner.Result unreadableRate(ProcessRunner.Result probed) {
        String stdout = probed.stdout().replaceAll("\"sample_rate\": \"\\d+\"", "\"sample_rate\": \"0\"");
        assertThat(stdout).as("판독 출력에 표본율 칸이 있다").isNotEqualTo(probed.stdout());
        return new ProcessRunner.Result(stdout, probed.stderr());
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 최종 단계 그래프를 그대로 두고 섞기 앞 트랙별 묶음 이음을 두 채널 PCM 으로 뽑는다. */
    private Map<Integer, float[][]> graphPcm(List<Integer> tracks) {
        List<String> last = runner.commands().stream().filter(RecordingRunner::isFinalStage).reduce((a, b) -> b)
                .orElseThrow();
        int at = last.indexOf("-filter_complex");
        String graph = last.get(at + 1);
        int mix = graph.indexOf("[acat" + tracks.getFirst() + "]volume=");
        StringBuilder g = new StringBuilder(graph.substring(0, mix));
        for (int n : tracks) {
            g.append("[acat").append(n).append("]anull[pcm").append(n).append("];");
        }
        List<String> cmd = new ArrayList<>(last.subList(0, at));
        cmd.addAll(List.of("-filter_complex", g.substring(0, g.length() - 1), "-map", "[vout]", "-f", "null", "-"));
        for (int n : tracks) {
            cmd.addAll(List.of("-map", "[pcm" + n + "]", "-c:a", "pcm_f32le", "-f", "f32le", "pcm_" + n + ".raw"));
        }
        runner.proceed(cmd, dir, deadline());
        Map<Integer, float[][]> out = new LinkedHashMap<>();
        for (int n : tracks) {
            out.put(n, channels(DirectSegments.pcm(dir.resolve("pcm_" + n + ".raw"))));
        }
        return out;
    }

    /** 손대지 않은 녹화 연속 fMP4 의 트랙 N 을 한 디코더로 디코드한 기준(48kHz · 두 채널). */
    private float[][] reference(int track) {
        Path raw = dir.resolve("ref_" + track + ".raw");
        if (!Files.exists(raw)) {
            runner.proceed(List.of("ffmpeg", "-hide_banner", "-nostdin", "-y", "-i", rs.continuous().toString(),
                    "-map", "0:a:" + track, "-c:a", "pcm_f32le", "-f", "f32le", raw.toString()), dir, deadline());
        }
        return channels(DirectSegments.pcm(raw));
    }

    private static List<Long> audioPts(Path file, int track) {
        return Ffmpeg.probe("-v", "error", "-select_streams", "a:" + track, "-show_entries", "packet=pts",
                        "-of", "csv=p=0", file.toString()).lines().filter(l -> !l.isBlank())
                .map(l -> Long.parseLong(l.split(",")[0].strip())).toList();
    }

    private static int audioStreams(Path file) {
        return (int) Ffmpeg.probe("-v", "error", "-select_streams", "a", "-show_entries", "stream=index",
                "-of", "csv=p=0", file.toString()).lines().filter(l -> !l.isBlank()).count();
    }

    private static int audioRate(Path file) {
        return Integer.parseInt(Ffmpeg.probe("-v", "error", "-select_streams", "a:1", "-show_entries",
                "stream=sample_rate", "-of", "csv=p=0", file.toString()).strip());
    }

    private static Instant deadline() {
        return Instant.now().plus(Duration.ofMinutes(3));
    }

    // ---------------------------------------------------------------- 판정

    /** [from, to) 에서 결과가 기준의 lag 자리와 같다 — 가장 잘 맞는 자리가 기대 lag 4표본 안 · 그 자리에서 최대 차 1e-4. */
    private static void assertOnPlan(float[] out, float[] ref, int from, int to, int lag, String what) {
        assertThat(to).as(what + " 구간").isGreaterThan(from + AAC_FRAME);
        int best = bestLag(out, ref, from, Math.min(to, from + 24_000), lag);
        assertThat(Math.abs(best - lag)).as(what + " 자리(기대 lag %d · 찾은 lag %d)", lag, best)
                .isLessThanOrEqualTo(LAG_TOLERANCE);
        assertThat(maxDiff(out, ref, from, to, best)).as(what + " PCM (1e-4 넘는 첫 자리 %d · 마지막 자리 %d)",
                firstOver(out, ref, from, to, best), lastOver(out, ref, from, to, best)).isLessThanOrEqualTo(1e-4);
    }

    private static int firstOver(float[] out, float[] ref, int from, int to, int lag) {
        for (int n = from; n < Math.min(to, out.length) && n + lag < ref.length; n++) {
            if (Math.abs(out[n] - ref[n + lag]) > 1e-4) {
                return n;
            }
        }
        return -1;
    }

    private static int lastOver(float[] out, float[] ref, int from, int to, int lag) {
        for (int n = Math.min(to, out.length) - 1; n >= from; n--) {
            if (n + lag < ref.length && Math.abs(out[n] - ref[n + lag]) > 1e-4) {
                return n;
            }
        }
        return -1;
    }

    private static void assertSilent(float[] out, int from, int to, String what) {
        assertThat(to).as(what + " 구간").isGreaterThan(from);
        assertThat(out.length).as(what + " 길이").isGreaterThanOrEqualTo(to);
        double worst = 0;
        for (int n = from; n < to; n++) {
            worst = Math.max(worst, Math.abs(out[n]));
        }
        assertThat(worst).as(what + " [%d, %d) 최대 크기", from, to).isLessThanOrEqualTo(1e-6);
    }

    private static int bestLag(float[] out, float[] ref, int from, int to, int around) {
        int best = around;
        double bestErr = Double.MAX_VALUE;
        for (int lag = around - 48; lag <= around + 48; lag++) {
            double err = 0;
            for (int n = from; n < to && err < bestErr; n++) {
                int k = n + lag;
                if (k < 0 || k >= ref.length || n >= out.length) {
                    err = Double.MAX_VALUE;
                    break;
                }
                double d = out[n] - ref[k];
                err += d * d;
            }
            if (err < bestErr) {
                bestErr = err;
                best = lag;
            }
        }
        return best;
    }

    private static double maxDiff(float[] out, float[] ref, int from, int to, int lag) {
        if (to > out.length || to + lag > ref.length || from + lag < 0) {
            return Double.MAX_VALUE;
        }
        double worst = 0;
        for (int n = from; n < to; n++) {
            worst = Math.max(worst, Math.abs(out[n] - ref[n + lag]));
        }
        return worst;
    }

    /** 섞인 스테레오 표본을 {왼쪽, 오른쪽} 으로. */
    private static float[][] channels(float[] stereo) {
        float[][] out = new float[2][stereo.length / 2];
        for (int i = 0; i < out[0].length; i++) {
            out[0][i] = stereo[2 * i];
            out[1][i] = stereo[2 * i + 1];
        }
        return out;
    }
}
