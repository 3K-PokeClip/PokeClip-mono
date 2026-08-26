package com.pokeclip.clip.broadcast;

import com.pokeclip.clip.jumpcard.JumpCardService;
import com.pokeclip.clip.jumpcard.api.HighlightRequest;
import com.pokeclip.clip.jumpcard.stream.JumpCardStreamController;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.TestIds;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.jwt.Jwt;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 <b>지켜야 할 불변식 하나만 잰다 — 「auth 왕복 동안 DB 커넥션을 쥐지 않는다」.</b>
 *
 * <p><b>사람 문 <u>넷</u>을 잰다</b>(방송 목록 · 카드 목록 · <b>통로</b> · <b>카드 집기</b>). 이름이 방송 쪽으로 남아
 * 있는 것은 표집 장치가 여기 있어서다 — 쪼개면 이 클래스의 대부분인 도우미가 복사된다.
 * 각 문에서 이 그물을 찾아오는 길은 그쪽 주석에 적어 두었다
 * ({@code JumpCardService.listOf} · {@code JumpCardStreamController.open}).
 *
 * <p><b>애너테이션의 유무를 재지 않는 것이 이 클래스의 요점이다.</b> 「{@code @Transactional}이
 * 붙었나」로 그물을 만들면 그것은 <b>우리가 믿는 것의 모양</b>이지 코드가 하는 일이 아니다.
 * 실제로 이 카드에서 구현자와 감사자가 <b>같은 논증으로 같이 틀렸고</b>(「지연 획득이라 auth
 * 왕복 중엔 안 쥔다」), 갈라 준 것은 합의가 아니라 이 모양의 측정이었다.
 * 불변식 그물은 <b>애너테이션이 붙어도 · 순서가 뒤집혀도 · 획득 모드가 바뀌어도</b> 빨간불이 된다.
 *
 * <p><b>왜 지켜야 하나</b> — auth 왕복은 최대 7초다({@code connect 2s + read 5s}). 그동안
 * 커넥션을 쥐면 사람이 기다리는 요청 하나가 풀에서 자리를 7초 뺏는다. 이 저장소는 같은 종류로
 * 두 번 데였다(POK-93의 {@code afterCommit} 풀 데드락 · {@code JumpCardStreamController}가
 * 자물쇠를 쥔 채 커넥션을 기다린 것).
 *
 * <p>🔴 <b>대조 둘이 없으면 이 시험의 {@code 0}은 아무 뜻이 없다.</b>
 * <ul>
 *   <li><b>양성 대조</b> — 손으로 쥐면 표집기가 그것을 본다. 없으면 표집기가 고장 나도 초록이다
 *   <li><b>요청이 실제로 갔나</b>({@code AUTH.callCount()}) — 요청 스레드가 곧바로 죽어도
 *       {@code active=0}이다. <b>「안 쥐었다」와 「아무 일도 안 했다」가 같은 값</b>이라
 *       이것이 빠지면 <b>위험한 쪽으로</b> 틀린다
 * </ul>
 *
 * <p>🔴 <b>주입 프로브를 그물로 승격하면 성격이 뒤집힌다.</b> 프로브의 목적은 <b>빨간불을
 * 보는 것</b>이라 그 조건에서 빨갛기만 하면 된다. 그물의 목적은 <b>초록을 지키는 것</b>이라
 * <b>그 초록이 「아무 일도 안 일어남」이 아님을 증명해야 한다.</b> 위 대조 둘이 그 증명이고,
 * 프로브를 그대로 옮기면 그 요구가 따라오지 않는다.
 *
 * <p>대조가 실제로 무는지도 확인했다 — auth를 부르기 <b>전에</b> 죽는 결함을 넣자
 * {@code 최대=0}이 되어 마지막 단언은 통과하고 <b>{@code callCount} 대조가 잡았다.</b>
 */
class BroadcastListTransactionTest extends IntegrationTestSupport {

    private static final String ACCESSIBLE = "/internal/editor-delegations/accessible";

    /** 스트리머 번호와 달라야 자격 판정이 본인 통과로 새지 않는다. */
    private static final String 요청자 = "4174";

    /** auth가 답을 붙들고 있는 시간. 표집 창보다 넉넉해야 창이 왕복 <b>안</b>에 들어간다. */
    private static final Duration 붙드는_시간 = Duration.ofMillis(700);

    /** 붙드는 시간 안에서만 잰다. */
    private static final Duration 표집_창 = Duration.ofMillis(400);

    /**
     * 표집 간격. 순수 바쁜 루프가 아닌 이유는 이 기계에서 세션 셋이 CPU를 나눠 쓰기 때문이다.
     * 재려는 것은 <b>왕복 내내 쥐는 것</b>(700ms)이라 2ms 간격이면 200번 넘게 본다.
     */
    private static final Duration 표집_간격 = Duration.ofMillis(2);

    private static final String RESOLVE = "/internal/editor-delegations/resolve";

    private final BroadcastListService service;
    private final JumpCardService cardService;
    private final JumpCardStreamController stream;
    private final JdbcTemplate jdbc;

    BroadcastListTransactionTest(BroadcastListService service, JumpCardService cardService,
                                 JumpCardStreamController stream, JdbcTemplate jdbc) {
        this.service = service;
        this.cardService = cardService;
        this.stream = stream;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 앞_테스트의_흔적을_지운다() {
        방송과_카드를_비운다(jdbc);
    }

    @Test
    void auth_왕복_동안_커넥션을_안_쥔다() throws Exception {
        HikariDataSource 풀 = (HikariDataSource) jdbc.getDataSource();

        int 손으로_쥐었을_때 = 손으로_하나_쥐고_센다(풀);

        AUTH.respondWith(ACCESSIBLE, 200,
                "{\"streamers\":[{\"streamerUserId\":%s,\"relation\":\"OWNER\"}]}".formatted(TestIds.STREAMER));
        방송을_넣는다("s-tx", TestIds.STREAMER);
        AUTH.holdFor(붙드는_시간);

        AtomicInteger 최대 = new AtomicInteger();
        Thread 요청 = new Thread(() -> service.list(요청자, BroadcastState.LIVE, 20, null), "tx-probe");
        try {
            요청.start();
            // 🔴 auth에 도착한 뒤부터 잰다. 도착 전에 재면 「아직 안 갔다」를 「안 쥔다」로 읽는다.
            auth에_도착할_때까지_기다린다();
            표집한다(풀, 최대);
            요청.join(5_000);
        } finally {
            AUTH.holdFor(Duration.ZERO);
        }

        assertThat(손으로_쥐었을_때)
                .as("표집기가 쥔 커넥션을 못 본다 — 아래 0은 아무것도 안 재고 있다")
                .isPositive();
        assertThat(AUTH.callCount())
                .as("요청이 auth까지 안 갔다 — active=0은 「안 쥐었다」가 아니라 「아무 일도 안 했다」다")
                .isPositive();
        assertThat(요청.isAlive()).as("요청이 안 끝났다 — 표집 결과를 믿을 수 없다").isFalse();
        assertThat(최대.get())
                .as("auth 왕복(최대 7초) 동안 커넥션을 쥐고 있다 — 풀에서 자리를 그만큼 뺏는다")
                .isZero();
    }

    /**
     * 🔴 <b>카드 목록 문도 같은 불변식 위에 있다.</b> 이쪽이 더 새기 쉽다 —
     * {@code JumpCardService}의 다른 메서드는 <b>전부</b> {@code @Transactional}이라(쓰기라서 필요하다)
     * 목록 메서드만 안 붙은 것이 실수처럼 보인다. 그 「정리」가 들어오는 순간 auth 왕복(최대 7초)
     * 동안 커넥션을 쥔다.
     *
     * <p>대조 셋은 위 갈래와 같다 — 표집기가 살아 있나 · 요청이 auth까지 갔나 · 요청이 끝났나.
     */
    @Test
    void 카드_목록도_auth_왕복_동안_커넥션을_안_쥔다() throws Exception {
        HikariDataSource 풀 = (HikariDataSource) jdbc.getDataSource();

        int 손으로_쥐었을_때 = 손으로_하나_쥐고_센다(풀);

        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"OWNER\"}");
        방송을_넣는다("s-tx-card", TestIds.STREAMER);
        AUTH.holdFor(붙드는_시간);

        AtomicInteger 최대 = new AtomicInteger();
        Thread 요청 = new Thread(
                () -> cardService.listOf(요청자, "s-tx-card", false, 20, null), "tx-probe-card");
        try {
            요청.start();
            auth에_도착할_때까지_기다린다();
            표집한다(풀, 최대);
            요청.join(5_000);
        } finally {
            AUTH.holdFor(Duration.ZERO);
        }

        assertThat(손으로_쥐었을_때)
                .as("표집기가 쥔 커넥션을 못 본다 — 아래 0은 아무것도 안 재고 있다")
                .isPositive();
        assertThat(AUTH.callCount())
                .as("요청이 auth까지 안 갔다 — active=0은 「안 쥐었다」가 아니라 「아무 일도 안 했다」다")
                .isPositive();
        assertThat(요청.isAlive()).as("요청이 안 끝났다 — 표집 결과를 믿을 수 없다").isFalse();
        assertThat(최대.get())
                .as("auth 왕복 동안 커넥션을 쥐고 있다 — 사람이 기다리는 요청이 풀에서 자리를 그만큼 뺏는다")
                .isZero();
    }

    /**
     * 🔴 <b>통로가 이 셋 중 가장 아프다.</b> 여기서 커넥션을 쥐면 그 요청은 곧이어
     * {@code openWithSnapshot}의 <b>자물쇠</b>도 기다린다 — 이 저장소가 POK-93에서 풀 데드락으로,
     * 그리고 이 문에서 자물쇠 안 커넥션 대기 <b>3142ms</b>로 두 번 데인 자리다.
     *
     * <p><b>서블릿 없이 컨트롤러를 직접 부른다.</b> 진짜 HTTP로 열면 이 클래스에 웹 서버가 필요하고
     * 컨텍스트가 갈린다. 재는 것은 「auth 왕복 중 활성 커넥션」이라 서블릿이 필요 없다 —
     * 대신 정리 콜백이 안 불려 <b>연결 자리 하나가 남는다</b>(상한 500, 한 번뿐).
     *
     * <p>대조 셋은 위 갈래와 같다. 여기에 하나 더 — <b>연 결과가 실제로 emitter여야</b> 한다.
     * 요청이 auth 앞에서 던지면 {@code 최대=0}이고 {@code callCount}도 0이 아닐 수 있다.
     */
    @Test
    void 통로도_auth_왕복_동안_커넥션을_안_쥔다() throws Exception {
        HikariDataSource 풀 = (HikariDataSource) jdbc.getDataSource();

        int 손으로_쥐었을_때 = 손으로_하나_쥐고_센다(풀);

        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"OWNER\"}");
        방송을_넣는다("s-tx-stream", TestIds.STREAMER);
        AUTH.holdFor(붙드는_시간);

        AtomicInteger 최대 = new AtomicInteger();
        AtomicInteger 열림 = new AtomicInteger();
        Thread 요청 = new Thread(() -> {
            if (stream.open("s-tx-stream", 토큰(), null, null, new MockHttpServletRequest())
                    .getBody() != null) {
                열림.incrementAndGet();
            }
        }, "tx-probe-stream");
        try {
            요청.start();
            auth에_도착할_때까지_기다린다();
            표집한다(풀, 최대);
            요청.join(5_000);
        } finally {
            AUTH.holdFor(Duration.ZERO);
        }

        assertThat(손으로_쥐었을_때)
                .as("표집기가 쥔 커넥션을 못 본다 — 아래 0은 아무것도 안 재고 있다")
                .isPositive();
        assertThat(AUTH.callCount())
                .as("요청이 auth까지 안 갔다 — active=0은 「안 쥐었다」가 아니라 「아무 일도 안 했다」다")
                .isPositive();
        assertThat(요청.isAlive()).as("요청이 안 끝났다 — 표집 결과를 믿을 수 없다").isFalse();
        assertThat(열림.get()).as("통로가 안 열렸다 — 판정 앞에서 죽었다면 이 시험은 아무것도 안 잰다")
                .isEqualTo(1);
        assertThat(최대.get())
                .as("auth 왕복(최대 7초) 동안 커넥션을 쥐고 있다 — 그 뒤 자물쇠까지 기다리면 남의 화면이 다 멈춘다")
                .isZero();
    }

    /**
     * 🔴 <b>카드를 만지는 문 넷은 읽기 전용도 아니다.</b> 여기서 커넥션을 쥐면 <b>쓰기 트랜잭션</b>이
     * auth 왕복(최대 7초) 동안 열려 있다 — 그 사이 그 행의 락도 함께 잡힌다.
     *
     * <p>넷 중 {@code claim} 하나만 잰다. 넷이 같은 한 줄({@code requireViewableCard})을 지나므로
     * 하나가 쥐면 넷이 쥔다 — 그 한 줄이 갈리는 날은 {@code JumpCardAccessTest}가 넷을 다 재고 있다.
     *
     * <p>대조 넷 — 표집기가 살아 있나 · auth까지 갔나 · 요청이 끝났나 · <b>실제로 집혔나</b>.
     * 마지막이 없으면 판정 앞에서 죽은 결함이 {@code 최대=0}으로 통과한다.
     */
    @Test
    void 카드를_집을_때도_auth_왕복_동안_커넥션을_안_쥔다() throws Exception {
        HikariDataSource 풀 = (HikariDataSource) jdbc.getDataSource();

        int 손으로_쥐었을_때 = 손으로_하나_쥐고_센다(풀);

        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"OWNER\"}");
        방송을_넣는다("s-tx-claim", TestIds.STREAMER);
        long 카드 = cardService.record("s-tx-claim", new HighlightRequest("evt-tx", "auto", 23_000L,
                new HighlightRequest.Window(0L, 42_000L), 97, null)).card().id();
        AUTH.holdFor(붙드는_시간);

        AtomicInteger 최대 = new AtomicInteger();
        AtomicInteger 집혔다 = new AtomicInteger();
        Thread 요청 = new Thread(() -> {
            if (cardService.claim(카드, 요청자).claimedBy() != null) {
                집혔다.incrementAndGet();
            }
        }, "tx-probe-claim");
        try {
            요청.start();
            auth에_도착할_때까지_기다린다();
            표집한다(풀, 최대);
            요청.join(5_000);
        } finally {
            AUTH.holdFor(Duration.ZERO);
        }

        assertThat(손으로_쥐었을_때)
                .as("표집기가 쥔 커넥션을 못 본다 — 아래 0은 아무것도 안 재고 있다")
                .isPositive();
        assertThat(AUTH.callCount())
                .as("요청이 auth까지 안 갔다 — active=0은 「안 쥐었다」가 아니라 「아무 일도 안 했다」다")
                .isPositive();
        assertThat(요청.isAlive()).as("요청이 안 끝났다 — 표집 결과를 믿을 수 없다").isFalse();
        assertThat(집혔다.get()).as("집히지도 않았다 — 판정 앞에서 죽었다면 이 시험은 아무것도 안 잰다")
                .isEqualTo(1);
        assertThat(최대.get())
                .as("쓰기 트랜잭션이 auth 왕복(최대 7초) 내내 열려 있다 — 커넥션과 행 락을 함께 쥔다")
                .isZero();
    }

    // ── 도우미 ──────────────────────────────────────────────────

    /** 통로 자리는 사람 단위다 — 다른 클래스와 겹치지 않는 번호를 쓴다. */
    private static Jwt 토큰() {
        Instant now = Instant.now();
        return Jwt.withTokenValue("tx-probe")
                .header("alg", "HS256")
                .subject("4175")
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(30)))
                .build();
    }

    private static int 손으로_하나_쥐고_센다(HikariDataSource 풀) throws SQLException {
        try (Connection 쥔_것 = 풀.getConnection()) {
            return 풀.getHikariPoolMXBean().getActiveConnections();
        }
    }

    private static void auth에_도착할_때까지_기다린다() throws InterruptedException {
        long 시한 = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (AUTH.callCount() == 0 && System.nanoTime() < 시한) {
            Thread.sleep(1);
        }
    }

    private static void 표집한다(HikariDataSource 풀, AtomicInteger 최대) throws InterruptedException {
        long 끝 = System.nanoTime() + 표집_창.toNanos();
        while (System.nanoTime() < 끝) {
            최대.accumulateAndGet(풀.getHikariPoolMXBean().getActiveConnections(), Math::max);
            Thread.sleep(표집_간격.toMillis());
        }
    }

    private void 방송을_넣는다(String streamId, String streamerId) {
        jdbc.update("""
                        INSERT INTO broadcasts
                            (stream_id, streamer_id, status, started_at, last_sequence)
                        VALUES (?, ?, 'live', ?, 1)""",
                streamId, streamerId,
                OffsetDateTime.ofInstant(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC));
    }
}
