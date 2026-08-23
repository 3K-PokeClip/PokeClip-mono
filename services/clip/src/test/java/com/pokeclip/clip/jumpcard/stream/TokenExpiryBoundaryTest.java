package com.pokeclip.clip.jumpcard.stream;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.jumpcard.JumpCardErrors.TokenAlreadyExpiredException;
import com.pokeclip.clip.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>토큰 수명이 1ms 미만으로 남았을 때 연결을 열면 안 된다.</b>
 *
 * <p>컨트롤러가 {@code isZero() || isNegative()}만 보면 {@code 0 < 남은수명 < 1ms}가 통과하고,
 * 기본 팩토리의 {@code d.toMillis()}가 <b>0</b>을 준다. 서블릿 규약상 {@code timeout <= 0}은
 * 「시한 없음」이라 <b>만료 직전 토큰으로 연 연결이 영원히 산다</b> — 인가 2차 감사가
 * {@code -59311ms}로 잡은 것과 <b>같은 결함의 다른 입구</b>다(PR #111 봇 지적 ④).
 *
 * <p><b>HTTP로는 못 잰다.</b> {@code exp}를 0.5ms 뒤로 맞추려 해도 요청이 도착하는 데 그보다
 * 오래 걸린다. 컨트롤러를 직접 불러 {@code SseEmitter}가 받은 시한을 읽는다.
 *
 * <p><b>진짜 토큰의 {@code exp}는 초 단위다</b>(2026-08-23 실측: 디코딩하면 {@code nano=0}).
 * 그래서 운영에서 이 창은 「초 경계 직전 1ms」 하나다. 좁지만 닫혀 있지 않고,
 * <b>설정 갈래({@code StreamPropertiesTest})는 타이밍 없이 결정적으로 열린다.</b>
 *
 * <p>상한을 크게 열어 둔 이유: 이 시험은 시한 가드만 잰다. 컨트롤러를 직접 부르면 서블릿이
 * emitter를 모르므로 정리 콜백이 안 불려 자리가 쌓이는데, 그것이 {@code 503}으로 번지면
 * 재는 대상이 흐려진다.
 */
@TestPropertySource(properties = {
        "pokeclip.jump-card.stream.timeout=PT3S",
        "pokeclip.jump-card.stream.max-per-user=100000",
        "pokeclip.jump-card.stream.max-per-stream=100000",
        "pokeclip.jump-card.stream.max-total=100000"})
class TokenExpiryBoundaryTest extends IntegrationTestSupport {

    /** 오프셋을 훑는 횟수. 재현 때 12~15회에 한 번 창에 들어갔다. */
    private static final int ATTEMPTS = 300;

    private final JumpCardStreamController controller;
    private final BroadcastRepository broadcasts;
    private final CardStreamRegistry registry;
    private final JdbcTemplate jdbc;

    TokenExpiryBoundaryTest(JumpCardStreamController controller, BroadcastRepository broadcasts,
                            CardStreamRegistry registry, JdbcTemplate jdbc) {
        this.controller = controller;
        this.broadcasts = broadcasts;
        this.registry = registry;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 정리() {
        방송과_카드를_비운다(jdbc);
        broadcasts.save(Broadcast.startedNow("s-1", "u-1", 1L, Instant.now(), null));
    }

    /**
     * <b>불변식으로 잰다</b> — 「0.5ms짜리 토큰을 만들면 401이다」로 쓰면 컨트롤러가 시계를 읽는
     * 시점을 못 맞춰 대부분 이미 만료로 흘러가고, <b>가드를 되돌려도 초록일 수 있다</b>.
     * 열리든 안 열리든 <b>시한 0짜리 emitter가 하나도 안 나오는 것</b>이 재는 대상이다.
     */
    @Test
    void 어떤_만료_직전_토큰도_시한_0인_연결을_만들지_못한다() {
        Map<String, Integer> outcome = new LinkedHashMap<>();

        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            long offsetNanos = 200_000L + (attempt % 200) * 25_000L; // 0.2ms ~ 5.2ms
            try {
                SseEmitter emitter = open(Instant.now().plusNanos(offsetNanos), "boundary-" + attempt);
                Long timeout = emitter.getTimeout();
                outcome.merge(timeout == null ? "timeout=null" : "timeout>=1", 1, Integer::sum);

                assertThat(timeout)
                        .as("남은 수명이 1ms 미만인데 연결이 열렸다 — SseEmitter가 「시한 없음」을 받는다")
                        .isNotNull()
                        .isGreaterThanOrEqualTo(1L);
            } catch (TokenAlreadyExpiredException e) {
                outcome.merge("401 token_expired", 1, Integer::sum);
            }
        }

        assertThat(outcome)
                .as("전부 401이면 창을 한 번도 안 지나간 것이라 가드를 재지 못했다")
                .containsKey("401 token_expired");
    }

    /** 경계 바로 위. 하한을 넓게 잡으면 멀쩡한 짧은 토큰까지 막힌다. */
    @Test
    void 남은_수명이_1ms_이상이면_연다() {
        SseEmitter emitter = open(Instant.now().plus(Duration.ofSeconds(2)), "boundary-ok");

        assertThat(emitter.getTimeout()).isBetween(1L, 3000L);
    }

    /** 양성 대조. 넉넉한 토큰은 설정 상한을 그대로 받는다. */
    @Test
    void 넉넉한_토큰은_설정값을_받는다() {
        SseEmitter emitter = open(Instant.now().plus(Duration.ofMinutes(30)), "boundary-normal");

        assertThat(emitter.getTimeout()).isEqualTo(3000L);
    }

    /**
     * <b>가드를 {@code toMillis()}로 쓰면 여기서 500이 난다</b> — 아주 먼 {@code exp}는
     * {@code Duration.toMillis()}가 long을 넘겨 {@code ArithmeticException("long overflow")}이
     * 된다(실측). 401이어야 할 자리가 500이 되면 프론트가 「토큰 문제」로 못 읽는다.
     * 발급자는 auth 하나라 이런 {@code exp}가 실제로 오려면 auth가 넣어야 하지만,
     * 가드가 <b>자기 입력에 터지는 것</b>은 그 자체로 신호를 죽인다.
     */
    @Test
    void 아주_먼_만료_토큰도_500이_아니라_설정값을_받는다() {
        SseEmitter emitter = open(Instant.MAX, "boundary-far");

        assertThat(emitter.getTimeout()).isEqualTo(3000L);
    }

    /**
     * <b>재는 시점과 시한이 걸리는 시점 사이에 자물쇠 대기가 통째로 들어간다.</b>
     *
     * <p>{@code untilExpiry}를 컨트롤러 입구에서 한 번만 재면, {@code openWithSnapshot}이
     * 자물쇠를 기다리는 동안 {@code exp}가 지나가도 그 낡은 값이 emitter에 걸린다 —
     * <b>만료된 토큰으로 연 연결이 {@code exp + 대기시간}까지 산다</b>
     * (2026-08-23 재현, PR #112 봇 지적 ④: 자물쇠를 3초 쥐었더니 만료 <b>2,508ms 뒤에</b>
     * 200으로 열렸고 연결이 {@code exp}를 <b>3,398ms</b> 넘겨 살았다).
     *
     * <p>입구의 가드는 그대로 둔다 — 명백히 만료된 토큰을 자물쇠를 잡기 전에 거르는 빠른 실패다.
     * 이 시험이 재는 것은 <b>두 번째</b> 판정이 자물쇠 안에 있는가다.
     *
     * <p>자물쇠를 밖에서 쥐는 것은 {@code publish}·{@code broadcastEnded}가 쓰는 모니터와
     * 같은 것을 잡는 것이다({@code synchronized} 인스턴스 메서드의 모니터는 인스턴스 자신이다).
     *
     * <p>🔴 <b>그래서 이 시험은 registry가 {@code synchronized}를 쓴다는 데 의존한다.</b>
     * 자물쇠를 {@code ReentrantLock}으로 바꾸면 여기가 <b>다른 것을 잡게 되어 지연을 못 만들고
     * 조용히 헛통과한다</b> — 바꾸는 사람은 이 시험을 같이 고쳐야 한다
     * ({@code PublishOrderTest}도 같은 의존이 있다. 저장소에서 그 둘뿐이다).
     * 검증 장치가 대상과 어긋나면 아무것도 안 재고도 초록이 된다 — 이 판에서 {@code SseReader}가
     * 관대해 {@code ended} 시험 여섯 개가 헛통과하던 것과 같은 모양이다.
     * 실제 운영에서 이 대기를 만드는 것은 남의 {@code openWithSnapshot}이 자물쇠 안에서 도는
     * DB 조회(5.9~22.4ms 실측)와 {@code publish}·{@code broadcastEnded}다.
     */
    @Test
    void 자물쇠를_기다리는_사이에_만료되면_열지_않는다() throws Exception {
        Duration hold = Duration.ofMillis(600);
        Instant exp = Instant.now().plusMillis(150);   // 기다리는 동안 지나간다

        CountDownLatch held = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            synchronized (registry) {
                held.countDown();
                try {
                    Thread.sleep(hold.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "expiry-lock-holder");
        holder.setDaemon(true);
        holder.start();
        held.await();
        Thread.sleep(20);   // 자물쇠를 확실히 쥔 뒤에 요청한다

        try {
            assertThatThrownBy(() -> open(exp, "boundary-lock-wait"))
                    .as("자물쇠를 기다리는 동안 exp가 지났는데 열리면 그 연결은 만료 토큰으로 산다")
                    .isInstanceOf(TokenAlreadyExpiredException.class);
        } finally {
            holder.join();
        }
    }

    @Test
    void 이미_만료된_토큰은_401이다() {
        assertThatThrownBy(() -> open(Instant.now().minusSeconds(30), "boundary-expired"))
                .isInstanceOf(TokenAlreadyExpiredException.class);
    }

    private SseEmitter open(Instant expiresAt, String subject) {
        Jwt jwt = Jwt.withTokenValue("boundary")
                .header("alg", "HS256")
                .subject(subject)
                .issuedAt(expiresAt.minus(Duration.ofMinutes(30)))
                .expiresAt(expiresAt)
                .build();
        return controller.open("s-1", jwt, null, null).getBody();
    }
}
