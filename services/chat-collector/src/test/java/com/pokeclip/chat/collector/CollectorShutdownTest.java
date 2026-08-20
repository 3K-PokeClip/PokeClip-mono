package com.pokeclip.chat.collector;

import com.pokeclip.chat.collector.fake.FakeChzzkBehavior;
import com.pokeclip.chat.collector.fake.FakeChzzkTest;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
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
class CollectorShutdownTest extends IntegrationTestSupport {

    @LocalServerPort int port;
    @Autowired FakeChzzkBehavior behavior;

    @AfterEach
    void tearDown() {
        behavior.reset();
    }

    @Test
    void 컨텍스트를_닫으면_구독을_반납하고_소켓을_닫고_판정_라인을_남긴다() throws Exception {
        ConfigurableApplicationContext context = bootCollector();

        // 양성 대조. 붙은 적이 없으면 아래 단언 전부가 "안 했다"를 통과로 읽는다.
        assertThat(context.getBean(CollectionStatus.class).state())
                .as("수집이 시작되지 않았다면 종료 검사는 아무것도 안 본 것이다")
                .isEqualTo(CollectionStatus.State.COLLECTING);
        assertThat(behavior.unsubscribeCallCount())
                .as("닫기도 전에 반납이 왔다면 아래 단언이 무의미하다")
                .isZero();
        // 번호는 프로세스 안에서 유일하다. 상수 1을 박으면 남의 세션 줄과 갈리지 않고,
        // 컨텍스트를 닫은 뒤에는 빈을 못 꺼내므로 지금 읽어 둔다.
        long session = context.getBean(CollectorRunner.class).lastSessionNo();

        // LogCaptor를 부팅 "뒤에" 단다. 스프링 부트는 시작하면서 로그백을
        // 재초기화하는데, 그때 루트 로거에 붙여 둔 appender가 통째로 떨어진다.
        // 부팅 전에 달면 수집 로그가 한 줄도 안 잡혀 판정 라인 단언이
        // 빈 목록에서 실패한다 — 실제로 전체 실행에서 그렇게 났다.
        try (LogCaptor captor = new LogCaptor()) {
            context.close();      // ← @PreDestroy 경로가 진짜로 도는 자리

            assertThat(captor.messages())
                    .as("판정 라인이 종료 경로에서 안 나오면 무엇을 수집했는지 알 길이 없다")
                    .anyMatch(m -> m.startsWith("chat.session.verdict"));

            // 정상 종료의 사유값을 아무 테스트도 안 봤다. 값을 보는 것은
            // TRANSPORT_CLOSED·SESSION_AUTH_FAILED 둘뿐이라, null → "SHUTDOWN"
            // 분기를 "UNKNOWN"으로 바꿔도 전부 초록이었다.
            assertThat(captor.messages())
                    .as("정상 종료의 사유가 없으면 조용히 끊긴 것과 같은 줄이 된다")
                    .anyMatch(m -> m.startsWith("chat.session.verdict")
                            && m.contains("reason=SHUTDOWN"));

            // 반납 결말 셋 중 실제로 검사되던 것은 failed 하나뿐이었다. 두 라벨을
            // 서로 뒤바꿔도(RETURNED("skipped")·SKIPPED("returned")) 전체 89건이
            // 초록이었다 — 운영에서 subscription=returned를 보고 "반납됐다"고 읽는데
            // 실제로는 수립 실패였던 상태가 만들어질 수 있다.
            assertThat(captor.messages())
                    .as("반납에 성공한 결말이 returned로 안 나가면 세 갈래를 가른 의미가 없다")
                    .contains("chat.session.released session=" + session + " stream=none subscription=returned");
        }

        assertThat(behavior.unsubscribeCallCount())
                .as("구독을 반납 안 하면 세션 반납이 서버가 알아챌 때까지 미뤄진다")
                .isEqualTo(1);

        // 서버가 종료를 관측하는 것은 비동기다. context.close()가 돌아온 시점에
        // 아직 안 왔을 수 있어 기다린다 — 바로 단언하면 간헐 실패한다.
        awaitClosedByServer();
        assertThat(behavior.closedSessionCount())
                .as("WS가 안 닫혔으면 서버는 우리가 살아 있다고 본다")
                .isEqualTo(1);
        assertThat(behavior.receivedFrames())
                .as("끊는다고 알리는 프레임이 없으면 서버는 죽은 전송을 스스로 알아채야 한다")
                .contains("1");
    }

    private void awaitClosedByServer() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (behavior.closedSessionCount() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    /** 반납 순서가 뒤집히면 소켓이 먼저 닫혀 반납이 무의미해진다. */
    @Test
    void 소켓을_닫기_전에_반납을_보낸다() {
        ConfigurableApplicationContext context = bootCollector();
        assertThat(context.getBean(CollectionStatus.class).state())
                .isEqualTo(CollectionStatus.State.COLLECTING);

        context.close();

        // 양성 대조. 반납이 아예 안 왔으면 아래 단언은 "안 열려 있었다"가 아니라
        // "물어본 적이 없다"를 읽는다.
        assertThat(behavior.unsubscribeCallCount()).isEqualTo(1);

        // 건수만 세면 순서를 전혀 안 본다 — releaseAndClose()에서 close()를 먼저
        // 부르도록 뒤집어도 전부 초록이었다. 가짜 서버가 "반납이 왔을 때 WS가
        // 열려 있었는가"를 기록하게 하고 그 값을 본다.
        assertThat(behavior.unsubscribeSawOpenSession())
                .as("소켓이 먼저 닫혔다면 반납은 이미 정리되는 중인 세션에 간 것이다")
                .isTrue();
    }

    /**
     * 반납 실패 갈래. <b>여기가 없으면 이 갈래를 밟는 테스트가 0개고</b>,
     * unsubscribeChatQuietly의 try/catch를 지워도 빨간불이 안 난다 —
     * 예외가 종료 훅 밖으로 나가면 소켓 닫기가 통째로 건너뛰어진다는
     * 그 catch의 존재 이유가 무검사로 남는다.
     */
    @Test
    void 반납이_실패해도_소켓을_닫고_사유를_따로_남긴다() throws Exception {
        behavior.unsubscribeStatus = 500;
        ConfigurableApplicationContext context = bootCollector();
        assertThat(context.getBean(CollectionStatus.class).state())
                .isEqualTo(CollectionStatus.State.COLLECTING);
        long session = context.getBean(CollectorRunner.class).lastSessionNo();

        try (LogCaptor captor = new LogCaptor()) {
            context.close();

            // skipped 하나로 뭉뚱그리면 "반납할 세션 키가 없었다"(수립 실패)와
            // "반납을 보냈는데 실패했다"를 아무도 못 가른다.
            assertThat(captor.messages())
                    .as("반납이 매번 터져도 수립 실패와 같은 줄이 나가면 원인을 못 찾는다")
                    .contains("chat.session.released session=" + session + " stream=none subscription=failed");
        }

        // 양성 대조. 반납을 아예 안 보냈다면 실패 갈래를 밟은 것이 아니다.
        assertThat(behavior.unsubscribeCallCount()).isEqualTo(1);

        awaitClosedByServer();
        assertThat(behavior.closedSessionCount())
                .as("반납이 터졌다고 소켓 닫기를 건너뛰면 서버는 우리가 살아 있다고 본다")
                .isEqualTo(1);
    }

    /**
     * <b>종료가 구독 반납 왕복을 인터럽트하면 안 된다.</b>
     *
     * <p>반납은 실서버에서 약 1초 걸리는 왕복이고 읽기 시한은 5초다. 그런데
     * {@code stop()}은 재연결 스레드를 2초만 기다리고 {@code shutdownNow()}로
     * 인터럽트했다 — 치지직이 느리게 답하는 배포 순간에 정확히 걸린다.
     *
     * <p>인터럽트되면 반납 REST가 즉시 실패하고, <b>세션 키는 이미 소모된 뒤라
     * 아무도 다시 못 보낸다</b>({@code releaseAndClose}가 먼저 걷어 가고 자리도
     * 이미 비었다). 그러면 서버가 죽은 전송을 알아챌 때까지 10초~4분 42초 동안
     * 자리가 남고(실측), 상한이 3개라 짧은 간격의 재시작 세 번이면 막힌다 —
     * 이 카드가 없애려던 좀비를 종료 경로가 만드는 셈이다.
     *
     * <p><b>지연이 5초인 이유는 창 때문이다.</b> 헛통과하는 것은 {@code stop()}이
     * 판단하기 <b>전에</b> 반납이 스스로 끝난 실행이고, 그 여백은
     * 지연 − 기본 대기(2초)다. 3초면 창이 1초뿐이라 컨텍스트 닫기가 1초만 넘어도
     * <b>단언이 저절로 참</b>이 된다 — 인터럽트를 되살려도 초록인 실행이 생긴다.
     * 5초면 3초다.
     *
     * <p><b>읽기 시한을 같이 올린다.</b> 기본 5초 그대로면 반납이 스스로
     * read-timeout으로 죽어 "인터럽트당한 것"과 같은 결말이 되고, 무엇 때문에
     * 실패했는지 갈리지 않는다. 여기서 보는 것은 시한이 아니라 <b>종료가
     * 기다리는가</b>다 — 시한 자체는 {@code HttpTimeoutTest}가 본다.
     */
    @Test
    void 반납_왕복_중에_종료해도_인터럽트하지_않고_기다린다() throws Exception {
        behavior.unsubscribeDelay = Duration.ofSeconds(5);

        ConfigurableApplicationContext context =
                bootCollector("--spring.http.clients.read-timeout=15s");
        assertThat(context.getBean(CollectionStatus.class).state())
                .as("붙지도 않았다면 반납할 구독이 없어 이 검사는 아무것도 안 본다")
                .isEqualTo(CollectionStatus.State.COLLECTING);
        long session = context.getBean(CollectorRunner.class).lastSessionNo();

        try (LogCaptor captor = new LogCaptor()) {
            // 절단이 재연결 스레드를 뒷정리로 보낸다. 반납이 서버에 도착한 시점
            // (지연은 센 뒤에 건다)이 곧 그 스레드가 왕복에 갇힌 시점이다.
            behavior.closeSession();
            awaitUnsubscribeCall();
            assertThat(behavior.unsubscribeCallCount())
                    .as("반납이 아직 안 나갔다면 종료가 인터럽트할 왕복이 없다")
                    .isEqualTo(1);

            long began = System.nanoTime();
            context.close();
            Duration closing = Duration.ofNanos(System.nanoTime() - began);

            assertThat(captor.messages())
                    .as("반납 왕복을 인터럽트하면 자리가 서버에 남아 상한 3개를 먹는다")
                    .contains("chat.session.released session=" + session
                            + " stream=none subscription=returned");

            // <b>전제를 같이 잰다.</b> 지연이 어쩌다 사라지면 반납이 종료보다 먼저
            // 끝나고, 그러면 위 단언은 인터럽트를 되살려도 통과한다 — 검사가 아무것도
            // 안 지키는 상태가 조용히 생긴다. 닫기가 기본 대기(2초)보다 오래 걸렸다는
            // 것이 곧 "반납이 그 시점에 아직 나가 있었다"는 뜻이다.
            assertThat(closing)
                    .as("닫기가 기본 대기 안에 끝났다면 인터럽트할 왕복이 없었던 것이다")
                    .isGreaterThan(Duration.ofSeconds(2));
        }
    }

    private void awaitUnsubscribeCall() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (behavior.unsubscribeCallCount() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    private ConfigurableApplicationContext bootCollector(String... extraArgs) {
        // 명령행 인자로 넘긴다. .properties()는 기본값 소스라 우선순위가 가장 낮아
        // application-test.yml의 enabled: false에 진다 — 실제로 그렇게 됐다.
        String[] args = java.util.stream.Stream.concat(
                java.util.stream.Stream.of(
                        "--pokeclip.chzzk.enabled=true",
                        "--pokeclip.chzzk.base-url=http://localhost:" + port,
                        "--pokeclip.chzzk.establish-timeout=" + Duration.ofSeconds(5),
                        // 따로 띄우는 컨텍스트라 @DynamicPropertySource가 안 걸린다.
                        // 안 넘기면 localhost:5432로 가서 — 로컬 PG가 꺼져 있으면 부팅
                        // 실패, 켜져 있으면 팀 공용 DB에 V301을 조용히 적용한다.
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword()),
                java.util.Arrays.stream(extraArgs)).toArray(String[]::new);
        return new SpringApplicationBuilder(CollectorApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("test")
                .run(args);
    }
}
