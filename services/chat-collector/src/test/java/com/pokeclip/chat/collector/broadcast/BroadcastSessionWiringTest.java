package com.pokeclip.chat.collector.broadcast;

import com.pokeclip.chat.collector.ChzzkProperties;
import com.pokeclip.chat.collector.CollectorApplication;
import com.pokeclip.chat.collector.fake.FakeChzzkBehavior;
import com.pokeclip.chat.collector.fake.FakeChzzkServer;
import com.pokeclip.chat.collector.session.SessionRegistry;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>편지 한 통이 실제로 세션을 열고 닫는가.</b> 이 카드에서 부품이 처음 하나로 물리는 자리다 —
 * 큐 러너(태스크 6) → 판정기(태스크 5) → 열쇠(태스크 7) → 등록부(태스크 9)가 전부 <b>스프링이
 * 조립한 진짜 빈</b>이고, 폴링도 운영과 같은 데몬 스레드가 돈다.
 *
 * <p><b>가짜는 바깥 셋뿐이다</b> — 큐(SQS)·치지직·auth. 우리 부품은 하나도 안 바꿨다.
 * 이것이 {@code multi-session-test-reality} 문항 3의 답이다: 이음매를 가짜로 때우면
 * 「켜도 아무것도 안 열리는데 health는 초록」을 못 잡는데, 그 상태가 정확히 이 카드가
 * 막으려는 실패다(clip이 {@code @Bean Optional<T>}로 실제로 밟았다).
 *
 * <p><b>포트를 미리 잡아 두고 {@code DEFINED_PORT}로 띄운다.</b> 가짜 치지직은 이 앱의 웹
 * 컨테이너가 서빙하는데({@code FakeChzzkServer}), 등록부 빈은 {@code pokeclip.chzzk.base-url}을
 * <b>컨텍스트가 만들어질 때</b> 읽는다 — 랜덤 포트면 그 값을 미리 못 준다. 다른 검사들이
 * 등록부를 손으로 만들어 포트를 넘길 수 있었던 것과 달리, 여기서는 <b>스프링이 만든 그 빈</b>을
 * 봐야 해서 순서를 뒤집었다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "pokeclip.broadcast.intake.enabled=true",
        // 가짜 큐가 대신 답하므로 값은 안 쓰인다. 비면 IntakeProperties가 부팅을 막는다.
        "pokeclip.broadcast.intake.queue-url=http://localhost:1/000000000000/broadcast.fifo",
        // <b>옛 경로는 꺼져 있다.</b> 아래 검사가 그 상태에서도 편지로 붙는 것을 잰다.
        "pokeclip.chzzk.enabled=false",
        "pokeclip.chzzk.access-token=",
        "pokeclip.link.internal-token=wiring-test-internal-token"
})
@ActiveProfiles("test")
@Import({FakeChzzkServer.class, BroadcastSessionWiringTest.FakeQueueConfig.class})
// 이 클래스가 끝나면 컨텍스트를 닫는다. 안 닫으면 캐시된 채로 JVM이 끝날 때까지
// 톰캣·폴링 스레드·가짜 auth가 <b>남의 검사가 도는 내내</b> 살아 있다
// (CollectorBootWiringTest가 러너를 손으로 멈추는 것과 같은 이유). 닫는 김에
// SqsIntakeLoop.stop()의 종료 경로도 매 실행에서 한 번은 실제로 지나간다.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BroadcastSessionWiringTest extends IntegrationTestSupport {

    private static final Duration AWAIT = Duration.ofSeconds(15);

    /** 가짜 치지직을 서빙할 이 앱의 포트. 컨텍스트보다 먼저 정해져야 한다(클래스 주석). */
    private static final int PORT = freePort();

    private static final FakeAuth AUTH = FakeAuth.start();

    @Autowired SessionRegistry registry;
    @Autowired FakeChzzkBehavior behavior;
    @Autowired FakeQueue queue;
    @Autowired ChzzkProperties chzzk;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("pokeclip.chzzk.base-url", () -> "http://localhost:" + PORT);
        registry.add("pokeclip.link.auth-base-url", AUTH::baseUrl);
    }

    /**
     * <b>루프가 조용해지는 것을 먼저 기다린다.</b> 컨텍스트도 표도 클래스 전체가 나눠 쓰는데,
     * 편지를 지운 직후에도 <b>이미 꺼내 간 회차는 끝까지 처리된다</b> — 그 회차가 다음 검사가
     * 시작된 뒤에 세션을 열거나 끝난 방송 메모를 남기면, 그 검사는 자기가 안 보낸 편지의
     * 결과를 보게 된다. 실제로 그렇게 샜다: 첫 검사가 남긴 {@code s1} 메모 때문에 뒤 검사의
     * 시작 편지가 전부 {@code IGNORED_STALE}로 걸러졌고, <b>그중 하나는 그 상태로 초록이었다</b>
     * (열쇠를 한 번도 안 물어보고 편지만 지워졌는데 단언이 통과했다).
     *
     * <p>회차를 두 번 더 세는 것으로 조용해진 것을 안다 — 편지를 꺼내는 스레드가 하나라
     * 다음 회차가 시작됐다는 것은 앞 회차가 끝났다는 뜻이다. 시간으로 재지 않는다.
     */
    /**
     * <b>끝난 방송 표를 검사 앞에서도 비운다.</b> 이 표는 클래스들이 나눠 쓰는데, 다른 검사
     * 클래스가 마지막 검사에서 남긴 {@code s1} 메모가 여기까지 살아 들어온다 — 그러면 이
     * 클래스의 첫 시작 편지가 {@code IGNORED_STALE}로 걸러져 세션이 안 열린다(실제로 그렇게
     * 빨간불이 났다). 뒤에서 비우는 것만으로는 <b>앞선 클래스</b>를 못 막는다.
     */
    @BeforeEach
    void 표를_비운다() {
        jdbc.update("DELETE FROM chat_ended_streams");
    }

    @AfterEach
    void tearDown() throws Exception {
        int polls = queue.polls();
        queue.reset();
        awaitUntil(AWAIT, () -> queue.polls() >= polls + 2);
        // <b>한 번 더 지운다.</b> 첫 지우기와 회차가 끝나는 사이에 그 회차가 남긴 삭제 기록이
        // 들어온다 — 그것을 안 지우면 다음 검사의 {@code deleted()}가 남의 편지로 시작한다
        // (결함 주입 도중 실제로 그렇게 빨간불이 났다).
        queue.reset();
        registry.closeAll();
        jdbc.update("DELETE FROM chat_ended_streams");
        AUTH.reset();
        behavior.reset();
    }

    /**
     * <b>설정에 채널을 한 글자도 안 적어도 편지 하나면 붙는다.</b> 지시서의
     * {@code 시작_편지를_받으면_세션이_열린다}를 여기 합쳤다 — 두 갈래가 같은 코드를 지나고,
     * 이쪽이 「설정이 비어 있다」까지 같이 잰다.
     *
     * <p>운영 기본값이 {@code CHZZK_ENABLED=false}·토큰 없음이다. 그 상태에서 안 붙으면
     * 이 카드는 아무것도 바꾸지 못한 것이다.
     */
    // 문항 1: 세션 하나로 통과한다 — 다중화가 아니라 <b>배선</b>을 재는 갈래라 그렇다.
    //         다중화는 아래 종료 갈래가 잰다.
    // 문항 2: activeCount()==1만 보면 <b>발급을 한 번도 안 하고</b> 등록만 한 구현도 통과한다.
    //         가짜 치지직에서 접속과 토큰을 같이 본다.
    // 문항 4: 붙기는 붙었는데 <b>설정 토큰으로</b> 붙었으면 스트리머별 열쇠가 죽은 것이다 —
    //         연결된 토큰이 auth가 준 값인지 본다. 설정 토큰은 아예 빈 문자열로 둔다.
    @Test
    void 설정에_채널을_안_적어도_편지_하나로_수집이_시작된다() throws Exception {
        assertThat(chzzk.enabled()).as("옛 경로가 켜져 있으면 이 갈래가 무엇을 쟀는지 흐려진다").isFalse();
        assertThat(chzzk.accessToken()).isEmpty();
        AUTH.grants(42L, "ch42", "tok42");

        String letter = queue.deliver(started("evt-1", "s1", 42L, 1));

        awaitUntil(AWAIT, () -> registry.activeStreamIds().contains("s1"));
        assertThat(registry.activeStreamIds()).containsExactly("s1");
        assertThat(behavior.connectedTokens())
                .as("설정 토큰은 비어 있다 — auth가 준 열쇠로 붙지 않았으면 여기서 갈린다")
                .containsExactly("tok42");
        awaitUntil(AWAIT, () -> queue.deleted().contains(letter));
        assertThat(queue.deleted()).as("더 볼 일 없는 편지가 큐에 남으면 그 방송이 영원히 다시 열린다")
                .containsExactly(letter);
    }

    /**
     * <b>종료 편지는 그 방송만 닫는다.</b> 세션이 여럿이 되는 순간 「전부 닫기」와
     * 「그것만 닫기」가 갈리고, 세션 하나로는 그 둘이 구분되지 않는다.
     */
    // 문항 1: <b>세션 하나로는 성립조차 안 한다</b> — 닫히면 안 되는 남의 세션이 없다.
    // 문항 4: activeStreamIds()==["s2"]만 보면 <b>s2의 소켓까지 닫힌</b> 구현도 통과한다.
    //         「UP인데 수집 없음」이 이 서비스의 유일한 치명 실패라 상대 쪽 소켓도 본다.
    @Test
    void 종료_편지를_받으면_그_세션만_닫힌다() throws Exception {
        AUTH.grants(42L, "ch42", "tok42");
        AUTH.grants(43L, "ch43", "tok43");
        queue.deliver(started("evt-1", "s1", 42L, 1));
        queue.deliver(started("evt-2", "s2", 43L, 1));
        awaitUntil(AWAIT, () -> registry.activeCount() == 2);
        // awaitUntil은 시한이 차면 <b>조용히</b> 돌아온다. 여기서 안 세우면 「하나도 안 열렸다」가
        // 아래 토큰 단언의 실패로 둔갑해 원인이 반대로 보인다(실제로 그렇게 헤맸다).
        assertThat(registry.activeCount()).as("둘 다 안 열렸으면 아래 갈래는 성립조차 안 한다").isEqualTo(2);
        assertThat(behavior.connectedTokens())
                .as("두 스트리머가 각자의 열쇠로 붙어야 한다 — 하나로 둘을 열면 채팅이 두 번 들어온다")
                .containsExactlyInAnyOrder("tok42", "tok43");

        String bye = queue.deliver(ended("evt-3", "s1", 42L, 2));

        awaitUntil(AWAIT, () -> queue.deleted().contains(bye));
        awaitUntil(AWAIT, () -> registry.activeCount() == 1);
        assertThat(queue.deleted()).as("종료 편지가 큐에 남으면 그 방송이 영원히 다시 닫힌다").contains(bye);
        assertThat(registry.activeStreamIds()).containsExactly("s2");
        assertThat(behavior.isConnected("tok42")).as("닫으라고 한 쪽은 실제로 끊겨야 한다").isFalse();
        assertThat(behavior.isConnected("tok43"))
                .as("종료 편지 한 통이 남의 소켓까지 닫으면 그 방송들의 채팅이 통째로 사라진다")
                .isTrue();
    }

    /**
     * SQS는 at-least-once라 같은 편지가 두 번 오는 것이 정상이다.
     * <b>멱등의 방어선은 등록부의 {@code open()}이 이미 있는 방송에 false를 주는 것</b>이고,
     * 그 false를 {@code RETRY_LATER}로 읽으면 같은 회차가 영원히 반복된다.
     */
    // 문항 2: activeCount()==1은 <b>둘째가 소켓만 더 열고 등록을 안 해도</b> 참이다 —
    //         상한 3개를 우리 손으로 태우는 그 구현을 세션 발급 호출 수로 가른다.
    // 문항 4: 「둘 다 지웠다」만 보면 <b>둘 다 아무것도 안 한</b> 구현도 통과한다 —
    //         첫 통에서 세션이 실제로 열린 것을 먼저 확인하고 나서 둘째를 넣는다.
    @Test
    void 같은_시작_편지가_두_번_와도_세션은_하나다() throws Exception {
        AUTH.grants(42L, "ch42", "tok42");
        String body = started("evt-1", "s1", 42L, 1);
        String first = queue.deliver(body);
        awaitUntil(AWAIT, () -> registry.activeCount() == 1 && queue.deleted().contains(first));
        assertThat(registry.activeCount()).as("첫 통이 세션을 안 열었으면 「두 번째」가 없다").isEqualTo(1);
        assertThat(queue.deleted()).contains(first);
        int authCallsAfterFirst = behavior.authCallCount();

        String again = queue.deliver(body);   // 같은 봉투 — 재전송이다

        awaitUntil(AWAIT, () -> queue.deleted().contains(again));
        assertThat(queue.deleted())
                .as("재전송된 편지가 RETRY_LATER로 큐에 남으면 같은 회차가 영원히 반복된다")
                .contains(again);
        assertThat(registry.activeCount()).isEqualTo(1);
        assertThat(behavior.authCallCount())
                .as("둘째가 세션 발급까지 갔으면 계정별 상한 3개 중 하나를 우리 손으로 태운 것이다")
                .isEqualTo(authCallsAfterFirst);
    }

    /**
     * <b>열쇠를 못 받으면 세션을 안 열고, 편지의 운명은 사유가 정한다.</b>
     * 연동을 안 한 스트리머는 몇 번을 물어도 답이 같아 지우고(안 지우면 그 방송의 큐가
     * 영원히 막힌다), auth가 잠깐 아픈 것은 남긴다(지우면 그 방송이 통째로 안 걷힌다).
     */
    // 문항 2: 「세션이 안 열렸다」는 <b>아무것도 안 일어나도</b> 참이다 —
    //         auth가 실제로 불렸는지를 상대 쪽에서 먼저 확인한다.
    // 문항 4: 「지웠다/안 지웠다」만 보면 두 사유가 뒤바뀐 구현이 각각 반쪽씩 통과한다 —
    //         한 갈래에서 둘을 같이 잰다.
    @Test
    void 다시_물어도_같은_사유는_편지를_지우고_잠깐의_장애는_남긴다() throws Exception {
        AUTH.refuses(42L, "NOT_LINKED");
        AUTH.refuses(43L, "REFRESH_UNAVAILABLE");

        String linked = queue.deliver(started("evt-1", "s1", 42L, 1));
        String glitch = queue.deliver(started("evt-2", "s2", 43L, 1));

        awaitUntil(AWAIT, () -> queue.deleted().contains(linked));
        assertThat(queue.deleted())
                .as("연동 없는 스트리머의 편지가 큐에 남으면 그 방송 그룹이 영원히 막힌다")
                .containsExactly(linked);
        // 다시 받아졌다 = 큐에 남아 있다. 「아직 안 지워졌다」를 시간으로 재지 않는다.
        awaitUntil(AWAIT, () -> queue.receiveCount(glitch) >= 2);
        assertThat(queue.receiveCount(glitch))
                .as("auth 장애로 못 연 편지를 지우면 그 방송의 채팅이 통째로 사라진다")
                .isGreaterThanOrEqualTo(2);
        assertThat(queue.deleted()).doesNotContain(glitch);
        assertThat(registry.activeCount()).isZero();
        assertThat(AUTH.callCount()).as("아무것도 안 물어봤으면 위 단언들이 전부 공짜로 참이다")
                .isGreaterThanOrEqualTo(2);
    }

    /**
     * <b>세션 수립 실패는 다시 오면 될 수 있다.</b> 치지직이 5xx라 못 붙은 것이지
     * 그 방송이 없어진 것이 아니다 — 지우면 그 방송은 영영 안 걷힌다.
     */
    // 문항 2: 「안 지웠다」는 러너가 죽어 아무것도 안 해도 참이다 —
    //         발급이 실제로 두 번 이상 시도된 것을 상대 쪽에서 본다.
    @Test
    void 세션_수립에_실패하면_편지를_남긴다() throws Exception {
        AUTH.grants(42L, "ch42", "tok42");
        behavior.failSessionCreateFor("tok42", 503);

        String letter = queue.deliver(started("evt-1", "s1", 42L, 1));

        // <b>발급 재시도를 기다린다.</b> 「다시 받아졌다」를 기다리면 그 회차의 발급은 아직
        // 안 나갔을 수 있어 아래 단언이 흔들린다(전체 실행에서 실제로 그렇게 깨졌다).
        // 발급이 두 번 나갔다는 것은 편지가 두 번 왔다는 뜻이므로 이쪽이 더 강한 조건이다.
        awaitUntil(AWAIT, () -> behavior.authCallCount() >= 2);
        assertThat(behavior.authCallCount()).as("발급을 아예 안 갔으면 이 갈래는 아무것도 안 쟀다")
                .isGreaterThanOrEqualTo(2);
        assertThat(queue.receiveCount(letter))
                .as("다시 오면 될 수 있는 편지를 지우면 그 방송의 채팅이 통째로 사라진다")
                .isGreaterThanOrEqualTo(2);
        assertThat(queue.deleted()).isEmpty();
        assertThat(registry.activeCount()).isZero();
    }

    /**
     * 열린 적 없는 방송의 종료 편지도 지운다. 재전송·순서 뒤집힘으로 흔히 온다 —
     * 여기서 터지면 그 방송의 큐가 영원히 막힌다.
     */
    @Test
    void 열린_적_없는_방송의_종료_편지도_지운다() throws Exception {
        String bye = queue.deliver(ended("evt-9", "없는방송", 42L, 1));

        awaitUntil(AWAIT, () -> queue.deleted().contains(bye));
        assertThat(queue.deleted()).containsExactly(bye);
        assertThat(registry.activeCount()).isZero();
    }

    /**
     * <b>두 시작 경로를 같이 켜면 부팅을 거부한다.</b>
     *
     * <p>옛 경로의 세션이 영구 정지(REVOKED·401·403)하면 {@code CollectorRunner}가
     * <b>프로세스를 exit 1로 내린다.</b> 그 결말은 「이 프로세스가 수집할 것이 그 하나뿐」일 때만
     * 맞다 — 편지로 연 세션이 백 개 떠 있는데 옛 경로 하나의 동의 철회로 전원이 끊기면,
     * 되살릴 STARTED 편지는 이미 소비돼 큐에 없다.
     *
     * <p>「둘 다 켜지면 옛 경로를 조용히 무시」로 풀지 않았다. 그러면 {@code CHZZK_ENABLED=true}가
     * 아무 일도 안 하는 스위치가 되는데, 이 서버가 반복해서 데인 자리가 정확히 그 모양이다.
     */
    // 문항 2: 「부팅이 실패했다」만 보면 <b>아무 이유로나</b> 실패해도 통과한다 —
    //         사유 문장을 같이 본다.
    @Test
    void 옛_경로와_편지_경로를_같이_켜면_부팅이_실패한다() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(CollectorApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("test")
                .run("--pokeclip.chzzk.enabled=true",
                        "--pokeclip.chzzk.access-token=probe-token",
                        "--pokeclip.chzzk.base-url=http://localhost:1",
                        "--pokeclip.broadcast.intake.enabled=true",
                        "--pokeclip.broadcast.intake.queue-url=http://localhost:1/q",
                        "--pokeclip.link.internal-token=wiring-test-internal-token",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword()))
                .rootCause()
                .hasMessageContaining("CHZZK_ENABLED");
    }

    // ------------------------------------------------------------------
    // 편지
    // ------------------------------------------------------------------

    private static String started(String eventId, String streamId, long streamerId, long sequence) {
        return envelope(eventId, "broadcast.started", streamId, streamerId, sequence);
    }

    private static String ended(String eventId, String streamId, long streamerId, long sequence) {
        return envelope(eventId, "broadcast.ended", streamId, streamerId, sequence);
    }

    private static String envelope(String eventId, String eventType, String streamId,
                                   long streamerId, long sequence) {
        return """
                {"schemaVersion":1,"eventId":"%s","eventType":"%s","occurredAt":"2026-08-19T00:00:00Z",
                 "streamId":"%s","streamerId":"%d","sequence":%d,"traceId":"t-1","payload":{}}"""
                .formatted(eventId, eventType, streamId, streamerId, sequence);
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------
    // 바깥 셋 중 둘 — 큐와 auth
    // ------------------------------------------------------------------

    /**
     * 가짜 큐를 <b>{@code @Primary}로</b> 얹는다. 운영 {@code sqsClient} 빈은 그대로 만들어지고
     * ({@code IntakeConfiguration}의 조건이 살아 있는지가 그것으로 드러난다), 러너가 받는
     * {@code ObjectProvider}는 이쪽을 고른다.
     */
    @TestConfiguration
    static class FakeQueueConfig {

        @Bean
        @Primary
        FakeQueue fakeQueue() {
            return new FakeQueue();
        }
    }

    /**
     * 편지를 넣고, 무엇이 지워졌는지·몇 번 다시 왔는지를 본다.
     *
     * <p><b>가시성 시한을 흉내 낸다</b> — 안 지운 편지는 {@link #VISIBILITY} 뒤에 다시 나온다.
     * 그래야 「안 지웠다」를 <b>기다림 없이</b> 잴 수 있다: 같은 편지가 두 번 온 것이 곧
     * 「아직 큐에 있다」의 증거다. 시간을 재서 「아직 안 지워졌네」로 판정하면 몇 밀리초 뒤에
     * 지워지는 구현도 통과한다.
     *
     * <p>빈손일 때 잠깐 자는 것은 롱폴링 흉내다. 안 자면 러너 루프가 CPU를 태운다 —
     * {@code pollOnce}는 성공하면 곧바로 다음 회차로 간다(롱폴링이 대기 역할이라는 전제).
     */
    static final class FakeQueue implements SqsClient {

        private static final long VISIBILITY_NANOS = Duration.ofMillis(300).toNanos();

        private final Object lock = new Object();
        private final List<Letter> letters = new ArrayList<>();
        private final List<String> deleted = new ArrayList<>();
        private final AtomicInteger sequence = new AtomicInteger();
        private final AtomicInteger polls = new AtomicInteger();

        /** @return 이 편지의 receiptHandle. 지워졌는지·다시 왔는지를 이 이름으로 짚는다 */
        String deliver(String body) {
            synchronized (lock) {
                Letter letter = new Letter("rh-" + sequence.incrementAndGet(), body);
                letters.add(letter);
                lock.notifyAll();
                return letter.handle;
            }
        }

        List<String> deleted() {
            synchronized (lock) {
                return List.copyOf(deleted);
            }
        }

        /** 회차 수. <b>다음 회차가 시작됐다 = 앞 회차가 끝났다</b>(꺼내는 스레드가 하나다). */
        int polls() {
            return polls.get();
        }

        int receiveCount(String handle) {
            synchronized (lock) {
                return letters.stream().filter(l -> l.handle.equals(handle))
                        .mapToInt(l -> l.receives).findFirst().orElse(0);
            }
        }

        void reset() {
            synchronized (lock) {
                letters.clear();
                deleted.clear();
            }
        }

        @Override
        public ReceiveMessageResponse receiveMessage(ReceiveMessageRequest request) {
            polls.incrementAndGet();
            synchronized (lock) {
                List<Message> batch = new ArrayList<>();
                long now = System.nanoTime();
                for (Letter letter : letters) {
                    if (batch.size() >= request.maxNumberOfMessages()) {
                        break;
                    }
                    if (letter.visibleAtNanos - now <= 0) {
                        letter.visibleAtNanos = now + VISIBILITY_NANOS;
                        letter.receives++;
                        batch.add(Message.builder().messageId(letter.handle)
                                .receiptHandle(letter.handle).body(letter.body).build());
                    }
                }
                if (batch.isEmpty()) {
                    try {
                        lock.wait(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return ReceiveMessageResponse.builder().messages(batch).build();
            }
        }

        @Override
        public DeleteMessageResponse deleteMessage(DeleteMessageRequest request) {
            synchronized (lock) {
                letters.removeIf(l -> l.handle.equals(request.receiptHandle()));
                deleted.add(request.receiptHandle());
                return DeleteMessageResponse.builder().build();
            }
        }

        @Override
        public String serviceName() {
            return "sqs";
        }

        @Override
        public void close() {
        }

        private static final class Letter {
            private final String handle;
            private final String body;
            private long visibleAtNanos = System.nanoTime();
            private int receives;

            private Letter(String handle, String body) {
                this.handle = handle;
                this.body = body;
            }
        }
    }

    /**
     * auth의 {@code POST /internal/chzzk-link/resolve}를 흉내 낸다. <b>회원 번호마다 다른 답</b>을
     * 줘야 「두 스트리머가 각자의 열쇠로 붙는가」를 잴 수 있다.
     *
     * <p>{@code ChzzkLinkClientTest}의 {@code FakeAuth}와 <b>목적이 다르다</b> — 저쪽은 한 명에게
     * 상태·지연·본문을 자유롭게 먹이며 클라이언트 하나를 재고, 이쪽은 회원별 정상 응답을 준다.
     * 계약(항상 200, 거절은 {@code {valid:false,reason}})은 같으므로 <b>한쪽을 고치면 다른 쪽도 본다.</b>
     */
    static final class FakeAuth {

        private static final Pattern USER_ID = Pattern.compile("\"userId\"\\s*:\\s*(\\d+)");

        private final HttpServer server;
        private final Map<Long, String> bodies = new ConcurrentHashMap<>();
        private final AtomicInteger calls = new AtomicInteger();

        private FakeAuth(HttpServer server) {
            this.server = server;
        }

        static FakeAuth start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
                server.setExecutor(Executors.newCachedThreadPool(runnable -> {
                    Thread thread = new Thread(runnable, "fake-auth-wiring");
                    thread.setDaemon(true);
                    return thread;
                }));
                FakeAuth fake = new FakeAuth(server);
                server.createContext("/", fake::handle);
                server.start();
                return fake;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        void grants(long userId, String channelId, String accessToken) {
            bodies.put(userId, "{\"valid\":true,\"channelId\":\"" + channelId + "\",\"accessToken\":\""
                    + accessToken + "\",\"expiresAt\":\"2027-01-01T00:00:00Z\"}");
        }

        void refuses(long userId, String reason) {
            bodies.put(userId, "{\"valid\":false,\"reason\":\"" + reason + "\"}");
        }

        int callCount() {
            return calls.get();
        }

        void reset() {
            bodies.clear();
            calls.set(0);
        }

        String baseUrl() {
            return "http://localhost:" + server.getAddress().getPort();
        }

        private void handle(HttpExchange exchange) throws IOException {
            calls.incrementAndGet();
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Matcher matcher = USER_ID.matcher(request);
            long userId = matcher.find() ? Long.parseLong(matcher.group(1)) : -1L;
            // 모르는 회원은 「연동 없음」이다 — 검사가 깜빡하고 안 정해 둔 회원이 조용히
            // 성공하면, 그 검사는 열쇠 조회를 한 번도 안 재고 통과한다.
            byte[] body = bodies.getOrDefault(userId, "{\"valid\":false,\"reason\":\"NOT_LINKED\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }
}
