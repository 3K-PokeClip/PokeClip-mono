package com.pokeclip.chat.collector.broadcast.reattach;

import ch.qos.logback.classic.Level;
import com.pokeclip.chat.collector.ChzzkProperties;
import com.pokeclip.chat.collector.StopReason;
import com.pokeclip.chat.collector.archive.ChatArchive;
import com.pokeclip.chat.collector.broadcast.BroadcastSessions;
import com.pokeclip.chat.collector.broadcast.EndedStream;
import com.pokeclip.chat.collector.broadcast.EndedStreamStore;
import com.pokeclip.chat.collector.broadcast.LinkedSessionStarter;
import com.pokeclip.chat.collector.broadcast.ProcessResult;
import com.pokeclip.chat.collector.broadcast.StreamerId;
import com.pokeclip.chat.collector.broadcast.attach.LaneKey;
import com.pokeclip.chat.collector.broadcast.attach.StreamerSerialExecutor;
import com.pokeclip.chat.collector.fake.FakeChzzkBehavior;
import com.pokeclip.chat.collector.fake.FakeChzzkTest;
import com.pokeclip.chat.collector.link.ChzzkLinkClient;
import com.pokeclip.chat.collector.link.LinkProperties;
import com.pokeclip.chat.collector.persist.ChatBuffer;
import com.pokeclip.chat.collector.session.SessionKey;
import com.pokeclip.chat.collector.session.SessionRegistry;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import com.pokeclip.chat.collector.support.TestPersistence;
import com.pokeclip.web.support.LogCaptor;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 재부착 — clip의 「방송 중 목록」을 걸러 <b>알림 경로와 같은 줄</b>에 넣는다 (POK-219 태스크 7).
 *
 * <p><b>가짜를 하나만 둔다 — clip 창구다.</b> 나머지(등록부·붙이기 문·메모 표·공백 측정기·줄)는
 * 전부 실물이다. 붙이기 문을 가짜로 두면 이 카드가 물어야 할 것 셋이 통째로 안 재어진다 —
 * <b>같은 줄에 들어가는가 · 갈아끼움을 못 이기는가 · 끝난 방송을 다시 여는가</b>는 모두
 * 등록부·문의 실제 동작이지 우리 판정문이 아니다.
 *
 * <p>{@code sessions}는 실물 {@link LinkedSessionStarter}를 감싼 기록기다 —
 * <b>문을 두드렸다</b>와 <b>실제로 붙었다</b>를 따로 볼 수 있어야 문항 6이 성립한다.
 *
 * <p><b>{@code attach-test-reality} 문항에 대한 답</b>
 * <ul>
 *   <li>문항 2(자동으로 참이 되나): {@code started()}가 비었다는 단언은 <b>혼자 두지 않는다</b> —
 *       {@code verify(client).list()}로 목록이 실제로 왔는지, {@code chat.reattach.swept}의
 *       {@code candidates}·{@code submitted}로 <b>어느 거름망이 걸렀는지</b>까지 같이 본다.
 *       거름망 둘이 겹쳐 있어(싼 것 먼저 · 줄 안에서 다시) 하나를 지워도 다른 하나가 막는다 —
 *       숫자를 안 보면 그 주입이 <b>초록으로 나온다</b></li>
 *   <li>문항 4(직렬 검사가 너무 빨라서 통과하나): 같은 줄 검사는 앞 작업을 latch로 <b>붙들고</b>
 *       {@code inFlight()==2}로 재부착이 실제로 제출된 것을 확인한 뒤에 「아직 안 돌았다」를
 *       단언하고, 풀어 준 뒤 <b>돌아가는 것까지</b> 본다</li>
 *   <li>문항 5(비동기 검사가 안 기다려서 통과하나): 모든 「안 붙었다」 단언 앞에
 *       {@code awaitIdle}이 있다 — 줄이 비었다는 것은 그 작업이 <b>끝났다</b>는 뜻이다</li>
 * </ul>
 */
@FakeChzzkTest
class ReattacherTest extends IntegrationTestSupport {

    private static final String A001 = "live-R-A001";
    private static final String A999 = "live-R-A999";
    private static final String B001 = "live-R-B001";
    /** 회원 <b>9</b>의 방송. {@link FakeAuth}가 안 아는 회원이라 {@code NOT_LINKED}(영구 거절)로 답한다. */
    private static final String C001 = "live-R-C001";
    private static final List<String> STREAMS = List.of(A001, A999, B001, C001);

    private static final Instant 시작 = Instant.parse("2026-08-31T04:00:00Z");
    /** 붙는 시점. 시작보다 10분 뒤다 — {@code EPOCH} 되돌림 주입이 갈아끼움을 이기게 하려면 뒤여야 한다. */
    private static final Instant 지금 = Instant.parse("2026-08-31T04:10:00Z");

    private static final Duration IDLE_BUDGET = Duration.ofSeconds(5);

    @LocalServerPort int port;
    @Autowired FakeChzzkBehavior behavior;
    @Autowired RestClient.Builder restClientBuilder;
    @Autowired EndedStreamStore store;
    @Autowired GapMeasurer measurer;
    @Autowired JdbcTemplate jdbc;

    private SessionRegistry registry;
    private FakeAuth auth;
    private StreamerSerialExecutor lanes;
    private RecordingSessions sessions;
    private LiveBroadcastClient client;

    @BeforeEach
    void 내_흔적을_지운다() {
        // 표 둘 다 JVM에 하나뿐인 컨테이너에 있다. 앞 검사·다른 클래스가 남긴 줄이
        // 메모 조회와 MAX(received_at)를 조용히 어긋나게 한다.
        STREAMS.forEach(streamId -> {
            jdbc.update("DELETE FROM chat_ended_streams WHERE stream_id = ?", streamId);
            jdbc.update("DELETE FROM chat_messages WHERE stream_id = ?", streamId);
        });
        auth = FakeAuth.start();
        auth.grants(1L, "channel-1", "token-1");
        auth.grants(2L, "channel-2", "token-2");
        auth.grants(7L, "channel-7", "token-7");
        registry = newRegistry();
        lanes = new StreamerSerialExecutor(50);
        // 🔴 <b>레코더를 안 문다 — 운영 배선이 그렇다</b>(POK-219 감사 라운드 3).
        // 한때 여기에 no-op 람다가 물려 있어서, 문이 메모를 남기던 시절에도
        // 「재부착이 메모를 만든다」가 이 파일에서 안 보였다. 지금은 문 자체에
        // 레코더가 없다 — 아래 「auth가 영구히 거절하는 갈래」 둘이 그것을 잰다.
        sessions = new RecordingSessions(new LinkedSessionStarter(newLinkClient(), registry));
        client = mock(LiveBroadcastClient.class);
    }

    @AfterEach
    void tearDown() {
        if (lanes != null) lanes.close();
        if (registry != null) registry.closeAll();
        if (auth != null) auth.stop();
        behavior.reset();
    }

    // ── 거름망 ────────────────────────────────────────────────────────────

    @Test
    @Timeout(30)
    void 안_붙은_방송을_줄에_넣는다() {
        returns(live(A001, "7", 시작));

        newReattacher(lanes).sweep();

        assertThat(lanes.awaitIdle(IDLE_BUDGET)).isTrue();
        assertThat(sessions.started()).containsExactly(A001);
        // 문을 두드린 것과 실제로 붙은 것은 다르다.
        assertThat(registry.currentStreamIdOf(7L)).isEqualTo(A001);
    }

    /**
     * 싼 거름망 — 메모리만 본다. DB 왕복도 줄 제출도 안 한다.
     *
     * <p>🔴 <b>{@code started()}가 비었다는 것만으로는 이 거름망을 못 잰다.</b> 지우면
     * 줄 안의 늦은 재확인({@code ALREADY_ATTACHED})이 대신 막아 <b>여전히 초록</b>이다.
     * {@code candidates=0}이 이 거름망 자체를 재는 유일한 자리다.
     */
    @Test
    @Timeout(30)
    void 이미_붙어_있는_방송은_건너뛴다() {
        열어둔다(A001, 7L, 시작);
        returns(live(A001, "7", 시작));

        try (LogCaptor captor = new LogCaptor()) {
            newReattacher(lanes).sweep();

            assertThat(lanes.awaitIdle(IDLE_BUDGET)).isTrue();
            assertThat(sessions.started()).isEmpty();
            assertThat(captor.messages()).anyMatch(line -> line.startsWith("chat.reattach.swept")
                    && line.contains("received=1") && line.contains("candidates=0"));
        }
        verify(client).list();
    }

    /** 포기 메모가 있으면 안 붙는다(PRD 결정). 같은 토큰이면 또 401이고 여는 트리거도 없다. */
    @Test
    @Timeout(30)
    void 포기_기록이_있는_방송은_건너뛴다() {
        store.rememberStopped(A001, StopReason.LINK_UNAVAILABLE.name(),
                Instant.parse("2026-08-31T03:00:00Z"));
        returns(live(A001, "7", 시작));

        메모가_있으면_안_붙는다();
    }

    /** clip이 종료 알림을 놓쳐 「방송 중」으로 남긴 줄이다. 우리는 끝난 것을 안다. */
    @Test
    @Timeout(30)
    void 종료_기록이_있는_방송은_건너뛴다() {
        store.remember(A001, 9, Instant.parse("2026-08-31T03:30:00Z"));
        returns(live(A001, "7", 시작));

        메모가_있으면_안_붙는다();
    }

    private void 메모가_있으면_안_붙는다() {
        try (LogCaptor captor = new LogCaptor()) {
            newReattacher(lanes).sweep();

            assertThat(lanes.awaitIdle(IDLE_BUDGET)).isTrue();
            assertThat(sessions.started()).isEmpty();
            // 🔴 candidates=1 submitted=0 — 「목록엔 있었는데 메모가 걸렀다」를 못박는다.
            // submitted를 안 보면 줄 안의 늦은 재확인이 대신 막아 주입이 초록으로 나온다.
            assertThat(captor.messages()).anyMatch(line -> line.startsWith("chat.reattach.swept")
                    && line.contains("candidates=1") && line.contains("submitted=0"));
        }
        assertThat(registry.activeCount()).isZero();
    }

    // ── 줄에 들어간 뒤에 다시 보는 것 둘 ──────────────────────────────────

    /**
     * 🔴 계획 검증 M2. 목록 조회는 스케줄러 스레드에서, 붙이기는 줄에서 한참 뒤에 돈다.
     * 그 사이에 종료 알림이 처리되면 <b>끝난 방송에 세션이 선다</b> — 그리고 닫을 트리거가
     * 없다(그 ENDED는 이미 소비됐다). 계정당 세 자리 중 하나를 프로세스가 끝날 때까지 먹는다.
     * {@code LinkedSessionStarter}는 {@code EndedStreamStore}를 안 본다.
     *
     * <p><b>창을 시간으로 벌리지 않는다.</b> 종료 알림이 실제로 도는 자리가 <b>같은 줄</b>이므로,
     * 그 줄을 붙들고 있는 작업이 메모를 남기게 하면 운영과 같은 순서가 결정적으로 만들어진다.
     */
    @Test
    @Timeout(30)
    void 목록을_받은_뒤에_종료_알림이_처리돼도_다시_열지_않는다() throws Exception {
        CountDownLatch 잡았다 = new CountDownLatch(1);
        CountDownLatch 놓는다 = new CountDownLatch(1);
        // 알림 경로의 ENDED 작업 흉내 — 같은 줄이라 재부착보다 반드시 먼저 끝난다.
        lanes.submit(LaneKey.of("7"), () -> {
            잡았다.countDown();
            await(놓는다);
            store.remember(A001, 9, Instant.parse("2026-08-31T04:05:00Z"));
        });
        assertThat(잡았다.await(5, TimeUnit.SECONDS)).isTrue();
        returns(live(A001, "7", 시작));

        try (LogCaptor captor = new LogCaptor()) {
            newReattacher(lanes).sweep();

            // 목록 조회 시점엔 메모가 없었다 — 그래서 줄에 들어갔다.
            assertThat(lanes.inFlight()).as("재부착이 실제로 제출됐다").isEqualTo(2);
            놓는다.countDown();
            assertThat(lanes.awaitIdle(IDLE_BUDGET)).isTrue();
            assertThat(sessions.started()).isEmpty();
            assertThat(captor.messages()).anyMatch(line ->
                    line.startsWith("chat.reattach.skipped_late")
                            && line.contains("stream=" + A001)
                            && line.contains("reason=MEMO_APPEARED"));
        }
        assertThat(registry.activeCount()).isZero();
    }

    /**
     * 🔴 계획 검증 T5. {@code sessions.start}는 「이미 걷고 있음」과 「새로 열었음」에
     * <b>똑같이 {@code PROCESSED}</b>를 준다. 안 가르면 {@code gap_measured}가
     * <b>붙지도 않은 재부착의 공백</b>을 찍어, 나중에 그 로그를 세면 유실이 실제보다 많아 보인다.
     */
    @Test
    @Timeout(30)
    void 목록을_받은_뒤에_붙어_버리면_공백을_안_찍는다() throws Exception {
        CountDownLatch 잡았다 = new CountDownLatch(1);
        CountDownLatch 놓는다 = new CountDownLatch(1);
        // 알림 경로의 STARTED 작업 흉내 — 재부착보다 먼저 붙는다.
        lanes.submit(LaneKey.of("7"), () -> {
            잡았다.countDown();
            await(놓는다);
            sessions.start(A001, new StreamerId(true, 7L), 시작);
        });
        assertThat(잡았다.await(5, TimeUnit.SECONDS)).isTrue();
        returns(live(A001, "7", 시작));

        try (LogCaptor captor = new LogCaptor()) {
            newReattacher(lanes).sweep();

            assertThat(lanes.inFlight()).as("재부착이 실제로 제출됐다").isEqualTo(2);
            놓는다.countDown();
            assertThat(lanes.awaitIdle(IDLE_BUDGET)).isTrue();
            assertThat(registry.currentStreamIdOf(7L)).isEqualTo(A001);
            assertThat(captor.messages())
                    .as("붙지도 않은 재부착의 공백을 찍으면 유실이 실제보다 많아 보인다")
                    .noneMatch(line -> line.startsWith("chat.reattach.gap_measured"));
            // 부정 단언 혼자는 로그가 0줄이어도 참이다 — 긍정 단언을 같이 둔다.
            assertThat(captor.messages()).anyMatch(line ->
                    line.startsWith("chat.reattach.skipped_late")
                            && line.contains("reason=ALREADY_ATTACHED"));
        }
    }

    // ── auth가 영구히 거절하는 갈래 ──────────────────────────────────────

    /**
     * auth가 영구히 거절한 갈래는 <b>세션이 서 보지도 못한다.</b> 그런데 러너에게는
     * {@code PROCESSED}와 똑같이 「지운다」라 <b>한때 그 둘이 한 값이었다</b> —
     * {@code LINK_REFUSED}가 갈라 준 것이 정확히 이 자리다. 안 가르면 안 붙은 방송에
     * 「붙었고 공백은 이만큼」이 나가고 「못 붙었다」는 안 나간다 —
     * <b>이 카드의 유일한 복구 지표를 세면 회복이 실제보다 많아 보인다.</b>
     */
    @Test
    @Timeout(30)
    void auth가_영구히_거절하면_안_붙었다고_남기고_공백을_안_찍는다() {
        returns(live(C001, "9", 시작));

        try (LogCaptor captor = new LogCaptor()) {
            newReattacher(lanes).sweep();

            assertThat(lanes.awaitIdle(IDLE_BUDGET)).isTrue();
            // 문항 6 — 「안 찍혔다」는 재부착이 아예 안 돌아도 참이다. 두드린 것을 같이 잰다.
            assertThat(sessions.started()).as("문은 실제로 두드렸다").containsExactly(C001);
            assertThat(registry.currentStreamIdOf(9L)).as("세션은 서지 않았다").isNull();
            assertThat(captor.messages())
                    .as("세션이 서지도 않았는데 공백을 찍으면 회복이 실제보다 많아 보인다")
                    .noneMatch(line -> line.startsWith("chat.reattach.gap_measured"));
            assertThat(captor.messages()).anyMatch(line ->
                    line.startsWith("chat.reattach.not_attached")
                            && line.contains("stream=" + C001)
                            && line.contains("result=LINK_REFUSED"));
        }
    }

    /**
     * 🔴 <b>재부착이 포기 메모를 만들면 자기가 자기를 24시간 막는다</b>(PRD 결정, 감사 라운드 3).
     * 그 메모는 「알림을 지우기 전에 남긴다」였는데 <b>재부착에는 지울 알림이 없다.</b>
     *
     * <p><b>두 번째 회차를 같이 돌리는 것이 이 검사의 이빨이다.</b> 메모 표만 보면
     * 「이 검사가 쓰는 문에 레코더가 안 물려 있어서」도 초록이다 — 다음 회차가 실제로
     * 문을 다시 두드리는 것까지 봐야 <b>거름망({@code sweepOnce}의 {@code findAllIds})이
     * 그 방송을 안 걸렀다</b>가 재어진다.
     */
    @Test
    @Timeout(30)
    void 재부착은_포기_메모를_안_만들어_다음_회차가_다시_시도한다() {
        returns(live(C001, "9", 시작));
        Reattacher reattacher = newReattacher(lanes);

        reattacher.sweep();
        assertThat(lanes.awaitIdle(IDLE_BUDGET)).isTrue();
        assertThat(store.find(C001)).as("재부착이 남긴 포기 메모").isEmpty();

        reattacher.sweep();
        assertThat(lanes.awaitIdle(IDLE_BUDGET)).isTrue();
        assertThat(sessions.started())
                .as("메모가 생겼다면 둘째 회차는 후보 단계에서 걸러 문을 안 두드린다")
                .containsExactly(C001, C001);
    }

    // ── 시작 시각 · 스트리머 식별자 ───────────────────────────────────────

    /**
     * 시각을 못 받은 줄은 {@link Instant#EPOCH}로 친다. 갈아끼움은 「더 늦게 시작한 방송만」이라
     * EPOCH는 <b>절대 못 이긴다</b> — 자리가 비었을 때만 붙고 살아 있는 세션을 안 뺏는다.
     *
     * <p>문항 6 — {@code currentStreamIdOf}만 보면 「안 뺏겼다」와 <b>「재부착이 아예 안 돌았다」</b>가
     * 같은 초록이다. 문을 실제로 두드렸다는 것({@code started()})을 같이 단언한다.
     */
    @Test
    @Timeout(30)
    void 시작_시각이_null이면_살아_있는_세션을_안_뺏는다() {
        열어둔다(A001, 7L, 시작);
        returns(live(A999, "7", null));

        try (LogCaptor captor = new LogCaptor()) {
            newReattacher(lanes).sweep();

            assertThat(lanes.awaitIdle(IDLE_BUDGET)).isTrue();
            assertThat(sessions.started()).as("재부착은 실제로 돌았다").containsExactly(A999);
            assertThat(registry.currentStreamIdOf(7L)).isEqualTo(A001);
            // 🔴 <b>안 붙었으면 공백도 안 찍는다.</b> 이 단언이 없어서 주입 X5
            // (not_attached 갈래 삭제)가 <b>초록이었다</b> — T5와 같은 결함인데
            // 「PROCESSED가 아닌 결과」 쪽 갈래를 재는 검사가 하나도 없었다.
            assertThat(captor.messages())
                    .as("붙지도 않은 재부착의 공백을 세면 유실이 실제보다 많아 보인다")
                    .noneMatch(line -> line.startsWith("chat.reattach.gap_measured"));
            assertThat(captor.messages()).anyMatch(line ->
                    line.startsWith("chat.reattach.not_attached")
                            && line.contains("stream=" + A999)
                            && line.contains("result=IGNORED_STALE"));
        }
    }

    /** 1번이 식별자 체계를 바꾸면 모든 방송이 이 길이다. 로그만으로는 안 보여 센다. */
    @Test
    @Timeout(30)
    void 스트리머를_숫자로_못_읽으면_건너뛰고_센다() {
        returns(live(A001, "not-a-number", 시작));

        Reattacher reattacher = newReattacher(lanes);
        reattacher.sweep();

        assertThat(lanes.awaitIdle(IDLE_BUDGET)).isTrue();
        assertThat(sessions.started()).isEmpty();
        assertThat(reattacher.unreadableStreamerIds()).isEqualTo(1L);
    }

    /**
     * 🔴 <b>줄 하나의 계약 위반이 그 회차 전부를 죽이면 안 된다</b>(로컬 리뷰 라운드 2 「같은 부류」).
     *
     * <p><b>재현한 것</b>: 첫 거름망이 {@code Set.copyOf(...).contains(item.streamId())}인데
     * JDK의 불변 Set은 {@code contains(null)}에서 <b>크기와 무관하게</b>
     * {@code NullPointerException}을 던진다(빈 Set도 그렇다 — 실측). 그래서 {@code streamId}가
     * 없는 줄 <b>하나</b>가 그 회차의 <b>다른 모든 방송</b>을 같이 죽이고,
     * 남는 것은 {@code chat.reattach.failed causeType=NullPointerException} 한 줄이라
     * 어느 줄이 이상했는지도 안 보인다.
     *
     * <p><b>왜 예외가 아니라 건너뛰기인가</b> — 이 서버에 이미 그 갈래가 있다.
     * 봉투가 깨진 것({@code broadcasts} 칸 자체가 없음)은 회차가 성립하지 않으므로
     * {@link LiveBroadcastClient}가 던지고, <b>줄 하나가 깨진 것</b>은
     * {@code chat.reattach.streamer_id_unreadable}와 같이 <b>세고 넘어간다.</b>
     * {@link LiveBroadcasts} javadoc이 그 방향을 못박아 뒀다 — 「clip이 그런 줄을 빼면 그 방송은
     * 영영 안 걷히고 우리는 그런 줄이 있었다는 것조차 모른다」.
     *
     * <p><b>카운터를 새로 안 만든다.</b> {@code unreadableStreamerIds}는 판정기·재부착·health·
     * README 넷에 걸쳐 있어 짝을 하나 더 만들면 그 넷이 같이 흔들린다. 여기서 잃는 것은
     * <b>진단</b>이고 로그 한 줄이 그것을 준다 — 「식별자 체계가 통째로 바뀌었나」를 세어야 하는
     * 그 카운터와 달리, {@code stream_id}는 clip 쪽 {@code V201}이 {@code NOT NULL}이라
     * 한 줄이라도 오면 그 자체가 사건이다.
     */
    @Test
    @Timeout(30)
    void streamId가_없는_줄은_건너뛰고_나머지는_붙인다() {
        returns(live(null, "7", 시작), live(B001, "2", 시작));

        try (LogCaptor captor = new LogCaptor()) {
            newReattacher(lanes).sweep();

            assertThat(lanes.awaitIdle(IDLE_BUDGET)).isTrue();
            assertThat(sessions.started()).containsExactly(B001);
            assertThat(registry.currentStreamIdOf(2L)).isEqualTo(B001);
            assertThat(captor.levelOf("chat.reattach.stream_id_missing")).isEqualTo(Level.WARN);
            // 아래 짝 검사와 갈리는 자리 — 칸이 빈 것을 원소가 없는 것으로 세면 안 된다.
            assertThat(captor.messages())
                    .noneMatch(line -> line.startsWith("chat.reattach.null_row"));
            // 양성 대조 — 회차가 통째로 죽었다면 위 단언들은 「안 붙었다」로도 만족될 수 있다.
            assertThat(captor.messages())
                    .as("줄 하나가 이상하다고 그 회차가 통째로 죽으면 안 된다")
                    .noneMatch(line -> line.startsWith("chat.reattach.failed"));
        }
    }

    /**
     * 🔴 <b>위 검사의 짝이다 — 한 칸 바깥이 안 막혀 있었다</b>(로컬 리뷰 라운드 3).
     * 위는 {@code {"broadcasts":[{"streamId":null,…}]}}를 막고, 여기는
     * {@code {"broadcasts":[null]}}을 막는다.
     *
     * <p><b>재현한 것</b>(가짜 clip에 본문을 직접 줘서): Jackson 3는 배열 원소의 {@code null}을
     * <b>리스트에 그대로 넣는다</b> — {@code [null, {…}]}에서 {@code size=2}이고 첫 원소가
     * {@code null}이다. 그러면 위 거름망의 {@code item.streamId()}가 <b>거름망 자신</b>에서
     * NPE를 던져, 라운드 2가 넣은 방어가 도는 자리까지 못 간다.
     *
     * <p><b>왜 로그를 갈랐나</b> — 라운드 2가 고치려던 것이 정확히 「{@code causeType=NPE}만
     * 남아서 clip 잘못인지 우리 버그인지 안 갈린다」였다. 둘을 한 카운터로 묶으면
     * {@code stream_id_missing}을 본 사람이 <b>없는 칸을 찾으러</b> 간다 — clip의 명부에는
     * {@code streamId}가 {@code NOT NULL}이라 그런 줄이 없고, 실제로 깨진 것은 <b>배열</b>이다.
     * 원인이 다르면 이름도 달라야 그 로그가 진단이 된다.
     */
    @Test
    @Timeout(30)
    void 줄_자체가_null이면_건너뛰고_나머지는_붙인다() {
        returnsIncludingNullRow(null, live(B001, "2", 시작));

        try (LogCaptor captor = new LogCaptor()) {
            newReattacher(lanes).sweep();

            assertThat(lanes.awaitIdle(IDLE_BUDGET)).isTrue();
            assertThat(sessions.started()).containsExactly(B001);
            assertThat(registry.currentStreamIdOf(2L)).isEqualTo(B001);
            assertThat(captor.levelOf("chat.reattach.null_row")).isEqualTo(Level.WARN);
            assertThat(captor.messages()).anyMatch(line -> line.equals("chat.reattach.null_row count=1"));
            // 🔴 이 수정의 목적을 재는 단언 — 거르기만 재고 로그를 안 재면 「왜 갈랐나」가 0개다.
            assertThat(captor.messages())
                    .as("칸이 빈 것과 원소가 없는 것은 원인이 달라 이름도 달라야 한다")
                    .noneMatch(line -> line.startsWith("chat.reattach.stream_id_missing"));
            // 양성 대조 — 회차가 통째로 죽었다면 위 단언들은 「안 붙었다」로도 만족될 수 있다.
            assertThat(captor.messages())
                    .as("줄 하나가 이상하다고 그 회차가 통째로 죽으면 안 된다")
                    .noneMatch(line -> line.startsWith("chat.reattach.failed"));
        }
    }

    // ── 실패와 백프레셔 ──────────────────────────────────────────────────

    /** 던지면 {@code @Scheduled}가 이 뒤로 안 돈다 — 재부착이 영영 멈추는데 아무 신호도 없다. */
    @Test
    @Timeout(30)
    void clip이_죽어도_예외가_안_나가고_경고만_남는다() {
        given(client.list()).willThrow(new RestClientException("clip 연결 거부"));

        try (LogCaptor captor = new LogCaptor()) {
            newReattacher(lanes).sweep();

            assertThat(captor.levelOf("chat.reattach.failed")).isEqualTo(Level.WARN);
            // 문항 6 — clip이 죽어서인가, 다른 이유로 던져서인가. causeType까지 본다.
            assertThat(captor.messages()).anyMatch(line ->
                    line.startsWith("chat.reattach.failed")
                            && line.contains("causeType=RestClientException"));
        }
    }

    @Test
    @Timeout(30)
    void 붙은_뒤_공백을_로그로_남긴다() {
        returns(live(A001, "7", 시작));

        try (LogCaptor captor = new LogCaptor()) {
            newReattacher(lanes).sweep();

            assertThat(lanes.awaitIdle(IDLE_BUDGET)).isTrue();
            assertThat(captor.messages()).anyMatch(line ->
                    line.startsWith("chat.reattach.gap_measured")
                            && line.contains("stream=" + A001)
                            && line.contains("basis=BROADCAST_START")
                            && line.contains("gapMs=600000"));
        }
    }

    /**
     * 🔴 <b>재는 데 실패해도 붙는다.</b> 공백 측정은 로그 한 줄을 위한 <b>관측</b>이지
     * 「붙어도 되는가」의 <b>판정</b>이 아니다. 바로 위 {@code store.find}와 성격이 갈리는
     * 자리다 — 그쪽은 실패하면 안 붙는 것이 안전한 방향이지만, 이쪽이 붙이기를 막으면
     * <b>부수 기능의 실패가 이 카드의 목적을 통째로 무력화한다</b>(로컬 리뷰 라운드 1).
     *
     * <p><b>DB가 아픈 동안에는 채팅 저장도 안 된다.</b> 그래도 세션은 서 있어야 DB가 회복될 때
     * 곧바로 저장이 이어진다 — 안 붙어 있으면 회복돼도 걷을 것이 없고, 채팅에는 백필이 없다.
     */
    @Test
    @Timeout(30)
    void 공백_측정이_던져도_붙는다() {
        returns(live(A001, "7", 시작));
        // 반개방 DB에서 socketTimeout이 끊을 때 나오는 것과 같은 자리의 예외다.
        GapMeasurer 던지는_측정기 = mock(GapMeasurer.class);
        given(던지는_측정기.measure(any(), any(), any()))
                .willThrow(new DataAccessResourceFailureException("반개방"));

        try (LogCaptor captor = new LogCaptor()) {
            new Reattacher(client, registry, store, 던지는_측정기, lanes, sessions, () -> 지금).sweep();

            assertThat(lanes.awaitIdle(IDLE_BUDGET)).isTrue();
            // ① 세션이 실제로 섰다. 「문을 두드렸다」로 갈음하지 않는다 — 붙이기가 아예
            //    안 불리는 것이 이 결함의 증상이었다.
            assertThat(registry.currentStreamIdOf(7L)).as("측정 실패가 붙이기를 막으면 안 된다")
                    .isEqualTo(A001);
            assertThat(sessions.started()).containsExactly(A001);
            // ② 붙이기가 실패한 것으로 기록되지 않는다. 이 줄이 남으면 「어느 방송이 안
            //    붙었나」를 세는 유일한 진단이 거짓말을 한다.
            assertThat(captor.messages())
                    .noneMatch(line -> line.startsWith("chat.reattach.attach_failed"));
            // ③ 「못 쟀다」가 로그에 남고 원인 타입까지 온다. 예외 객체는 안 넘긴다 —
            //    SLF4J가 throwable로 렌더해 접속 문자열이 통째로 실린다.
            assertThat(captor.levelOf("chat.reattach.gap_measure_failed")).isEqualTo(Level.WARN);
            assertThat(captor.messages()).anyMatch(line ->
                    line.startsWith("chat.reattach.gap_measure_failed")
                            && line.contains("stream=" + A001)
                            && line.contains("causeType=DataAccessResourceFailureException"));
            // ④ 「못 쟀다」와 「공백이 없다」가 갈린다. UNKNOWN으로 뭉치면 「clip이 시작
            //    시각을 안 줬다」와 섞여 뒤에 로그를 세는 쪽이 두 원인을 못 가른다.
            assertThat(captor.messages()).anyMatch(line ->
                    line.startsWith("chat.reattach.gap_measured")
                            && line.contains("stream=" + A001)
                            && line.contains("basis=MEASURE_FAILED")
                            && line.contains("gapMs=-1"));
        }
    }

    /**
     * 🔴 <b>{@code catch}의 폭이 {@code Throwable}인 것을 실제로 잰다.</b> 위 검사만으로는
     * 안 재어진다 — {@code RuntimeException}으로 좁혀도 <b>그대로 초록이었다</b>(주입 E).
     * DB 예외가 unchecked라서다. 그러면 {@code LinkageError}류 한 번에 붙이기가 다시
     * 막히는데 아무 그물도 안 운다.
     *
     * <p>이 서버가 같은 자리에 이미 데여 있다 — 아카이버의 틱은 {@code Throwable}이고
     * 적재의 틱은 {@code RuntimeException}이라 「쌍둥이 미대조」로 {@code CLAUDE.md}에
     * 남아 있다. 폭이 갈리면 <b>한쪽만 조용히 멈춘다.</b>
     */
    @Test
    @Timeout(30)
    void 공백_측정이_Error를_던져도_붙는다() {
        returns(live(A001, "7", 시작));
        GapMeasurer 터지는_측정기 = mock(GapMeasurer.class);
        given(터지는_측정기.measure(any(), any(), any()))
                .willThrow(new NoSuchMethodError("드라이버가 갈렸다"));

        new Reattacher(client, registry, store, 터지는_측정기, lanes, sessions, () -> 지금).sweep();

        assertThat(lanes.awaitIdle(IDLE_BUDGET)).isTrue();
        assertThat(registry.currentStreamIdOf(7L)).as("관측의 실패가 붙이기를 막으면 안 된다")
                .isEqualTo(A001);
    }

    /** 버리면 그 방송은 다음 회차까지 안 걷힌다 — 미뤘다는 사실이 로그에 남아야 한다. */
    @Test
    @Timeout(30)
    void 줄이_가득_차면_남은_방송을_다음_회차로_미룬다() {
        try (StreamerSerialExecutor full = new StreamerSerialExecutor(1);
             LogCaptor captor = new LogCaptor()) {
            returns(live(A001, "1", 시작), live(B001, "2", 시작));

            newReattacher(full).sweep();

            assertThat(full.awaitIdle(IDLE_BUDGET)).isTrue();
            assertThat(captor.levelOf("chat.reattach.deferred")).isEqualTo(Level.WARN);
            assertThat(captor.messages()).anyMatch(line ->
                    line.startsWith("chat.reattach.deferred") && line.contains("count=1"));
        }
    }

    // ── 🔴 이 카드가 반드시 닫아야 할 것 ─────────────────────────────────

    /**
     * 🔴 <b>재부착과 알림이 같은 스트리머면 같은 줄이다.</b> 이 카드 설계의 기둥이고,
     * 감사 라운드 2의 G3가 <b>알림 경로 쪽에서</b> 이 그물이 0개임을 찾은 자리다.
     *
     * <p><b>왜 이것이 안전의 근거인가</b>: {@code SessionRegistry.retargetOrSkip}은 원자적이지
     * 않고(자리를 읽고 · 이름을 바꾸고 · 자리를 다시 확인한다), 그 {@code SEAT_STOPPING} 주석이
     * <b>「지금 도달 경로가 없는 이유는 상태가 아니라 스레드 수다 … 수립을 워커로 빼는 날 이
     * 자리를 다시 본다」</b>고 못박아 뒀다(계획 검증 I4). 태스크 3이 수립을 줄로 뺐고 태스크 7이
     * <b>둘째 제출자</b>를 붙인다 — 그래서 이제 「스레드가 하나」 대신 <b>「같은 스트리머는 같은
     * 줄이고 줄 안은 직렬」</b>이 그 자리를 지킨다. 줄 이름이 갈리는 순간 그 보증이 사라진다.
     *
     * <p><b>{@code "007"} 대 {@code "7"}로 잰다.</b> 두 발행자가 다른 시스템이라
     * (1번의 SQS 봉투 vs clip 명부의 칸) 값의 표기가 갈릴 여지가 바로 그 자리다.
     * {@code LaneKey}를 안 거치면 {@code trim()}만으로는 8종 중 4종이 다른 줄이 된다.
     */
    @Test
    @Timeout(30)
    void 재부착은_알림_경로와_같은_줄에_들어간다() throws Exception {
        CountDownLatch 잡았다 = new CountDownLatch(1);
        CountDownLatch 놓는다 = new CountDownLatch(1);
        // 알림 경로가 잡은 줄. 봉투의 streamerId는 "7"로 왔다.
        lanes.submit(LaneKey.of("7"), () -> {
            잡았다.countDown();
            await(놓는다);
        });
        assertThat(잡았다.await(5, TimeUnit.SECONDS)).isTrue();
        // 🔴 <b>clip 명부는 같은 사람을 "007"로 준다 — 방향이 이쪽이어야 한다.</b>
        // 반대로 두면(알림 "007" · clip "7") 재부착이 원문을 써도 줄 이름이 "7"로 같아
        // <b>정규화를 지워도 초록이다</b> — 실제로 그렇게 썼다가 주입 X1이 초록으로 나왔다.
        returns(live(A001, "007", 시작));
        // 붙이기가 <b>시작됐는지</b>를 첫 DB 호출로 잡는다. sessions.started()로 재면
        // 그것도 주입 아래에서 초록이었다 — 다른 줄로 갔어도 거기까지 가는 데 DB 왕복
        // 둘이 걸려서, 「아직 안 왔다」와 「못 온다」가 <b>경합으로만</b> 갈렸다.
        GatedStore 게이트 = new GatedStore(jdbc);

        new Reattacher(client, registry, 게이트, measurer, lanes, sessions, () -> 지금).sweep();

        assertThat(lanes.inFlight()).as("재부착이 실제로 제출됐다").isEqualTo(2);
        assertThat(게이트.들어왔다(Duration.ofSeconds(1)))
                .as("같은 줄이면 앞 작업이 끝나기 전에는 시작조차 못 한다")
                .isFalse();
        // 🔴 <b>결정적인 증거는 이 줄이다.</b> submit은 동기라 이 시점의 줄 수는 경합이 없고,
        // 이름이 갈리면 <b>반드시</b> 2다(주입된 작업은 게이트에 붙들려 줄이 안 사라진다).
        assertThat(lanes.laneCount()).as("같은 스트리머면 줄이 하나다").isEqualTo(1);

        놓는다.countDown();
        assertThat(게이트.들어왔다(Duration.ofSeconds(5)))
                .as("풀어 주면 돈다 — 「영영 안 돈다」와 가른다").isTrue();
        게이트.놓는다();
        assertThat(lanes.awaitIdle(IDLE_BUDGET)).isTrue();
        assertThat(sessions.started()).containsExactly(A001);
    }

    /**
     * 🔴 <b>재부착이 실패해도 {@code dropPending}을 부르지 않는다.</b>
     *
     * <p>그 줄의 대기열에는 <b>알림 경로의 배치</b>가 들어 있을 수 있다. 버리면 그 알림들은
     * 지워지지 않은 채 가시성 시한이 지나야 다시 온다 — 재부착 하나가 실패했다는 사실은
     * 그 알림들이 실패한다는 근거가 <b>아니므로</b> 얻는 것 없이 늦추기만 한다.
     * (반대 방향은 해롭지 않다: 알림 배치가 실패해 대기 중인 재부착 작업이 버려져도
     * 재부착은 상태가 없고 주기적이라 <b>다음 회차가 같은 목록을 다시 받는다.</b>)
     */
    @Test
    @Timeout(30)
    void 붙이기가_던져도_같은_줄의_다음_작업을_안_버린다() throws Exception {
        CountDownLatch 잡았다 = new CountDownLatch(1);
        CountDownLatch 놓는다 = new CountDownLatch(1);
        lanes.submit(LaneKey.of("7"), () -> {
            잡았다.countDown();
            await(놓는다);
        });
        assertThat(잡았다.await(5, TimeUnit.SECONDS)).isTrue();
        sessions.throwOnStart();
        returns(live(A001, "7", 시작));

        try (LogCaptor captor = new LogCaptor()) {
            newReattacher(lanes).sweep();
            assertThat(lanes.inFlight()).isEqualTo(2);

            AtomicBoolean 알림이_돌았다 = new AtomicBoolean();
            assertThat(lanes.submit(LaneKey.of("7"), () -> 알림이_돌았다.set(true))).isTrue();
            놓는다.countDown();

            assertThat(lanes.awaitIdle(IDLE_BUDGET)).isTrue();
            assertThat(알림이_돌았다).as("재부착 실패가 알림 배치를 버리면 안 된다").isTrue();
            assertThat(captor.messages()).anyMatch(line ->
                    line.startsWith("chat.reattach.attach_failed")
                            && line.contains("stream=" + A001)
                            && line.contains("causeType=IllegalStateException"));
        }
    }

    // ── 조립 ─────────────────────────────────────────────────────────────

    private Reattacher newReattacher(StreamerSerialExecutor executor) {
        return new Reattacher(client, registry, store, measurer, executor, sessions, () -> 지금);
    }

    private void returns(LiveBroadcasts.Item... items) {
        given(client.list()).willReturn(new LiveBroadcasts(List.of(items), false));
    }

    /**
     * {@code returns}를 못 쓴다 — {@code List.of}가 {@code null} 원소를 거부한다.
     * 실물 경로는 Jackson이 만드는 리스트이고 <b>그쪽은 {@code null}을 담는다</b>(재현함).
     */
    private void returnsIncludingNullRow(LiveBroadcasts.Item... items) {
        given(client.list()).willReturn(new LiveBroadcasts(Arrays.asList(items), false));
    }

    private static LiveBroadcasts.Item live(String streamId, String streamerId, Instant startedAt) {
        return new LiveBroadcasts.Item(streamId, streamerId, startedAt);
    }

    /** 실물 등록부에 세션을 하나 세운다 — 가짜 치지직이 받는다. */
    private void 열어둔다(String streamId, long streamerId, Instant startedAt) {
        assertThat(registry.open(new SessionKey(streamId, streamerId, "channel-" + streamerId, startedAt),
                "token-" + streamerId)).isTrue();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("게이트가 안 풀렸다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private ChzzkLinkClient newLinkClient() {
        // ASCII만 쓴다 — 이 값은 HTTP 헤더로 나가고 JDK HttpClient가 한글 헤더 값을 거절한다.
        return new ChzzkLinkClient(restClientBuilder,
                new LinkProperties(auth.baseUrl(), "internal-token"));
    }

    private SessionRegistry newRegistry() {
        return new SessionRegistry(
                new ChzzkProperties(true, "설정-토큰-쓰면-안-된다", "http://localhost:" + port,
                        Duration.ofSeconds(5), Duration.ofMillis(200), Duration.ofSeconds(60)),
                restClientBuilder, new ChatBuffer(1_000),
                TestPersistence.disabledPersister(), ChatArchive.NONE);
    }

    /**
     * 붙이기가 <b>줄 안에서 실제로 시작된 순간</b>을 잡는다 — {@code attachOne}이 가장 먼저
     * 하는 일이 메모 재확인이라 여기가 입구다.
     *
     * <p><b>왜 실물을 상속하나</b>: 이 검사가 재는 것은 「줄이 같은가」이지 「메모 조회가
     * 맞는가」가 아니다. 메모 조회를 가짜로 바꾸면 그 뒤 갈래가 통째로 달라져 무엇을 쟀는지
     * 흐려진다 — 신호만 얹고 나머지는 실물 그대로 흘린다.
     */
    private static final class GatedStore extends EndedStreamStore {

        private final CountDownLatch 입구 = new CountDownLatch(1);
        private final CountDownLatch 출구 = new CountDownLatch(1);

        private GatedStore(JdbcTemplate jdbc) {
            super(jdbc);
        }

        @Override
        public Optional<EndedStream> find(String streamId) {
            입구.countDown();
            await(출구);
            return super.find(streamId);
        }

        boolean 들어왔다(Duration budget) throws InterruptedException {
            return 입구.await(budget.toMillis(), TimeUnit.MILLISECONDS);
        }

        void 놓는다() {
            출구.countDown();
        }
    }

    /**
     * 실물 {@link LinkedSessionStarter}를 감싸 <b>문을 두드린 사실</b>만 기록한다.
     *
     * <p>가짜로 대체하지 않는 이유: 갈아끼움 판정·이미 열림 판정은 등록부의 실제 동작이고,
     * 이 카드가 물어야 할 것이 정확히 그것이다. 기록만 따로 두는 이유: 「두드렸다」와
     * 「붙었다」가 갈려야 문항 6의 <b>「재부착이 아예 안 돌았다」</b>를 배제할 수 있다.
     */
    private static final class RecordingSessions implements BroadcastSessions {

        private final BroadcastSessions delegate;
        private final List<String> started = new CopyOnWriteArrayList<>();
        private volatile boolean 던진다;

        private RecordingSessions(BroadcastSessions delegate) {
            this.delegate = delegate;
        }

        @Override
        public ProcessResult start(String streamId, StreamerId streamer, Instant startedAt) {
            started.add(streamId);
            if (던진다) {
                throw new IllegalStateException("붙이기가 터졌다");
            }
            return delegate.start(streamId, streamer, startedAt);
        }

        @Override
        public boolean stop(String streamId) {
            return delegate.stop(streamId);
        }

        void throwOnStart() {
            던진다 = true;
        }

        List<String> started() {
            return List.copyOf(started);
        }
    }

    /**
     * auth의 {@code POST /internal/chzzk-link/resolve}를 흉내 낸다.
     *
     * <p>🔴 <b>루프백에 바인딩한다.</b> 와일드카드에 걸면 남의 프로세스가 포트를 가로채
     * CI를 8회에 1번 깬다(POK-174 실측).
     */
    private static final class FakeAuth {

        private static final Pattern USER_ID = Pattern.compile("\"userId\"\\s*:\\s*(\\d+)");

        private final HttpServer server;
        private final Map<Long, String> bodies = new ConcurrentHashMap<>();

        private FakeAuth(HttpServer server) {
            this.server = server;
        }

        static FakeAuth start() {
            try {
                HttpServer server = HttpServer.create(
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
                server.setExecutor(Executors.newCachedThreadPool(runnable -> {
                    Thread thread = new Thread(runnable, "fake-auth-reattach");
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

        String baseUrl() {
            // 주소가 아니라 이름으로 잇는다 — 루프백이 IPv6면 getHostString()이
            // 대괄호 없는 0:0:0:0:0:0:0:1을 줘서 URI가 깨진다(이 기계에서 실측).
            return "http://localhost:" + server.getAddress().getPort();
        }

        void stop() {
            server.stop(0);
        }

        private void handle(HttpExchange exchange) throws IOException {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Matcher matcher = USER_ID.matcher(request);
            long userId = matcher.find() ? Long.parseLong(matcher.group(1)) : -1L;
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
