package com.pokeclip.chat.collector.session;

import com.pokeclip.chat.collector.ChzzkProperties;
import com.pokeclip.chat.collector.archive.ChatArchive;
import com.pokeclip.chat.collector.fake.FakeChzzkBehavior;
import com.pokeclip.chat.collector.fake.FakeChzzkTest;
import com.pokeclip.chat.collector.persist.ChatBuffer;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import com.pokeclip.chat.collector.support.TestPersistence;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>세션 여럿을 종료 유예 안에 닫는가.</b>
 *
 * <p>닫는 순서는 프로세스 하나에 대해 이렇게 정했다 —
 * <b>편지 그만 받기 → 마지막 회차 빗장 → 세션 닫기 → 싱크 닫기 → 판정.</b>
 * 앞의 둘은 {@code SqsIntakeLoop.stop()}(라이프사이클, 빈 파괴보다 먼저)과
 * {@link SessionRegistry}의 종료 빗장이 맡고, 뒤의 셋은 {@code CollectorRunner.stop()}이
 * 한 줄기로 돈다.
 *
 * <p><b>예산(유예 20초, 편지 경로 기준)</b>: 마지막 회차 join 2초 + 세션 닫기 최악 8초
 * (반납 시한 접속 2 + 읽기 5, 소켓 닫기 1 — <b>겹쳐서 나가므로 세션 수와 무관</b>) +
 * 싱크 닫기 5초 = <b>15초</b>. 러너 자신의 재연결 대기 9초는 옛 경로 전용이고 두 경로는
 * 같이 켜지지 못한다(부팅 거부). 롱폴링 마지막 회차(최대 20초)는 <b>기다리지 않는다</b> —
 * 기다리면 그 하나로 유예를 다 쓴다. 대신 빗장이 그 회차의 세션 열기를 막고, 편지는
 * {@code RETRY_LATER}로 큐에 남아 다음 프로세스가 받는다.
 *
 * <p>실측이 계획의 산수를 셋 뒤집었다(태스크 1·8B). <b>반납 왕복 55~69ms</b>(약 1초가 아니다) ·
 * <b>완전히 겹친다</b>(총 소요 = 최대 개별 소요, HTTP/2) · <b>소켓 닫기 1초</b>(첫 {@code .get}이
 * 만료되면 catch로 빠져 둘째가 안 돈다). 「상한 7초」는 관측된 왕복이 아니라 <b>시한</b>이다.
 *
 * <p>갈래마다 {@code .claude/skills/multi-session-test-reality} 문항 다섯의 답을 주석에 남긴다.
 */
@FakeChzzkTest
class SessionShutdownTest extends IntegrationTestSupport {

    private static final Duration AWAIT = Duration.ofSeconds(10);
    private static final Duration FIRST_DELAY = Duration.ofMillis(200);
    private static final Duration MAX_DELAY = Duration.ofSeconds(60);

    @LocalServerPort int port;
    @Autowired FakeChzzkBehavior behavior;
    @Autowired RestClient.Builder restClientBuilder;

    private SessionRegistry registry;
    private ChatBuffer buffer;

    @AfterEach
    void tearDown() {
        if (registry != null) {
            registry.closeAll();
        }
        behavior.reset();
    }

    /**
     * <b>세션 열 개를 순차로 닫으면 유예를 넘긴다.</b> 실측 기준 세션당 반납 55~69ms +
     * 소켓 닫기 1초라 열 명이면 약 11초, 백 명이면 유예 20초를 통째로 넘는다.
     *
     * <p>반납을 800ms 붙들어 그 차이를 실험실에서 잰다 — 순차면 8초 이상, 나란히면
     * 한 번의 800ms다.
     */
    // 문항 1: 세션 하나로 바꾸면 순차·동시가 구분되지 않아 초록이 된다. 그래서 열을 연다.
    // 문항 2: 시간 단언은 "아무것도 안 하면" 자동으로 참이다 — 닫기 전에 소켓 열 개가
    //         실제로 서 있었는지, 닫은 뒤 반납이 열 건 다 왔는지를 같이 본다.
    // 문항 3: 겹침은 우리 쪽에서 안 센다. 상대(가짜 서버) 안에 동시에 몇이 있었나는
    //         아래 갈래가 따로 잰다.
    @Test
    void 세션이_열_개여도_종료_유예_안에_닫힌다() {
        givenRegistry();
        for (int i = 0; i < 10; i++) {
            assertThat(registry.open(key("s" + i, i, "ch" + i), "tok" + i)).isTrue();
        }
        assertThat(openSocketsOfMine(10))
                .as("닫을 소켓이 열 개가 아니면 이 시간 단언은 아무것도 안 잰다")
                .isEqualTo(10);
        behavior.unsubscribeDelay = Duration.ofMillis(800);
        int releasesBefore = behavior.unsubscribeCallCount();

        long startedAt = System.nanoTime();
        registry.closeAll();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(elapsed)
                .as("순차로 닫으면 800ms × 10 = 8초다. 백 명이면 유예 20초를 넘긴다")
                .isLessThan(Duration.ofSeconds(5));
        assertThat(behavior.unsubscribeCallCount() - releasesBefore)
                .as("빨리 끝났지만 반납을 건너뛴 것이라면 상한 3개를 열 개나 태운 것이다")
                .isEqualTo(10);
        assertThat(registry.activeCount()).isZero();
    }

    /**
     * <b>「동시에 부른다」와 「동시에 나간다」는 다르다.</b> {@code RestClient}가 프로세스에
     * 하나라 같은 호스트 동시 연결 상한에 걸리면 실제로는 배치로 나가고, 그러면 예산이
     * 배치 수만큼 곱해진다 — 검사는 초록인데 운영에서 유예를 넘긴다.
     *
     * <p><b>🔴 이 값은 가짜 서버(HTTP/1.1)가 잰 것이다.</b> 판정 근거는 태스크 1의 실계정
     * 프로브다 — 치지직 상대 세션 셋 동시 반납이 총 67ms·69ms(= 최대 개별 소요)였고 전 구간
     * HTTP_2였다. 가짜는 커넥션을 나눠 써서 겹치고 실물은 커넥션 하나에 스트림으로 몰려
     * 겹친다 — <b>겹친다는 결론은 같고 이유가 다르다.</b>
     */
    // 문항 1: 세션 하나면 "겹침"이 성립하지 않는다.
    // 문항 3: 이 갈래가 문항 3 자체다. 관측점이 상대 쪽(서버 안에 동시에 몇이 있었나)이다.
    // 문항 4: 도착 시각 뭉치로 재면 뭉치 경계(몇 ms)를 우리가 고르게 되고 답이 그 값에
    //         달린다. "안에 몇이 같이 있었나"는 고를 것이 없다.
    @Test
    void 반납이_실제로_겹쳐서_나간다() {
        givenRegistry();
        for (int i = 0; i < 10; i++) {
            assertThat(registry.open(key("s" + i, i, "ch" + i), "tok" + i)).isTrue();
        }
        // 붙들지 않으면 왕복이 로컬에서 1ms라 열 개가 저절로 어긋나 지나간다.
        behavior.unsubscribeDelay = Duration.ofMillis(500);
        int releasesBefore = behavior.unsubscribeCallCount();

        registry.closeAll();

        // <b>겹친 수는 남의 반납이 섞이면 부풀 수 있다.</b> 이 창에 들어온 반납이 정확히
        // 내 열 개였음을 같이 못박는다 — 안 그러면 앞선 검사의 낙오 반납이 숫자를 채워
        // 배치로 나가는 구현도 통과시킨다.
        assertThat(behavior.unsubscribeCallCount() - releasesBefore).isEqualTo(10);
        assertThat(behavior.maxConcurrentReleases())
                .as("배치로 나가면 여기가 1이다 — 그때 종료 예산은 배치 수만큼 곱해진다")
                .isGreaterThanOrEqualTo(10);
    }

    /**
     * <b>한 스트리머의 반납이 실패해도 나머지는 닫힌다.</b> 안 닫히면 그 자리가 서버에
     * 10초~4분 42초 남고(실측) 상한은 계정당 3개다.
     */
    // 문항 2: {@code activeCount()==0}은 <b>자동으로 참이다</b> — 등록부에서 지운 것은
    //         우리 자신이고, 소켓을 하나도 안 닫아도 0이 된다. 상대 쪽에서 반납 건수와
    //         열린 소켓 수를 같이 본다.
    // 문항 5: <b>이 갈래를 빨간불로 만드는 결함을 못 만들었다.</b> 시도한 것 —
    //         ① 순차 닫기로 되돌림(주입 A): 이 갈래만 <b>초록이었다</b>(다른 둘은 빨강).
    //         ② 반납 실패가 어디서 삼켜지는지 코드로 추적: {@code ChatSession.releaseAndClose}가
    //            {@code unsubscribeChatQuietly}의 실패를 {@code Release.FAILED}로 바꿀 뿐이고
    //            소켓 닫기는 어느 결말에서도 돈다 — 즉 실패가 위로 안 새서 막을 갈림이 없다.
    //         남기는 이유는 실패 갈래를 <b>실제로 밟으면서</b> 나머지 둘의 반납·소켓 닫기가
    //         건너뛰어지지 않는 것을 양성 대조(반납 3건)와 함께 보기 때문이다.
    //         <b>이 갈래를 「검사받는 방어」로 읽지 마라.</b>
    @Test
    void 한_세션이_반납에_실패해도_나머지가_닫힌다() throws Exception {
        givenRegistry();
        for (int i = 0; i < 3; i++) {
            assertThat(registry.open(key("s" + i, i, "ch" + i), "tok" + i)).isTrue();
        }
        behavior.failReleaseFor("tok1", 500);
        int releasesBefore = behavior.unsubscribeCallCount();

        registry.closeAll();

        assertThat(registry.activeCount()).isZero();
        assertThat(behavior.unsubscribeCallCount() - releasesBefore)
                .as("실패한 하나에서 멈추면 뒤엣것의 반납이 아예 안 나간다")
                .isEqualTo(3);
        awaitUntil(AWAIT, () -> openSocketsOfMine(3) == 0);
        assertThat(openSocketsOfMine(3))
                .as("반납이 터졌다고 소켓 닫기를 건너뛰면 서버는 우리가 살아 있다고 본다")
                .isZero();
    }

    /**
     * <b>닫는 중에 온 채팅은 아직 담긴다.</b> 수신 게이트를 세션 닫기 <b>앞에</b> 내리면,
     * 퍼시스터가 멀쩡하고 소켓도 아직 살아 있는데 그 구간의 채팅을 통째로 버린다.
     * 반대로 게이트가 아예 없으면 소켓이 닫힌 뒤 콜백에 남아 있던 마지막 프레임이
     * <b>곧 닫힐 바구니</b>에 들어가 아무도 저장하지 않으면서 등식의 좌변만 올린다.
     * 그래서 순서가 <b>전부 닫기 → 게이트</b>다.
     *
     * <p>반납 왕복을 붙들어 그 창을 연다 — {@code cleanUpOnce}는 반납이 돌아온 <b>뒤에</b>
     * 소켓을 닫으므로, 반납이 갇혀 있는 동안 소켓은 살아 있고 채팅을 받는다.
     */
    // 문항 2: "담겼다"를 등록부 카운터로만 보면 <b>세기만 하고 안 담는</b> 구현도 통과한다 —
    //         바구니 크기를 같이 본다(둘이 갈리면 등식이 벌어진다).
    // 문항 5: 게이트를 닫기 앞으로 옮기면 둘 다 0으로 빨간불 — 확인함(주입 F).
    //         <b>이 갈래를 넣기 전에는 그 주입이 6개 전부 초록이었다.</b>
    @Test
    void 닫는_중에_온_채팅은_아직_담긴다() throws Exception {
        givenRegistry();
        assertThat(registry.open(key("s1", 1L, "chA"), "tokA")).isTrue();
        // 반납이 도착한 것을 센 뒤에 붙든다 — 그 시점이 곧 "닫는 스레드가 갇힌 시점"이다.
        behavior.unsubscribeDelay = Duration.ofMillis(1_500);
        int releasesBefore = behavior.unsubscribeCallCount();

        Thread closing = new Thread(registry::shutdown, "test-shutdown");
        closing.start();
        awaitUntil(AWAIT, () -> behavior.unsubscribeCallCount() > releasesBefore);
        assertThat(behavior.unsubscribeCallCount() - releasesBefore)
                .as("반납이 아직 안 나갔으면 소켓이 이미 닫혔을 수 있어 아래가 무의미하다")
                .isEqualTo(1);

        behavior.emitChatTo("tokA", "{\"content\":\"닫는중\",\"messageTime\":1}");

        closing.join(AWAIT.toMillis());
        assertThat(closing.isAlive()).as("닫기가 안 끝났으면 아래 값이 아직 움직인다").isFalse();
        assertThat(buffer.size())
                .as("게이트를 닫기 앞에 내리면 퍼시스터가 멀쩡한데도 이 채팅을 버린다")
                .isEqualTo(1);
        assertThat(registry.receivedTotal())
                .as("담기만 하고 안 세면 등식의 좌변이 그만큼 모자란다")
                .isEqualTo(1);
    }

    /**
     * 🔴 <b>감사 1이 재현한 중대다.</b> {@code stop()} 뒤에도 소켓이 살아 있고 구독 반납이
     * 0건이며, 싱크가 닫힌 뒤에도 채팅이 계속 담겼다.
     *
     * <p><b>위 세 갈래는 이 결함을 못 잡는다</b> — 셋 다 {@code registry.closeAll()}을 검사가
     * 직접 부르므로, 종료가 등록부를 한 번도 안 지나도 전부 초록이다. 여기서는 부르지 않는다.
     *
     * <p><b>{@code runner.stop()}을 직접 부르는 것으로도 모자란다</b> — 그러면 「stop()이
     * 등록부를 지나는가」는 재지만 「그 stop()이 종료 경로에 실제로 걸려 있는가」는 안 잰다.
     * 수집기를 <b>따로 띄운 컨텍스트로 올리고 그것을 닫는다.</b> 가짜 서버는 이 클래스의
     * 컨텍스트라 닫힌 뒤에도 "무엇을 받았는지"를 물어볼 수 있다({@code CollectorShutdownTest}와
     * 같은 수법 — 같은 컨텍스트에서 닫으면 톰캣이 같이 죽어 <b>소켓 0개가 자동으로 참</b>이 된다).
     */
    // 문항 2: 위 문단이 그 답이다. 반납 건수(상대 쪽 관측)를 같이 봐서 "닫혔다"가
    //         등록부 딱지가 아니라 실제 왕복임을 못박는다.
    // 문항 4: 반납 2건만 보면 <b>소켓은 안 닫은</b> 구현도 통과한다 — 서버가 관측한 열린
    //         소켓 수를 같이 본다.
    @Test
    void 프로세스_종료가_등록부_세션까지_닫는다() throws Exception {
        ConfigurableApplicationContext context = bootCollector();
        SessionRegistry booted = context.getBean(SessionRegistry.class);
        assertThat(booted.open(key("s1", 42L, "chA"), "tok42")).isTrue();
        assertThat(booted.open(key("s2", 43L, "chB"), "tok43")).isTrue();
        // 양성 대조. 안 붙었으면 아래 단언 전부가 "안 했다"를 통과로 읽는다.
        assertThat(behavior.isConnected("tok42")).isTrue();
        assertThat(behavior.isConnected("tok43")).isTrue();
        int releasesBefore = behavior.unsubscribeCallCount();

        context.close();

        assertThat(booted.activeCount()).isZero();
        assertThat(behavior.unsubscribeCallCount() - releasesBefore)
                .as("종료가 등록부를 안 지나면 반납이 0건이다 — 감사가 재현한 그 값이다")
                .isEqualTo(2);
        awaitUntil(AWAIT, () -> !behavior.isConnected("tok42") && !behavior.isConnected("tok43"));
        assertThat(behavior.isConnected("tok42"))
                .as("소켓이 살아 있으면 서버는 우리가 아직 수집 중이라고 본다")
                .isFalse();
        assertThat(behavior.isConnected("tok43")).isFalse();
    }

    /**
     * <b>정지 신호 뒤에도 세션이 하나 더 열릴 수 있다.</b> 큐 롱폴링(최대 20초)에 들어간
     * {@code receiveMessage}는 인터럽트로 안 끊기고, 돌아오면 <b>이미 받은 편지를 마저
     * 처리한다</b>. 그 회차를 기다리면 그 하나로 유예 20초를 다 쓰므로 기다리지 않는다 —
     * 대신 등록부가 빗장을 건다.
     *
     * <p><b>편지를 잃지 않는다.</b> {@code open()}이 false이고 그 스트리머의 현재 방송도
     * 없으면 {@code LinkedSessionStarter}가 {@code RETRY_LATER}를 내므로 편지는 지워지지
     * 않고 큐에 남는다 — 다음 프로세스가 그것을 받는다.
     */
    // 문항 2: {@code opened==false}만 보면 <b>아무것도 안 하는</b> 구현도 통과한다.
    //         빗장 앞뒤로 세션 발급 호출 수가 안 늘었는지를 상대 쪽에서 본다.
    // 문항 4: false를 주면서 소켓만 여는 구현이 있다 — 열린 소켓 수를 같이 본다.
    @Test
    void 종료가_시작된_뒤에_온_시작_편지는_세션을_열지_않는다() {
        givenRegistry();
        assertThat(registry.open(key("s1", 1L, "chA"), "tokA")).isTrue();
        // <b>{@code closeAll()}이 아니라 {@code shutdown()}이다.</b> 앞엣것은 「지금 붙어
        // 있는 것을 다 닫는다」라 그 뒤에도 다시 열 수 있다 — 빗장은 종료 전용이다.
        registry.shutdown();
        int authCallsAfterClose = behavior.authCallCount();

        assertThat(registry.open(key("s2", 2L, "chB"), "tokB"))
                .as("종료 중에 세션을 열면 아무도 그것을 닫지 않는다")
                .isFalse();

        assertThat(behavior.authCallCount())
                .as("발급까지 갔으면 상한 3개 중 하나를 태우고 그 자리는 아무도 안 반납한다")
                .isEqualTo(authCallsAfterClose);
        assertThat(behavior.isConnected("tokB"))
                .as("false를 주면서 소켓만 여는 구현이 여기서 걸린다")
                .isFalse();
        assertThat(registry.activeCount()).isZero();
        assertThat(registry.currentStreamIdOf(2L))
                .as("현재 방송이 null이어야 편지가 RETRY_LATER로 큐에 남는다")
                .isNull();
    }

    /**
     * <b>판정 줄의 검산 등식이 등록부 세션을 센다.</b>
     * {@code received = persisted + conflicts + poisoned + dropped}에서 좌변만 세션별이고
     * 우변 넷은 공유 부품의 프로세스 누계다 — 좌변을 안 합치면 등식이 깨지는 것이 아니라
     * <b>「받은 게 없다」로 읽힌다.</b> 이 서비스가 유실을 알아채는 유일한 계기가 죽는다.
     *
     * <p>옛 경로가 꺼져 있으면 판정 줄 자체가 안 나가던 자리도 여기서 같이 잡는다 —
     * 편지 경로는 옛 경로를 <b>반드시</b> 꺼야 하므로(같이 켜면 부팅 거부) 그대로 두면
     * 운영에서 판정 줄이 한 줄도 안 나간다.
     */
    // 문항 1: 세션 둘의 몫이 <b>더해져야</b> 8이다. 하나면 합산인지 아닌지 안 갈린다.
    // 문항 2: "판정 줄이 있다"만 보면 received=0인 줄도 통과한다 — 값을 본다.
    // 문항 4: received=8은 <b>러너 자신의 세션</b>이 8을 받아도 통과한다. 옛 경로를 꺼
    //         러너 몫이 0인 것을 배선으로 못박았다(설정 기본값 + 두 경로 동시 켜짐 거부).
    @Test
    void 판정_줄이_등록부_세션이_받은_양을_싣는다() throws Exception {
        ConfigurableApplicationContext context = bootCollector();
        SessionRegistry booted = context.getBean(SessionRegistry.class);
        booted.open(key("s1", 42L, "chA"), "tok42");
        booted.open(key("s2", 43L, "chB"), "tok43");
        for (int i = 0; i < 5; i++) {
            behavior.emitChatTo("tok42", "{\"content\":\"a" + i + "\",\"messageTime\":" + (i + 1) + "}");
        }
        for (int i = 0; i < 3; i++) {
            behavior.emitChatTo("tok43", "{\"content\":\"b" + i + "\",\"messageTime\":" + (i + 1) + "}");
        }
        awaitUntil(AWAIT, () -> booted.receivedTotal() >= 8);
        assertThat(booted.receivedTotal())
                .as("바늘이 코드 안을 지나갔는가 — 안 흐르면 아래 단언이 0==0이다")
                .isEqualTo(8);

        // LogCaptor는 부팅 "뒤에" 단다. 스프링 부트가 시작하면서 로그백을 재초기화하며
        // 루트 로거의 appender를 떼기 때문이다(CollectorShutdownTest에서 실측된 함정).
        try (LogCaptor captor = new LogCaptor()) {
            context.close();

            assertThat(captor.messages())
                    .as("편지 경로에서 판정 줄이 아예 안 나가면 등식을 볼 자리가 없다")
                    .anyMatch(m -> m.startsWith("chat.session.verdict"));
            assertThat(captor.messages())
                    .as("등록부 몫을 안 합치면 received=0이고 「받은 게 없다」로 읽힌다")
                    .anyMatch(m -> m.startsWith("chat.session.verdict") && m.contains(" received=8 "));
        }
    }

    // ------------------------------------------------------------------
    // 도우미
    // ------------------------------------------------------------------

    private void givenRegistry() {
        buffer = new ChatBuffer(1_000);
        registry = new SessionRegistry(
                new ChzzkProperties(true, "설정-토큰-쓰면-안-된다",
                        "http://localhost:" + port, Duration.ofSeconds(5), FIRST_DELAY, MAX_DELAY),
                restClientBuilder,
                buffer, TestPersistence.disabledPersister(),
                ChatArchive.NONE);
    }

    /**
     * 수집기를 <b>따로 띄운다.</b> 가짜 서버는 이 클래스의 컨텍스트에 남으므로, 저쪽을
     * 닫아도 무엇을 받았는지 물어볼 수 있다.
     *
     * <p>옛 경로는 켜지 않는다(테스트 프로파일 기본값 false) — 러너가 세션을 하나도 안 열어야
     * 판정 줄의 {@code received}가 <b>등록부 몫만</b>으로 채워진다.
     */
    private ConfigurableApplicationContext bootCollector() {
        return new SpringApplicationBuilder(
                com.pokeclip.chat.collector.CollectorApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("test")
                .run("--pokeclip.chzzk.base-url=http://localhost:" + port,
                        "--pokeclip.chzzk.establish-timeout=" + Duration.ofSeconds(5),
                        // 따로 띄우는 컨텍스트라 @DynamicPropertySource가 안 걸린다. 안 넘기면
                        // localhost:5432로 가서 로컬 PG를 건드린다.
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword());
    }

    private static SessionKey key(String streamId, long streamerId, String channelId) {
        return new SessionKey(streamId, streamerId, channelId);
    }

    /**
     * <b>내 토큰의 소켓만 센다.</b> 가짜 서버는 스프링 컨텍스트 하나에 하나뿐이라 전체 수를
     * 세면 앞선 검사 클래스가 남긴 소켓까지 들어온다 — 실제로 12가 나왔다.
     */
    private long openSocketsOfMine(int sessions) {
        return java.util.stream.IntStream.range(0, sessions)
                .filter(i -> behavior.isConnected("tok" + i))
                .count();
    }
}
