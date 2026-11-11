package com.pokeclip.render.work;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.pokeclip.render.job.ErrorCode;
import com.pokeclip.render.job.OffsetOrigin;
import com.pokeclip.render.job.RenderFailure;
import com.pokeclip.render.media.AssWriter;
import com.pokeclip.render.media.SegmentTiming;
import com.pokeclip.render.recipe.Recipe.Anchor;
import com.pokeclip.render.recipe.Recipe.SubtitleMode;
import com.pokeclip.render.recipe.Recipe.SubtitlePosition;
import com.pokeclip.render.recipe.Recipe.SubtitleSegment;
import com.pokeclip.render.recipe.Recipe.Subtitles;
import com.pokeclip.render.work.JoinedRenderer.Captions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 촘촘한 프레임 원본의 자리 있는 번인 거절(D59 · kty 2026-11-04). ASS 시각은 10ms 격자라 프레임 간격이 11ms 보다 짧으면 계획
 * 프레임만 덮도록 가를 수 없다. 그래서 자리가 있는 번인(ASS)을 태울 주문만 거절하고, 자막 없음 · CC 만 · 자리 없는 번인(SRT)은
 * 그대로 둔다. 간격은 조각마다 그 조각 안 원본 프레임으로만 잰다 — 조각 이음 자리 간격은 사유가 아니다. ffmpeg 없이 판정
 * 함수만 본다 — 배선은 {@link JoinedSubtitleTest}.
 */
class JoinedBurnGapTest {

    private static final long TS = 90_000;
    private static final UUID JOB = UUID.fromString("00000000-0000-0000-0000-00000000d059");
    private static final SubtitlePosition BOTTOM = new SubtitlePosition(Anchor.BOTTOM, 0.97);
    private static final List<SubtitleSegment> CUES = List.of(new SubtitleSegment(100, 600, "가"));
    private static final Captions SOME = new Captions(CUES, List.of(new SubtitleSegment(100, 600, "가")));
    private static final String MESSAGE = "프레임 간격이 11ms 보다 짧은 원본에는 자리가 있는 번인 자막을 넣을 수 없다";

    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(JoinedRenderer.class)).addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(JoinedRenderer.class)).detachAppender(logs);
    }

    /** 100 · 144 · 200fps(900 · 625 · 450틱)에 자리 있는 번인이면 종결 거절이다. 문장은 고정 글자 · 신호에 최소 간격. */
    @ParameterizedTest(name = "촘촘한_원본에_자리_있는_번인이면_거절한다_{0}틱")
    @ValueSource(longs = {900, 625, 450})
    void 촘촘한_원본에_자리_있는_번인이면_거절한다(long step) {
        RenderFailure f = catchThrowableOfType(RenderFailure.class,
                () -> require(positioned(SubtitleMode.BURN_AND_CC), SOME, grid(step)));

        assertThat(f).as("거절").isNotNull();
        assertThat(f.code()).isEqualTo(ErrorCode.VALIDATION);
        assertThat(f.retryable()).isFalse();
        assertThat(f.getMessage()).isEqualTo(MESSAGE);
        long gapUs = Math.floorDiv(step * 1_000_000, TS);
        assertThat(logs.list).singleElement().satisfies(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).isEqualTo("render.burn_rejected jobId=" + JOB + " minGapUs=" + gapUs);
        });
    }

    /** 번인만(CC 없음)이어도 자리가 있으면 같다. */
    @Test
    void 번인만이어도_자리가_있으면_거절한다() {
        assertThat(catchThrowableOfType(RenderFailure.class,
                () -> require(positioned(SubtitleMode.BURN_ONLY), SOME, grid(450))))
                .isNotNull();
    }

    /** 30 · 60 · 90fps 와 경계 정확히 11ms(990틱)는 자리 있는 번인도 그대로 간다. */
    @ParameterizedTest(name = "간격이_11ms_이상이면_자리_있는_번인도_간다_{0}틱")
    @ValueSource(longs = {3_000, 1_500, 1_000, 990})
    void 간격이_11ms_이상이면_자리_있는_번인도_간다(long step) {
        assertThatCode(() -> require(positioned(SubtitleMode.BURN_AND_CC), SOME, grid(step)))
                .doesNotThrowAnyException();
        assertThat(logs.list).isEmpty();
    }

    /** 경계 바로 아래(989틱 = 10.99ms)는 거절이다. */
    @Test
    void 경계_바로_아래는_거절한다() {
        assertThat(catchThrowableOfType(RenderFailure.class,
                () -> require(positioned(SubtitleMode.BURN_AND_CC), SOME, grid(989))))
                .isNotNull();
    }

    /** 촘촘한 원본이어도 자막 없음 · CC 만 · 자리 없는 번인(SRT)은 그대로 간다 — ASS 를 쓰지 않는다. */
    @ParameterizedTest(name = "촘촘한_원본도_ASS_를_쓰지_않으면_간다_{0}틱")
    @ValueSource(longs = {900, 625, 450})
    void 촘촘한_원본도_ASS_를_쓰지_않으면_간다(long step) {
        long[] frames = grid(step);
        assertThatCode(() -> require(null, Captions.NONE, frames)).as("자막 없음").doesNotThrowAnyException();
        assertThatCode(() -> require(positioned(SubtitleMode.CC_ONLY), SOME, frames))
                .as("CC 만(자리 칸이 있어도)").doesNotThrowAnyException();
        assertThatCode(() -> require(unpositioned(SubtitleMode.BURN_AND_CC), SOME, frames))
                .as("자리 없는 번인 + CC").doesNotThrowAnyException();
        assertThatCode(() -> require(unpositioned(SubtitleMode.BURN_ONLY), SOME, frames))
                .as("자리 없는 번인만").doesNotThrowAnyException();
        assertThat(logs.list).isEmpty();
    }

    /** 결과에 남은 자막이 없으면 번인 자체를 하지 않으므로(빈 파일 금지 · {@code encode}) 거절하지 않는다. */
    @Test
    void 남은_자막이_없으면_자리_있는_번인도_거절하지_않는다() {
        assertThatCode(() -> require(positioned(SubtitleMode.BURN_AND_CC), Captions.NONE, grid(450)))
                .doesNotThrowAnyException();
    }

    // ── 조각 이음(재접속) ────────────────────────────────────────────

    /**
     * 재접속 이음의 짧은 간격은 거절 사유가 아니다(D59 1라운드 cx H — 「90fps 초과 원본」 은 조각 안 원본 간격이다). 실제 OBS
     * 판독 꼴: 앞 조각 A 는 정상 구간(c = 6000 · 120장 · V = 360,000) · 뒤 조각 B 는 새 녹화 첫 파일(c = 3180). 둘 다 30fps 인데
     * 이음 간격 = c_B − c_A + 간격 = 180틱(2ms)이다. 결과 프레임 열 전체로 재면 촘촘하지만, 조각 안 간격은 3000틱이라 렌더한다.
     */
    @Test
    void 재접속_이음의_짧은_간격은_거절하지_않는다() {
        Map<Long, SegmentTiming> timings = Map.of(1L, piece(6_000, 3_000, 120), 2L, piece(3_180, 3_000, 120));
        RenderTimeline timeline = reconnected(timings);
        assertThat(AssWriter.denseGap(JoinedRenderer.resultFrameTicks(timeline, timings), TS))
                .as("결과 프레임 열 전체로는 이음 자리 180틱이 가장 짧다").hasValue(180);

        assertThatCode(() -> JoinedRenderer.requireAssFrames(JOB, positioned(SubtitleMode.BURN_AND_CC), SOME, timeline,
                timings, TS)).doesNotThrowAnyException();
        assertThat(logs.list).isEmpty();
    }

    /** 이어 붙인 조각 가운데 하나라도 조각 안 간격이 촘촘하면(뒤 조각 200fps) 거절한다. 신호는 그 조각 안 간격이다. */
    @Test
    void 조각_하나라도_안쪽_간격이_촘촘하면_거절한다() {
        Map<Long, SegmentTiming> timings = Map.of(1L, piece(6_000, 3_000, 120), 2L, piece(0, 450, 800));
        RenderTimeline timeline = reconnected(timings);

        RenderFailure f = catchThrowableOfType(RenderFailure.class, () -> JoinedRenderer.requireAssFrames(JOB,
                positioned(SubtitleMode.BURN_AND_CC), SOME, timeline, timings, TS));

        assertThat(f).as("거절").isNotNull();
        assertThat(f.getMessage()).isEqualTo(MESSAGE);
        assertThat(logs.list).singleElement().extracting(ILoggingEvent::getFormattedMessage)
                .isEqualTo("render.burn_rejected jobId=" + JOB + " minGapUs=5000");
    }

    /** 촘촘한 조각이 여럿이면 신호는 그 가운데 가장 짧은 조각 안 간격이다(100fps 조각 · 200fps 조각 → 5000µs). */
    @Test
    void 촘촘한_조각이_여럿이면_가장_짧은_간격을_남긴다() {
        assertThat(catchThrowableOfType(RenderFailure.class, () -> JoinedRenderer.requireAssFrames(JOB,
                positioned(SubtitleMode.BURN_AND_CC), SOME, List.of(grid(900), grid(450)), TS))).isNotNull();

        assertThat(logs.list).singleElement().extracting(ILoggingEvent::getFormattedMessage)
                .isEqualTo("render.burn_rejected jobId=" + JOB + " minGapUs=5000");
    }

    /** 첫 PTS = D0 + c · 간격 {@code interval} · {@code frames} 장 · V = frames × interval 인 조각. */
    private static SegmentTiming piece(long c, long interval, int frames) {
        return TimelineFixtures.timing(TimelineFixtures.grid(c, interval, frames), frames * interval,
                TimelineFixtures.track(TimelineFixtures.AUDIO_FIRST, 10));
    }

    /** 조각 1 영상 시작부터 조각 2 안 1초까지 — 2 는 1 과 다른 녹화(끊긴 이웃 · 재접속). */
    private static RenderTimeline reconnected(Map<Long, SegmentTiming> timings) {
        return RenderTimeline.of(
                TimelineFixtures.plan(OffsetOrigin.VIDEO_START, TimelineFixtures.at(1, 0),
                        TimelineFixtures.end(2, 1_000_000), List.of(1L, 2L)),
                List.of(TimelineFixtures.media(1, false), TimelineFixtures.media(2, false)), timings, List.of(0));
    }

    /** 조각 하나짜리 결과 프레임 열로 판정한다. */
    private static void require(Subtitles spec, Captions captions, long[] frames) {
        JoinedRenderer.requireAssFrames(JOB, spec, captions, List.of(frames), TS);
    }

    private static Subtitles positioned(SubtitleMode mode) {
        return new Subtitles(mode, CUES, BOTTOM);
    }

    private static Subtitles unpositioned(SubtitleMode mode) {
        return new Subtitles(mode, CUES, null);
    }

    /** 0 부터 {@code step} 틱 간격 프레임 200개. */
    private static long[] grid(long step) {
        return LongStream.range(0, 200).map(i -> i * step).toArray();
    }
}
