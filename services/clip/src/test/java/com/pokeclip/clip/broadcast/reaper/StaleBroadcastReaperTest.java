package com.pokeclip.clip.broadcast.reaper;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastEventProcessor;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.broadcast.BroadcastStatus;
import com.pokeclip.clip.broadcast.Envelopes;
import com.pokeclip.clip.broadcast.ProcessResult;
import com.pokeclip.clip.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 놓친 방송 치우기(POK-244). <b>스케줄러 없이 한 회차씩 손으로 돌린다</b> — 시각은 주입한 시계다.
 *
 * <p>🔴 <b>첫 번째 시험이 이 카드의 완료 조건 1이다</b>: 조각이 계속 들어오는 방송은 절대 안 닫힌다.
 * 나머지는 「닫힌 뒤 편지가 늦게 와도 기존 순서 규칙이 그대로다」와 멱등이다.
 *
 * <p>봉투의 시작 시각은 {@code Envelopes}가 고정한 2026-08-18T00:00Z다. 시계를 그 뒤로 옮겨 잰다.
 */
class StaleBroadcastReaperTest extends IntegrationTestSupport {

    private static final Instant STARTED = Instant.parse("2026-08-18T00:00:00Z");
    private static final Duration GRACE = Duration.ofMinutes(30);

    private final BroadcastEventProcessor processor;
    private final BroadcastRepository broadcasts;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    private final List<String> notified = new ArrayList<>();
    private Instant now = STARTED.plus(Duration.ofHours(2));

    StaleBroadcastReaperTest(BroadcastEventProcessor processor, BroadcastRepository broadcasts,
                             JdbcTemplate jdbc, TransactionTemplate tx) {
        this.processor = processor;
        this.broadcasts = broadcasts;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @BeforeEach
    void 비운다() {
        방송과_카드를_비운다(jdbc);
        notified.clear();
    }

    private StaleBroadcastReaper reaper() {
        return new StaleBroadcastReaper(jdbc, broadcasts, tx, notified::add, GRACE, () -> now);
    }

    @Test
    void 조각이_계속_들어오는_방송은_시작한_지_오래돼도_안_닫힌다() {
        processor.process(Envelopes.started("e1", "s-alive", 1L));
        조각(  "s-alive", 1, now.minus(Duration.ofHours(1)));
        조각(  "s-alive", 2, now.minus(Duration.ofMinutes(3)));   // 유예(30분) 안이다

        assertThat(reaper().reapOnce()).isZero();
        assertThat(상태("s-alive")).isEqualTo("live");
        assertThat(notified).isEmpty();
    }

    @Test
    void 조각이_그친_지_유예를_넘긴_방송은_닫히고_종료_시각은_마지막_조각이다() {
        processor.process(Envelopes.started("e1", "s-dead", 1L));
        Instant last = now.minus(Duration.ofMinutes(31));
        조각("s-dead", 1, now.minus(Duration.ofMinutes(50)));
        조각("s-dead", 2, last);

        assertThat(reaper().reapOnce()).isEqualTo(1);

        Broadcast b = broadcasts.findByStreamId("s-dead").orElseThrow();
        assertThat(b.getStatus()).isEqualTo(BroadcastStatus.ENDED);
        assertThat(b.getEndedAt()).as("지금이 아니라 마지막 조각 시각").isEqualTo(last);
        assertThat(b.getVodExpiresAt()).isEqualTo(last.plus(Broadcast.VOD_RETENTION));
        assertThat(b.getLastSequence()).as("순서 번호는 안 올린다").isEqualTo(1L);
        assertThat(notified).containsExactly("s-dead");
    }

    /** 마지막 조각은 옛것인데 시작 편지가 그보다 늦다 — 둘 중 늦은 쪽이 신호다(안 닫는 쪽으로). */
    @Test
    void 조각이_하나도_없으면_시작_시각으로_재고_늦은_쪽을_신호로_본다() {
        processor.process(Envelopes.started("e1", "s-nosegs", 1L));   // 시작 2시간 전
        processor.process(Envelopes.started("e2", "s-fresh", 1L));
        jdbc.update("UPDATE broadcasts SET started_at = ? WHERE stream_id = ?",
                Timestamp.from(now.minus(Duration.ofMinutes(5))), "s-fresh");
        조각("s-fresh", 1, now.minus(Duration.ofHours(1)));   // 조각은 오래됐지만 시작이 5분 전

        assertThat(reaper().reapOnce()).isEqualTo(1);
        assertThat(상태("s-nosegs")).as("조각 없음 → 시작 2시간 전 → 닫힘").isEqualTo("ended");
        assertThat(상태("s-fresh")).as("늦은 신호(시작 5분 전)를 따른다").isEqualTo("live");
    }

    /**
     * 🔴 완료 조건 2·3. 닫은 뒤 진짜 {@code ended}(높은 순서)는 정상 반영돼 시각을 정정하고,
     * 낮은 순서의 {@code started}는 그대로 낡은 편지다 — 치우개가 순서 번호를 안 건드린 덕이다.
     */
    @Test
    void 닫은_뒤에_온_진짜_편지는_기존_순서_규칙_그대로_처리된다() {
        processor.process(Envelopes.started("e1", "s-late", 3L));
        조각("s-late", 1, now.minus(Duration.ofHours(1)));
        assertThat(reaper().reapOnce()).isEqualTo(1);

        assertThat(processor.process(Envelopes.started("e-old", "s-late", 2L)))
                .as("낮은 순서의 시작").isEqualTo(ProcessResult.IGNORED_STALE);
        assertThat(processor.process(Envelopes.ended("e-real", "s-late", 4L)))
                .as("높은 순서의 진짜 종료").isEqualTo(ProcessResult.PROCESSED);
        Broadcast b = broadcasts.findByStreamId("s-late").orElseThrow();
        assertThat(b.getStatus()).isEqualTo(BroadcastStatus.ENDED);
        assertThat(b.getEndedAt()).as("편지 시각으로 정정").isEqualTo(Instant.parse("2026-08-18T01:00:00Z"));
        assertThat(b.getLastSequence()).isEqualTo(4L);
        assertThat(processor.process(Envelopes.ended("e-real", "s-late", 4L)))
                .as("같은 편지가 또 와도 중복").isEqualTo(ProcessResult.DUPLICATE);
    }

    @Test
    void 두_번_돌려도_한_번만_닫고_한_번만_알린다() {
        processor.process(Envelopes.started("e1", "s-twice", 1L));
        조각("s-twice", 1, now.minus(Duration.ofHours(1)));

        assertThat(reaper().reapOnce()).isEqualTo(1);
        assertThat(reaper().reapOnce()).isZero();
        assertThat(notified).containsExactly("s-twice");
    }

    @Test
    void 이미_끝난_방송과_남의_조각은_안_본다() {
        processor.process(Envelopes.started("e1", "s-ended", 1L));
        processor.process(Envelopes.ended("e2", "s-ended", 2L));
        processor.process(Envelopes.started("e3", "s-other", 1L));
        조각("s-ended", 1, now);            // 끝난 방송에 새 조각이 있어도 되살리지 않는다
        조각("s-other", 1, now.minus(Duration.ofMinutes(1)));

        assertThat(reaper().reapOnce()).isZero();
        assertThat(상태("s-ended")).isEqualTo("ended");
        assertThat(상태("s-other")).isEqualTo("live");
        assertThat(notified).isEmpty();
    }

    @Test
    void 알림_훅이_없어도_표는_굳힌다() {
        processor.process(Envelopes.started("e1", "s-nohook", 1L));
        StaleBroadcastReaper silent = new StaleBroadcastReaper(jdbc, broadcasts, tx, null, GRACE, () -> now);

        assertThat(silent.reapOnce()).isEqualTo(1);
        assertThat(상태("s-nohook")).isEqualTo("ended");
    }

    // ── 도우미 ──────────────────────────────────────────────────

    /** 1번 장부에 조각 한 줄. 치우개가 읽는 것은 {@code seq} 최대 행의 {@code start_wall_utc}다. */
    private void 조각(String streamId, long seq, Instant startWall) {
        jdbc.update("INSERT INTO stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key) "
                        + "VALUES (?, ?, ?, ?, 4000, ?)",
                streamId, seq, seq * 4000, Timestamp.from(startWall), "k/" + streamId + "/" + seq);
    }

    private String 상태(String streamId) {
        return jdbc.queryForObject("SELECT status FROM broadcasts WHERE stream_id = ?", String.class, streamId);
    }
}
