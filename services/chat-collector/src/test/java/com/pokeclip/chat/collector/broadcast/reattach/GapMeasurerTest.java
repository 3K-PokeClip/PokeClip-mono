package com.pokeclip.chat.collector.broadcast.reattach;

import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 유실 구간 측정을 <b>실 PostgreSQL</b>에서 잰다. 가짜로 못 재는 것이 둘이다 —
 * {@code timestamptz} 왕복, 그리고 <b>조회가 색인을 타는가</b>.
 *
 * <p><b>방송 번호를 검사마다 비운다.</b> {@code chat_messages}는 Flyway가 만들고 컨테이너는
 * JVM에 하나뿐이라({@code IntegrationTestSupport}) 앞 검사·다른 클래스가 남긴 줄이
 * {@code MAX()}를 조용히 어긋나게 한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class GapMeasurerTest extends IntegrationTestSupport {

    private static final List<String> STREAMS =
            List.of("live-A-001", "live-B-001", "live-C-001", "live-OTHER");

    /** 지문(channel_id, sender_channel_id, message_time, content_sha256)이 겹치면 접힌다. */
    private static final AtomicInteger SEQ = new AtomicInteger();

    private final GapMeasurer measurer;
    private final JdbcTemplate jdbc;

    GapMeasurerTest(GapMeasurer measurer, JdbcTemplate jdbc) {
        this.measurer = measurer;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 내_방송_채팅만_비운다() {
        STREAMS.forEach(streamId ->
                jdbc.update("DELETE FROM chat_messages WHERE stream_id = ?", streamId));
    }

    @Test
    void 마지막_채팅_시각부터_잰다() {
        insertChat("live-A-001", Instant.parse("2026-08-31T04:00:00Z"));
        insertChat("live-A-001", Instant.parse("2026-08-31T04:05:00Z"));

        Gap gap = measurer.measure("live-A-001", Instant.parse("2026-08-31T03:00:00Z"),
                Instant.parse("2026-08-31T04:10:00Z"));

        assertThat(gap.basis()).isEqualTo(Gap.Basis.LAST_CHAT);
        assertThat(gap.since()).isEqualTo(Instant.parse("2026-08-31T04:05:00Z"));
        assertThat(gap.gapMs()).isEqualTo(300_000L);
    }

    /**
     * 문항 2 — {@code basis}만 보면 <b>조회가 아예 안 돌아서</b> BROADCAST_START가 된 것과
     * 못 가른다. 그래서 {@code gapMs}까지 본다(방송 시작 기준 10분).
     */
    @Test
    void 채팅이_한_건도_없으면_방송_시작부터_잰다() {
        Gap gap = measurer.measure("live-B-001", Instant.parse("2026-08-31T04:00:00Z"),
                Instant.parse("2026-08-31T04:10:00Z"));

        assertThat(gap.basis()).isEqualTo(Gap.Basis.BROADCAST_START);
        assertThat(gap.gapMs()).isEqualTo(600_000L);
    }

    /** {@code WHERE stream_id = ?}가 없으면 남의 방송 채팅으로 공백을 재서 「안 끊겼다」가 된다. */
    @Test
    void 남의_방송_채팅은_안_센다() {
        insertChat("live-OTHER", Instant.parse("2026-08-31T04:09:00Z"));

        Gap gap = measurer.measure("live-B-001", Instant.parse("2026-08-31T04:00:00Z"),
                Instant.parse("2026-08-31T04:10:00Z"));

        assertThat(gap.basis()).isEqualTo(Gap.Basis.BROADCAST_START);
    }

    /** clip이 {@code startedAt}을 {@code null}로 줘 EPOCH가 된 방송. 1970년부터 재면 56년이 찍힌다. */
    @Test
    void 방송_시작_시각을_모르면_공백을_계산하지_않는다() {
        Gap gap = measurer.measure("live-C-001", Instant.EPOCH,
                Instant.parse("2026-08-31T04:10:00Z"));

        assertThat(gap.basis()).isEqualTo(Gap.Basis.UNKNOWN);
        assertThat(gap.since()).isNull();
        assertThat(gap.gapMs()).isEqualTo(-1L);
    }

    /**
     * 이 조회가 색인을 못 타면 채팅 표 전체를 훑는다. 방송이 많으면 재부착이 통째로 느려진다.
     *
     * <p>🔴 {@code queryForObject}가 아니다(계획 검증 M4). 진짜 PG 17.10 실측:
     * {@code IncorrectResultSizeDataAccessException: expected 1, actual 5} — EXPLAIN은 여러 줄이다.
     *
     * <p>🔴 <b>리터럴이 아니라 파라미터({@code ?})로 잰다.</b> 앱이 던지는 것이 그것이고,
     * POK-218이 「리터럴 EXPLAIN은 앱이 던지는 것과 다를 수 있다」로 데인 자리다.
     *
     * <p>문항 6 — {@code contains("idx_…")}만 보면 「계획 문자열 어딘가에 이름이 스쳤다」와
     * 「그것으로 읽었다」를 못 가른다. {@code Seq Scan}이 없는 것도 같이 본다.
     *
     * <p>🔴 <b>SQL을 여기 다시 적지 마라 — {@link GapMeasurer#LAST_RECEIVED}를 그대로
     * {@code EXPLAIN} 한다.</b> 처음엔 손으로 베껴 적었는데, 그러면 <b>사본만 재고 운영 질의는
     * 아무도 안 본다</b>: 운영 질의를 {@code md5(stream_id) = md5(?)}로 바꿔도 이 검사가
     * 초록이었다(결함 주입 N). 베낀 문자열을 재는 것은 아무것도 재지 않는 것과 같다.
     */
    @Test
    void 조회가_색인을_탄다() {
        insertChat("live-A-001", Instant.parse("2026-08-31T04:00:00Z"));

        List<String> plan = jdbc.queryForList(
                "EXPLAIN " + GapMeasurer.LAST_RECEIVED, String.class, "live-A-001");
        String joined = String.join("\n", plan);

        assertThat(joined).contains("idx_chat_messages_stream_received");
        assertThat(joined).doesNotContain("Seq Scan");
    }

    /**
     * 🔴 <b>이 검사는 결함 주입이 초록으로 나와서 생겼다.</b> {@code Math.max(0L, …)}를 지워도
     * 다섯 건이 전부 초록이었다 — {@code since}가 {@code now}보다 뒤인 입력을 주는 검사가
     * <b>하나도 없었다.</b>
     *
     * <p><b>지어낸 상황이 아니다.</b> {@code received_at}은 <b>우리 기계</b>의 벽시계이고
     * {@code broadcastStartedAt}은 <b>clip 기계</b>의 벽시계다 — 두 시계가 어긋나거나(서버가
     * 다르다) VM 복원류로 시계가 뒤로 가면 기준 시각이 「지금」보다 미래가 된다. 이 서버는 이미
     * 벽시계 역행 데이터를 실물로 만난 적이 있다({@code CLAUDE.md}「시차 보정」).
     *
     * <p>음수를 그대로 실으면 {@code gap_measured} 로그가 <b>「−10분 동안 유실됐다」</b>는
     * 말이 안 되는 값을 남기고, 그 숫자를 읽는 사람이 부호를 무시하면 그럴듯하게 틀린다.
     * <b>0이 정직하다</b> — 「못 받은 구간이 없다」가 실제로 맞는 말이다.
     *
     * <p>{@code since}는 <b>안 접는다</b>. 값과 기준을 같이 주는 것이 이 record의 뜻이라,
     * 기준까지 지어내면 나중에 이 줄만 보고 무엇을 쟀는지 알 수 없다.
     */
    @Test
    void 기준_시각이_지금보다_뒤여도_공백이_음수가_되지_않는다() {
        insertChat("live-A-001", Instant.parse("2026-08-31T04:10:00Z"));

        Gap gap = measurer.measure("live-A-001", Instant.parse("2026-08-31T03:00:00Z"),
                Instant.parse("2026-08-31T04:00:00Z"));

        assertThat(gap.basis()).isEqualTo(Gap.Basis.LAST_CHAT);
        assertThat(gap.since()).as("기준까지 지어내면 숫자의 뜻을 잃는다")
                .isEqualTo(Instant.parse("2026-08-31T04:10:00Z"));
        assertThat(gap.gapMs()).isZero();
    }

    private void insertChat(String streamId, Instant receivedAt) {
        int n = SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO chat_messages
                  (channel_id, sender_channel_id, content, message_time, received_at,
                   content_sha256, stream_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                "gap-ch", "gap-sender", "ㅋㅋ" + n,
                Timestamp.from(receivedAt), Timestamp.from(receivedAt),
                String.format("%064d", n), streamId);
    }
}
