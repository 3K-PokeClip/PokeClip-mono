package com.pokeclip.clip.thumbnail;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.jumpcard.JumpCardCreated;
import com.pokeclip.clip.segment.TimelineOriginReader;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.LedgerSeed;
import com.pokeclip.clip.support.TestIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static com.pokeclip.clip.support.LedgerSeed.at;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 장면을 영구히 못 내는 카드는 내려놓는다(POK-196 · kty 2026-11-12 결정 (가)). 카드 장면은 질의가 아니라 카드 장면 함수가 고르므로
 * 후보 50장을 뽑은 뒤에야 장면이 없음을 안다 — 내려놓지 않으면 그런 카드가 묶음 자리를 계속 차지해 그보다 오래된 카드의 사진이
 * 영영 안 나간다(커밋 리뷰 r1 HIGH — 정확히 50장이면 후보가 고정된다).
 *
 * <p>영구 불가 = 방송이 끝났거나 회차가 완결됐고, ① 장면이 없음(마지막 귀속 행 뒤) ② 장면 조각 {@code upload_state = failed}
 * ③ 회차 행이 없음. 방송 중이면 · 장면 조각이 아직이면 · 방송 장면이 계산 전제를 깨 격리됐으면 내려놓지 않는다.
 *
 * <p>시각은 {@link LedgerSeed#BASE}에서 잰 ms다.
 */
class CardThumbnailGiveUpTest extends IntegrationTestSupport {

    private static final String 조각_창고 = "segments-test";
    private static final String 사진_창고 = "clips-test";

    private final JdbcTemplate jdbc;
    private final ThumbnailTargets targets;
    private final ThumbnailRepository thumbnails;
    private final BroadcastRepository broadcasts;
    private final TimelineOriginReader origins;
    private final ObjectMapper mapper;
    private final LedgerSeed seed;

    private final List<JsonNode> sent = new ArrayList<>();
    private final Instant now = at(50_000_000);

    CardThumbnailGiveUpTest(JdbcTemplate jdbc, ThumbnailTargets targets, ThumbnailRepository thumbnails,
                            BroadcastRepository broadcasts, TimelineOriginReader origins, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.targets = targets;
        this.thumbnails = thumbnails;
        this.broadcasts = broadcasts;
        this.origins = origins;
        this.mapper = mapper;
        this.seed = new LedgerSeed(jdbc);
    }

    @BeforeEach
    void 비운다() {
        방송과_카드를_비운다(jdbc);
        sent.clear();
    }

    /**
     * 회귀(r1 HIGH): 번호가 큰 영구 불가 카드가 묶음 크기만큼 있어도 번호가 작은 정상 카드가 두 순회 안에 나간다. 내려놓지 않으면
     * 매 순회 후보가 그 50장에 고정된다.
     */
    @Test
    void 영구_불가_카드가_묶음을_채워도_오래된_정상_카드가_두_순회_안에_나간다() {
        회차_방송("S-ok", "OKKEY", true);
        long 정상 = 카드("S-ok", 1_000);
        회차_방송("S-stuck", "STUCKKEY", true);
        List<Long> 막힌 = new ArrayList<>();
        for (int i = 0; i < ThumbnailSweeper.BATCH_LIMIT; i++) {
            막힌.add(카드("S-stuck", 100_000 + i));
        }
        ThumbnailSweeper s = sweeper();

        s.sweepCards();
        s.sweepCards();

        assertThat(sent).extracting(job -> job.path("targetId").asString()).containsExactly(String.valueOf(정상));
        assertThat(막힌).allSatisfy(id -> assertThat(내려놓음(id)).as("카드 %d", id).isTrue());
    }

    /** ① 끝난 방송의 마지막 귀속 행 뒤 카드는 장면이 영구히 없다 — 내려놓고 다시 안 본다. */
    @Test
    void 끝난_방송의_마지막_행_뒤_카드는_내려놓는다() {
        회차_방송("S-after", "AFTERKEY", true);
        long card = 카드("S-after", 9_000);

        sweeper().sweepCards();
        sweeper().sweepCards();

        assertThat(sent).isEmpty();
        assertThat(내려놓음(card)).isTrue();
    }

    /** ② 끝난 방송에서 장면 조각이 {@code failed}면 그 조각은 안 올라온다 — 내려놓는다. */
    @Test
    void 끝난_방송에서_장면_조각이_실패면_내려놓는다() {
        회차_방송("S-failed", "FAILKEY", true);
        jdbc.update("UPDATE public.stream_segments SET upload_state = 'failed' WHERE stream_id = ? AND seq = 1",
                "FAILKEY");
        long card = 카드("S-failed", 1_000);

        sweeper().sweepCards();

        assertThat(sent).isEmpty();
        assertThat(내려놓음(card)).isTrue();
    }

    /** ③ 끝난 방송의 회차에 행이 하나도 없으면 장면이 영구히 없다 — 내려놓는다. */
    @Test
    void 끝난_방송의_회차_행이_없으면_내려놓는다() {
        broadcasts.save(Broadcast.startedNow("S-empty", TestIds.STREAMER, "EMPTYKEY", 1L, at(0), null));
        seed.session("S-empty", "EMPTYKEY", "S-empty", null);
        끝낸다("S-empty");
        long card = 카드("S-empty", 1_000);

        sweeper().sweepCards();

        assertThat(sent).isEmpty();
        assertThat(내려놓음(card)).isTrue();
    }

    /** 방송 편지는 아직 끝이 아니어도 회차가 완결이면(세션 모두 끝 · 회차 닫힘) 끝난 것으로 본다. */
    @Test
    void 회차가_완결이면_방송_편지_전에도_내려놓는다() {
        회차_방송("S-closed", "CLOSEDKEY", false);
        seed.endSession("S-closed", true);
        long card = 카드("S-closed", 9_000);

        sweeper().sweepCards();

        assertThat(내려놓음(card)).isTrue();
    }

    /** 방송 중이면 라이브 머리 카드는 다음 행을 기다린다 — 내려놓지 않는다(줄도 안 만든다). */
    @Test
    void 방송_중이면_장면이_없어도_내려놓지_않는다() {
        회차_방송("S-live", "LIVEKEY", false);
        카드("S-live", 9_000);

        sweeper().sweepCards();

        assertThat(sent).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM thumbnails", Integer.class)).isZero();
    }

    /** 끝난 방송이어도 장면 조각이 아직(pending)이면 올라올 수 있다 — 기다린다. */
    @Test
    void 끝난_방송에서_장면_조각이_아직이면_기다린다() {
        회차_방송("S-pend", "PENDKEY", true);
        jdbc.update("UPDATE public.stream_segments SET upload_state = 'pending' WHERE stream_id = ? AND seq = 1",
                "PENDKEY");
        카드("S-pend", 1_000);

        sweeper().sweepCards();

        assertThat(sent).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM thumbnails", Integer.class)).isZero();
    }

    /** 장면 계산이 깨져 격리된 방송(장부 불변식 위반)은 끝났어도 내려놓지 않는다 — 데이터 결함을 포기로 덮지 않는다. */
    @Test
    void 격리된_방송의_카드는_내려놓지_않는다() {
        broadcasts.save(Broadcast.startedNow("S-bad", TestIds.STREAMER, "BADKEY", 1L, at(0), null));
        seed.session("S-bad", "BADKEY", "S-bad", null);
        seed.row("BADKEY", 1).pts(0).wallAndPdt(0).duration(-1).session("S-bad").insert();
        끝낸다("S-bad");
        카드("S-bad", 9_000);

        sweeper().sweepCards();

        assertThat(sent).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM thumbnails", Integer.class)).isZero();
    }

    /** 빠른 순회(새 카드 명단)도 같은 처분이다 — 내려놓은 카드는 명단에서 빠진다. */
    @Test
    void 빠른_순회도_영구_불가_카드를_내려놓는다() {
        회차_방송("S-after", "AFTERKEY", true);
        long card = 카드("S-after", 9_000);
        ThumbnailSweeper s = sweeper();
        s.cardCreated(new JumpCardCreated(card));

        s.sweepNewCards();

        assertThat(내려놓음(card)).isTrue();
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    /** 회차 귀속 행 둘([0, 4000) · [4000, 8000))이 올라간 방송. {@code ended}면 방송을 끝낸다. */
    private void 회차_방송(String streamId, String key, boolean ended) {
        broadcasts.save(Broadcast.startedNow(streamId, TestIds.STREAMER, key, 1L, at(0), null));
        seed.session(streamId, key, streamId, null);
        seed.row(key, 1).pts(0).wallAndPdt(0).session(streamId).insert();
        seed.row(key, 2).pts(4_000).wallAndPdt(4_000).session(streamId).insert();
        if (ended) {
            끝낸다(streamId);
        }
    }

    private void 끝낸다(String streamId) {
        jdbc.update("UPDATE broadcasts SET status = 'ended', ended_at = ? WHERE stream_id = ?",
                Timestamp.from(at(600_000)), streamId);
    }

    /** 사진 줄이 「횟수를 다 쓴」 꼴(사진 없음 · 시도 상한)인가. */
    private boolean 내려놓음(long cardId) {
        List<Integer> attempts = jdbc.queryForList(
                "SELECT attempts FROM thumbnails WHERE kind = 'card' AND target_id = ? AND s3_key IS NULL",
                Integer.class, String.valueOf(cardId));
        return attempts.size() == 1 && attempts.get(0) >= ThumbnailSweeper.MAX_ATTEMPTS;
    }

    private ThumbnailSweeper sweeper() {
        ThumbnailQueueClient queue = new ThumbnailQueueClient(null, "unused") {
            @Override
            public void send(String body) {
                sent.add(mapper.readTree(body));
            }
        };
        return new ThumbnailSweeper(targets, thumbnails, queue, broadcasts, origins, mapper, 조각_창고, 사진_창고,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private long 카드(String streamId, long ts) {
        return jdbc.queryForObject("""
                        INSERT INTO jump_cards (stream_id, source, event_id, stream_timestamp_ms, window_start_ms, window_end_ms, score, event_seq)
                        VALUES (?, 'auto', ?, ?, ?, ?, 50, 0)
                        RETURNING id""",
                Long.class, streamId, "evt-" + streamId + "-" + ts, ts, ts - 500, ts + 500);
    }
}
