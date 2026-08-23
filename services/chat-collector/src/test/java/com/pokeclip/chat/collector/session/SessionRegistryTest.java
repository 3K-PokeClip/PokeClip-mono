package com.pokeclip.chat.collector.session;

import com.pokeclip.chat.collector.ChzzkProperties;
import com.pokeclip.chat.collector.CollectionStatus;
import com.pokeclip.chat.collector.StopReason;
import com.pokeclip.chat.collector.archive.ChatArchive;
import com.pokeclip.chat.collector.fake.FakeChzzkBehavior;
import com.pokeclip.chat.collector.fake.FakeChzzkTest;
import com.pokeclip.chat.collector.persist.ChatBuffer;
import com.pokeclip.chat.collector.status.CollectionState;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import com.pokeclip.chat.collector.support.TestPersistence;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

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
    /** 등록부 세션이 실제로 채팅을 흘려보내는 바구니. 세션 격리를 재려면 바늘이 흘러야 한다. */
    private ChatBuffer buffer;

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
        // 자리가 빈 것과 서버가 절단을 관측한 것은 다른 시각이다 — 아래 갈래의 같은 주석 참고.
        awaitUntil(AWAIT, () -> !behavior.isConnected("tokA"));
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

    /**
     * 🔴 <b>늦게 도착한 앞 방송의 시작이 지금 걷는 방송을 되돌리면 안 된다.</b>
     *
     * <p>도달 경로는 이 클래스가 이미 아는 사실이다 — FIFO 그룹이 방송 번호라 같은
     * 스트리머의 두 방송은 <b>순서 보장이 없고</b>, 앞 방송의 시작이 auth 장애로
     * {@code RETRY_LATER}에 걸려 밀리면 뒤 방송이 먼저 처리된다. 그때 무조건 갈아끼우면
     * 살아 있는 뒤 방송의 채팅이 <b>앞 방송 번호로 기록되고</b>, 이어서 앞 방송의 종료
     * 편지가 오면 세션이 닫힌다 — 뒤 방송의 시작 편지는 이미 소비돼 되살릴 길이 없다.
     *
     * <p>문항 1: 세션 하나로도 성립한다. 재는 것이 「한 자리에 온 두 방송의 앞뒤」라
     * 스트리머가 둘이면 오히려 자리가 갈려 이 갈래가 안 생긴다.
     * <p>문항 4: {@code currentStreamIdOf}만 보면 <b>갈아끼움을 통째로 없앤 구현도</b>
     * 통과한다 — 그래서 아래 대조군에서 <b>나중 방송은 실제로 가져가는지</b>를 같이 본다.
     * <p>문항 5: {@code startedAt} 비교를 빼면 s1으로 되돌아가 빨간불(확인함 — 이 검사를
     * 만든 프로브가 그 상태를 재현했다).
     */
    @Test
    void 늦게_온_앞_방송의_시작은_지금_걷는_방송을_되돌리지_못한다() {
        givenRegistry();
        Instant earlier = Instant.parse("2026-08-19T10:00:00Z");
        Instant later = Instant.parse("2026-08-19T11:00:00Z");

        // 뒤 방송(s2)이 먼저 처리됐다 — 그 편지의 그룹이 앞섰다.
        assertThat(registry.open(keyStartedAt("s2", 1L, "chA", later), "tokA")).isTrue();

        // 밀려 있던 앞 방송(s1)의 시작이 이제야 재시도된다.
        assertThat(registry.open(keyStartedAt("s1", 1L, "chA", earlier), "tokA"))
                .as("낡은 시작이 자리를 가져가면 안 된다")
                .isFalse();
        assertThat(registry.currentStreamIdOf(1L))
                .as("지금 걷는 방송이 바뀌면 뒤 방송의 채팅이 앞 방송 번호로 기록된다")
                .isEqualTo("s2");
        assertThat(registry.isStaleStart(1L, earlier))
                .as("이 편지는 다시 물어도 낡았다 — 큐에 남기면 그 그룹을 영원히 막는다")
                .isTrue();

        // 대조군 — 진짜 나중 방송은 가져간다. 「갈아끼움을 없앤 구현」을 막는다.
        Instant latest = Instant.parse("2026-08-19T12:00:00Z");
        assertThat(registry.open(keyStartedAt("s3", 1L, "chA", latest), "tokA")).isTrue();
        assertThat(registry.currentStreamIdOf(1L)).isEqualTo("s3");
        // s3보다 늦게 시작한 방송은 아직 낡지 않았다. <b>같은 시각을 물으면 참이다</b> —
        // 지금 걷는 그 방송 자신의 재전송이고, 그 갈래는 여기까지 오기 전에
        // {@code currentStreamIdOf} 비교가 먼저 걸러 낸다.
        assertThat(registry.isStaleStart(1L, latest.plusSeconds(1))).isFalse();
    }

    /**
     * 🔴 <b>갈아끼우면 새 방송은 0에서 다시 센다 — 다만 총량은 안 줄어든다.</b>
     *
     * <p>{@code receivedOf}는 「그 방송이 받은 양」이라고 문서화돼 있는데, 갈아끼움이
     * 번호만 바꾸던 시절에는 <b>앞 방송 몫이 그대로 딸려 왔다</b>(codex P2, 재현함).
     * 그러면 방송별 유실 판단이 통째로 흐려진다.
     *
     * <p><b>둘을 같이 재야 한다.</b> 방송별 값만 0으로 만들면 그만큼 프로세스 총량이 줄어
     * 판정 줄이 「받은 게 없다」로 읽는다 — 유실을 알아채는 유일한 계기가 죽는 자리다.
     * 그래서 앞 방송 몫이 {@code receivedTotal}에는 남아 있는지도 본다.
     *
     * <p>문항 1: 세션 하나로 성립한다 — 재는 것이 한 자리에서 방송이 바뀌는 순간이다.
     * <p>문항 4: {@code receivedOf("s2")==0}만 보면 <b>지표를 통째로 날린 구현도</b>
     * 통과한다. 총량 단언이 그것을 막는다.
     * <p>문항 5: {@code retarget}의 {@code beginBroadcast()}를 빼면 5로 빨간불,
     * 반환값을 {@code closedReceived}에 안 더하면 총량이 0이 되어 빨간불(둘 다 확인함).
     */
    @Test
    void 갈아끼우면_새_방송은_0에서_세고_총량은_남는다() throws Exception {
        givenRegistry();
        Instant first = Instant.parse("2026-08-19T10:00:00Z");
        Instant second = Instant.parse("2026-08-19T11:00:00Z");
        registry.open(keyStartedAt("s1", 1L, "chA", first), "tokA");
        awaitUntil(AWAIT, () -> behavior.isConnected("tokA"));
        for (int i = 0; i < 5; i++) {
            behavior.emitChatTo("tokA", "{\"content\":\"a" + i + "\",\"messageTime\":" + (i + 1) + "}");
        }
        awaitUntil(AWAIT, () -> registry.receivedOf("s1") >= 5);
        assertThat(registry.receivedOf("s1")).as("바늘이 흘렀는가").isEqualTo(5);

        assertThat(registry.open(keyStartedAt("s2", 1L, "chA", second), "tokA")).isTrue();

        assertThat(registry.receivedOf("s2"))
                .as("새 방송이 앞 방송 몫을 안고 가면 방송별 유실 판단이 흐려진다")
                .isZero();
        assertThat(registry.receivedTotal())
                .as("방송 경계로 잘랐다고 프로세스 총량이 줄면 판정 줄이 유실로 읽는다")
                .isEqualTo(5);
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
        // <b>등록부에서 빠진 것과 서버가 절단을 관측한 것은 다른 시각이다</b> — 소켓을 닫는
        // 것은 우리 쪽이고, 종료 프레임이 서버에 닿는 것은 비동기다
        // ({@code FakeChzzkBehavior.awaitSessionClosed} 주석). 위 대기는 자리가 빈 것만
        // 보므로 여기서 한 번 더 기다린다 — {@code SessionShutdownTest}가 같은 관측점에
        // 같은 대기를 앞세운다. 시한이 차도 조용히 돌아오므로 판정은 아래 단언이 한다.
        awaitUntil(AWAIT, () -> !behavior.isConnected("tokA"));
        assertThat(behavior.isConnected("tokA")).isFalse();
    }

    /**
     * <b>같은 스트리머의 새 방송이 오면 연결을 새로 열지 않는다.</b>
     *
     * <p>치지직 구독은 「토큰 주인의 채팅」이라 방송을 못 고른다(구독 API에 채널·방송
     * 파라미터가 없다). 연결을 둘 열면 <b>같은 채팅이 두 번 들어오고</b> 계정별 상한 3개 중
     * 둘을 우리 손으로 태운다.
     *
     * <p>도달 경로: SQS FIFO의 그룹 열쇠가 방송 번호라 같은 스트리머의 앞 ENDED와 다음
     * STARTED는 순서 보장이 없다.
     */
    // 문항 4: activeCount()==1만 보면 <b>둘째가 소켓만 열고 등록만 안 한</b> 구현도 통과한다.
    //         상대 쪽에서 발급 호출과 접속 수를 같이 본다.
    // 문항 5: 자리 열쇠를 streamId로 되돌리면 authCalls=2·connections=2로 빨간불 — 확인함(주입 G).
    @Test
    void 같은_스트리머의_새_방송이_오면_연결을_새로_열지_않는다() {
        givenRegistry();
        assertThat(registry.open(key("s1", 42L, "chA"), "tok42")).isTrue();
        int authCallsAfterFirst = behavior.authCallCount();
        int connectionsAfterFirst = behavior.connectionCount();

        registry.open(key("s2", 42L, "chA"), "tok42");

        assertThat(registry.activeCount()).isEqualTo(1);
        assertThat(behavior.authCallCount()).isEqualTo(authCallsAfterFirst);
        assertThat(behavior.connectionCount())
                .as("접속이 늘었으면 그 소켓으로 같은 채팅이 한 번 더 들어온다")
                .isEqualTo(connectionsAfterFirst);
    }

    /**
     * <b>세션은 그대로 두고 방송 번호만 갈아낀다.</b>
     *
     * <p>닫았다 새로 열면 그 사이의 채팅이 유실된다 — 실시간 푸시라 되받을 방법이 없다.
     * 그리고 닫지 않는 한 <b>새 방송의 채팅은 이미 이 소켓으로 들어오고 있다.</b>
     * 갈아끼우지 않으면 태스크 12가 그 채팅을 <b>끝난 방송 번호로</b> 찍는다.
     *
     * <p>{@code currentStreamIdOf}는 <b>세션에게 묻는다</b> — 등록부의 딱지만 바꾸고 세션은
     * 옛 번호를 든 구현이 여기서 걸린다.
     */
    // 문항 5: retarget을 no-op으로 되돌리면 "s1"이 나와 빨간불 — 확인함(주입 K).
    @Test
    void 같은_스트리머의_새_방송이_오면_방송_번호만_갈아낀다() {
        givenRegistry();
        registry.open(key("s1", 42L, "chA"), "tok42");

        assertThat(registry.open(key("s2", 42L, "chA"), "tok42"))
                .as("갈아끼웠으면 그 편지는 「더 볼 일 없음」이라 지워져야 한다")
                .isTrue();

        assertThat(registry.currentStreamIdOf(42L)).isEqualTo("s2");
        assertThat(registry.activeStreamIds()).containsExactly("s2");
        assertThat(registry.statusOf("s1")).as("옛 번호로는 더 이상 안 찾혀야 한다").isNull();
    }

    /**
     * 양성 대조. <b>「늘 하나만 연다」인 구현에서도 위 검사 둘은 초록이다.</b>
     */
    @Test
    void 다른_스트리머는_각자_연결을_연다() {
        givenRegistry();
        assertThat(registry.open(key("s1", 42L, "chA"), "tok42")).isTrue();
        int authCallsAfterFirst = behavior.authCallCount();

        assertThat(registry.open(key("s2", 43L, "chB"), "tok43")).isTrue();

        assertThat(registry.activeCount()).isEqualTo(2);
        assertThat(behavior.authCallCount()).isEqualTo(authCallsAfterFirst + 1);
        assertThat(behavior.connectedTokens()).containsExactlyInAnyOrder("tok42", "tok43");
    }

    /**
     * <b>이미 갈아낀 뒤에 옛 번호로 닫으면 아무 일도 안 한다.</b>
     *
     * <p>앞 방송의 종료 편지가 늦게 오는 길이다(FIFO 그룹이 방송 번호라 순서 보장이 없다).
     * 거기서 닫으면 <b>살아 있는 새 방송을 끊고</b>, 그것을 되살릴 STARTED 편지는 이미
     * 소비돼 큐에 없다.
     */
    // 문항 4: close가 false를 주면서 <b>소켓은 닫는</b> 구현도 반환값만 보면 통과한다.
    //         상대 쪽 소켓이 살아 있는지 같이 본다.
    // 문항 5: retarget을 no-op으로 되돌리면 세션이 옛 번호를 든 채라 close("s1")이 <b>살아 있는
    //         새 방송을 끊는다</b> — 빨간불 확인함(주입 K). 「현재일 때만 닫는다」를 지키는 것은
    //         세션이 번호의 유일한 주인이라는 구조 자체다.
    @Test
    void 이미_다음_방송으로_갈아낀_뒤에_옛_번호로_닫으면_아무_일도_안_한다() {
        givenRegistry();
        registry.open(key("s1", 42L, "chA"), "tok42");
        registry.open(key("s2", 42L, "chA"), "tok42");

        assertThat(registry.close("s1")).isFalse();

        assertThat(registry.activeCount()).isEqualTo(1);
        assertThat(registry.currentStreamIdOf(42L)).isEqualTo("s2");
        assertThat(behavior.isConnected("tok42"))
                .as("살아 있는 새 방송의 소켓을 끊으면 그 구간 채팅이 통째로 사라진다")
                .isTrue();
    }

    @Test
    void 끝난_방송의_번호로_닫으면_그_세션이_닫힌다() throws Exception {
        givenRegistry();
        registry.open(key("s1", 42L, "chA"), "tok42");

        assertThat(registry.close("s1")).isTrue();

        assertThat(registry.activeCount()).isZero();
        assertThat(registry.currentStreamIdOf(42L)).isNull();
        // 자리가 빈 것과 서버가 절단을 관측한 것은 다른 시각이다 — 위 갈래의 같은 주석 참고.
        awaitUntil(AWAIT, () -> !behavior.isConnected("tok42"));
        assertThat(behavior.isConnected("tok42")).isFalse();
    }

    /**
     * 그 스트리머의 방송이 닫히면 <b>다음 방송은 새 연결로 열려야 한다.</b>
     * 자리를 스트리머로 잡는 것이 「한 번 열면 그 사람은 영영 못 연다」가 되면 안 된다.
     */
    @Test
    void 앞_방송을_닫으면_같은_스트리머의_다음_방송이_열린다() {
        givenRegistry();
        registry.open(key("s1", 42L, "chA"), "tok42");
        registry.close("s1");

        assertThat(registry.open(key("s2", 42L, "chA"), "tok42")).isTrue();
        assertThat(registry.activeStreamIds()).containsExactly("s2");
        assertThat(behavior.isConnected("tok42")).isTrue();
    }

    /**
     * <b>두 방송의 채팅이 안 섞인다.</b> 지표를 나눠 쓰면 서로의 수를 읽는다.
     *
     * <p>이 갈래가 없으면 세션별 {@code CollectionMetrics}를 공유 하나로 되돌려도
     * <b>전부 초록이다</b>(감사가 실측으로 잡았다). 되돌렸을 때의 결말은 요약 줄이 겹치는
     * 정도가 아니라, {@code beginSession()}의 수신 시계 되잡기가 <b>남의 수신 공백을
     * 지우는</b> 것이다 — 한 방송이 한 건도 못 받는데 공백이 안 자란다.
     *
     * <p>등록부 검사가 여태 {@code unusedBuffer()}를 써서 <b>채팅이 한 건도 안 흘렀다.</b>
     * 여기서 처음 흘린다.
     */
    // 문항 1: 세션 하나면 「섞인다」가 성립하지 않는다.
    // 문항 4: 합계(8)만 보면 <b>둘이 서로의 것을 세도</b> 통과한다. 각자 자기 것만 세는지 본다.
    // 문항 5: 지표를 공유 하나로 되돌리면 둘 다 8을 읽어 빨간불 — 확인함(주입 H).
    @Test
    void 두_방송의_채팅이_안_섞인다() throws Exception {
        givenRegistry();
        registry.open(key("s1", 1L, "chA"), "tokA");
        registry.open(key("s2", 2L, "chB"), "tokB");

        for (int i = 0; i < 5; i++) {
            behavior.emitChatTo("tokA", "{\"content\":\"a" + i + "\",\"messageTime\":" + (i + 1) + "}");
        }
        for (int i = 0; i < 3; i++) {
            behavior.emitChatTo("tokB", "{\"content\":\"b" + i + "\",\"messageTime\":" + (i + 1) + "}");
        }

        awaitUntil(AWAIT, () -> buffer.size() >= 8);
        assertThat(buffer.size()).as("바늘이 코드 안을 지나갔는가 — 안 흐르면 아래 단언이 0==0이다").isEqualTo(8);
        assertThat(registry.receivedOf("s1")).isEqualTo(5);
        assertThat(registry.receivedOf("s2")).isEqualTo(3);
        assertThat(registry.receivedTotal()).isEqualTo(8);
    }

    /**
     * <b>닫힌 세션이 받은 몫도 총량에 남는다.</b> 살아 있는 세션만 더하면 세션이 닫힐 때마다
     * 총량이 줄고, 판정 줄이 그것을 <b>「받은 게 없다」</b>로 읽는다 — 유실을 알아채는
     * 유일한 계기가 죽는다.
     */
    // 문항 5: closeEntry의 closedReceived 누적을 빼면 5→0으로 빨간불 — 확인함(주입 I).
    @Test
    void 세션이_닫혀도_받은_양은_총량에_남는다() throws Exception {
        givenRegistry();
        registry.open(key("s1", 1L, "chA"), "tokA");
        for (int i = 0; i < 5; i++) {
            behavior.emitChatTo("tokA", "{\"content\":\"a" + i + "\",\"messageTime\":" + (i + 1) + "}");
        }
        awaitUntil(AWAIT, () -> registry.receivedTotal() >= 5);
        assertThat(registry.receivedTotal()).isEqualTo(5);

        registry.close("s1");

        assertThat(registry.receivedTotal())
                .as("닫혔다고 받은 사실이 사라지면 판정 줄이 유실을 못 잡는다")
                .isEqualTo(5);
    }

    /**
     * 🔴 <b>닫는 「중」에도 총량이 유지된다.</b> 위 검사는 {@code close()}가 <b>끝난 뒤</b>를
     * 재므로 이 창을 못 본다.
     *
     * <p>{@code closeAll()}은 자리를 map에서 <b>먼저</b> 빼고 {@code closeEntry}를 제출한다.
     * 누계를 {@code session.close()} <b>뒤에</b> 더하면 그 사이 총량이 <b>0으로 보인다</b> —
     * 반납 왕복이 걸리는 시간만큼(실측 세션당 수백 ms, 반개방이면 초 단위) 창이 열려 있다.
     * {@code closeAll}이 8초 예산을 넘겨 포기하고 나가면 <b>그 값이 그대로 판정 줄에 실린다</b>
     * ({@code CollectorRunner.stop()}이 바로 다음 줄에서 찍는다).
     *
     * <p>문항 1: 세션 하나로도 성립한다 — 재는 것이 「자리 뺌과 누계 이관 사이」라 세션 수와
     * 무관하다. 다중 세션 요소가 없는 것이 맞다.
     * <p>문항 3: 겹치는 것을 만들려면 닫기가 <b>실제로 오래 걸려야</b> 한다 — 가짜 서버의
     * 반납을 3초 붙들어 그 창을 손으로 잡을 수 있게 벌린다. 지연이 없으면 닫기가 밀리초에
     * 끝나 창을 못 잡고, 그때는 이 검사가 <b>고치기 전에도 초록</b>이다.
     * <p>문항 5: 누계 이관을 {@code close()} 뒤로 되돌리면 0으로 빨간불(확인함 — 프로브
     * {@code F1ClosedReceivedProbeTest}가 그 상태를 재현해 이 검사를 만들었다).
     *
     * <p>🔴 <b>이 검사가 드물게 빨간불일 수 있다. 그때 값은 반드시 0이고, 검사 문제가 아니라
     * 코드에 실재하는 창이다 — 일부러 안 고쳤다.</b> {@code closeAll()}은 {@code sessions.remove}로
     * 자리를 <b>먼저</b> 빼고 <b>다음 문장</b>에서 {@code detach}로 누계를 옮긴다. 그 두 문장
     * 사이에서 총량은 그 세션 몫만큼 0으로 보이고, 아래가 {@code activeCount()==0}으로 창의
     * 시작을 잡으므로 {@code remove} 직후 그 스레드가 선점되면 폴링이 창 안에 들어온다.
     *
     * <p><b>재현 방법(결정적)</b>: {@code closeAll()}의 {@code remove}와 {@code detach} 사이에
     * {@code Thread.sleep(500)}을 넣으면 아래 「닫는 중에 판정 줄이 읽으면 이 값이 나간다」
     * 단언이 {@code expected: 5L but was: 0L}로 떨어진다(POK-128 critic이 실제로 그렇게 재현했다). <b>부하로는 못 연다</b> — 스피너
     * 20개(코어 10)를 띄우고 6회 돌려 6/6 초록이었다.
     *
     * <p><b>안 고치는 이유</b>: 운영 영향이 없고, 순서를 뒤집으면 더 나쁘다. 판정 줄은
     * {@code closeAll()}을 부른 <b>같은 스레드</b>가 그 다음 줄에서 찍으므로 이 창을 지나갈 수
     * 없다(다른 스레드가 하필 그 몇 인스트럭션에 읽으면 총량이 작게 나가지만, 그 창을
     * 결정적으로 여는 장치는 못 만들었다). {@code detach}를 {@code remove} 앞에 두면 그 사이에
     * 읽는 쪽이 같은 몫을 <b>두 번</b> 세어 총량이 커진다 — 지금 순서는 남는 오차가 「작게
     * 보이는」 쪽이라 유실을 놓치지 않는 방향이다.
     *
     * <p><b>구현자가 처음에 「타이밍 의존 검사 문제」로 본 것은 절반만 맞았다</b> — 타이밍에
     * 의존하는 것은 맞지만 검사가 잘못 잰 것이 아니라 <b>코드의 창을 정확히 잡은 것</b>이다.
     * <b>0이 아닌 다른 값으로 깨지면 그때는 다른 결함이다.</b>
     */
    @Test
    void 닫는_중에도_받은_양이_총량에_남는다() throws Exception {
        givenRegistry();
        assertThat(registry.open(key("s1", 1L, "chA"), "tokA")).isTrue();
        // <b>자리를 잡은 것과 소켓이 붙은 것은 다르다</b>(3층 CLAUDE.md). 붙기 전에 쏘면
        // 그 채팅은 어디에도 안 남고, 아래가 0을 재면서 초록으로 지나간다.
        awaitUntil(AWAIT, () -> behavior.isConnected("tokA"));
        assertThat(behavior.isConnected("tokA")).as("소켓이 붙었다").isTrue();

        for (int i = 0; i < 5; i++) {
            behavior.emitChatTo("tokA", "{\"content\":\"a" + i + "\",\"messageTime\":" + (i + 1) + "}");
        }
        awaitUntil(AWAIT, () -> registry.receivedTotal() >= 5);
        // <b>awaitUntil은 시한이 차면 조용히 돌아온다</b> — 여기서 안 재면 채팅이 한 건도
        // 안 들어온 판이 아래 창 단언까지 그대로 흘러가 「0==0」으로 통과하거나,
        // 엉뚱한 자리에서 빨간불이 난다(실제로 그랬다).
        assertThat(registry.receivedTotal()).as("바늘이 흘렀는가").isEqualTo(5);

        behavior.unsubscribeDelay = Duration.ofSeconds(3);
        try {
            Thread closer = new Thread(registry::closeAll, "close-all");
            closer.start();

            // 자리가 빠진 순간이 창의 시작이다. 여기서 총량을 읽는 것이 판정 줄이 하는 일이다.
            awaitUntil(AWAIT, () -> registry.activeCount() == 0);
            assertThat(registry.receivedTotal())
                    .as("닫는 중에 판정 줄이 읽으면 이 값이 나간다")
                    .isEqualTo(5);

            closer.join(AWAIT.toMillis() * 3);
            assertThat(closer.isAlive()).as("닫기가 끝났다").isFalse();
        } finally {
            behavior.unsubscribeDelay = Duration.ZERO;
        }

        assertThat(registry.receivedTotal()).as("닫힌 뒤에도 그대로다").isEqualTo(5);
    }

    /**
     * <b>{@code CHZZK_ENABLED}가 꺼져 있어도 편지로 연 세션은 붙는다.</b>
     *
     * <p>그 스위치는 프로세스 공용 설정이고 운영 기본값이 false다({@code application.yml}).
     * 등록부가 그것을 보면 <b>편지로 연 세션이 전부 조용히 돌아간다</b> — 로그는 INFO 한 줄뿐이라
     * 「편지는 지워졌는데 그 방송은 영영 안 걷힌다」가 된다.
     */
    // 문항 2: opened==true만 보면 <b>세션 발급을 안 하고 true를 주는</b> 구현도 통과한다.
    //         상대 쪽에서 실제 접속을 본다.
    // 문항 5: enabled 검사를 open()으로 되돌리면 opened=false로 빨간불 — 확인함(주입 J).
    @Test
    void 설정_스위치가_꺼져_있어도_편지로_연_세션은_붙는다() {
        givenRegistry(false);

        assertThat(registry.open(key("s1", 1L, "chA"), "tokA")).isTrue();

        assertThat(registry.activeCount()).isEqualTo(1);
        assertThat(behavior.isConnected("tokA"))
                .as("true만 주고 실제로는 안 붙는 구현을 여기서 가른다")
                .isTrue();
        assertThat(stateOf("s1")).isEqualTo(CollectionStatus.State.COLLECTING);
    }

    /**
     * <b>세션 줄이 전부 어느 방송의 것인지 말하는가.</b>
     *
     * <p>감사 1이 잡은 자리다 — {@code StreamSession}의 로그 14줄 중 방송 번호를 싣는 줄이
     * <b>0줄</b>이었다. 세션이 하나였을 때는 프로세스에 세션이 그것뿐이라 없어도 됐지만,
     * 여럿이면 {@code chat.session.reconnecting}·{@code closed}·{@code pong_timeout}이
     * <b>누구 것인지 모르는 줄</b>이 된다. 「일부만 안 걷힌다」를 로그로 가르는 열쇠다.
     *
     * <p>등록부 줄({@code chat.registry.*})은 원래 {@code stream=}을 싣는다 — 갈림이
     * 정확히 등록부에서 끊겨 있었다.
     */
    // 문항 1: 세션 하나면 「누구 것인지」가 애초에 안 헷갈린다. 둘을 열고 <b>둘 다</b> 나오는지 본다.
    // 문항 2: 빈 목록에 allMatch는 자동으로 참이다 — 줄 수를 먼저 못박는다.
    // 문항 4: allMatch만 보면 <b>모든 줄에 같은 번호를 박은</b> 구현도 통과한다.
    //         s1과 s2가 각각 나오는지 같이 본다.
    @Test
    void 세션_줄이_전부_어느_방송의_것인지_말한다() throws Exception {
        givenRegistry();
        try (LogCaptor captor = new LogCaptor()) {
            registry.open(key("s1", 1L, "chA"), "tokA");
            registry.open(key("s2", 2L, "chB"), "tokB");
            // A만 끊어 절단·재연결 줄을 실제로 만든다. 안 끊으면 수립 줄만 남아
            // 「누구 것인지 모르는 줄」이 가장 많이 나오는 갈래를 하나도 안 본다.
            behavior.failSessionCreateFor("tokA", 503);
            behavior.dropConnectionFor("tokA");
            awaitUntil(AWAIT, () -> attemptOf("s1") >= 1);
            registry.closeAll();

            // <b>판정 줄은 뺀다.</b> 그것은 세션이 아니라 프로세스의 줄이라 가리킬 방송이
            // 하나가 아니다(세션 수는 registrySessions=가 든다).
            List<String> sessionLines = captor.messages().stream()
                    .filter(m -> m.startsWith("chat.session.")
                            && !m.startsWith("chat.session.verdict"))
                    .toList();

            assertThat(sessionLines)
                    .as("줄이 거의 없으면 아래 allMatch는 빈 목록 위에서 자동으로 참이다")
                    .hasSizeGreaterThan(4);
            assertThat(sessionLines)
                    .as("방송 번호가 없는 줄은 세션이 여럿일 때 누구 것인지 영영 모른다")
                    .allMatch(m -> m.contains(" stream="));
            assertThat(sessionLines).anyMatch(m -> m.contains(" stream=s1"));
            assertThat(sessionLines).anyMatch(m -> m.contains(" stream=s2"));
        }
    }

    /**
     * <b>최대 수신 공백이 편지 경로에서 어디에도 안 실리던 자리</b>(태스크 11이 넘긴 숙제).
     *
     * <p>판정 줄은 프로세스 누계인데 이 값은 <b>최댓값이라 세션끼리 더할 수 없어</b>
     * 거기 못 싣는다 — 그리고 편지 경로에서는 러너가 세션을 하나도 안 열어 그 항이 늘 0이다.
     * 즉 세션 종료 줄이 없으면 <b>「한 방송이 오래 아무것도 못 받았다」가 어느 줄에도 안 남는다.</b>
     */
    // 문항 2: contains("maxReceiveGap=")이면 0ms를 박아 둔 구현도 통과한다.
    //         공백을 실제로 만들고 값을 파싱해 본다.
    // 문항 4: 값이 실려도 <b>남의 세션 것</b>이면 뜻이 없다 — stream=s1인 줄에서 읽는다.
    @Test
    void 세션_종료_줄이_그_방송의_최대_수신_공백을_싣는다() throws Exception {
        givenRegistry();
        try (LogCaptor captor = new LogCaptor()) {
            registry.open(key("s1", 1L, "chA"), "tokA");
            behavior.emitChatTo("tokA", "{\"content\":\"a\",\"messageTime\":1}");
            awaitUntil(AWAIT, () -> registry.receivedOf("s1") == 1);
            assertThat(registry.receivedOf("s1")).as("첫 건이 안 오면 공백을 만들 상대가 없다").isEqualTo(1);
            Thread.sleep(300);
            behavior.emitChatTo("tokA", "{\"content\":\"b\",\"messageTime\":2}");
            awaitUntil(AWAIT, () -> registry.receivedOf("s1") == 2);
            assertThat(registry.receivedOf("s1")).as("둘째 건이 안 오면 공백이 안 재어진다").isEqualTo(2);

            registry.close("s1");

            String ended = captor.messages().stream()
                    .filter(m -> m.startsWith("chat.session.ended") && m.contains(" stream=s1 "))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("s1의 세션 종료 줄이 없다"));
            assertThat(millisOf(ended, "maxReceiveGap="))
                    .as("0ms를 박아 두면 「오래 못 받았다」가 어느 줄에도 안 남는다")
                    .isGreaterThanOrEqualTo(200);
        }
    }

    /** {@code 키=123ms} 꼴에서 숫자만. 형식이 바뀌면 파싱이 터져 그대로 빨간불이 된다. */
    private static long millisOf(String line, String key) {
        String rest = line.substring(line.indexOf(key) + key.length());
        return Long.parseLong(rest.substring(0, rest.indexOf("ms")));
    }

    /**
     * <b>수립 중에 끊기면 그 세션의 재연결 루프는 이미 떠 있다.</b> 자리만 비우고
     * 멈춤 신호를 안 내리면 그 루프가 백오프 뒤 <b>스스로 다시 붙는다</b> —
     * 등록부가 모르는, 아무도 닫을 수 없는 세션이다.
     *
     * <p>실재하는 창이다({@code closeAfterSubscribed} 주석: 토큰 revoke나 연결 상한
     * 초과가 여기 떨어진다). 결말 셋 — ① <b>계정당 상한 3개를 우리 손으로 태운다.</b>
     * 429는 속도 제한이 아니라 「자리 없음」이라 시간이 아니라 자리가 풀려야 낫는다
     * (태스크 1 실계정) ② {@link SessionRegistry#shutdown()}이 자리에 없는 그것을 못 닫아
     * 서버 쪽 자리가 10초~4분 42초 남는다 ③ 같은 편지가 다시 오면 <b>같은 계정에 소켓이
     * 둘 선다</b> — 구독 대상이 토큰 주인이라 같은 채팅이 두 소켓에 다 들어온다.
     */
    // 문항 1: 이 갈래는 다중화가 아니라 「실패한 수립의 뒷정리」를 잰다. 세션 하나로
    //         성립하고, 둘로 늘려도 같은 것을 재므로 하나만 연다.
    // 문항 2: "안 붙어 있다"는 <b>아무것도 안 일어나도 참</b>이다 — 소켓이 실제로 섰던
    //         것을 연결 수 델타로 먼저 못박는다.
    // 문항 3: 해당 없음. 동시성을 안 잰다.
    // 문항 4: {@code activeCount()==0}은 <b>「자리 잡은 수」라 유령을 못 본다</b> —
    //         이 결함이 바로 그 모양이다. 판정은 상대 쪽 소켓이 한다.
    // 문항 5: open()의 실패 자리에서 closeEntry 호출을 빼면 빨간불(확인함).
    @Test
    void 수립_중에_끊겨_실패한_세션은_스스로_다시_붙지_않는다() throws Exception {
        givenRegistry();
        int connectionsBefore = behavior.connectionCount();
        int releasesBefore = behavior.unsubscribeCallCount();
        behavior.closeAfterSubscribed = true;

        assertThat(registry.open(key("s1", 1L, "chA"), "tokGhost"))
                .as("수립이 절단으로 끝났는데 붙었다고 보고하면 루프가 그것을 믿고 빠져나간다")
                .isFalse();
        // <b>막는 것을 치운다.</b> 유령이 살아 있다면 이제 깨끗하게 다시 붙는다 —
        // 안 치우면 되살아난 소켓이 곧바로 또 끊겨 "안 붙어 있다"가 우연히 참이 된다.
        behavior.closeAfterSubscribed = false;

        assertThat(behavior.connectionCount() - connectionsBefore)
                .as("소켓이 한 번도 안 섰으면 아래 단언은 아무것도 안 잰다")
                .isGreaterThanOrEqualTo(1);

        // <b>부정 단언이라 시간이 필요하다.</b> 백오프 첫 지연이 200ms라 3초면 재시도가
        // 여러 번 지나간다 — 감사 재현이 3초에서 되살아난 소켓을 봤다.
        Thread.sleep(3_000);

        // <b>구독도 본다 — 소켓이 닫혀도 서버 쪽 구독이 남으면</b> 상한 3개 중 하나가
        // 그대로 묶인다(급사 경로에서 10초~4분 42초).
        //
        // <b>🔴 이 값은 판별자가 아니라 양성 대조다.</b> 처방을 되돌린 상태에서도 1 이상이
        // 나온다(주입으로 확인함) — 유령이 되살아나기 <i>전에</i> 앞 소켓의 절단 정리가
        // 반납을 이미 보내기 때문이다. 그래서 단언 순서를 구독→소켓으로 두어도 <b>빨간불은
        // 소켓 쪽에서 난다.</b> 감사 프로브가 종료 뒤에 {@code unsubDelta=0}을 본 것과
        // 다른데, <b>어느 창에서 잰 값인지까지는 대조하지 않았다.</b> 판정은 소켓이 한다.
        assertThat(behavior.unsubscribeCallCount() - releasesBefore)
                .as("구독이 서버에 남으면 소켓만 닫아도 그 자리는 안 돌아온다")
                .isGreaterThanOrEqualTo(1);
        assertThat(behavior.isConnected("tokGhost"))
                .as("등록부가 모르는 소켓이 살아 있으면 아무도 그것을 닫지 못한다")
                .isFalse();
        assertThat(registry.activeCount()).isZero();
        assertThat(registry.currentStreamIdOf(1L))
                .as("자리도 비어 있어야 다음 편지가 「이미 열림」으로 안 걸린다")
                .isNull();
    }

    /**
     * <b>같은 유령의 예외 갈래.</b> 절단이 먼저 오고 수립이 시한 만료로 <b>던지면</b>
     * {@code open()}은 위 갈래가 아니라 {@code catch (SessionEstablishException)}으로
     * 빠진다. 두 길은 코드에서 같은 자리로 합류하지만, <b>합류가 깨지는 순간 한쪽만
     * 고친 구현이 그대로 초록이 된다</b> — 이번 카드에서 「쌍둥이 중 한쪽만」이 세 번 나왔다.
     */
    // 문항 1·3·4: 위 갈래와 같다.
    // 문항 2: 시한 만료로 실제로 예외 갈래를 탔는지는 연결 수 델타와 반납 건수로 본다.
    // 문항 5: 정상 반환 갈래만 닫도록 고친 구현에서 빨간불이다(확인함) —
    //         합류 자리를 지우고 성공 갈래에만 처방을 두어 재현했다.
    @Test
    void 수립이_예외로_끝나도_그_세션은_스스로_다시_붙지_않는다() throws Exception {
        // 시한을 1초로 줄인다. 5초면 이 갈래 하나가 검사 시간을 통째로 먹는다.
        givenRegistry(true, Duration.ofSeconds(1));
        int connectionsBefore = behavior.connectionCount();
        int releasesBefore = behavior.unsubscribeCallCount();
        behavior.sendSubscribed = false;        // ⑤가 영영 안 온다 — 시한까지 매달린다
        behavior.closeAfterSubscribed = true;   // 그 전에 이미 끊긴다

        assertThat(registry.open(key("s1", 1L, "chA"), "tokGhostThrow"))
                .as("수립이 예외로 끝났는데 붙었다고 보고하면 안 된다")
                .isFalse();
        behavior.closeAfterSubscribed = false;
        behavior.sendSubscribed = true;         // 이제 다시 붙을 수 있다

        assertThat(behavior.connectionCount() - connectionsBefore)
                .as("소켓이 한 번도 안 섰으면 아래 단언은 아무것도 안 잰다")
                .isGreaterThanOrEqualTo(1);

        Thread.sleep(3_000);

        assertThat(behavior.unsubscribeCallCount() - releasesBefore)
                .as("구독이 서버에 남으면 소켓만 닫아도 그 자리는 안 돌아온다")
                .isGreaterThanOrEqualTo(1);
        assertThat(behavior.isConnected("tokGhostThrow"))
                .as("예외 갈래에만 유령이 남으면 종료가 그 자리를 영영 못 돌려준다")
                .isFalse();
        assertThat(registry.activeCount()).isZero();
        assertThat(registry.currentStreamIdOf(1L)).isNull();
    }

    /**
     * <b>낡은 세션의 영구 정지 손잡이가 그 자리에 앉은 다음 세션을 끊으면 안 된다.</b>
     *
     * <p>{@code close(streamId)}·{@code closeAll()}은 자리를 걷을 때 값까지 보는데
     * 영구 정지 경로만 스트리머로 지우고 있었다 — <b>비대칭이다.</b> 그러면 이미 갈아끼워진
     * 새 세션을 낡은 신호가 닫고, 그것을 되살릴 STARTED 편지는 이미 소비돼 큐에 없다.
     *
     * <p><b>실제 도달 경로는 재현하지 못했다.</b> 창이 재연결 루프의 {@code while} 통과와
     * 이 콜백 도달 사이 몇 줄이라 붙잡을 I/O가 없다. 그래서 <b>손잡이를 리플렉션으로 꺼내
     * 직접 부른다</b> — 창을 벌리는 대신 창 안에서 일어나는 일을 그대로 재연한다.
     * 재현 못 한 것은 「그 창에 실제로 떨어지는 빈도」이지 「떨어지면 무슨 일이 나는가」가 아니다.
     */
    // 문항 1: 세션 하나로는 「앞 세션의 신호가 뒤 세션을 끊는다」가 성립하지 않는다.
    //         같은 스트리머 자리에 세션을 <b>연달아 둘</b> 세운다.
    // 문항 2: {@code activeCount()==1}은 신호를 <b>아예 안 보내도</b> 참이다 —
    //         손잡이를 실제로 부른 것을 소켓 생존과 방송 번호로 같이 본다.
    // 문항 4: 자리가 1이어도 <b>그 자리 주인이 앞 세션</b>이면 틀린 상태다 — 번호를 본다.
    // 문항 5: {@code stopOne}의 값 비교를 빼면(스트리머로만 지우면) 새 세션이 닫혀
    //         빨간불이다(확인함).
    @Test
    void 낡은_세션의_영구_정지가_그_자리의_새_세션을_안_끊는다() throws Exception {
        givenRegistry();
        assertThat(registry.open(key("s1", 7L, "chA"), "tokOld")).isTrue();
        Consumer<StopReason> staleHandle = permanentStopHandleOf("s1");

        // 앞 세션을 닫고 같은 스트리머 자리에 새 세션을 세운다. 갈아끼움(retarget)이
        // 아니라 <b>다른 세션 객체</b>여야 한다 — 갈아끼움은 객체를 그대로 둔다.
        assertThat(registry.close("s1")).isTrue();
        assertThat(registry.open(key("s2", 7L, "chB"), "tokNew")).isTrue();
        assertThat(behavior.isConnected("tokNew")).isTrue();

        // 낡은 손잡이가 이제야 도착한다.
        staleHandle.accept(StopReason.REVOKED);

        assertThat(registry.currentStreamIdOf(7L))
                .as("낡은 신호가 자리 주인을 안 보고 지우면 살아 있는 새 방송이 끊긴다")
                .isEqualTo("s2");
        assertThat(behavior.isConnected("tokNew"))
                .as("자리는 남기고 소켓만 닫는 구현이 여기서 걸린다")
                .isTrue();
        assertThat(registry.activeCount()).isEqualTo(1);
    }

    // 문항 5: retargetOrSkip의 STOPPED 가드를 지우면 open이 true(갈아끼움)를 돌려줘 빨간불 —
    //         PR #98 2판 codex P1 「좁은 창, 재현 못 함」을 리스너를 붙들어 결정적으로 연다.
    // 문항 4: open()==false만 보면 「자리 없음」과 구분이 안 된다 — 이름이 안 바뀌었는지·편지가 남는지(isStaleStart=false)도 본다.
    // 문항 3: 리스너를 붙드는 스레드는 그 세션의 재연결 루프(가상 스레드)다. memoStarted가 풀린
    //         시점에 그 스레드는 permanentStopListener.accept 안에 있고, 아래 단언은 그 동안 돈다.
    @Test
    void 포기_메모를_남기는_동안은_stopped로_남고_새_방송은_갈아끼우지_않는다() throws Exception {
        givenRegistry();
        java.util.concurrent.CountDownLatch memoStarted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch memoRelease = new java.util.concurrent.CountDownLatch(1);
        registry.onPermanentStop((streamId, reason) -> {
            memoStarted.countDown();
            try {
                memoRelease.await(10, java.util.concurrent.TimeUnit.SECONDS);   // DB가 느린 동안을 흉내낸다
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        registry.open(key("s1", 1L, "chA"), "tokA");
        awaitUntil(AWAIT, () -> behavior.isConnected("tokA"));
        behavior.failSessionCreateFor("tokA", 401);
        behavior.dropConnectionFor("tokA");
        assertThat(memoStarted.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        try {
            assertThat(stateOf("s1")).as("메모를 남기는 동안 등록부에 STOPPED로 남는다 — 창구가 stopped를 답하는 근거")
                    .isEqualTo(CollectionStatus.State.STOPPED);
            SessionKey next = key("s2", 1L, "chA");
            assertThat(registry.open(next, "tokA2")).as("죽은 세션에 갈아끼우면 안 된다").isFalse();
            assertThat(registry.currentStreamIdOf(1L)).as("이름이 바뀌지 않았다").isEqualTo("s1");
            assertThat(registry.isStaleStart(1L, next.startedAt()))
                    .as("편지는 낡은 것이 아니다 — RETRY_LATER로 남아 다음 회차에 온다").isFalse();
        } finally {
            memoRelease.countDown();
        }
        awaitUntil(AWAIT, () -> registry.activeCount() == 0);
        assertThat(registry.open(key("s3", 1L, "chA"), "tokA3")).as("자리가 비면 새로 연다").isTrue();
    }

    /**
     * <b>첫 수립이 영구 실패인 동안에도 창구가 {@code stopped}를 답해야 한다.</b>
     *
     * <p>같은 401을 <b>두 길</b>로 맞을 수 있는데 답이 갈렸다 — ⓐ 붙어 있다가 끊겨 재연결
     * 발급이 거부되면 {@code StreamSession}이 {@code status.stopped(reason)}을 찍고 <b>나서</b>
     * 알림으로 내려가지만, ⓑ 첫 수립에서 거부되면 그 자리가 {@code status}를 안 건드려
     * {@code ESTABLISHING} 그대로였다. <b>둘 다 배너를 끄는 값이 아니어야 하는데
     * {@code establishing}은 배너를 끈다</b>(PRD 응답 표) — 포기 알림을 「지우기 전」에 둔
     * 결정이 지키려던 것이 ⓑ에서만 안 지켜졌다.
     *
     * <p>창의 폭은 메모 INSERT 한 번이라 평시 밀리초이고 DB가 반개방이면 최대 10초다
     * ({@code socketTimeout}). 유실도 카운터 오류도 없어 <b>사소</b>로 뒀지만,
     * <b>고쳐 놓고 검사를 안 붙이면 다음 사람이 되돌려도 초록이다</b> — 이 갈래를 지키는
     * 검사가 이 파일에 하나도 없었다(봇 1판 S1 실측: 되돌려도 491/실패 0).
     */
    // 문항 1: 다중화가 주제가 아니다 — 한 세션의 <b>두 길</b>(ⓐ·ⓑ)이 같은 답을 주는지를 잰다.
    //         세션을 둘 여는 것은 대조군을 세우기 위해서지 세션 수가 요점이 아니다.
    // 문항 2: 「알림이 갔다」만 보면 status를 안 건드려도 참이다 — 리스너를 붙들어
    //         <b>그 구간을 고정한 채</b> 등록부가 무엇을 답하는지 직접 읽는다.
    // 문항 3: 리스너를 붙드는 스레드는 open()을 부른 스레드 자신이다(첫 수립의 catch는
    //         재연결 루프가 아니라 open의 호출 스레드 위에서 돈다). 그래서 open을 별도
    //         스레드에 태우고 본 스레드가 그 동안 단언한다.
    // 문항 4: state==STOPPED만 보면 <b>ⓐ에서만 맞는</b> 구현도 통과한다 — 같은 검사에서
    //         ⓑ를 재고, 사유가 실렸는지·창구가 needsRelink를 켜는지까지 본다.
    // 문항 5: SessionRegistry.open의 catch에서 status.stopped(e.reason()) 한 줄을 지우면
    //         ESTABLISHING이 잡혀 빨간불(확인함).
    @Test
    void 첫_수립이_영구_실패한_그_자리에서도_stopped를_답한다() throws Exception {
        givenRegistry();
        java.util.concurrent.CountDownLatch memoStarted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch memoRelease = new java.util.concurrent.CountDownLatch(1);
        List<StopReason> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        registry.onPermanentStop((streamId, reason) -> {
            seen.add(reason);
            memoStarted.countDown();
            try {
                memoRelease.await(10, java.util.concurrent.TimeUnit.SECONDS);   // 메모가 저장되는 동안
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        // 첫 수립의 발급부터 401 — 소켓이 한 번도 안 선다. ⓐ와 갈리는 지점이 이것이다.
        behavior.failSessionCreateFor("tokB", 401);
        Thread opener = new Thread(() -> registry.open(key("s1", 9L, "chB"), "tokB"), "probe-opener");
        opener.setDaemon(true);
        opener.start();
        try {
            assertThat(memoStarted.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    .as("알림이 안 왔으면 아래 단언은 아무것도 안 잰다").isTrue();
            assertThat(seen).as("영구 사유로 왔어야 한다").containsExactly(StopReason.SESSION_AUTH_REJECTED);
            assertThat(behavior.isConnected("tokB")).as("ⓑ는 소켓이 선 적이 없다 — ⓐ와 갈리는 지점").isFalse();

            CollectionStatus.Snapshot now = registry.statusOf("s1");
            assertThat(now).as("자리는 아직 남아 있다 — 알림 안에서 붙들려 있다").isNotNull();
            assertThat(now.state())
                    .as("establishing은 배너를 끄는 값이다 — 포기한 방송이 「붙는 중」으로 보인다")
                    .isEqualTo(CollectionStatus.State.STOPPED);
            assertThat(CollectionState.needsRelink(now.reason()))
                    .as("사유를 안 실으면 창구가 needsRelink를 못 켠다")
                    .isTrue();
        } finally {
            memoRelease.countDown();
        }
        opener.join(10_000);
        awaitUntil(AWAIT, () -> registry.activeCount() == 0);
        assertThat(registry.activeCount()).as("붙들림이 풀리면 자리가 비어야 한다").isZero();
    }

    // <b>리스너 예외 방어의 안쪽 겹을 재는 유일한 검사다</b>(critic A1). 방어가 두 겹인데 —
    // 바깥 = StoppedStreamRecorder.record의 catch · 안쪽 = SessionRegistry.notifyPermanentStop의 catch —
    // 안쪽만 지워도 <b>기존 검사 43개가 전부 초록이었다</b>(감사 실측 green 43 / red 0).
    // 지우면 알림에서 튄 stopOne이 sessions.remove·closeEntry를 못 돌아 자리가 영구히 남고(active=1),
    // 이 카드가 넣은 STOPPED 가드가 그 자리를 보고 갈아끼움을 거절하므로
    // <b>그 스트리머는 새 방송을 영영 못 연다</b>(가드 전에는 갈아끼워졌다).
    // 문항 1: 다중화가 주제가 아니다 — 두 길(ⓐ·ⓑ)을 재려고 세션을 둘 여는 것이지 세션 수가 요점이 아니다.
    // 문항 2: activeCount()==0은 <b>애초에 안 열었어도</b> 참이다 — 알림이 두 길 다 실제로 갔는지를
    //         calls로, 소켓이 섰다 내려갔는지를 connectionCount·closedSessionCount로 같이 본다.
    // 문항 4: closedSessionCount()는 ⓐ를 <b>가르지 못한다</b> — dropConnectionFor가 서버 쪽 소켓을 닫아
    //         그 자체로 1이 오른다(FakeChzzkBehavior.forget 확인). 「소켓이 섰다 내려갔다」의 양성 대조로만
    //         쓰고, 안쪽 겹을 실제로 가르는 것은 ⓐ의 activeCount()==0과 ⓑ의 「open이 안 던진다」다.
    // 문항 5: 등록부 notifyPermanentStop의 catch를 rethrow로 바꾸면 ⓐ는 자리가 안 비어,
    //         ⓑ는 open()이 예외로 튀어 빨간불(확인함).
    @Test
    void 던지는_리스너를_걸어도_두_길_다_자리가_비고_open은_안_던진다() throws Exception {
        givenRegistry();
        List<String> calls = new java.util.concurrent.CopyOnWriteArrayList<>();
        registry.onPermanentStop((streamId, reason) -> {
            calls.add(streamId);
            // 메모 저장이 던지는 상황이다. 바깥 겹(recorder)이 없는 리스너를 일부러 건다 —
            // 있으면 안쪽 겹을 지워도 아무 일이 안 일어나 이 검사가 다시 아무것도 안 잰다.
            throw new IllegalStateException("리스너가 던진다");
        });

        // ⓐ 붙어 있다가 끊긴 뒤 재연결 발급이 401 — 알림은 그 세션의 재연결 루프 위 stopOne에서 간다.
        assertThat(registry.open(key("s1", 1L, "chA"), "tokA")).isTrue();
        awaitUntil(AWAIT, () -> behavior.isConnected("tokA"));
        behavior.failSessionCreateFor("tokA", 401);
        behavior.dropConnectionFor("tokA");

        awaitUntil(AWAIT, () -> calls.contains("s1"));
        assertThat(calls).as("알림이 안 갔으면 아래 activeCount 단언은 공짜다").contains("s1");
        awaitUntil(AWAIT, () -> registry.activeCount() == 0);
        assertThat(registry.activeCount())
                .as("리스너가 던져 stopOne이 튀면 자리가 남고 그 스트리머는 새 방송을 영영 못 연다")
                .isZero();
        assertThat(behavior.connectionCount()).as("ⓐ는 소켓이 한 번 섰다").isGreaterThanOrEqualTo(1);
        assertThat(behavior.closedSessionCount()).as("그 소켓이 내려갔다").isGreaterThanOrEqualTo(1);

        // ⓑ 첫 수립에서 발급이 401 — stopOne이 아니라 open()의 catch에서 간다. 소켓은 선 적이 없다.
        int connectionsAfterA = behavior.connectionCount();
        behavior.failSessionCreateFor("tokB", 401);
        java.util.concurrent.atomic.AtomicBoolean openedB =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        Throwable thrown = catchThrowable(() -> openedB.set(registry.open(key("s2", 2L, "chB"), "tokB")));

        assertThat(thrown)
                .as("이 길의 알림은 편지 폴링 스레드 위에서 동기로 돈다 — 튀면 그 회차가 통째로 중단되고 편지가 안 지워진다")
                .isNull();
        assertThat(openedB).as("수립에 실패했으니 false다").isFalse();
        assertThat(calls).contains("s2");
        assertThat(registry.activeCount()).isZero();
        assertThat(behavior.connectionCount())
                .as("ⓑ는 소켓이 선 적이 없다 — 발급에서 끝났다")
                .isEqualTo(connectionsAfterA);
    }

    /**
     * 그 방송 세션이 들고 있는 <b>영구 정지 손잡이</b>를 꺼낸다. 등록부가 세션에 넘긴
     * 콜백이고, 재연결 루프가 재시도 불가 사유를 확정했을 때 부르는 바로 그것이다.
     */
    @SuppressWarnings("unchecked")
    private Consumer<StopReason> permanentStopHandleOf(String streamId) throws Exception {
        java.lang.reflect.Field sessionsField = SessionRegistry.class.getDeclaredField("sessions");
        sessionsField.setAccessible(true);
        java.util.Map<Long, ?> seats = (java.util.Map<Long, ?>) sessionsField.get(registry);
        for (Object entry : seats.values()) {
            java.lang.reflect.Method sessionOf = entry.getClass().getDeclaredMethod("session");
            sessionOf.setAccessible(true);
            StreamSession session = (StreamSession) sessionOf.invoke(entry);
            if (!session.key().streamId().equals(streamId)) {
                continue;
            }
            java.lang.reflect.Field handle =
                    StreamSession.class.getDeclaredField("onPermanentStop");
            handle.setAccessible(true);
            return (Consumer<StopReason>) handle.get(session);
        }
        throw new AssertionError("자리에 " + streamId + " 세션이 없다");
    }

    // ------------------------------------------------------------------
    // 도우미
    // ------------------------------------------------------------------

    private void givenRegistry() {
        givenRegistry(true);
    }

    private void givenRegistry(boolean enabled) {
        givenRegistry(enabled, Duration.ofSeconds(5));
    }

    private void givenRegistry(boolean enabled, Duration establishTimeout) {
        buffer = new ChatBuffer(1_000);
        registry = new SessionRegistry(
                // <b>설정 토큰은 안 쓰인다.</b> 세션마다 자기 토큰으로 붙는 것을
                // 위 connectedTokens() 단언이 지킨다 — 여기에 진짜 같은 값을 두면
                // 설정에서 읽는 회귀가 그 단언을 통과해 버린다.
                new ChzzkProperties(enabled, "설정-토큰-쓰면-안-된다",
                        "http://localhost:" + port, establishTimeout, FIRST_DELAY, MAX_DELAY),
                restClientBuilder,
                buffer, TestPersistence.disabledPersister(),
                ChatArchive.NONE);
    }

    /**
     * <b>부르는 순서대로 방송 시작 시각이 늦어진다.</b> 대부분의 검사가 「먼저 연 방송 →
     * 나중 방송」 순으로 부르므로 그 의도와 맞는다. 앞뒤를 뒤집어 재는 검사는
     * {@link #keyStartedAt} 로 시각을 직접 준다.
     */
    private static SessionKey key(String streamId, long streamerId, String channelId) {
        return keyStartedAt(streamId, streamerId, channelId,
                Instant.EPOCH.plusSeconds(KEY_SEQ.incrementAndGet()));
    }

    private static SessionKey keyStartedAt(String streamId, long streamerId, String channelId,
                                           Instant startedAt) {
        return new SessionKey(streamId, streamerId, channelId, startedAt);
    }

    private static final java.util.concurrent.atomic.AtomicLong KEY_SEQ =
            new java.util.concurrent.atomic.AtomicLong();

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
