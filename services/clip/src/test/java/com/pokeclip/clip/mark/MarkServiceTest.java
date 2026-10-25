package com.pokeclip.clip.mark;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.jumpcard.JumpCardService;
import com.pokeclip.clip.jumpcard.JumpCardService.RecordResult;
import com.pokeclip.clip.mark.MarkErrors.MarkRejectedException;
import com.pokeclip.clip.segment.TimelineOriginReader;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.TestIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 마킹의 시각 환산과 방송 고르기(POK-391). 받은 시각을 {@code Clock}으로 고정해 ms를 정확히 잰다.
 * 응답 코드·본문은 {@code MarkControllerTest}가 잰다.
 *
 * <p>기준점(ADR-079) = 첫 조각의 벽시계 − 그 조각의 {@code start_pts_ms}. 여기서는 pts 0인 조각 하나를 {@link #T0}에 심어
 * 기준점이 정확히 {@link #T0}이 되게 한다.
 */
class MarkServiceTest extends IntegrationTestSupport {

    private static final String VECTOR_PASSPHRASE = "EXAMPLE-4b-test-vector-not-a-key";
    private static final String BEARER = "Bearer 136ef57bb5b3eaa8c35485438c358ff1eb392d925714b76f1e2eb756d2a04315";
    private static final String TOKEN = "EXAMPLESTREAMKEYTOKEN00001";
    private static final String STREAMID = "#!::r=" + TOKEN + ",m=publish";
    private static final String 나 = TestIds.STREAMER;

    /** 기준점. */
    private static final Instant T0 = Instant.parse("2026-10-23T00:00:00Z");

    /** 방송 시작 편지 시각. 첫 조각이 그 30초 뒤(실측에서 첫 조각은 시작 편지 수십 초 뒤였다). */
    private static final Instant STARTED = T0.minusSeconds(30);

    /** 받은 시각(서버 시계). */
    private static final Instant RECEIVED = T0.plusSeconds(600);

    /** 플러그인 시계의 보낸 시각: 서버 시계와 아무 관계가 없는 값이다(시계 차를 일부러 크게). */
    private static final long SENT_AT = 1_000_000_000_000L;

    private final StreamKeyResolveClient keys;
    private final BroadcastRepository broadcasts;
    private final TimelineOriginReader origins;
    private final JumpCardService cards;
    private final JdbcTemplate jdbc;

    MarkServiceTest(StreamKeyResolveClient keys, BroadcastRepository broadcasts, TimelineOriginReader origins,
                    JumpCardService cards, JdbcTemplate jdbc) {
        this.keys = keys;
        this.broadcasts = broadcasts;
        this.origins = origins;
        this.cards = cards;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 정리() {
        방송과_카드를_비운다(jdbc);
        AUTH.respondWith("/internal/stream-keys/resolve", 200,
                "{\"valid\":true,\"userId\":7,\"passphrase\":\"" + VECTOR_PASSPHRASE + "\"}");
    }

    // ── 시각 ────────────────────────────────────────────────────

    /** 누른 시각을 (받은 시각 − sentAt)만큼 옮기고 기준점을 뺀다: 받기 3초 전에 눌렀으면 R − 3초 − T0. */
    @Test
    void 누른_시각을_받은_시각_sentAt_차로_옮기고_기준점을_뺀다() {
        방송("S-1", TOKEN, STARTED);
        조각(TOKEN, T0, 0);

        RecordResult result = mark(SENT_AT - 3_000, SENT_AT, RECEIVED);

        assertThat(result.created()).isTrue();
        assertThat(result.card().streamTimestampMs()).isEqualTo(600_000L - 3_000L);
        assertThat(result.card().streamId()).isEqualTo("S-1");
    }

    /** 플러그인 시계가 앞서 누른 시각이 받은 시각보다 미래가 되면 받은 시각이다. */
    @Test
    void 보정한_시각이_미래면_받은_시각() {
        방송("S-1", TOKEN, STARTED);
        조각(TOKEN, T0, 0);

        assertThat(mark(SENT_AT + 5_000, SENT_AT, RECEIVED).card().streamTimestampMs()).isEqualTo(600_000L);
    }

    /** 기준점보다 앞(첫 조각 전)에 눌렀으면 0이다(감사 1회차 A2: 음수가 저장으로 가면 500이다). */
    @Test
    void 기준점보다_앞이면_0() {
        방송("S-1", TOKEN, STARTED);
        조각(TOKEN, T0, 0);
        long beforeOrigin = Duration.between(T0.minusSeconds(10), RECEIVED).toMillis();

        RecordResult result = mark(SENT_AT - beforeOrigin, SENT_AT, RECEIVED);

        assertThat(result.card().streamTimestampMs()).isZero();
        assertThat(result.card().window().startMs()).isZero();
    }

    /** 기준점에 pts가 있으면 그만큼 앞이다: ms는 기준점에서 잰다(벽시계 − pts). */
    @Test
    void 첫_조각의_pts만큼_기준점이_앞선다() {
        방송("S-1", TOKEN, STARTED);
        조각(TOKEN, T0, 4_000);

        assertThat(mark(SENT_AT, SENT_AT, RECEIVED).card().streamTimestampMs()).isEqualTo(604_000L);
    }

    /**
     * 계획 검증 E6. 방송 시작보다 2분 넘게 앞에 누른 것은 이 방송의 것이 아니다. 딱 2분은 받는다.
     * 400이다(감사 2회차 B3): 다시 보내도 결과가 같아 404(플러그인 재시도)는 헛돈다.
     */
    @Test
    void 방송_시작보다_2분_넘게_앞이면_400_딱_2분은_받는다() {
        방송("S-1", TOKEN, STARTED);
        조각(TOKEN, T0, 0);
        long toEdge = Duration.between(STARTED.minus(Duration.ofMinutes(2)), RECEIVED).toMillis();

        assertRejected(() -> mark(SENT_AT - toEdge - 1, SENT_AT, RECEIVED), HttpStatus.BAD_REQUEST, "invalid_request");
        assertThat(mark(SENT_AT - toEdge, SENT_AT, RECEIVED).card().streamTimestampMs()).isZero();
    }

    /**
     * 감사 2회차 B1(계획 검증 E5). 받은 시각은 문이 넘긴 값이다: 서비스가 그 뒤에 시계를 다시 읽으면(auth 왕복 뒤) 그만큼
     * 누른 시각이 밀린다. 시계를 넘긴 값보다 10초 뒤로 두고, ms가 넘긴 값 기준인지 잰다.
     */
    @Test
    void 받은_시각은_넘긴_값을_쓰고_시계를_다시_읽지_않는다() {
        방송("S-1", TOKEN, STARTED);
        조각(TOKEN, T0, 0);
        String eventId = UUID.randomUUID().toString();
        MarkService service = new MarkService(keys, broadcasts, origins, cards,
                Clock.fixed(RECEIVED.plusSeconds(10), ZoneOffset.UTC));

        RecordResult result = service.mark(BEARER, eventId, 본문(eventId, SENT_AT - 3_000, SENT_AT), RECEIVED);

        assertThat(result.card().streamTimestampMs()).isEqualTo(600_000L - 3_000L);
    }

    /** 감사 1회차 A2. 방송 길이 상한(48시간) 밖의 ms는 시각이 틀린 것이라 400. */
    @Test
    void 상한_밖_ms는_400() {
        방송("S-1", TOKEN, STARTED);
        조각(TOKEN, T0, 0);
        Instant late = T0.plus(Duration.ofHours(48)).plusMillis(1);

        assertRejected(() -> mark(SENT_AT, SENT_AT, late), HttpStatus.BAD_REQUEST, "invalid_request");
        assertThat(mark(SENT_AT, SENT_AT, T0.plus(Duration.ofHours(48))).card().streamTimestampMs())
                .isEqualTo(Duration.ofHours(48).toMillis());
    }

    /** 시계 차 계산이 넘치면 400이다(500이면 플러그인이 10분 동안 같은 값을 다시 보낸다). */
    @Test
    void 시각_계산이_넘치면_400() {
        방송("S-1", TOKEN, STARTED);
        조각(TOKEN, T0, 0);

        assertRejected(() -> mark(Long.MAX_VALUE, 1, RECEIVED), HttpStatus.BAD_REQUEST, "invalid_request");
    }

    @Test
    void 기준점이_없으면_503_timeline_not_ready() {
        방송("S-1", TOKEN, STARTED);

        assertThatThrownBy(() -> mark(SENT_AT, SENT_AT, RECEIVED))
                .isInstanceOfSatisfying(MarkRejectedException.class, e -> {
                    assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.reason()).isEqualTo("timeline_not_ready");
                    assertThat(e.retryAfterSeconds()).isEqualTo(5);
                });
    }

    // ── 방송 ────────────────────────────────────────────────────

    /** 계획 검증 E3. 켜진 방송이 둘이면 물리 키가 이 스트림키 토큰인 방송: 늦게 시작한 쪽이 아니어도. */
    @Test
    void live가_둘이면_물리_키가_맞는_방송() {
        방송("S-old", TOKEN, STARTED);
        방송("S-new", "OTHERKEY", STARTED.plusSeconds(20));
        조각(TOKEN, T0, 0);
        조각("OTHERKEY", T0.plusSeconds(20), 0);

        assertThat(mark(SENT_AT, SENT_AT, RECEIVED).card().streamId()).isEqualTo("S-old");
    }

    @Test
    void 물리_키가_안_맞으면_늦게_시작한_방송() {
        방송("S-old", "KEY-A", STARTED);
        방송("S-new", "KEY-B", STARTED.plusSeconds(20));
        조각("KEY-A", T0, 0);
        조각("KEY-B", T0.plusSeconds(20), 0);

        assertThat(mark(SENT_AT, SENT_AT, RECEIVED).card().streamId()).isEqualTo("S-new");
    }

    @Test
    void ended_vod_ready_방송에는_안_붙는다() {
        방송("S-ended", TOKEN, STARTED);
        방송("S-vod", TOKEN + "x", STARTED);
        jdbc.update("UPDATE broadcasts SET status = 'ended' WHERE stream_id = 'S-ended'");
        jdbc.update("UPDATE broadcasts SET status = 'vod_ready' WHERE stream_id = 'S-vod'");
        조각(TOKEN, T0, 0);

        assertRejected(() -> mark(SENT_AT, SENT_AT, RECEIVED), HttpStatus.NOT_FOUND, "broadcast_not_found");
    }

    @Test
    void 남의_켜진_방송에는_안_붙는다() {
        broadcasts.save(Broadcast.startedNow("S-other", "8", TOKEN, 1L, STARTED, null));
        조각(TOKEN, T0, 0);

        assertRejected(() -> mark(SENT_AT, SENT_AT, RECEIVED), HttpStatus.NOT_FOUND, "broadcast_not_found");
    }

    /** 방송이 끝난 뒤 온 같은 eventId 재전송도 그 카드 200이다(선조회가 방송 고르기 앞이다). */
    @Test
    void 방송이_끝난_뒤_온_재전송도_같은_카드() {
        방송("S-1", TOKEN, STARTED);
        조각(TOKEN, T0, 0);
        String eventId = UUID.randomUUID().toString();
        RecordResult first = mark(eventId, SENT_AT, SENT_AT, RECEIVED);
        jdbc.update("UPDATE broadcasts SET status = 'ended' WHERE stream_id = 'S-1'");

        RecordResult again = mark(eventId, SENT_AT, SENT_AT, RECEIVED);

        assertThat(again.created()).isFalse();
        assertThat(again.card().id()).isEqualTo(first.card().id());
    }

    // ── 도우미 ──────────────────────────────────────────────────

    private RecordResult mark(long pressedAt, long sentAt, Instant received) {
        return mark(UUID.randomUUID().toString(), pressedAt, sentAt, received);
    }

    private RecordResult mark(String eventId, long pressedAt, long sentAt, Instant received) {
        MarkService service = new MarkService(keys, broadcasts, origins, cards, Clock.fixed(received, ZoneOffset.UTC));
        return service.mark(BEARER, eventId, 본문(eventId, pressedAt, sentAt), service.now());
    }

    private static String 본문(String eventId, long pressedAt, long sentAt) {
        return "{\"streamid\":\"" + STREAMID + "\",\"eventId\":\"" + eventId
                + "\",\"pressedAt\":" + pressedAt + ",\"sentAt\":" + sentAt + "}";
    }

    private void 방송(String streamId, String ingestKey, Instant startedAt) {
        broadcasts.save(Broadcast.startedNow(streamId, 나, ingestKey, 1L, startedAt, null));
    }

    private void 조각(String ingestKey, Instant wall, long ptsMs) {
        jdbc.update("""
                        INSERT INTO public.stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, upload_state)
                        VALUES (?, 1, ?, ?, 4000, ?, 'uploaded')""",
                ingestKey, ptsMs, Timestamp.from(wall), "seg/" + ingestKey + "/1");
    }

    private static void assertRejected(Runnable call, HttpStatus status, String reason) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(MarkRejectedException.class, e -> {
            assertThat(e.status()).isEqualTo(status);
            assertThat(e.reason()).isEqualTo(reason);
        });
    }
}
