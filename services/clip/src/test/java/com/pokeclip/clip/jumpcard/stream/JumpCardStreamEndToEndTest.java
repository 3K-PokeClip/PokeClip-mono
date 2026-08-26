package com.pokeclip.clip.jumpcard.stream;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.jumpcard.JumpCardService;
import com.pokeclip.clip.jumpcard.JumpCardSnapshot;
import com.pokeclip.clip.jumpcard.api.HighlightRequest;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.SseReader;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.clip.support.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실제 HTTP로 SSE를 연다. MockMvc로는 비동기 응답을 끝까지 흘려보내지 않아 이 갈래들이 안 보인다.
 *
 * <p>🔴 <b>시험마다 사용자 번호를 다르게 쓴다.</b> 같은 번호를 재사용하면 앞 시험이 닫은 연결의
 * 자리가 즉시 반납되지 않아(서버는 다음 쓰기가 실패해야 안다) 뒤 시험이 503을 맞는다.
 *
 * <p>🔴 <b>자격 판정을 여기서 재지 않는다</b> — {@code StreamAccessTest}가 맡는다. 이 클래스는
 * 전부 「자격이 있는 사람」으로 두고 통로 자체의 동작을 잰다. 답을 안 걸면 가짜 auth가 503을
 * 주므로, 「덮어쓰기를 빠뜨렸다」가 조용히 통과하지 않는다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JumpCardStreamEndToEndTest extends IntegrationTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String RESOLVE = "/internal/editor-delegations/resolve";

    private final int port;
    private final JumpCardService service;
    private final BroadcastRepository broadcasts;
    private final CardStreamRegistry registry;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    JumpCardStreamEndToEndTest(@LocalServerPort int port, JumpCardService service,
                               BroadcastRepository broadcasts, CardStreamRegistry registry, JdbcTemplate jdbc,
                               TransactionTemplate transactions) {
        this.port = port;
        this.service = service;
        this.broadcasts = broadcasts;
        this.registry = registry;
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    /** 카드를 남기면 다른 클래스의 broadcasts.deleteAllInBatch()가 FK로 죽는다. */
    @BeforeEach
    void 정리() {
        jdbc.update("DELETE FROM jump_cards");
        broadcasts.deleteAllInBatch();
        broadcasts.save(Broadcast.startedNow("s-1", TestIds.STREAMER, 1L, Instant.now(), null));
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"OWNER\"}");
    }

    /**
     * 🔴 <b>뜻이 뒤집힌 갈래.</b> 전에는 「연결 직후 그 방송 카드 전부가 순번 순으로 온다」였고,
     * 지금은 <b>하나도 안 온다</b>(POK-174). 지우지 않고 뒤집는 이유는 지우면 통로가 지난 카드를
     * 다시 흘려도 이 층에서 아무도 모르기 때문이다. 순번 순·{@code hidden} 표시는 목록 문의
     * 계약이 되어 {@code JumpCardListControllerTest}가 잰다.
     *
     * <p>헤더 단언 둘은 그대로 산다 — 카드가 없어도 주석이 첫 쓰기라 헤더는 바로 나가야 한다.
     */
    @Test
    void 연결_직후에는_카드가_안_오고_헤더는_바로_온다() throws Exception {
        long first = service.record("s-1", auto("evt-1", 1_000_000L)).card().id();
        service.record("s-1", auto("evt-2", 2_000_000L));
        service.hide(first, "1702");

        try (SseReader reader = open("s-1", TestTokens.access("1701"))) {
            assertThat(reader.statusCode()).isEqualTo(200);
            assertThat(reader.headers().firstValue("X-Accel-Buffering"))
                    .as("앞단 프록시가 모아 보내면 「3초 내 도착」이 깨진다").hasValue("no");
            assertThat(reader.headers().firstValue("Content-Type").orElse(""))
                    .startsWith("text/event-stream");

            // PRD 기준과 같은 3초를 기다린다 — 그 안에 오는 것이 이 문의 계약이었다.
            Thread.sleep(3_000);
            assertThat(reader.named())
                    .as("연결 직후 지난 카드가 나갔다 — 화면이 같은 카드를 목록과 통로 양쪽에서 받는다")
                    .isEmpty();
        }
    }

    /**
     * <b>카드가 0장인 방송에 붙어도 헤더가 바로 온다.</b>
     *
     * <p>{@code SseEmitter}는 첫 쓰기가 있어야 응답을 커밋한다. 카드가 없고 방송이 진행 중이면
     * 쓸 것이 없어 헤더가 <b>다음 하트비트까지</b> 늦는다(실기동 실측 5.449초, 최악 20초).
     * 브라우저에는 「느리다」가 아니라 <b>「연결이 안 된다」</b>로 보인다.
     * <b>방송이 막 시작해 카드가 아직 없을 때가 정확히 이 상태다.</b>
     *
     * <p>이 클래스는 하트비트가 운영 기본값(20초)이라 이 갈래가 실제로 드러난다 —
     * 하트비트를 짧게 준 컨텍스트에서는 우연히 통과한다.
     */
    @Test
    void 카드가_0장인_방송에_붙어도_헤더가_바로_온다() {
        broadcasts.save(Broadcast.startedNow("s-empty", TestIds.STREAMER, 3L, Instant.now(), null));
        assertThat(service.snapshotsOf("s-empty")).as("카드가 0장이어야 이 갈래를 잰다").isEmpty();

        long startedAt = System.nanoTime();
        try (SseReader reader = open("s-empty", TestTokens.access("1703"))) {
            // SseReader 생성자가 헤더를 받을 때까지 막힌다 — 여기까지 온 시간이 곧 헤더 지연이다.
            Duration untilHeaders = Duration.ofNanos(System.nanoTime() - startedAt);

            assertThat(reader.statusCode()).isEqualTo(200);
            assertThat(reader.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
            assertThat(untilHeaders)
                    .as("헤더가 하트비트까지 늦으면 브라우저는 연결 실패로 본다")
                    .isLessThan(Duration.ofSeconds(1));
        }
    }

    /**
     * 🔴 <b>뜻이 뒤집힌 갈래.</b> 「재연결하면 전부 다시 온다」였는데 통로가 지난 카드를 안 보내므로
     * <b>재연결해도 안 온다</b>(POK-174 — 따라잡기는 목록 문이 맡는다). {@code Last-Event-ID}를
     * 받아 적기만 하고 쓰지 않는 것은 그대로다.
     *
     * <p>이 갈래를 남기는 이유는 <b>{@code Last-Event-ID}가 오면 다르게 굴게 되는 것</b>을 막기
     * 위해서다 — 그 값으로 따라잡기를 구현하면 여기가 빨간불이 된다.
     */
    @Test
    void 재연결해도_카드는_안_오고_Last_Event_ID는_무시한다() throws Exception {
        service.record("s-1", auto("evt-1", 1_000_000L));
        service.record("s-1", auto("evt-2", 2_000_000L));

        try (SseReader reader = open("s-1", TestTokens.access("1704"),
                Map.of("Last-Event-ID", "999"))) {
            assertThat(reader.await(1, Duration.ofSeconds(3))).as("주석조차 안 왔다").isTrue();
            Thread.sleep(1_000);
            assertThat(reader.named()).isEmpty();
        }
    }

    /**
     * 「연결이 닫히면 자리가 돌아온다」를 여기서 잰다 — 단위 시험에서는 콜백이 안 불려 잴 수 없다.
     *
     * <p>서버는 <b>다음 쓰기가 실패해야</b> 끊긴 것을 안다. 카드 한 장으로는 부족했고 여러 장을
     * 밀어야 반납됐다(plan-critic 실측: 끊고 1초=1, 1장=1, 30장=0). 그래서 카드를 밀어 반납을
     * 강제한 뒤 다시 연다.
     */
    @Test
    void 끊고_다시_열_수_있다() {
        // 전용 방송을 쓴다. connectionCount()는 서버 전체 수라 다른 시험이 열어 둔 연결이
        // 섞이면 "0이 된다"를 못 잰다 — 그 연결들은 다음 쓰기가 있어야 정리되기 때문이다.
        // 그래서 기준선을 재고, 이 시험이 연 자리 하나가 돌아오는 것만 본다.
        broadcasts.save(Broadcast.startedNow("s-reopen", TestIds.STREAMER, 2L, Instant.now(), null));
        JumpCardSnapshot card = service.record("s-reopen", auto("evt-drain", 100_000L)).card();

        int baseline = registry.connectionCount();
        String token = TestTokens.access("1705");
        try (SseReader reader = open("s-reopen", token)) {
            assertThat(reader.statusCode()).isEqualTo(200);
            assertThat(reader.await(1, Duration.ofSeconds(3)))
                    .as("주석이 와야 연결이 명부에 오른 것이다 — 카드는 더 이상 안 온다").isTrue();
        }
        assertThat(registry.connectionCount()).isEqualTo(baseline + 1);

        // 카드를 저장하는 것으로는 안 밀린다 — publishAfterCommit이 아직 비어 있다(태스크 10이 채운다).
        // 여기서 재는 것은 「쓰기가 실패하면 자리가 반납된다」이므로 출구를 직접 부른다.
        //
        // 🔴 <b>순번을 매번 올린다.</b> 같은 스냅샷을 그대로 30번 보내면 두 번째부터
        // 「낡은 발행」으로 걸러져 <b>쓰기가 한 번밖에 안 일어나고 자리가 안 반납된다</b>
        // (CardStreamRegistry.isStale — 순번이 같으면 내용도 같으므로 버린다).
        // 실제로 카드가 서른 번 바뀌면 트리거가 순번을 서른 번 올리므로 이쪽이 운영과 같은 모양이다.
        for (int i = 0; i < 30; i++) {
            registry.publish(순번을_올린다(card, card.eventSeq() + 1 + i));
        }
        awaitUntil(() -> registry.connectionCount() == baseline, Duration.ofSeconds(10));

        try (SseReader again = open("s-reopen", token)) {
            assertThat(again.statusCode()).isEqualTo(200);
        }
    }

    @Test
    void 토큰_없음_401_서명_틀림_401() {
        try (SseReader none = new SseReader(url("s-1"), Map.of())) {
            assertThat(none.statusCode()).isEqualTo(401);
        }
        try (SseReader bad = open("s-1", TestTokens.tampered(TestTokens.access("1706")))) {
            assertThat(bad.statusCode()).isEqualTo(401);
        }
    }

    @Test
    void 없는_방송은_404다() {
        try (SseReader reader = open("s-없음", TestTokens.access("1707"))) {
            assertThat(reader.statusCode()).isEqualTo(404);
        }
    }

    /** {@code ended}는 초기 전송에서 <b>살아남은 것</b>이다 — 없애면 화면이 끝난 방송에 영영 붙어 있는다. */
    @Test
    void 끝난_방송에_붙으면_ended만_오고_닫힌다() {
        broadcasts.save(Broadcast.endedPlaceholder("s-ended", TestIds.STREAMER, 9L, Instant.now()));
        service.record("s-ended", auto("evt-1", 1_000_000L));

        try (SseReader reader = open("s-ended", TestTokens.access("1708"))) {
            assertThat(reader.awaitName("ended", Duration.ofSeconds(3))).isTrue();
            assertThat(reader.named()).extracting(SseReader.Event::name)
                    .as("카드가 섞여 나왔다 — 초기 전송이 되살아났다").containsExactly("ended");
            assertThat(reader.awaitClosed(Duration.ofSeconds(3)))
                    .as("더 올 카드가 없는데 열어 두면 연결만 먹는다").isTrue();
        }
    }

    /**
     * 연결 수명 = min(설정값, 토큰 exp까지). 만료 시점에 닫히고 브라우저가 새 토큰으로 다시 붙는다.
     *
     * <p><b>헤더는 주석이 틔운다.</b> 서버가 아무것도 안 쓰면 응답 헤더가 안 나가고 클라이언트는
     * 헤더를 기다리다 타임아웃을 맞는다 — {@code AsyncRequestTimeoutException}이 <b>본문 없는
     * 503</b>으로 잡혀 200을 볼 수 없다(실측). 하트비트는 20초라 2초짜리 토큰에는 안 닿는다.
     * <b>POK-174 전에는 카드를 하나 저장해 그 카드가 첫 쓰기가 되게 했다</b> — 지금은 카드가
     * 안 나가므로 {@code sendInitial}의 주석이 유일한 첫 쓰기이고, 그것이 이 갈래를 지탱한다.
     */
    @Test
    void 토큰_만료_시각에_연결이_닫힌다() {
        String shortLived = TestTokens.access("1709", Instant.now().plusSeconds(2));

        try (SseReader reader = open("s-1", shortLived)) {
            assertThat(reader.statusCode()).as("본문=%s", reader.body()).isEqualTo(200);
            assertThat(reader.await(1, Duration.ofSeconds(3)))
                    .as("주석조차 안 오면 헤더가 안 나간 것이라 위 200도 못 봤을 것이다").isTrue();
            assertThat(reader.awaitClosed(Duration.ofSeconds(6)))
                    .as("만료 뒤에도 연결이 살면 죽은 토큰으로 계속 받는다").isTrue();
        }
    }

    // ── 커밋 뒤 발행 · 종료 알림 (태스크 10) ──────────────────────────────

    /** PRD 성공 기준이 3초다. {@code await}가 3초 안에 통과한 것이 아니라 <b>실제 시각차</b>를 잰다. */
    @Test
    void 카드를_넣으면_3초_안에_연결된_화면에_card가_온다() {
        try (SseReader reader = open("s-1", TestTokens.access("1710"))) {
            assertThat(reader.statusCode()).isEqualTo(200);
            서두를_틔운다(reader);

            // 🔴 「card가 하나 더 왔다」로 재면 안 된다. 서두를 틔우며 보낸 카드가 아직 오는 중이면
            // 그것이 먼저 도착해 조건을 만족시키고, 그러면 <b>옛 카드의 도착 시각</b>을 재게 된다
            // (전송에 20ms만 넣어도 그 일이 실제로 벌어진다 — 실측 `but was: 500000L`).
            // `awaitName("card", ...)`은 더 나쁘다: 앞 단계의 card로 <b>이미 참</b>이라 한 번도 안
            // 기다리고, 옛 카드를 집으면 시각차가 <b>음수</b>가 되어 3초 단언이 공짜로 통과한다
            // (비동기 2차 감사). 그래서 <b>이번에 보낸 그 카드</b>가 올 때까지 기다리고 그것만 잰다.
            Instant sent = Instant.now();
            post2A("s-1", "evt-live", 5_020_000L);

            awaitUntil(() -> 카드가_왔나(reader, 5_020_000L), Duration.ofSeconds(3));
            SseReader.Event card = 카드_찾기(reader, 5_020_000L);

            assertThat(Duration.between(sent, card.receivedAt()))
                    .as("보낸 시각과 그 카드가 도착한 시각의 차가 성공 기준이다")
                    .isLessThan(Duration.ofSeconds(3));
        }
    }

    /** 점유는 남에게 보여야 의미가 있다 — 안 보이면 둘이 같은 카드를 잡는다. */
    @Test
    void 집으면_다른_연결에도_card가_온다() {
        long id = service.record("s-1", auto("evt-claim", 3_000_000L)).card().id();

        try (SseReader watcher = open("s-1", TestTokens.access("1711"))) {
            // 연결이 명부에 오른 뒤라야 발행이 도착한다. 카드는 더 이상 초기 전송으로 안 오므로
            // 주석으로 확인한다(POK-174).
            assertThat(watcher.await(1, Duration.ofSeconds(3))).isTrue();
            int before = watcher.named().size();

            service.claim(id, "1712");

            assertThat(watcher.awaitName("card", Duration.ofSeconds(3))).isTrue();
            awaitUntil(() -> watcher.named().size() > before, Duration.ofSeconds(3));
            assertThat(MAPPER.readTree(마지막_card(watcher).data()).get("claimedBy").asString())
                    .isEqualTo("1712");
        }
    }

    /**
     * <b>놓기가 나갈 때 {@code claimedBy}가 비어 있어야 한다.</b> 네이티브 UPDATE 뒤 1차 캐시에
     * 낡은 엔티티가 남으면 「놓았는데 아직 잡혀 있는」 카드가 화면에 뜬다 —
     * {@code @Modifying(clearAutomatically = true)}가 그것을 막는다.
     * 태스크 5까지는 {@code publishAfterCommit}이 비어 있어 이 자리가 가려져 있었다.
     */
    @Test
    void 놓으면_비어_있는_카드가_나간다() {
        long id = service.record("s-1", auto("evt-release", 4_000_000L)).card().id();
        service.claim(id, "1714");

        try (SseReader watcher = open("s-1", TestTokens.access("1713"))) {
            assertThat(watcher.await(1, Duration.ofSeconds(3))).isTrue();
            int before = watcher.named().size();

            service.release(id, "1714");

            awaitUntil(() -> watcher.named().size() > before, Duration.ofSeconds(3));
            assertThat(MAPPER.readTree(마지막_card(watcher).data()).get("claimedBy").isNull())
                    .as("놓았는데 잡힌 채로 나가면 아무도 그 카드를 못 집는다").isTrue();
        }
    }

    /**
     * 「안 온다」를 재기 전에 <b>같은 대기로 긍정 경로를 먼저 잰다</b> — 그래야 그 대기 시간이
     * 충분하다는 증거가 생긴다(async-test-reality 문항 4(가)).
     */
    @Test
    void 트랜잭션이_되감기면_발행되지_않는다() {
        try (SseReader reader = open("s-1", TestTokens.access("1715"))) {
            assertThat(reader.statusCode()).isEqualTo(200);
            서두를_틔운다(reader);

            // (가) 긍정 경로 — 커밋된 카드가 3초 안에 온다. 이것이 아래 3초 대기의 근거다.
            service.record("s-1", auto("evt-ok", 1_000_000L));
            awaitUntil(() -> 카드가_왔나(reader, 1_000_000L), Duration.ofSeconds(3));

            // (나) 되감기
            assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                service.record("s-1", auto("evt-rollback", 9_000_000L));
                throw new IllegalStateException("되감기");
            })).isInstanceOf(IllegalStateException.class);

            // 이벤트 개수를 세지 않는다 — 초기 스냅샷·하트비트가 섞여 흔들린다.
            // 「되감긴 그 카드가 왔는가」만 본다(문항 5).
            잠깐(3_000);
            assertThat(카드가_왔나(reader, 9_000_000L)).as("롤백된 카드가 화면에 나갔다").isFalse();
        }
        // 개수가 아니라 「그 카드가 없다」를 잰다 — 개수는 서두를 틔우며 만든 카드까지 세어
        // 시험 준비를 바꾸면 같이 흔들린다(문항 5).
        assertThat(service.snapshotsOf("s-1")).extracting(c -> c.window().startMs())
                .as("되감긴 카드가 표에 남았다").doesNotContain(9_000_000L)
                .as("같은 트랜잭션 밖의 성공한 카드까지 사라졌다").contains(1_000_000L);
    }

    /**
     * <b>방아쇠가 {@code broadcastEnded} 손 호출임을 이름에 적었다.</b> 전에는 이름이
     * 「종료 편지가 처리되면」인데 바로 다음 줄이 손 호출이라 <b>편지 처리는 단언에 아무 기여를
     * 안 했다</b> — 그대로 두면 다음 사람이 「편지 경로를 재는 시험」으로 읽는다(감사 사소 ⑥).
     * 편지 경로는 {@code EndedNotificationEndToEndTest}가 진짜 큐로 잰다.
     */
    @Test
    void broadcastEnded를_부르면_ended가_오고_닫힌다() {
        try (SseReader reader = open("s-1", TestTokens.access("1716"))) {
            서두를_틔운다(reader);

            registry.broadcastEnded("s-1");

            assertThat(reader.awaitName("ended", Duration.ofSeconds(3))).isTrue();
            assertThat(reader.awaitClosed(Duration.ofSeconds(3))).isTrue();
        }
    }

    /**
     * 응답 헤더를 실제로 내보낸다. 보낼 것이 하나도 없으면 서버가 아무것도 안 써서 헤더가
     * 안 나가고 클라이언트가 헤더를 기다린 채 멈춘다(태스크 9 실측).
     */
    private void 서두를_틔운다(SseReader reader) {
        registry.publish(service.snapshotsOf("s-1").isEmpty()
                ? service.record("s-1", auto("evt-open", 500_000L)).card()
                : service.snapshotsOf("s-1").get(0));
        assertThat(reader.awaitNamed(1, Duration.ofSeconds(3))).isTrue();
    }

    /**
     * <b>이미 만료된 토큰으로는 통로가 안 열린다.</b> 디코더의 clock skew 허용치(기본 60초)
     * 안쪽 토큰은 인증을 통과해 컨트롤러까지 온다 — 그때 남은 수명이 음수이고,
     * 서블릿 규약상 {@code timeout <= 0}은 「시한 없음」이라 <b>만료된 토큰일수록 연결이 더
     * 오래 산다</b>(인가 2차 감사 실측: exp 59초 전 → timeout -59311ms → 45초 뒤에도 살아 있음).
     *
     * <p>기존 {@code 토큰_만료_시각에_연결이_닫힌다}는 exp가 <b>미래</b>인 토큰이라 이 창을 안 지난다.
     */
    @Test
    void 이미_만료된_토큰으로는_통로가_안_열리고_연결도_안_남는다() {
        service.record("s-1", auto("evt-expired", 2_000_000L));
        int before = registry.connectionCount();

        // skew 허용치(60초) 안쪽이라 인증은 통과한다 — 그래서 컨트롤러의 가드가 유일한 방어선이다.
        String expired = TestTokens.access("1717", Instant.now().minusSeconds(30));

        try (SseReader reader = open("s-1", expired)) {
            assertThat(reader.statusCode()).as("본문=%s", reader.body()).isEqualTo(401);
        }
        assertThat(registry.connectionCount())
                .as("401인데 연결이 남으면 그 연결은 시한이 없어 영영 산다").isEqualTo(before);
    }

    /** 그 창의 카드가 화면에 도착했는가. 개수 대신 이것을 본다. */
    private boolean 카드가_왔나(SseReader reader, long windowStartMs) {
        return reader.events().stream()
                .filter(e -> "card".equals(e.name()) && e.data() != null && !e.data().isEmpty())
                .anyMatch(e -> MAPPER.readTree(e.data()).get("window").get("startMs").asLong() == windowStartMs);
    }

    /** 그 창의 카드를 집는다. 「마지막 card」가 아니라 <b>이번에 보낸 그 카드</b>여야 시각이 뜻을 갖는다. */
    private SseReader.Event 카드_찾기(SseReader reader, long windowStartMs) {
        return reader.events().stream()
                .filter(e -> "card".equals(e.name()) && e.data() != null && !e.data().isEmpty())
                .filter(e -> MAPPER.readTree(e.data()).get("window").get("startMs").asLong() == windowStartMs)
                .findFirst().orElseThrow();
    }

    private SseReader.Event 마지막_card(SseReader reader) {
        return reader.events().stream().filter(e -> "card".equals(e.name()))
                .reduce((a, b) -> b).orElseThrow();
    }

    private void post2A(String streamId, String eventId, long start) {
        String body = """
                {"eventId":"%s","source":"auto","streamTimestampMs":%d,
                 "window":{"startMs":%d,"endMs":%d},"score":97,"evidence":{"multiplier":4.2}}
                """.formatted(eventId, start + 23_000L, start, start + 42_000L);
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port
                                    + "/internal/broadcasts/" + streamId + "/highlights"))
                            .header("X-Internal-Token", "test-only-internal-token-32bytes-long!!")
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(201);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void 잠깐(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 같은 카드의 <b>다음</b> 상태. 순번만 바꾼다 — record라 복사 생성자가 없다. */
    private JumpCardSnapshot 순번을_올린다(JumpCardSnapshot card, long eventSeq) {
        return new JumpCardSnapshot(card.id(), card.streamId(), card.source(), card.streamTimestampMs(),
                card.window(), card.score(), card.evidence(), card.claimedBy(), card.claimedAt(),
                card.claimExpiresAt(), card.hidden(), card.hiddenBy(), eventSeq, card.createdAt());
    }

    private HighlightRequest auto(String eventId, long start) {
        return new HighlightRequest(eventId, "auto", start + 23_000L,
                new HighlightRequest.Window(start, start + 42_000L), 97,
                MAPPER.readTree("{\"multiplier\":4.2}"));
    }

    private String url(String streamId) {
        return "http://localhost:" + port + "/api/clip/broadcasts/" + streamId + "/events";
    }

    private SseReader open(String streamId, String token) {
        return open(streamId, token, Map.of());
    }

    private SseReader open(String streamId, String token, Map<String, String> extraHeaders) {
        java.util.Map<String, String> headers = new java.util.LinkedHashMap<>(extraHeaders);
        headers.put("Authorization", "Bearer " + token);
        return new SseReader(url(streamId), headers);
    }

    private void awaitUntil(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError(timeout + " 안에 조건이 참이 되지 않았다");
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
