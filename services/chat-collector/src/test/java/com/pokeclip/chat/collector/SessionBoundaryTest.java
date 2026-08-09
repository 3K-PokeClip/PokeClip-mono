package com.pokeclip.chat.collector;

import com.pokeclip.chat.collector.fake.FakeChzzkBehavior;
import com.pokeclip.chat.collector.fake.FakeChzzkTest;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
class SessionBoundaryTest {

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
     * 러너 생성을 한 곳으로 모은다. 태스크 8·9a가 그대로 부른다.
     *
     * @return 방금 선 세션의 번호. <b>줄을 고르는 열쇠라 반드시 받아서 쓴다</b> —
     *         상수 1·2를 박으면 남의 러너가 늦게 찍은 줄과 구분이 안 된다
     */
    private long startRunner() {
        status = new CollectionStatus();
        runner = new CollectorRunner(new ChzzkProperties(
                true, "test-token", "http://localhost:" + port,
                Duration.ofSeconds(5), Duration.ofMillis(50), Duration.ofSeconds(1)),
                status, restClientBuilder);
        runner.start();
        return runner.lastSessionNo();
    }

    @Test
    void 두_번째_세션도_반납하고_판정을_남긴다() throws Exception {
        try (LogCaptor captor = new LogCaptor()) {
            long first = startRunner();
            assertThat(status.state()).isEqualTo(CollectionStatus.State.COLLECTING);
            behavior.closeSession();
            awaitUntil(() -> behavior.unsubscribeCallCount() == 1);

            // 두 번째 세션. 루프는 아직 없으므로 테스트가 직접 연다.
            runner.start();
            long second = runner.lastSessionNo();
            assertThat(status.state())
                    .as("한 번만 도는 가드가 남아 있으면 여기서 못 올라온다")
                    .isEqualTo(CollectionStatus.State.COLLECTING);
            behavior.closeSession();

            awaitUntil(() -> behavior.unsubscribeCallCount() == 2);
            assertThat(behavior.unsubscribeCallCount())
                    .as("두 번째 세션의 반납이 안 나가면 자리가 서버에 남아 상한 3개를 먹는다")
                    .isEqualTo(2);
            // 판정 줄이 반납보다 먼저 나가고 둘이 같은 스레드라, 반납 도착이 2면
            // 판정 둘은 이미 찍혀 있다. 그래도 개수만 세지 않고 세션 번호로 좁힌다 —
            // LogCaptor는 JVM 전역 루트 로거에 붙어 있어(web-support/LogCaptor.java:21-26)
            // 앞 클래스의 낙오 스레드가 늦게 찍은 줄이 빠진 판정을 메워 줄 수 있다.
            // 번호가 프로세스 안에서 유일하므로 그 낙오는 여기 안 걸린다.
            assertThat(verdictLines(captor, first))
                    .as("첫 세션의 판정")
                    .isEqualTo(1);
            assertThat(verdictLines(captor, second))
                    .as("세션이 둘이면 판정도 둘이다. 한 번만 도는 가드면 두 번째가 사라진다")
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

            behavior.closeSession();
            awaitUntil(() -> behavior.unsubscribeCallCount() == 2);
            assertThat(behavior.unsubscribeCallCount())
                    .as("앞 세션 정리가 새 세션 자리를 지우면 그 세션의 반납이 통째로 사라진다")
                    .isEqualTo(2);
        }
    }

    /**
     * <b>앞 세션에서 벌어진 pong 공백이 판정에 남는가.</b>
     *
     * <p>{@code Heartbeat}는 소켓마다 새로 만들어진다. 세션이 끝날 때 걷어 올리지
     * 않으면 판정 줄의 {@code maxPongGap}은 <b>마지막 세션 것</b>뿐이고, 앞 세션에서
     * pong이 끊겼다는 사실이 통째로 사라진다 — POK-85가 정한 실패 조건이
     * 조용히 무력해진다.
     *
     * <p><b>공백을 sleep으로 만들지 않고 그 값을 알리는 줄을 기다린다.</b>
     * {@code chat.session.pong_timeout}은 pong 임계(송신 주기 800ms + pingTimeout
     * 2400ms의 절반 = 2000ms)를 넘겨야 나가므로, 그 줄이 나온 시점에 세션 1의 공백은
     * 이미 2초를 넘겼다. 단언은 그 절반인 1000ms다 — 세션 2의 공백(생애가 수십 ms다)과
     * <b>자릿수가 달라야</b> "앞 세션 값이 남았다"를 실제로 가른다.
     *
     * <p><b>두 세션의 판정 줄을 다 본다.</b> 세션 2 줄만 보면 「걷고 나서 찍는다」는
     * 순서를 아무것도 안 지킨다 — 찍기를 걷기 앞으로 옮겨도 세션 2 줄에는 세션 1이
     * 남긴 2.4s가 그대로 실려 초록이다(CP3 실측: 그 상태로 127건 전부 통과).
     * 그때 <b>모든 세션이 자기 공백을 자기 판정 줄에서 잃는다.</b> 판정이 프로세스
     * 종료 1회로 옮겨지면(9b) 남는 줄이 마지막 세션 것 하나뿐이라, 그 순간
     * ping·pong 공백이 어디에도 안 남고 POK-85의 합격선이 통째로 사라진다.
     * 세션 1 줄은 그 순서가 지켜져야만 값을 갖는다 — 정상 2.4s 대 뒤집으면 0ms다.
     */
    @Test
    void 앞_세션의_pong_공백이_판정에_남는다() throws Exception {
        try (LogCaptor captor = new LogCaptor()) {
            // 세션 1: pong을 끊는다. ping은 그대로 나가므로 서버가 먼저 끊지 않는다.
            behavior.answerPong = false;
            behavior.disconnectWhenPingMissing = false;
            long first = startRunner();

            awaitUntil(() -> hasLine(captor, "chat.session.pong_timeout"));
            assertThat(captor.messages())
                    .as("공백이 임계를 넘은 것을 보지 않고 끊으면, 뒤 단언이 무엇을 재는지 알 수 없다")
                    .anyMatch(m -> m.startsWith("chat.session.pong_timeout"));

            behavior.closeSession();
            awaitUntil(() -> behavior.unsubscribeCallCount() == 1);

            // 세션 2: pong이 정상이고 즉시 끝난다. 이쪽 값은 수십 ms다.
            behavior.answerPong = true;
            runner.start();
            long second = runner.lastSessionNo();
            behavior.closeSession();
            // <b>반납이 서버에 도착한 것</b>을 기다린다. 판정 줄은 반납보다 먼저
            // 나가므로 이 시점에 이미 있다.
            //
            // <b>판정 줄을 기다리면 안 된다.</b> 그 줄은 뒷정리의 첫 줄이라
            // {@code releaseAndClose()}보다 <b>앞</b>에 찍히고(CollectorRunner의
            // 판정·반납 순서), 가짜 서버는 반납을 <b>도착 시점에</b> 센다. 즉
            // <b>「판정 줄이 나왔다」는 「이 세션의 뒷정리가 끝났다」가 아니다.</b>
            // 실제로 그 줄을 보고 빠져나왔더니 자기 반납이 서버에 닿기도 전에
            // tearDown의 reset()이 카운터를 0으로 만들었고, 그 도착이 다음 테스트의
            // 수로 넘어가 「앞_세션이_살아_있으면…」이 2 != 1로 깨졌다.
            // (서버가 끊은 세션은 forget()이 자리를 즉시 비워 reset()의
            //  awaitSessionClosed()가 아무것도 안 막는다.)
            //
            // 960bc41의 커밋 메시지는 이 대기가 "늦게 찍히는 반납 줄"이었다고 적었다.
            // 틀렸다 — 기다리던 것은 판정 줄이고, 문제는 그 줄이 늦게 찍히는 것이
            // 아니라 뒷정리가 끝나기 전에 찍히는 것이다. 그 기록을 믿고 "판정 줄이
            // 나왔으니 이 세션은 끝났다"로 대기를 걸면 같은 오염이 그대로 재발한다.
            awaitUntil(() -> behavior.unsubscribeCallCount() == 2);
            assertThat(behavior.unsubscribeCallCount())
                    .as("두 번째 세션의 반납이 안 세어진 채 테스트가 끝나면 다음 테스트로 샌다")
                    .isEqualTo(2);

            // 세션 1의 판정 줄. 자기 값을 걷기 전에 찍으면 여기가 0ms가 된다.
            assertThat(millisOf(verdictLine(captor, first), "maxPongGap="))
                    .as("걷기보다 먼저 찍으면 그 세션 판정에 자기 공백이 안 실린다 "
                            + "— 판정이 프로세스 종료 1회로 옮겨지는 순간 값이 통째로 사라진다")
                    .isGreaterThanOrEqualTo(1000);

            // 세션 2의 판정 줄. 이쪽은 걷어 올리기가 살아 있어야 값을 갖는다.
            assertThat(millisOf(verdictLine(captor, second), "maxPongGap="))
                    .as("마지막 세션 값만 실으면 앞 세션의 공백이 판정에서 사라지고, "
                            + "POK-85가 정한 실패 조건이 조용히 무력해진다")
                    .isGreaterThanOrEqualTo(1000);
        }
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

    private static boolean hasLine(LogCaptor captor, String prefix) {
        return captor.messages().stream().anyMatch(m -> m.startsWith(prefix));
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
            assertThat(captor.messages().stream()
                    .filter(m -> m.startsWith("chat.session.verdict"))
                    .filter(m -> m.contains("reason=" + StopReason.REVOKED))
                    .count())
                    .as("판정이 통째로 사라지거나, 뒤에 온 신호가 첫 사유를 덮어 원인이 바뀐다")
                    .isEqualTo(1);

            // 자리가 풀렸는가. 이것이 이 테스트의 본체다.
            runner.start();
            assertThat(status.state())
                    .as("앞 세션이 자리를 문 채 남으면 start_skipped만 반복하고 영영 못 붙는다")
                    .isEqualTo(CollectionStatus.State.COLLECTING);
            assertThat(behavior.authCallCount())
                    .as("자리가 안 풀리면 세션 발급에 닿지도 못한다")
                    .isEqualTo(2);
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

            runner.start();

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
