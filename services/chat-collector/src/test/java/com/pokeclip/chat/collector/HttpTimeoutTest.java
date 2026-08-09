package com.pokeclip.chat.collector;

import com.pokeclip.chat.collector.fake.FakeChzzkBehavior;
import com.pokeclip.chat.collector.fake.FakeChzzkTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * {@code spring.http.clients.*}에 적어 둔 타임아웃이 <b>실제로 걸리는지</b>를
 * 행동으로 잰다. 설정을 읽는 코드가 있는지가 아니라, 답을 안 주는 서버를 두고
 * 그 시한 안에 끊기는지를 본다.
 *
 * <p><b>연결은 받고 답을 안 주는 상태</b>가 이 검사의 표적이다. 거부(401)와 다르다 —
 * 거부는 즉시 사유가 나오는데 이쪽은 시한이 없으면 영영 매달린다. 그때 멈추는 것은
 * 수립 하나가 아니다: {@code createSession()}은 {@code establishTimeout}으로 못 끊는
 * 동기 호출이라 <b>부팅이 안 끝나고</b>, 같은 클라이언트가 종료 시 구독 반납에도 쓰여
 * <b>종료도 안 끝난다.</b>
 *
 * <p>수집기를 별도 컨텍스트로 띄우는 이유는, 러너가 실제로 스프링에서 받는 클라이언트를
 * 검사해야 하기 때문이다. 테스트가 손으로 만든 클라이언트를 넘기면 러너가 그것을 버리고
 * 자기 것을 새로 만들어도 초록이다 — 그게 정확히 여기서 잡으려는 결함이다.
 */
@FakeChzzkTest
class HttpTimeoutTest {

    /** 가짜 서버가 붙들고 있는 시간. read-timeout보다 훨씬 길다. */
    private static final Duration AUTH_DELAY = Duration.ofSeconds(6);

    /** 이 값이 실제로 걸리는지가 검사 대상이다. 운영값은 application.yml에 있다. */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(1);

    /**
     * <b>판정은 아래 heldFor 단언이 한다.</b> 이 상한은 그 단언에 도달조차 못 하는
     * 경우만 막는 안전망이라 넉넉하다 — 시한이 어디에도 안 걸리고 서버마저 답을
     * 영영 안 주면 이 테스트가 빨간불 대신 <b>멈춘 채로</b> 끝난다.
     *
     * <p>상한을 판정에 쓰면 실패 메시지가 "8초 넘었다"가 되어, 무엇을 재려던
     * 검사였는지가 사라진다.
     */
    private static final Duration UPPER_BOUND = Duration.ofSeconds(25);

    @LocalServerPort int port;
    @Autowired FakeChzzkBehavior behavior;

    @AfterEach
    void tearDown() {
        behavior.reset();
    }

    @Test
    void 세션_발급이_답을_안_주면_설정한_read_timeout에서_끊는다() {
        behavior.authDelay = AUTH_DELAY;

        ConfigurableApplicationContext context =
                assertTimeoutPreemptively(UPPER_BOUND, this::bootCollector);
        // 부팅 시간이 안 섞이도록 요청이 서버에 도착한 시점부터 잰다.
        Duration heldFor = behavior.sinceAuthRequest();
        // <b>같은 순간에 걷는다.</b> 부팅이 끝난 뒤에도 재연결이 계속 두드리므로,
        // 나중에 읽으면 이 수가 재시도로 부풀어 양성 대조가 아니라 잡음이 된다.
        int callsAtBoot = behavior.authCallCount();

        try {
            // 양성 대조. 발급을 아예 안 시도했다면 위 시간은 아무것도 안 잰 것이다.
            assertThat(callsAtBoot)
                    .as("세션 발급을 시도한 적이 없으면 시한을 잰 것이 아니다")
                    .isEqualTo(1);

            // 여기가 이 검사의 본체다. 상태만 보면 "시한에 걸려 멈췄다"는 잡아도
            // 어디서 멈췄는지는 안 나오고, 무엇보다 <b>얼마나 기다렸는지</b>가 안 나온다.
            // 그래서 상태보다 먼저 단언한다 — 순서를 뒤집으면 실패 메시지가
            // "STOPPED가 아니다"가 되어 시간을 잰 검사였다는 것이 사라진다.
            assertThat(heldFor)
                    .as("서버가 붙들고 있는 " + AUTH_DELAY.toSeconds()
                            + "초를 다 기다렸다면 read-timeout이 어디에도 안 걸린 것이다")
                    .isLessThan(READ_TIMEOUT.multipliedBy(3));

            CollectionStatus status = context.getBean(CollectionStatus.class);
            // 시한 초과는 다시 걸면 풀릴 수 있는 사유라 영구 정지가 아니다.
            assertThat(status.state()).isEqualTo(CollectionStatus.State.RECONNECTING);
            assertThat(status.reason())
                    .as("시한에 걸린 것을 사유 없이 멈추면 조용한 실패가 된다")
                    .isEqualTo(StopReason.SESSION_AUTH_FAILED);
        } finally {
            context.close();
        }
    }

    /**
     * 명령행 인자로 넘긴다. {@code .properties()}는 기본값 소스라 우선순위가 가장 낮아
     * application-test.yml의 {@code enabled: false}에 진다.
     */
    private ConfigurableApplicationContext bootCollector() {
        return new SpringApplicationBuilder(CollectorApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("test")
                .run("--pokeclip.chzzk.enabled=true",
                        "--pokeclip.chzzk.base-url=http://localhost:" + port,
                        // 수립 시한을 지연보다 길게 준다. 이래야 끊은 주체가
                        // establishTimeout이 아니라 read-timeout임이 확정된다.
                        "--pokeclip.chzzk.establish-timeout=" + Duration.ofSeconds(30),
                        // 재시도가 이 측정을 오염시키지 않게 한 번만 재게 한다.
                        // 서버는 6초를 붙들고 있으므로 첫 간격이 짧으면 측정 창 안에
                        // 두 번째 요청이 들어와 "얼마나 기다렸나"가 흐려진다.
                        "--pokeclip.chzzk.reconnect-first-delay=30s",
                        "--spring.http.clients.read-timeout=" + READ_TIMEOUT);
    }
}
