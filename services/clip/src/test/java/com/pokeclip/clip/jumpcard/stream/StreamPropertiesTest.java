package com.pokeclip.clip.jumpcard.stream;

import ch.qos.logback.classic.Level;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설정값 하한. <b>0 이하가 통과하면 설정 한 줄로 연결이 불사가 된다</b> —
 * {@code SseEmitter}는 서블릿 규약상 {@code timeout <= 0}을 「시한 없음」으로 읽는다.
 * 만료 토큰으로 여는 경로(컨트롤러 가드)와 <b>뿌리가 같은 두 번째 입구</b>다(인가 2차 감사).
 */
class StreamPropertiesTest {

    @Test
    void timeout이_0이면_기본값이_들어간다() {
        StreamProperties properties = new StreamProperties(
                Duration.ofSeconds(20), Duration.ZERO, 4, 1000, 4, 50, 500);

        assertThat(properties.timeout())
                .as("0이 그대로 가면 SseEmitter가 「시한 없음」이 되어 연결이 안 죽는다")
                .isEqualTo(Duration.ofHours(4));
    }

    @Test
    void timeout이_음수여도_기본값이_들어간다() {
        StreamProperties properties = new StreamProperties(
                Duration.ofSeconds(20), Duration.ofSeconds(-1), 4, 1000, 4, 50, 500);

        assertThat(properties.timeout()).isEqualTo(Duration.ofHours(4));
    }

    /** heartbeat=0은 스케줄러가 기동에서 죽는다. 시끄럽게 실패하지만 막는 것이 대칭이다. */
    @Test
    void heartbeat가_0이하면_기본값이_들어간다() {
        assertThat(new StreamProperties(Duration.ZERO, Duration.ofHours(4), 4, 1000, 4, 50, 500).heartbeat())
                .isEqualTo(Duration.ofSeconds(20));
        assertThat(new StreamProperties(Duration.ofSeconds(-5), Duration.ofHours(4), 4, 1000, 4, 50, 500).heartbeat())
                .isEqualTo(Duration.ofSeconds(20));
    }

    /**
     * <b>{@code timeout}과 같은 뿌리의 세 번째 자리.</b> {@code heartbeat}도
     * {@code CardStreamRegistry.startHeartbeat}에서 {@code toMillis()}로 잘려 스케줄러에 들어간다 —
     * {@code PT0.0005S}는 0도 음수도 아니라 「0 이하」 가드를 지나고, 잘리면 {@code period=0}이
     * 되어 {@code scheduleAtFixedRate}가 {@code IllegalArgumentException}을 던진다(실측).
     *
     * <p><b>망가지는 방향이 {@code timeout}과 반대다</b> — {@code timeout}은 연결이 안 죽고
     * {@code heartbeat}는 <b>부팅이 죽는다</b>. 그래서 이 클래스가 「덮는다」고 선언해 놓고
     * 실제로는 다른 클래스에서 터졌다.
     */
    @Test
    void heartbeat가_1ms_미만이면_기본값이_들어간다() {
        StreamProperties properties = new StreamProperties(
                Duration.ofNanos(500_000), Duration.ofHours(4), 4, 1000, 4, 50, 500);

        assertThat(properties.heartbeat())
                .as("잘리면 period=0이 되어 scheduleAtFixedRate가 부팅에서 던진다")
                .isEqualTo(Duration.ofSeconds(20));
        assertThat(properties.heartbeat().toMillis())
                .as("스케줄러가 실제로 받는 값이다 — 0이면 부팅이 죽는다")
                .isGreaterThanOrEqualTo(1L);
    }

    /**
     * <b>{@code heartbeat}에는 WARN을 안 남긴다.</b> {@code timeout}과 기준이 같다 —
     * 증상이 없는 것만 알린다. {@code heartbeat}가 틀리면 프록시가 조용한 연결을 끊어
     * 드러나고, 1ms 미만이면 애초에 부팅이 죽어 더 시끄럽다.
     */
    @Test
    void heartbeat를_덮을_때는_로그를_안_남긴다() {
        try (LogCaptor captor = new LogCaptor()) {
            new StreamProperties(Duration.ofNanos(500_000), Duration.ofHours(4), 4, 1000, 4, 50, 500);

            assertThat(captor.messages())
                    .as("증상이 있는 설정까지 로그를 내면 진짜 신호가 묻힌다")
                    .noneMatch(m -> m.contains("pokeclip.jump-card.stream."));
        }
    }

    /** 경계 바로 위. 하한을 {@code <= 1}로 잘못 쓰면 이것까지 덮인다. */
    @Test
    void heartbeat_딱_1ms는_그대로_쓴다() {
        StreamProperties properties = new StreamProperties(
                Duration.ofMillis(1), Duration.ofHours(4), 4, 1000, 4, 50, 500);

        assertThat(properties.heartbeat()).isEqualTo(Duration.ofMillis(1));
    }

    /**
     * <b>0도 음수도 아닌데 「시한 없음」이 되는 자리.</b> {@code SseEmitter}는 {@code long ms}를
     * 받으므로 {@code toMillis()}가 <b>자른다</b> — {@code PT0.0005S}는 0이 되고, 서블릿 규약상
     * {@code timeout <= 0}은 「시한 없음」이다. 2026-08-23 재현(PR #111 봇 지적 ④): 이 값으로
     * 부팅해 진짜 HTTP로 연결하니 <b>20초 뒤에도 안 닫혔고</b> 하트비트 20개를 받았다
     * (설정 시한의 4만 배). 같은 경로에 {@code PT2S}를 주면 2995ms에 닫힌다.
     *
     * <p>이 클래스 주석이 「0 이하를 막는다」고 적어 두고 <b>실제로는 그 자리를 열어 두고 있었다.</b>
     * 자르기 <b>전</b> 값만 봤기 때문이다 — {@code JumpCardProperties}의 {@code toSeconds()}와 뿌리가 같다.
     */
    @Test
    void timeout이_1ms_미만이면_기본값이_들어간다() {
        StreamProperties properties = new StreamProperties(
                Duration.ofSeconds(20), Duration.ofNanos(500_000), 4, 1000, 4, 50, 500);

        assertThat(properties.timeout())
                .as("0은 아니지만 toMillis()가 0으로 잘라 「시한 없음」이 된다")
                .isEqualTo(Duration.ofHours(4));
    }

    /**
     * <b>덮되 조용히 덮지 않는다.</b> 운영자가 {@code PT0.0005S}를 적어 놓고 그대로 적용됐다고
     * 믿는 것을 막는다 — 덮었다는 사실 자체에 증상이 없기 때문이다.
     */
    @Test
    void timeout을_덮을_때_WARN을_남긴다() {
        try (LogCaptor captor = new LogCaptor()) {
            new StreamProperties(Duration.ofSeconds(20), Duration.ofNanos(500_000), 4, 1000, 4, 50, 500);

            assertThat(captor.levelOf("pokeclip.jump-card.stream.timeout"))
                    .as("덮었는데 아무 흔적도 안 남으면 설정이 틀린 채로 배포된다")
                    .isEqualTo(Level.WARN);
            assertThat(captor.messages())
                    .anyMatch(m -> m.contains("PT0.0005S") && m.contains("PT4H"));
        }
    }

    /**
     * <b>다른 값에는 일부러 안 남긴다.</b> 대칭을 깬 것이 아니라 <b>결과의 종류가 다르다</b> —
     * {@code stripes}·큐·상한 셋이 틀리면 느려지거나 거부가 늘어 <b>운영자가 겪어서 안다</b>.
     * {@code heartbeat}가 틀리면 프록시가 연결을 끊어 역시 보인다. {@code timeout}만
     * <b>인증 경계가 조용히 무너진다</b>. 증상이 없는 것만 로그로 알린다.
     */
    @Test
    void 숫자_설정을_덮을_때는_로그를_안_남긴다() {
        try (LogCaptor captor = new LogCaptor()) {
            new StreamProperties(Duration.ofSeconds(20), Duration.ofHours(4), 0, 0, 0, 0, 0);

            assertThat(captor.messages())
                    .as("증상이 있는 설정까지 로그를 내면 진짜 신호가 묻힌다")
                    .noneMatch(m -> m.contains("pokeclip.jump-card.stream."));
        }
    }

    /** 양성 대조. 하한이 지나치게 넓으면 멀쩡한 설정까지 기본값으로 덮는다. */
    @Test
    void 멀쩡한_값은_그대로_쓴다() {
        StreamProperties properties = new StreamProperties(
                Duration.ofSeconds(5), Duration.ofMinutes(30), 8, 500, 2, 20, 200);

        assertThat(properties.heartbeat()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.timeout()).isEqualTo(Duration.ofMinutes(30));
        assertThat(properties.stripes()).isEqualTo(8);
        assertThat(properties.maxPerUser()).isEqualTo(2);
    }

    /** 경계 바로 위. 하한을 {@code <= 1}로 잘못 쓰면 이것까지 덮인다. */
    @Test
    void 딱_1ms는_그대로_쓴다() {
        StreamProperties properties = new StreamProperties(
                Duration.ofSeconds(20), Duration.ofMillis(1), 4, 1000, 4, 50, 500);

        assertThat(properties.timeout()).isEqualTo(Duration.ofMillis(1));
    }
}
