package com.pokeclip.clip.jumpcard.stream;

import ch.qos.logback.classic.Level;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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

    /**
     * <b>{@code heartbeat}에는 상한도 필요하다.</b> 하한만 두면 <b>아주 큰 값</b>이 통과하는데,
     * {@code CardStreamRegistry.startHeartbeat}의 {@code toMillis()}가 그것을 자르다
     * {@code ArithmeticException("long overflow")}으로 <b>부팅을 죽인다</b>
     * (2026-08-24 실기동 재현, PR #113 봇 지적 ④).
     *
     * <p><b>경계를 두 값으로 못박는다.</b> 넘치는 자리는 초 값이
     * {@code Long.MAX_VALUE / 1000 = 9,223,372,036,854,775}를 넘을 때다:
     * <ul>
     *   <li>{@code PT2562047788015H} = 9,223,372,036,854,000초 → {@code toMillis()} 성공 → <b>그대로 쓴다</b></li>
     *   <li>{@code PT2562047788016H} = 9,223,372,036,857,600초 → {@code toMillis()} 던짐 → <b>덮는다</b></li>
     * </ul>
     *
     * <p>🔴 <b>봇이 예로 든 {@code PT100000000000H}로 시험을 쓰면 아무것도 안 재게 된다</b> —
     * 그 값은 {@code toMillis()}가 360,000,000,000,000,000을 돌려주고 <b>부팅도 멀쩡히 된다</b>
     * (같은 날 실기동으로 확인: {@code Started ClipApplication in 2.569 seconds}).
     * 그래서 아래 마지막 단언이 그 값을 <b>덮이지 않는 쪽</b>에 둔다.
     */
    @Test
    void heartbeat가_ms로_자를_수_없을_만큼_크면_기본값이_들어간다() {
        assertThat(new StreamProperties(Duration.parse("PT2562047788016H"), Duration.ofHours(4),
                4, 1000, 4, 50, 500).heartbeat())
                .as("toMillis()가 long을 넘겨 startHeartbeat에서 부팅이 죽는다")
                .isEqualTo(Duration.ofSeconds(20));

        assertThat(new StreamProperties(Duration.parse("PT2562047788015H"), Duration.ofHours(4),
                4, 1000, 4, 50, 500).heartbeat())
                .as("경계 바로 아래는 자를 수 있으므로 덮으면 안 된다")
                .isEqualTo(Duration.parse("PT2562047788015H"));

        assertThat(new StreamProperties(Duration.parse("PT100000000000H"), Duration.ofHours(4),
                4, 1000, 4, 50, 500).heartbeat())
                .as("봇이 든 값이다. 오버플로하지 않으므로 이것으로 시험을 쓰면 아무것도 안 잰다")
                .isEqualTo(Duration.parse("PT100000000000H"));
    }

    /**
     * <b>{@code stripes}에도 상한이 필요하다 — {@code heartbeat}와 같은 모양의 구멍이다.</b>
     * 하한만 두면 아주 큰 값이 통과하는데 {@code CardStreamExecutor}가 그 수만큼 배열을 잡다
     * <b>부팅이 죽는다</b>(2026-08-24 실기동:
     * {@code OutOfMemoryError: Requested array size exceeds VM limit}, PR #114 재현 중 발견).
     *
     * <p><b>경계를 둘로 못박는다.</b> 상한(1024)은 그대로 쓰고 1만 넘어도 덮는다 —
     * 하나만 쓰면 「상한이 있다」는 알아도 어디인지는 못 잡는다.
     */
    @Test
    void stripes가_상한을_넘으면_기본값이_들어간다() {
        assertThat(new StreamProperties(Duration.ofSeconds(20), Duration.ofHours(4),
                1024, 1000, 4, 50, 500).stripes())
                .as("상한 정각은 스레드 1024개다 — 덮으면 안 된다").isEqualTo(1024);

        assertThat(new StreamProperties(Duration.ofSeconds(20), Duration.ofHours(4),
                1025, 1000, 4, 50, 500).stripes())
                .as("1만 넘어도 덮어야 경계가 못 박힌다").isEqualTo(4);

        assertThat(new StreamProperties(Duration.ofSeconds(20), Duration.ofHours(4),
                Integer.MAX_VALUE, 1000, 4, 50, 500).stripes())
                .as("실기동에서 부팅을 죽인 값이다").isEqualTo(4);
    }

    /**
     * <b>덮는 것만으로는 부족하고 「그래서 뜨는가」까지 봐야 한다.</b> 죽던 자리가
     * {@code StreamProperties}가 아니라 <b>그 값을 받은 {@code CardStreamExecutor}의 생성자</b>라,
     * 두 빈을 같이 올려야 이 갈래를 실제로 지나간다.
     *
     * <p>상한을 지우면 여기서 {@code OutOfMemoryError}가 난다 — 배열 <b>크기 검사</b>에서 즉시
     * 터지므로 힙을 먹지 않는다(결함 주입으로 확인).
     */
    @Test
    void stripes가_아주_커도_컨텍스트가_뜬다() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(BoundProperties.class)
                .withBean(CardStreamExecutor.class)
                .withPropertyValues("pokeclip.jump-card.stream.stripes=2147483647")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(StreamProperties.class).stripes()).isEqualTo(4);
                    assertThat(context).hasSingleBean(CardStreamExecutor.class);
                });
    }

    /**
     * <b>같이 훑은 나머지 {@code int} 설정은 상한이 필요 없다 — 이것도 적어 둬야 다시 안 훑는다.</b>
     * {@code queue-capacity}는 {@code LinkedBlockingQueue}라 <b>지연 할당</b>이고
     * ({@code ArrayBlockingQueue}였다면 {@code stripes}와 같은 자리였다), 상한 셋은 비교에만 쓴다.
     * 2026-08-24 실기동: 넷 다 {@code 2147483647}로 부팅 성공({@code Started ClipApplication
     * in 2.186 seconds})했고 SSE 연결도 200이었다.
     */
    @Test
    void 나머지_int_설정은_최대값이어도_그대로_쓴다() {
        StreamProperties properties = new StreamProperties(Duration.ofSeconds(20), Duration.ofHours(4),
                4, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);

        assertThat(properties.queueCapacity()).isEqualTo(Integer.MAX_VALUE);
        assertThat(properties.maxPerUser()).isEqualTo(Integer.MAX_VALUE);
        assertThat(properties.maxPerStream()).isEqualTo(Integer.MAX_VALUE);
        assertThat(properties.maxTotal()).isEqualTo(Integer.MAX_VALUE);
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

    @EnableConfigurationProperties(StreamProperties.class)
    static class BoundProperties {
    }
}
