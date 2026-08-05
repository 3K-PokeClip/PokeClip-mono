package com.pokeclip.chat.collector;

import com.pokeclip.chat.collector.fake.FakeChzzkBehavior;
import com.pokeclip.chat.collector.fake.FakeChzzkTest;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 종료 경로를 <b>스프링 컨텍스트를 실제로 닫아서</b> 밟는다.
 *
 * <p>러너의 {@code stop()}을 직접 부르는 것으로는 이 결함을 또 못 잡는다 —
 * 그게 종료 경로가 통째로 비어 있는 채로 계획 4라운드·CP 5개·검토 5회·verifier
 * 2회를 전부 통과한 이유다. <b>@PreDestroy가 진짜로 불리는지</b>는 컨테이너가
 * 닫혀야만 알 수 있다.
 *
 * <p>가짜 서버는 이 클래스의 컨텍스트에서 돌고, 수집기는 <b>따로 띄운 컨텍스트</b>다.
 * 그래야 수집기 쪽만 닫아도 가짜 서버가 살아 있어 "무엇을 받았는지" 물어볼 수 있다.
 */
@FakeChzzkTest
class CollectorShutdownTest {

    @LocalServerPort int port;
    @Autowired FakeChzzkBehavior behavior;

    @AfterEach
    void tearDown() {
        behavior.reset();
    }

    @Test
    void 컨텍스트를_닫으면_구독을_반납하고_소켓을_닫고_판정_라인을_남긴다() {
        try (LogCaptor captor = new LogCaptor()) {
            ConfigurableApplicationContext context = bootCollector();

            // 양성 대조. 붙은 적이 없으면 아래 단언 전부가 "안 했다"를 통과로 읽는다.
            assertThat(context.getBean(CollectionStatus.class).state())
                    .as("수집이 시작되지 않았다면 종료 검사는 아무것도 안 본 것이다")
                    .isEqualTo(CollectionStatus.State.COLLECTING);
            assertThat(behavior.unsubscribeCallCount())
                    .as("닫기도 전에 반납이 왔다면 아래 단언이 무의미하다")
                    .isZero();

            context.close();          // ← @PreDestroy 경로가 진짜로 도는 자리

            assertThat(behavior.unsubscribeCallCount())
                    .as("구독을 반납 안 하면 세션 반납이 서버가 알아챌 때까지 미뤄진다")
                    .isEqualTo(1);
            assertThat(behavior.closedSessionCount())
                    .as("WS가 안 닫혔으면 서버는 우리가 살아 있다고 본다")
                    .isEqualTo(1);
            assertThat(behavior.receivedFrames())
                    .as("끊는다고 알리는 프레임이 없으면 서버는 죽은 전송을 스스로 알아채야 한다")
                    .contains("1");
            assertThat(captor.messages())
                    .anyMatch(m -> m.startsWith("chat.session.verdict"));
        }
    }

    /** 반납 순서가 뒤집히면 소켓이 먼저 닫혀 반납이 무의미해진다. */
    @Test
    void 소켓을_닫기_전에_반납을_보낸다() {
        ConfigurableApplicationContext context = bootCollector();
        assertThat(context.getBean(CollectionStatus.class).state())
                .isEqualTo(CollectionStatus.State.COLLECTING);

        context.close();

        // 가짜 서버는 반납 REST를 세션이 열려 있는 동안에만 받을 수 있다.
        // 순서가 뒤집혔다면 위 단언이 아니라 여기가 깨진다.
        assertThat(behavior.unsubscribeCallCount()).isEqualTo(1);
    }

    private ConfigurableApplicationContext bootCollector() {
        // 명령행 인자로 넘긴다. .properties()는 기본값 소스라 우선순위가 가장 낮아
        // application-test.yml의 enabled: false에 진다 — 실제로 그렇게 됐다.
        return new SpringApplicationBuilder(CollectorApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("test")
                .run("--pokeclip.chzzk.enabled=true",
                        "--pokeclip.chzzk.base-url=http://localhost:" + port,
                        "--pokeclip.chzzk.establish-timeout=" + Duration.ofSeconds(5));
    }
}
