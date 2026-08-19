package com.pokeclip.chat.collector.session;

import com.pokeclip.chat.collector.ChzzkProperties;
import com.pokeclip.chat.collector.CollectionStatus;
import com.pokeclip.chat.collector.archive.ChatArchive;
import com.pokeclip.chat.collector.fake.FakeChzzkBehavior;
import com.pokeclip.chat.collector.fake.FakeChzzkTest;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import com.pokeclip.chat.collector.support.TestPersistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스트리머 여럿을 동시에 수집하는가. <b>이 카드에서 검사 진위가 가장 위험한 자리다</b> —
 * 세션이 하나면 「프로세스에 하나」와 「세션마다 하나」가 구분되지 않아, 초록인데 아무것도
 * 안 재는 검사를 쓰기 쉽다({@code .claude/skills/multi-session-test-reality}).
 * 갈래마다 그 문항의 답을 주석으로 붙여 뒀다.
 *
 * <p><b>가짜 서버는 스트리머를 Access Token으로 가른다.</b> 구독 API에 채널 파라미터가
 * 없어(CLAUDE.md 「스트리머 여러 명」) 클라이언트가 채널을 보내는 자리가 한 군데도 없다 —
 * 지시서의 {@code dropConnectionFor("chA")}는 실서버에 없는 정보를 가짜가 지어내야
 * 성립한다. 그래서 손잡이 셋은 전부 토큰을 받는다.
 */
@FakeChzzkTest
class SessionRegistryTest extends IntegrationTestSupport {

    private static final Duration AWAIT = Duration.ofSeconds(10);

    /**
     * 재연결 백오프. <b>상한을 크게 둔다</b> — 한 세션의 루프가 상한에 닿아 오래 자야
     * 「실행기 하나를 나눠 쓰면 뒤엣것이 밀린다」가 드러난다. 상한이 짧으면 밀려도
     * 금방 풀려서 잘못된 구현이 통과한다.
     */
    private static final Duration FIRST_DELAY = Duration.ofMillis(200);
    private static final Duration MAX_DELAY = Duration.ofSeconds(60);

    @LocalServerPort int port;
    @Autowired FakeChzzkBehavior behavior;
    @Autowired RestClient.Builder restClientBuilder;

    private SessionRegistry registry;

    @AfterEach
    void tearDown() {
        if (registry != null) registry.closeAll();
        behavior.reset();
    }

    // 문항 1: 세션 하나로는 성립조차 안 한다(둘째 토큰이 없다).
    // 문항 4: activeCount()==2만으로는 <b>둘이 같은 스트리머여도</b> 통과한다 —
    //         가짜 서버가 실제로 본 서로 다른 토큰 수를 같이 본다.
    @Test
    void 스트리머가_둘이면_세션도_둘이다() {
        givenRegistry();
        assertThat(registry.open(key("s1", 1L, "chA"), "tokA")).isTrue();
        assertThat(registry.open(key("s2", 2L, "chB"), "tokB")).isTrue();

        assertThat(registry.activeCount()).isEqualTo(2);
        assertThat(registry.activeStreamIds()).containsExactlyInAnyOrder("s1", "s2");
        assertThat(behavior.connectedTokens())
                .as("세션 둘이 같은 토큰으로 붙어 있어도 activeCount는 2다. 상대 쪽에서 센다")
                .containsExactlyInAnyOrder("tokA", "tokB");
    }

    // 문항 1: 세션 하나면 "다른 방송"이 없다.
    // 문항 5: 상태를 세션이 나눠 쓰게 되돌리면(등록부가 CollectionStatus 하나를 주면)
    //         s2도 RECONNECTING이 되어 빨간불이다 — 확인함(주입 A).
    // 문항 4: statusOf("s2")==COLLECTING은 <b>s2의 소켓이 죽어도</b> 참일 수 있다.
    //         「UP인데 수집 없음」이 이 서비스의 유일한 치명 실패라 상대 쪽도 본다.
    @Test
    void 한_방송이_끊겨도_다른_방송은_살아_있다() throws Exception {
        givenRegistry();
        registry.open(key("s1", 1L, "chA"), "tokA");
        registry.open(key("s2", 2L, "chB"), "tokB");
        // A만 영영 못 붙게 막아 RECONNECTING에 머무르게 한다. 안 막으면 즉시 다시 붙어
        // 이 검사가 절단을 한 번도 못 본 채로 통과한다.
        behavior.failSessionCreateFor("tokA", 503);

        behavior.dropConnectionFor("tokA");

        awaitUntil(AWAIT, () -> stateOf("s1") == CollectionStatus.State.RECONNECTING);
        assertThat(stateOf("s1")).isEqualTo(CollectionStatus.State.RECONNECTING);
        assertThat(stateOf("s2"))
                .as("한 스트리머의 절단이 나머지 상태를 건드리면 상태를 나눠 쓰고 있는 것이다")
                .isEqualTo(CollectionStatus.State.COLLECTING);
        assertThat(behavior.isConnected("tokB"))
                .as("상태만 COLLECTING이고 소켓은 죽은 것이 이 서비스의 유일한 치명 실패다")
                .isTrue();
    }

    // POK-86 선례: 세션 자원을 필드로 흩어 놓으면 뒷정리가 남의 것을 지운다.
    // 문항 1: 세션 하나면 지울 남의 것이 없다.
    // 문항 4: activeStreamIds()가 ["s2"]여도 <b>s2의 소켓이 같이 닫혔으면</b> 통과한다.
    @Test
    void 한_방송의_뒷정리가_다른_방송의_세션을_지우지_않는다() throws Exception {
        givenRegistry();
        registry.open(key("s1", 1L, "chA"), "tokA");
        registry.open(key("s2", 2L, "chB"), "tokB");

        assertThat(registry.close("s1")).isTrue();

        assertThat(registry.activeCount()).isEqualTo(1);
        assertThat(registry.activeStreamIds()).containsExactly("s2");
        assertThat(behavior.isConnected("tokA")).as("닫으라고 한 쪽은 실제로 닫혀야 한다").isFalse();
        assertThat(behavior.isConnected("tokB")).as("안 닫으라고 한 쪽의 소켓이 살아 있는가").isTrue();
        // <b>「닫은 뒤에 되살아나지 않는가」는 여기서 안 잰다.</b> 붙어 있는 세션을 닫는
        // 길에서는 {@code cleanUpOnce}가 자리를 먼저 비워 뒤이은 절단 신호가 낡은 것으로
        // 걸러지므로, 멈춤 신호를 빼도 루프가 아예 안 뜬다(주입 F에서 실제로 초록이었다).
        // 그 자리를 재는 것은 {@code 재연결_중인_방송을_닫으면_다시_붙지_않는다}다.
    }

    // 문항 4: false를 돌려주면서 <b>소켓은 하나 더 열어 두는</b> 구현도 이 단언을
    //         통과한다. 그 구현은 연결 상한 3개를 우리 손으로 태운다 — 발급 호출 수를 같이 본다.
    @Test
    void 같은_방송을_두_번_열지_않는다() {
        givenRegistry();
        assertThat(registry.open(key("s1", 1L, "chA"), "tokA")).isTrue();
        int authCallsAfterFirst = behavior.authCallCount();

        assertThat(registry.open(key("s1", 1L, "chA"), "tokA")).isFalse();

        assertThat(registry.activeCount()).isEqualTo(1);
        assertThat(behavior.authCallCount())
                .as("두 번째 open이 세션 발급까지 갔으면 상한 3개 중 하나를 먹은 것이다")
                .isEqualTo(authCallsAfterFirst);
    }

    @Test
    void 없는_방송을_닫으면_아무_일도_안_일어난다() {
        givenRegistry();
        assertThat(registry.close("없음")).isFalse();
    }

    /**
     * <b>재연결 루프가 도는 중에 닫으면 그 루프도 멈춰야 한다.</b>
     *
     * <p>루프가 도는 동안 세션은 자리를 비워 놓은 상태라({@code reconnectLoop} 첫 줄의
     * 뒷정리) {@code close()}가 할 일이 없다 — <b>멈춤 신호를 안 내리면 아무것도 그 루프를
     * 멈추지 못한다.</b> 그러면 등록부에는 없는데 치지직에는 붙어 있는 세션이 생기고,
     * 종료 편지를 받은 방송의 채팅이 계속 들어온다. 등록부만 보면 영영 안 보인다.
     *
     * <p>바로 앞 갈래({@code 한_방송의_뒷정리가…})로는 이 자리를 못 잡는다 — 붙어 있는
     * 세션을 닫는 길에서는 {@code cleanUpOnce}가 자리를 먼저 비우므로 뒤이은 절단 신호가
     * <b>낡은 신호로 걸러져</b> 루프가 아예 안 뜬다. 신호가 실제로 하는 일이 있는 곳은
     * 여기뿐이다(주입 F로 확인).
     */
    // 문항 2: "닫혔다"를 등록부(activeCount)로만 재면 자동으로 참이다 — 등록부에서 지운 것은
    //         우리 자신이다. 상대 쪽에서 <b>발급 호출이 더 나가는지</b>를 본다.
    // 문항 5: closeEntry에서 countDown을 빼면 발급이 계속 나가 빨간불 — 확인함(주입 F).
    @Test
    void 재연결_중인_방송을_닫으면_다시_붙지_않는다() throws Exception {
        givenRegistry();
        registry.open(key("s1", 1L, "chA"), "tokA");
        behavior.failSessionCreateFor("tokA", 503);
        behavior.dropConnectionFor("tokA");
        awaitUntil(AWAIT, () -> attemptOf("s1") >= 1);
        assertThat(attemptOf("s1")).as("백오프 루프 안에서 닫아야 이 갈래가 성립한다").isGreaterThanOrEqualTo(1);

        assertThat(registry.close("s1")).isTrue();
        int authCallsAtClose = behavior.authCallCount();
        // 막아 둔 것을 푼다. 루프가 살아 있으면 다음 백오프에 <b>성공해서</b> 붙는다.
        behavior.failSessionCreateFor("tokA", 200);

        // 백오프는 200·400·800·1600ms다. 2초면 살아 있는 루프가 반드시 한 번 이상 두드린다.
        Thread.sleep(2_000);

        assertThat(behavior.authCallCount())
                .as("닫은 뒤에도 세션 발급이 나가면 그 루프는 아직 돌고 있다")
                .isEqualTo(authCallsAtClose);
        assertThat(behavior.isConnected("tokA"))
                .as("등록부에는 없는데 치지직에는 붙어 있는 세션이 생겼다")
                .isFalse();
        assertThat(registry.activeCount()).isZero();
    }

    /**
     * 태스크 8까지는 재연결 실행기가 프로세스에 하나였다. 세션이 하나뿐일 때는 그것과
     * 「세션마다 하나」가 구분되지 않아 기존 검사로는 이 자리를 못 잰다. <b>여기서 처음 잰다.</b>
     *
     * <p><b>🔴 지시서의 원안은 아무것도 안 쟀다.</b> 원안은 B가 {@code RECONNECTING}이
     * 되는 시각을 쟀는데, {@code requestReconnect}가 <b>실행기에 넘기기 전에</b>
     * {@code status.reconnecting(...)}을 찍는다 — 그 전이는 실행기가 하나든 스무 개든
     * 절단 즉시 일어난다. 실행기가 병목인지는 <b>루프가 실제로 돌아야</b> 드러나므로,
     * B가 다시 {@code COLLECTING}으로 돌아오는 것을 재도록 바꿨다.
     */
    // 문항 5: 실행기를 공유 단일 플랫폼 스레드로 되돌리면 B의 루프가 A의 무한 루프
    //         뒤에 줄을 서서 영영 안 돌아온다 — 확인함(주입 B).
    @Test
    void 한_방송이_재연결로_자는_동안_다른_방송이_다시_붙는다() throws Exception {
        givenRegistry();
        registry.open(key("s1", 1L, "chA"), "tokA");
        registry.open(key("s2", 2L, "chB"), "tokB");
        // A는 영영 못 붙는다 — 루프가 끝나지 않으므로 실행기 하나를 나눠 쓰면 그 자리가 영구히 막힌다.
        behavior.failSessionCreateFor("tokA", 503);
        behavior.dropConnectionFor("tokA");
        // 루프가 실제로 백오프에 들어간 것을 확인하고 나서 B를 끊는다. attempt는
        // 루프 <b>안에서만</b> 올라가므로, 이 값이 1 이상이면 루프가 돌고 있는 것이다.
        awaitUntil(AWAIT, () -> attemptOf("s1") >= 1);
        assertThat(attemptOf("s1")).as("A의 루프가 안 돌면 막을 자리가 없어 이 검사가 무의미하다").isGreaterThanOrEqualTo(1);

        behavior.dropConnectionFor("tokB");

        awaitUntil(AWAIT, () -> stateOf("s2") == CollectionStatus.State.COLLECTING
                && behavior.isConnected("tokB"));
        assertThat(stateOf("s2"))
                .as("A가 백오프에 잠긴 동안 B가 못 돌아오면 실행기가 병목이다")
                .isEqualTo(CollectionStatus.State.COLLECTING);
        assertThat(behavior.isConnected("tokB")).isTrue();
    }

    /**
     * 스무 세션이 <b>동시에</b> 재연결 루프 안에 있어도 (1) 스무 루프가 다 돌고
     * (2) 그것 때문에 플랫폼 스레드가 늘지 않는다.
     *
     * <p><b>🔴 지시서의 「캐리어를 세션 수만큼 만들지 않는다」는 아무것도 안 쟀다.</b>
     * 캐리어 수는 세션마다 플랫폼 스레드를 하나씩 만드는 구현에서도 안 는다(그쪽은
     * 캐리어를 안 쓴다). 즉 그 단언은 <b>잡으려는 잘못된 구현에서 초록이다.</b>
     * 게다가 원안은 세션을 열기만 하고 루프를 한 번도 안 돌려서, 실행기가 무엇이든
     * 통과한다. 여기서는 스무 루프를 실제로 돌린 뒤
     * <b>재연결 루프를 얹고 있는 플랫폼 스레드</b>를 직접 센다 —
     * {@code Thread.getAllStackTraces()}는 Java 21에서 <b>플랫폼 스레드만</b> 돌려주므로,
     * 가상 스레드 위에서 도는 루프는 0으로 잡히고 플랫폼 스레드 위의 루프는 그대로 잡힌다.
     *
     * <p>지시서의 {@code 스무_세션이_동시에_백오프에_들어가도_새_세션이_즉시_붙는다}는
     * 여기 흡수했다 — {@code open()}은 부르는 스레드(=검사 스레드)에서 도므로 실행기에
     * 줄을 설 일이 없어, 그 갈래는 어떤 실행기로도 통과한다.
     */
    // 문항 1: 세션 하나면 「스무 루프가 같이 돈다」가 성립하지 않는다.
    // 문항 3: 「동시에 겹쳤나」를 우리 쪽에서 세지 않는다 — 스무 세션 <b>전부</b>가
    //         attempt>=1이어야 하고, 크기 N인 풀이면 N개만 그렇게 된다.
    // 문항 5: 세션마다 플랫폼 스레드 하나로 되돌리면 스무 개가 잡힌다 — 확인함(주입 C).
    @Test
    void 스무_세션이_동시에_백오프에_들어가도_스무_루프가_같이_돈다() throws Exception {
        int sessions = 20;
        givenRegistry();
        for (int i = 0; i < sessions; i++) {
            assertThat(registry.open(key("s" + i, i, "ch" + i), "tok" + i)).isTrue();
        }
        // 앞선 검사 클래스가 남긴 재연결 스레드를 우리 것으로 세지 않는다.
        Set<Long> before = platformThreadsInReconnectLoop();

        for (int i = 0; i < sessions; i++) {
            behavior.failSessionCreateFor("tok" + i, 503);
            behavior.dropConnectionFor("tok" + i);
        }

        awaitUntil(Duration.ofSeconds(30), () -> reconnectingLoops(sessions) == sessions);
        assertThat(reconnectingLoops(sessions))
                .as("스무 개가 다 백오프에 들어가야 한다. 크기 N인 풀이면 N개에서 멈춘다")
                .isEqualTo(sessions);

        Set<Long> during = new HashSet<>(platformThreadsInReconnectLoop());
        during.removeAll(before);
        assertThat(during)
                .as("재연결 루프가 플랫폼 스레드를 잡고 있다. 세션 수만큼 늘면 백 명에서 무너진다")
                .isEmpty();
    }

    /**
     * C3. 동의 철회(REVOKED)는 재시도해도 안 풀린다. <b>그 세션만 닫혀야 한다.</b>
     *
     * <p>옮기기 전에는 이 갈래가 프로세스를 {@code exit 1}로 내렸다. 세션이 하나뿐일 때는
     * 맞았지만, 여럿이면 한 스트리머의 동의 철회가 나머지 전원의 수집을 끊는다.
     *
     * <p><b>지시서의 {@code assertThat(exitCalls).isZero()}는 뺐다</b> — 등록부는 exit
     * 손잡이를 <b>받지 않아서</b> 그 값을 올릴 수 있는 코드가 아예 없다. 문항 2가 말하는
     * 「자동으로 참이 되는 단언」이 정확히 이 모양이다. 구조가 막는다는 사실은 검사가
     * 아니라 {@code SessionRegistry.stopOne} 주석이 든다.
     */
    // 문항 1: 세션 하나면 "다른 세션"이 없다.
    // 문항 5: 콜백을 「아무것도 안 함」으로 되돌리면 s1이 등록부에 남아 빨간불,
    //         「전부 닫음」으로 되돌리면 s2가 사라져 빨간불 — 둘 다 확인함(주입 D·E).
    @Test
    void 한_세션의_영구_정지가_다른_세션을_안_끊는다() throws Exception {
        givenRegistry();
        registry.open(key("s1", 1L, "chA"), "tokA");
        registry.open(key("s2", 2L, "chB"), "tokB");

        behavior.sendRevokedTo("tokA");

        awaitUntil(AWAIT, () -> registry.activeStreamIds().size() == 1);
        assertThat(registry.activeStreamIds()).containsExactly("s2");
        assertThat(stateOf("s2")).isEqualTo(CollectionStatus.State.COLLECTING);
        assertThat(behavior.isConnected("tokB"))
                .as("철회한 쪽만 끊겨야 한다. 나머지 소켓이 같이 닫히면 전원이 멈춘 것이다")
                .isTrue();
        assertThat(behavior.isConnected("tokA")).isFalse();
    }

    // ------------------------------------------------------------------
    // 도우미
    // ------------------------------------------------------------------

    private void givenRegistry() {
        registry = new SessionRegistry(
                // <b>설정 토큰은 안 쓰인다.</b> 세션마다 자기 토큰으로 붙는 것을
                // 위 connectedTokens() 단언이 지킨다 — 여기에 진짜 같은 값을 두면
                // 설정에서 읽는 회귀가 그 단언을 통과해 버린다.
                new ChzzkProperties(true, "설정-토큰-쓰면-안-된다",
                        "http://localhost:" + port, Duration.ofSeconds(5), FIRST_DELAY, MAX_DELAY),
                restClientBuilder,
                TestPersistence.unusedBuffer(), TestPersistence.disabledPersister(),
                ChatArchive.NONE);
    }

    private static SessionKey key(String streamId, long streamerId, String channelId) {
        return new SessionKey(streamId, streamerId, channelId);
    }

    private CollectionStatus.State stateOf(String streamId) {
        CollectionStatus.Snapshot now = registry.statusOf(streamId);
        return now == null ? null : now.state();
    }

    private int attemptOf(String streamId) {
        CollectionStatus.Snapshot now = registry.statusOf(streamId);
        return now == null ? 0 : now.attempt();
    }

    /** 백오프 루프에 실제로 들어간 세션의 수. {@code attempt}는 루프 <b>안에서만</b> 오른다. */
    private long reconnectingLoops(int sessions) {
        return IntStream.range(0, sessions).filter(i -> attemptOf("s" + i) >= 1).count();
    }

    /**
     * {@link StreamSession#reconnectLoop}를 스택에 얹고 있는 <b>플랫폼</b> 스레드의 id.
     *
     * <p>{@code Thread.getAllStackTraces()}는 Java 21에서 플랫폼 스레드만 돌려준다 —
     * 그래서 가상 스레드 위의 루프는 여기 안 잡히고, 플랫폼 스레드 위의 루프는 잡힌다.
     * 이름으로 거르지 않는다: 이름은 구현이 바꾸면 그만이고, 그때 이 검사는 조용히 0을 센다.
     */
    private static Set<Long> platformThreadsInReconnectLoop() {
        return Thread.getAllStackTraces().entrySet().stream()
                .filter(e -> Arrays.stream(e.getValue()).anyMatch(frame ->
                        StreamSession.class.getName().equals(frame.getClassName())
                                && "reconnectLoop".equals(frame.getMethodName())))
                .map(e -> e.getKey().threadId())
                .collect(Collectors.toSet());
    }
}
