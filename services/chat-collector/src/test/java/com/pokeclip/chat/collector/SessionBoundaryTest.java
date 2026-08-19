package com.pokeclip.chat.collector;

import com.pokeclip.chat.collector.fake.FakeChzzkBehavior;
import com.pokeclip.chat.collector.fake.FakeChzzkTest;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import com.pokeclip.chat.collector.support.TestPersistence;
import com.pokeclip.chat.collector.support.TestHealth;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 뒷정리와 판정이 <b>세션마다</b> 도는지. 재연결이 붙기 전에 이것부터 푼다 —
 * 프로세스 1회 전제 위에 루프를 얹으면 두 번째 세션의 반납이 통째로 새고,
 * 연결 상한이 3개라 몇 번 만에 못 붙게 된다.
 */
@FakeChzzkTest
class SessionBoundaryTest extends IntegrationTestSupport {

    private static final Duration AWAIT = Duration.ofSeconds(5);

    /** 이 이름으로 도는 스레드가 곧 "아직 일하고 있다"의 증거다. */
    private static final Set<String> WORKER_NAMES = Set.of("chzzk-ping", "chzzk-summary");

    @LocalServerPort int port;
    @Autowired FakeChzzkBehavior behavior;
    @Autowired RestClient.Builder restClientBuilder;

    private CollectorRunner runner;
    /** 필드로 둔다 — 태스크 8·9a가 같은 클래스에 검사를 더하면서 이걸 읽는다. */
    private CollectionStatus status;

    @AfterEach
    void tearDown() {
        if (runner != null) runner.stop();
        behavior.reset();
    }

    /**
     * 러너 생성을 한 곳으로 모은다.
     *
     * <p><b>재시도 간격을 크게 준다.</b> 이 파일은 세션 경계를 손으로 그어 보는
     * 곳이라, 자동 재연결이 끼어들면 "테스트가 연 두 번째 세션"과 "루프가 연
     * 두 번째 세션"이 섞여 무엇을 검사했는지 알 수 없게 된다. <b>{@code authStatus=401}로
     * 막는 방법은 안 쓴다</b> — 그러면 {@code start()}가 던져 테스트 메서드가
     * 예외로 죽는다.
     *
     * @return 방금 선 세션의 번호. <b>줄을 고르는 열쇠라 반드시 받아서 쓴다</b> —
     *         상수 1·2를 박으면 남의 러너가 늦게 찍은 줄과 구분이 안 된다
     */
    private long startRunner() {
        status = new CollectionStatus();
        runner = new CollectorRunner(new ChzzkProperties(
                true, "test-token", "http://localhost:" + port,
                Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(60)),
                status, restClientBuilder,
                        TestPersistence.unusedBuffer(), TestPersistence.disabledPersister());
        runner.start();
        return runner.lastSessionNo();
    }

    /**
     * 재연결 루프는 아직 없다. <b>그래도 상태는 "다시 붙을 수 있는" 모양이어야 한다</b> —
     * {@code STOPPED}는 안 덮이는 상태라, 절단에 그것을 찍어 두면 루프를 얹어도
     * {@code establishing()}도 {@code collectingIfPending()}도 그 위를 못 지나
     * 영영 못 올라온다.
     */
    @Test
    void 절단_뒤에는_STOPPED가_아니라_RECONNECTING이다() throws Exception {
        startRunner();
        assertThat(status.state())
                .as("붙지도 않았다면 끊는 것에 아무 의미가 없다")
                .isEqualTo(CollectionStatus.State.COLLECTING);

        behavior.closeSession();

        awaitUntil(() -> status.state() == CollectionStatus.State.RECONNECTING);
        assertThat(status.state())
                .as("STOPPED로 찍으면 재연결이 붙어도 영영 못 올라온다")
                .isEqualTo(CollectionStatus.State.RECONNECTING);
        assertThat(status.reason())
                .as("무엇 때문에 끊겼는지가 없으면 재시도할 사유인지 사람이 못 가른다")
                .isEqualTo(StopReason.TRANSPORT_CLOSED);
        assertThat(TestHealth.legacyOnly(status).health().getStatus())
                .as("재연결 중에는 채팅이 실제로 안 들어온다. UP이면 밖에서 아무 신호도 없다")
                .isEqualTo(Status.DOWN);

        // <b>이 세션의 뒷정리가 끝난 것을 보고 나간다.</b> 상태는 자리를 비우기 전에
        // 찍히므로, 상태만 보고 빠져나오면 자기 반납이 tearDown의 reset() 뒤에 도착해
        // 다음 테스트의 수로 넘어간다 — 실제로 이 클래스의 뒤 테스트 셋이 그렇게 깨졌다.
        awaitUntil(() -> behavior.unsubscribeCallCount() == 1);
        assertThat(behavior.unsubscribeCallCount())
                .as("절단 뒤 반납이 안 나가면 자리가 서버에 남아 상한 3개를 먹는다")
                .isEqualTo(1);
    }

    /**
     * 재연결 루프가 할 일을 테스트가 손으로 한다. <b>계약이 성립하는지만 본다.</b>
     *
     * <p>루프가 붙기 전에 이 커밋만으로 "끊기면 DOWN이고 다시 수립하면 올라온다"가
     * 성립해야 한다. 성립 안 하면 9b는 상태 전이를 고치는 일과 루프를 붙이는 일을
     * 한 커밋에서 같이 하게 되고, 어느 쪽이 깨졌는지 가를 수 없다.
     */
    @Test
    void 절단_뒤_다시_수립하면_COLLECTING으로_돌아온다() throws Exception {
        startRunner();
        behavior.closeSession();

        awaitUntil(() -> status.state() == CollectionStatus.State.RECONNECTING);
        // 양성 대조. 여기 안 서면 "돌아왔다"가 아니라 "떠난 적이 없다"를 읽는다.
        assertThat(status.state())
                .as("재연결 중으로 가지도 않았다면 돌아오는 것을 검사할 수 없다")
                .isEqualTo(CollectionStatus.State.RECONNECTING);
        // <b>상태는 자리를 비우기 전에 찍힌다.</b> 상태만 보고 다시 시작하면
        // 앞 세션이 아직 자리를 들고 있어 start_skipped로 갈리고, 그러면 이 검사는
        // 계약이 아니라 타이밍을 잰다. 반납 도착은 자리를 비운 뒤의 사건이다.
        awaitUntil(() -> behavior.unsubscribeCallCount() == 1);

        runner.start();

        assertThat(status.state())
                .as("establishing()이 RECONNECTING을 안 덮으므로, "
                        + "collectingIfPending()이 RECONNECTING도 받아야 올라온다")
                .isEqualTo(CollectionStatus.State.COLLECTING);
        assertThat(TestHealth.legacyOnly(status).health().getStatus())
                .as("다시 붙었는데 DOWN으로 남으면 배포·헬스체크가 영영 안 통과한다")
                .isEqualTo(Status.UP);
    }

    @Test
    void 두_번째_세션도_반납하고_판정을_남긴다() throws Exception {
        try (LogCaptor captor = new LogCaptor()) {
            long first = startRunner();
            assertThat(status.state()).isEqualTo(CollectionStatus.State.COLLECTING);
            behavior.closeSession();
            awaitUntil(() -> behavior.unsubscribeCallCount() == 1);

            // 두 번째 세션. <b>재연결 루프가 아니라 테스트가 직접 연다</b> —
            // 여기서 보는 것은 "가드가 세션마다 새로 도는가"이고, 루프에 맡기면
            // 루프가 도는지까지 같이 재게 된다(그쪽은 ReconnectTest가 본다).
            runner.start();
            long second = runner.lastSessionNo();
            assertThat(status.state())
                    .as("한 번만 도는 가드가 남아 있으면 여기서 못 올라온다")
                    .isEqualTo(CollectionStatus.State.COLLECTING);

            // <b>두 번째 세션은 종료 경로로 치운다.</b> 여기서 또 끊으면 그 신호는
            // 아직 백오프 대기 중인 루프에 밀려 pending으로 들어가고, 그 루프가
            // 깨어날 때까지 아무도 안 치운다 — 운영에서는 루프만 세션을 열므로
            // "루프가 자는 동안 살아 있는 세션"이라는 상태 자체가 없다.
            runner.stop();

            awaitUntil(() -> behavior.unsubscribeCallCount() == 2);
            assertThat(behavior.unsubscribeCallCount())
                    .as("두 번째 세션의 반납이 안 나가면 자리가 서버에 남아 상한 3개를 먹는다")
                    .isEqualTo(2);
            // <b>세션 경계는 반납 횟수로 본다.</b> 판정은 더 이상 세션마다 안 나가고
            // 프로세스가 끝날 때 한 줄이다 — 재연결이 붙은 뒤로 절단은 끝이 아니라서,
            // 거기서 판정을 내면 최종이 아닌 최종 판정이 세션 수만큼 쌓인다.
            //
            // 세션 종료 줄은 세션마다 나간다. 그쪽이 "몇 번째 세션에서 무엇이
            // 막혔나"를 든다. 개수만 세지 않고 세션 번호로 좁힌다 — LogCaptor는
            // JVM 전역 루트 로거에 붙어 있어(web-support/LogCaptor.java:21-26)
            // 앞 클래스의 낙오 스레드가 늦게 찍은 줄이 빠진 것을 메워 줄 수 있다.
            assertThat(endedLines(captor, first)).as("첫 세션의 종료 줄").isEqualTo(1);
            assertThat(endedLines(captor, second))
                    .as("세션이 둘이면 종료 줄도 둘이다. 한 번만 도는 가드면 두 번째가 사라진다")
                    .isEqualTo(1);

            // 판정은 세션 수와 무관하게 한 줄이다. 절단마다 내면 그중 어느 것도
            // 최종이 아니고, 운영자는 "최종"을 세션 수만큼 보게 된다.
            assertThat(verdictLines(captor, first) + verdictLines(captor, second))
                    .as("세션이 둘이어도 수집이 끝난 것은 한 번이다")
                    .isEqualTo(1);
        }
    }

    /**
     * <b>앞 세션의 뒷정리가 반납 왕복에 갇힌 사이에</b> 다음 세션이 시작되는 창.
     *
     * <p>반납은 실서버에서 약 1초 걸린다(CLAUDE.md 실측). 그 동안 뒷정리 스레드는
     * 아직 자기 일이 안 끝났고, 깨어나서 마지막에 "세션 자리"를 지운다. 그 자리에
     * 이미 다음 세션이 들어와 있으면 <b>다음 세션이 통째로 지워진다</b> — 이후
     * 그 세션이 끊겨도 구독 반납도 소켓 닫기도 안 나가고, 상한이 3개라 금방 막힌다.
     *
     * <p>지연 300ms는 실측 1초보다 보수적인 값이다. 임의로 늘리거나 줄이지 않는다 —
     * 늘리면 이 테스트가 느려지기만 하고, 줄이면 재현이 다시 우연에 맡겨진다.
     *
     * <p><b>이 테스트를 실제로 지키는 것은 아래 `COLLECTING` 단언이다.</b> 자리를 늦게
     * 놓도록 되돌리면 그 줄에서 먼저 죽고, 마지막 「반납 == 2」에는 닿지도 않는다.
     * 즉 <b>지금 「반납 == 2」를 단독으로 빨갛게 만드는 변이는 없다</b>(CP1b가 찾지 못했다).
     * 그 줄이 무가치하다는 뜻이 아니라, <b>"창이 실제로 검사된다"의 근거로 그 줄을 들면
     * 안 된다</b>는 뜻이다 — CLAUDE.md의 T13(①②가 시한을 삼켜 본 단언에 안 닿는다)과
     * 같은 모양이다. 근거를 대야 할 때는 `COLLECTING` 쪽을 든다.
     */
    @Test
    void 앞_세션_반납이_왕복하는_사이에_시작한_세션도_반납된다() throws Exception {
        behavior.unsubscribeDelay = Duration.ofMillis(300);

        try (LogCaptor captor = new LogCaptor()) {
            long first = startRunner();
            assertThat(status.state()).isEqualTo(CollectionStatus.State.COLLECTING);

            behavior.closeSession();
            // 반납이 서버에 도착한 시점 = 뒷정리 스레드가 왕복에 갇힌 시점이다.
            awaitUntil(() -> behavior.unsubscribeCallCount() == 1);

            // 갇혀 있는 동안 자리를 잡는다. 이 순서가 이 테스트의 전부다.
            runner.start();
            assertThat(status.state())
                    .as("앞 세션 뒷정리가 반납에 갇혀 있어도 새 세션은 서야 한다")
                    .isEqualTo(CollectionStatus.State.COLLECTING);

            // <b>앞 뒷정리가 끝나는 것을 보고서야</b> 끊는다. 안 기다리면 새 세션의
            // 뒷정리가 앞 뒷정리보다 먼저 지나가 버려, 자리를 지우는 그 마지막
            // 한 줄을 지나기 전에 테스트가 끝난다 — 결함이 있어도 초록이 된다.
            awaitUntil(() -> releasedLines(captor, first) == 1);
            assertThat(releasedLines(captor, first))
                    .as("앞 세션 뒷정리가 끝나야 그 마지막 한 줄이 새 세션을 지울 기회를 갖는다")
                    .isEqualTo(1);

            // 새 세션은 종료 경로로 치운다. 또 끊으면 그 신호가 백오프 대기 중인
            // 루프에 밀려 pending으로 들어가 이 검사가 타이밍을 재게 된다.
            runner.stop();
            awaitUntil(() -> behavior.unsubscribeCallCount() == 2);
            assertThat(behavior.unsubscribeCallCount())
                    .as("앞 세션 정리가 새 세션 자리를 지우면 그 세션의 반납이 통째로 사라진다")
                    .isEqualTo(2);
        }
    }

    /**
     * <b>앞 세션에서 벌어진 하트비트 공백(ping·pong 둘 다)이 판정에 남는가.</b>
     *
     * <p>{@code Heartbeat}는 소켓마다 새로 만들어진다. 세션이 끝날 때 걷어 올리지
     * 않으면 판정 줄의 공백 값은 <b>마지막 세션 것</b>뿐이고, 앞 세션에서
     * 하트비트가 끊겼다는 사실이 통째로 사라진다 — POK-85가 정한 실패 조건이
     * 조용히 무력해진다.
     *
     * <p><b>공백을 sleep으로 만들지 않고 그 값을 알리는 줄을 기다린다.</b>
     * {@code chat.session.pong_timeout}은 pong 임계(송신 주기 800ms + pingTimeout
     * 2400ms의 절반 = 2000ms)를 넘겨야 나가므로, 그 줄이 나온 시점에 세션 1의 공백은
     * 이미 2초를 넘겼다. 단언은 그 절반인 1000ms다 — 세션 2의 공백(생애가 수십 ms다)과
     * <b>자릿수가 달라야</b> "앞 세션 값이 남았다"를 실제로 가른다.
     *
     * <p><b>줄이 두 종류다.</b> 세션 종료 줄({@code chat.session.ended})은 세션마다
     * 나가고 <b>그 세션의 값</b>을 싣는다. 판정 줄은 프로세스가 끝날 때 한 줄이고
     * <b>누계</b>를 싣는다. 그래서 부등호가 반대다 — 세션 2의 종료 줄은 1초 미만이어야
     * 하고(자기 값이다), 판정 줄은 1초 이상이어야 한다(세션 1 값이 걷어 올려졌다).
     *
     * <p>둘을 함께 보는 이유: 종료 줄만 보면 걷어 올리기가 통째로 빠져도 초록이고,
     * 판정 줄만 보면 「걷고 나서 찍는다」 순서가 뒤집혀도 초록이다.
     */
    @Test
    void 앞_세션의_하트비트_공백이_판정에_남는다() throws Exception {
        try (LogCaptor captor = new LogCaptor()) {
            // 세션 1: pong을 끊는다. ping은 그대로 나가므로 서버가 먼저 끊지 않는다.
            behavior.answerPong = false;
            behavior.disconnectWhenPingMissing = false;
            long first = startRunner();

            // <b>끊는 것은 좀비 판정이 한다.</b> 임계를 넘으면 그 신호가 곧 절단
            // 신호이므로 테스트가 따로 끊을 것이 없다 — 여기서 closeSession()을
            // 부르면 이미 닫힌 소켓에 대고 부르는 것이 된다.
            //
            // <b>반납이 서버에 도착한 것</b>을 기다린다. 상태는 자리를 비우기 전에
            // 찍히므로, 상태만 보고 앞지르면 자기 반납이 tearDown의 reset() 뒤에
            // 도착해 다음 테스트의 수로 넘어간다 — 실제로 그렇게 깨졌다.
            awaitUntil(() -> behavior.unsubscribeCallCount() == 1);
            assertThat(captor.messages())
                    .as("공백이 임계를 넘은 것을 보지 않으면, 뒤 단언이 무엇을 재는지 알 수 없다")
                    .anyMatch(m -> m.startsWith("chat.session.pong_timeout"));

            // 세션 2: pong이 정상이고 즉시 끝난다. 이쪽 값은 수십 ms다.
            behavior.answerPong = true;
            runner.start();
            long second = runner.lastSessionNo();
            assertThat(status.state())
                    .as("두 번째 세션이 안 섰다면 세션별 값과 누계를 가를 것이 없다")
                    .isEqualTo(CollectionStatus.State.COLLECTING);

            // 종료 경로로 치운다. 또 끊으면 그 신호가 백오프 대기 중인 루프에 밀려
            // pending으로 들어가 이 검사가 타이밍을 재게 된다.
            runner.stop();
            awaitUntil(() -> behavior.unsubscribeCallCount() == 2);
            assertThat(behavior.unsubscribeCallCount())
                    .as("두 번째 세션의 반납이 안 세어진 채 테스트가 끝나면 다음 테스트로 샌다")
                    .isEqualTo(2);

            // 세션 1의 종료 줄. 그 세션이 자기 값을 자기 줄에 싣는다.
            assertThat(millisOf(endedLine(captor, first), "maxPongGap="))
                    .as("걷기보다 먼저 찍으면 그 세션 줄에 자기 공백이 안 실린다")
                    .isGreaterThanOrEqualTo(1000);
            // 세션 2의 종료 줄은 자기 값을 싣는다 — 이쪽 공백은 수십 ms다.
            // 앞 세션 값이 여기 실려 있으면 걷기가 세션 경계를 안 지킨 것이다.
            assertThat(millisOf(endedLine(captor, second), "maxPongGap="))
                    .as("세션 줄에 앞 세션 값이 실리면 어느 세션에서 막혔는지 못 가른다")
                    .isLessThan(1000);

            // <b>판정 줄은 프로세스 누계다.</b> 세션이 끝날 때 걷어 올리지 않으면
            // 마지막 세션 값(수십 ms)만 남고 세션 1에서 pong이 끊겼다는 사실이
            // 통째로 사라진다 — POK-85가 정한 실패 조건이 조용히 무력해진다.
            assertThat(millisOf(verdictLine(captor, second), "maxPongGap="))
                    .as("걷어 올리지 않으면 앞 세션의 공백이 판정에서 사라진다")
                    .isGreaterThanOrEqualTo(1000);

            // <b>ping 공백도 같은 세 줄을 지나야 한다.</b> pong 쪽만 단언하면
            // {@code recordSessionEnd}에 ping 공백 대신 0을 넘겨도 전 검사가 초록이다
            // (변이로 확인했다). <b>ping 공백이 2026-08-01 사고의 유일한 신호였고</b>
            // 그것이 POK-85의 합격선이라, 한 칸 옆에 남은 이 구멍이 그대로 그 합격선이다.
            //
            // 임계 500ms의 근거: 세션 1은 송신 주기 800ms(pingInterval 1000ms × 0.8)로
            // 2초 넘게 살아 최소 한 번은 800ms 간격이 찍힌다. 세션 2는 생애가 수십 ms라
            // 그 값이 자기 생애를 못 넘는다 — <b>자릿수로 갈린다.</b>
            assertThat(millisOf(endedLine(captor, first), "maxPingGap="))
                    .as("걷기보다 먼저 찍으면 그 세션 줄에 자기 ping 공백이 안 실린다")
                    .isGreaterThanOrEqualTo(500);
            assertThat(millisOf(endedLine(captor, second), "maxPingGap="))
                    .as("세션 줄에 앞 세션 값이 실리면 어느 세션에서 ping이 막혔는지 못 가른다")
                    .isLessThan(500);
            assertThat(millisOf(verdictLine(captor, second), "maxPingGap="))
                    .as("걷어 올리지 않으면 앞 세션에서 ping이 막힌 사실이 판정에서 사라진다")
                    .isGreaterThanOrEqualTo(500);
        }
    }

    /** 그 세션의 종료 줄. 없으면 그 자리에서 터진다 — 0줄을 조용히 통과시키지 않는다. */
    private static String endedLine(LogCaptor captor, long session) {
        return captor.messages().stream()
                .filter(m -> m.startsWith("chat.session.ended session=" + session + " "))
                .findFirst()
                .orElseThrow(() -> new AssertionError("session=" + session + " 종료 줄이 없다"));
    }

    /** 그 세션의 판정 줄. 없으면 그 자리에서 터진다 — 0줄을 조용히 통과시키지 않는다. */
    private static String verdictLine(LogCaptor captor, long session) {
        return captor.messages().stream()
                .filter(m -> m.startsWith("chat.session.verdict session=" + session + " "))
                .findFirst()
                .orElseThrow(() -> new AssertionError("session=" + session + " 판정 줄이 없다"));
    }

    /**
     * {@code SummaryLogger.duration()}이 1초 미만은 "123ms", 이상은 "1.5s"로 쓴다.
     *
     * <p><b>값을 뽑아 비교하는 이유</b> — {@code doesNotContain("maxPongGap=0ms")}로
     * 쓰면 자동으로 참이 된다. {@code Heartbeat.gap()}이 진행 중인 공백까지 포함해
     * ({@code Heartbeat.java:201-205}) <b>값이 절대 0ms로 렌더되지 않기 때문</b>이다.
     * 그러면 이 테스트는 태스크 8이 고치려는 결함을 하나도 안 잡는다.
     */
    private static long millisOf(String line, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile(java.util.regex.Pattern.quote(key) + "([0-9.]+)(ms|s)")
                .matcher(line);
        assertThat(m.find()).as(key + "가 판정 줄에 없다").isTrue();
        double value = Double.parseDouble(m.group(1));
        return m.group(2).equals("s") ? Math.round(value * 1000) : Math.round(value);
    }

    /**
     * <b>번호가 러너마다 1부터 다시 시작하면 위 줄 수 단언들이 낙오를 못 가른다.</b>
     *
     * <p>검사는 메서드마다 러너를 새로 만들고, 앞 메서드의 뒷정리는 반납 왕복에
     * 갇혀 다음 메서드의 창까지 늦게 찍힌다({@code LogCaptor}는 JVM 전역 루트
     * 로거에 붙어 있다). 번호가 겹치면 그 낙오가 <b>판정이 0줄이 되는 진짜 결함을
     * 메워 준다</b> — 실측으로 「내 반납 줄 1개」 단언이 기대 1·실제 2로 깨졌다.
     *
     * <p>운영에서도 같은 이야기다. 러너가 둘 이상이면 서로 다른 세션이 같은 번호로
     * 나가고, 그러면 {@code session=N}으로 줄을 고르는 사람이 남의 세션을 집는다.
     */
    @Test
    void 러너가_달라도_세션_번호는_안_겹친다() {
        long first = startRunner();
        assertThat(status.state()).isEqualTo(CollectionStatus.State.COLLECTING);
        runner.stop();

        long second = startRunner();
        assertThat(status.state()).isEqualTo(CollectionStatus.State.COLLECTING);

        assertThat(second)
                .as("러너마다 1부터 세면 서로 다른 세션이 같은 번호로 로그에 나간다")
                .isGreaterThan(first);
    }

    /**
     * 세션 번호까지 붙여 센다. 번호는 줄의 첫 항이라 접두사 한 번으로 정확히 갈린다
     * (뒤에 공백을 붙여 {@code session=1}이 {@code session=10}을 안 먹는다).
     *
     * <p><b>번호는 러너가 아니라 이 러너를 부르는 쪽에서 받아 온 것이어야 한다.</b>
     * 상수를 박으면 이 필터는 낙오를 못 막는다 — 번호가 프로세스 안에서 유일해도
     * 상수 1은 언젠가 남의 세션의 번호다. {@link #러너가_달라도_세션_번호는_안_겹친다}가
     * 그 유일성을 지킨다.
     */
    private static long verdictLines(LogCaptor captor, long session) {
        return countLines(captor, "chat.session.verdict session=" + session + " ");
    }

    private static long releasedLines(LogCaptor captor, long session) {
        return countLines(captor, "chat.session.released session=" + session + " ");
    }

    /** 세션 하나의 끝. <b>판정과 달리 세션마다 나간다.</b> */
    private static long endedLines(LogCaptor captor, long session) {
        return countLines(captor, "chat.session.ended session=" + session + " ");
    }

    private static long countLines(LogCaptor captor, String prefix) {
        return captor.messages().stream().filter(m -> m.startsWith(prefix)).count();
    }

    /**
     * <b>사유가 이미 찍힌 뒤에 같은 절단의 {@code onClose}가 도착하는 경우.</b>
     *
     * <p>재연결 루프가 붙으면 한 번의 절단에 신호가 둘 들어온다 — pong 임계로 좀비를
     * 판정하는 쪽과 전송 절단 콜백. 먼저 온 쪽이 사유를 찍었으면 뒤에 온 쪽은 그 사유를
     * 덮지 않아야 한다. <b>그런데 사유를 안 덮는 것과 뒷정리를 안 하는 것은 다른 일이다.</b>
     * 둘을 한 번에 건너뛰면 그 세션이 자리를 문 채 남아 다음 세션이 <b>영영</b> 못 선다 —
     * 「루프가 둘 돈다」가 아니라 「루프가 영영 안 돈다」다. 구독은 서버에 남고
     * 상한이 3개라 금방 못 붙게 된다.
     *
     * <p>판정 줄을 세는 필터에 {@code reason=REVOKED}를 건다. 개수만 세면
     * <b>사유를 덮어쓰는 쪽으로 고쳐도 그대로 초록이다</b> — 그러면 진짜 원인이 사라진다.
     */
    @Test
    void 사유가_이미_찍혀_있어도_절단은_자리를_풀고_반납한다() throws Exception {
        try (LogCaptor captor = new LogCaptor()) {
            startRunner();
            assertThat(status.state()).isEqualTo(CollectionStatus.State.COLLECTING);

            // 신호①. 이 자리에 올 것은 태스크 6·7이고 아직 없으므로 테스트가 직접 찍는다.
            status.stopped(StopReason.REVOKED);
            // 신호②. 같은 절단의 onClose가 뒤따른다.
            behavior.closeSession();

            awaitUntil(() -> behavior.unsubscribeCallCount() == 1);
            assertThat(behavior.unsubscribeCallCount())
                    .as("사유가 이미 있다고 뒷정리까지 건너뛰면 구독이 서버에 남아 상한 3개를 먹는다")
                    .isEqualTo(1);
            // 자리가 풀렸는가. 이것이 이 테스트의 본체다.
            //
            // <b>자리를 잡았는지는 상태가 아니라 세션 발급 횟수로 본다.</b> 자리 CAS가
            // 발급보다 앞이라 발급 2회는 곧 "자리를 잡았다"이고, 못 잡았으면
            // start_skipped로 되돌아가 발급에 닿지도 못한다. 상태로는 못 잰다 —
            // STOPPED는 영구 정지라 establishing()도 collectingIfPending()도 그 위를
            // 안 지나므로, 자리를 제대로 잡아도 COLLECTING이 되지 않는다.
            //
            // <b>반환값도 함께 본다.</b> 여기가 「자리는 잡았는데 아무도 COLLECTING으로
            // 못 올린다」 갈래이고, 그 갈래에서 true를 주면 재연결 루프가 「붙었다」로
            // 읽고 빠져나간다 — 아무도 재시도하지 않는다. 이 줄이 없으면 그 갈래를
            // true로 바꿔도 전 검사가 초록이다(변이로 확인했다).
            assertThat(runner.start())
                    .as("올릴 수 없는 세션을 열고 true를 주면 루프가 붙었다고 믿고 빠져나간다")
                    .isFalse();
            assertThat(behavior.authCallCount())
                    .as("앞 세션이 자리를 문 채 남으면 start_skipped만 반복하고 발급에 닿지도 못한다")
                    .isEqualTo(2);
            assertThat(status.state())
                    .as("영구 정지가 되돌아오면 왜 멈췄는지가 사라지고 health가 UP으로 돌아간다")
                    .isEqualTo(CollectionStatus.State.STOPPED);
            // 그 세션은 아무도 COLLECTING으로 못 올린다. 그렇다고 열어 둔 채 버리면
            // 구독이 서버에 남아 상한 3개를 먹고, 자리도 안 비어 다음 세션이 못 선다.
            // 반납은 start()가 돌아오기 전에 끝난다 — 같은 스레드의 동기 왕복이다.
            assertThat(behavior.unsubscribeCallCount())
                    .as("올릴 수 없는 세션을 열어 놓고 안 치우면 소켓도 자리도 통째로 샌다")
                    .isEqualTo(2);

            // 판정은 프로세스가 끝날 때 나간다. <b>사유는 첫 것이 남아야 한다</b> —
            // 필터에 REVOKED를 건다. 개수만 세면 사유를 덮어쓰는 쪽으로 고쳐도
            // 그대로 초록이고, 그러면 진짜 원인이 사라진다.
            runner.stop();
            assertThat(captor.messages().stream()
                    .filter(m -> m.startsWith("chat.session.verdict"))
                    .filter(m -> m.contains("reason=" + StopReason.REVOKED))
                    .count())
                    .as("판정이 통째로 사라지거나, 뒤에 온 신호가 첫 사유를 덮어 원인이 바뀐다")
                    .isEqualTo(1);
        }
    }

    /**
     * <b>절단 없이</b> 또 시작하는 경우. 재연결 루프가 붙으면 신호 둘(절단 콜백 ·
     * pong 임계)이 겹치는 순간 이 길이 열린다.
     *
     * <p>앞 세션 위에 그냥 덮어쓰면 <b>앞 소켓·스케줄러를 아무도 닫지 않는다</b> —
     * 그 ping 스케줄러는 아무도 닫지 않을 소켓에 계속 쏘고, 앞 구독은 서버에 남아
     * 상한 3개를 먹는다. 그래서 자리를 못 잡으면 아무것도 하지 않고 <b>왜 안 했는지를
     * 남긴다</b> — 조용히 돌아가면 루프가 그 사실을 알 길이 없다.
     */
    @Test
    void 앞_세션이_살아_있으면_다시_시작해도_덮어쓰지_않는다() {
        List<Thread> before = liveWorkers();

        try (LogCaptor captor = new LogCaptor()) {
            startRunner();
            assertThat(status.state()).isEqualTo(CollectionStatus.State.COLLECTING);

            // 양성 대조. 실행기가 애초에 안 떴다면 "하나뿐이다"가 자동으로 참이 된다.
            assertThat(names(mineAmong(before)))
                    .as("앞 세션의 실행기가 돌고 있어야 덮어쓰기를 검사할 수 있다")
                    .containsExactlyInAnyOrder("chzzk-ping", "chzzk-summary");

            // <b>거부를 값으로 준다.</b> 로그 한 줄뿐이면 재연결 루프는 거부와 성공을
            // 못 가르고, 그때 읽는 status는 <b>앞 세션이 남긴 COLLECTING</b>이라
            // 「붙었다」로 오독해 루프가 빠져나간다 — 아무도 재시도하지 않게 된다.
            assertThat(runner.start())
                    .as("자리를 못 잡은 것을 값으로 안 주면 루프가 앞 세션의 상태를 자기 것으로 읽는다")
                    .isFalse();

            assertThat(behavior.authCallCount())
                    .as("앞 세션이 살아 있는데 새 세션을 열면 앞 소켓·스케줄러를 아무도 안 닫는다")
                    .isEqualTo(1);
            assertThat(names(mineAmong(before)))
                    .as("덮어쓴 세션의 ping 스케줄러는 아무도 닫지 않을 소켓에 계속 쏜다")
                    .containsExactlyInAnyOrder("chzzk-ping", "chzzk-summary");
            assertThat(status.state())
                    .as("거부된 start()가 상태를 먼저 건드리면 살아서 수집 중인 세션이 health에서 사라진다")
                    .isEqualTo(CollectionStatus.State.COLLECTING);
            assertThat(captor.messages())
                    .as("아무것도 안 하고 조용히 돌아가면 재연결 루프가 그 사실을 못 본다")
                    .anyMatch(m -> m.startsWith("chat.session.start_skipped"));
        }

        runner.stop();
        // 개수를 박지 않고 발급과 짝지운다. "하나다"는 덮어쓰기 결함에서도 그대로 참이다 —
        // 덮어쓰면 앞 세션이 고아가 되어 반납이 아예 안 나가므로 개수는 역시 1이다.
        // 발급한 만큼 반납했는가로 물으면 그 결함에서 1 != 2로 갈린다.
        assertThat(behavior.unsubscribeCallCount())
                .as("발급한 세션 수만큼 반납이 안 나가면 그 차이가 서버에 남아 상한 3개를 먹는다")
                .isEqualTo(behavior.authCallCount());
    }

    /** 앞 테스트가 남긴 동명 스레드를 내 것으로 세지 않도록 차집합을 쓴다. */
    private static List<Thread> mineAmong(List<Thread> before) {
        return liveWorkers().stream().filter(t -> !before.contains(t)).toList();
    }

    private static List<String> names(List<Thread> threads) {
        return threads.stream().map(Thread::getName).toList();
    }

    private static List<Thread> liveWorkers() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> WORKER_NAMES.contains(t.getName()))
                .filter(Thread::isAlive)
                .toList();
    }

    private static void awaitUntil(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + AWAIT.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }
}
