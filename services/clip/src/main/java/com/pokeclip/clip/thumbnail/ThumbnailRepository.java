package com.pokeclip.clip.thumbnail;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/** {@code thumbnails} 표(V211). 읽기는 한 장에 한 번(대상 여럿을 배열로), 쓰기는 한 줄 upsert다. */
@Repository
public class ThumbnailRepository {

    private final JdbcTemplate jdbc;

    ThumbnailRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 올라온 사진의 키. 주문만 나가고 아직 안 올라온 대상은 맵에 없다. */
    public Map<String, String> keysOf(ThumbnailKind kind, Collection<String> targetIds) {
        Map<String, String> keys = new HashMap<>();
        if (targetIds.isEmpty()) {
            return keys;
        }
        jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT target_id, s3_key FROM thumbnails
                     WHERE kind = ? AND target_id = ANY(?) AND s3_key IS NOT NULL""");
            ps.setString(1, kind.value());
            ps.setArray(2, con.createArrayOf("text", targetIds.toArray(String[]::new)));
            return ps;
        }, rs -> {
            keys.put(rs.getString("target_id"), rs.getString("s3_key"));
        });
        return keys;
    }

    /** 라이브 사진이 찍은 마지막 장면 시각. 순회기가 「새 조각이 없으면 다시 안 찍는다」에 쓴다. */
    public Map<String, Instant> liveCapturedAt(Collection<String> streamIds) {
        Map<String, Instant> captured = new HashMap<>();
        if (streamIds.isEmpty()) {
            return captured;
        }
        jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT target_id, captured_at FROM thumbnails
                     WHERE kind = 'live' AND target_id = ANY(?) AND captured_at IS NOT NULL""");
            ps.setArray(1, con.createArrayOf("text", streamIds.toArray(String[]::new)));
            return ps;
        }, rs -> {
            captured.put(rs.getString("target_id"), rs.getTimestamp("captured_at").toInstant());
        });
        return captured;
    }

    /**
     * 주문을 냈다고 적는다(card·clip). 이미 올라온 사진은 건드리지 않는다. 다시 주문할지는 {@code attempts}·{@code requested_at}으로
     * 순회기의 조회가 정한다.
     */
    @Transactional
    public void markRequested(ThumbnailKind kind, String targetId, Instant now) {
        // 대상 줄을 잠근 채 있을 때만 적는다(POK-256). 정리기가 고른 직후 탈퇴 정리가 대상을 지우면 빈 사진 줄이 되살아난다.
        if (!lockTarget(kind, targetId)) {
            return;
        }
        jdbc.update("""
                INSERT INTO thumbnails (kind, target_id, requested_at, attempts, updated_at)
                VALUES (?, ?, ?, 1, ?)
                ON CONFLICT (kind, target_id) DO UPDATE
                   SET requested_at = EXCLUDED.requested_at,
                       attempts = thumbnails.attempts + 1,
                       updated_at = EXCLUDED.updated_at""",
                kind.value(), targetId, Timestamp.from(now), Timestamp.from(now));
    }

    /** 찍을 것이 없는 대상(영상 산출물이 없는 완성 영상)을 다시 안 보게 횟수를 다 쓴 것으로 적는다. */
    @Transactional
    public void markGivenUp(ThumbnailKind kind, String targetId, int attempts, Instant now) {
        if (!lockTarget(kind, targetId)) {
            return;
        }
        jdbc.update("""
                INSERT INTO thumbnails (kind, target_id, requested_at, attempts, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (kind, target_id) DO UPDATE
                   SET requested_at = EXCLUDED.requested_at,
                       attempts = EXCLUDED.attempts,
                       updated_at = EXCLUDED.updated_at""",
                kind.value(), targetId, Timestamp.from(now), attempts, Timestamp.from(now));
    }

    /** 보고를 적은 결과. GONE은 대상이 없다(탈퇴로 지워졌다, POK-256). */
    public enum Saved { SAVED, KEPT_NEWER, GONE }

    /**
     * 대상이 있을 때만 보고를 적는다(POK-256). 대상 줄을 {@code FOR KEY SHARE}로 잡은 채 적는다: 탈퇴 정리는 방송·영상·카드 줄을
     * {@code FOR UPDATE}로 먼저 잡으므로, 정리가 먼저면 여기서 기다렸다가 「없음」을 보고, 여기가 먼저면 정리가 기다렸다가
     * 이 줄까지 지운다. 확인과 적기를 갈라 두면 그 사이에 정리가 끼어 대상 없는 사진 줄이 남는다(PR #220 codex P2).
     */
    @Transactional
    public Saved saveIfTargetExists(ThumbnailKind kind, String targetId, Instant capturedAt, Instant now) {
        if (!lockTarget(kind, targetId)) {
            return Saved.GONE;
        }
        return saveCaptured(kind, targetId, capturedAt, now) ? Saved.SAVED : Saved.KEPT_NEWER;
    }

    private boolean lockTarget(ThumbnailKind kind, String targetId) {
        if (kind == ThumbnailKind.LIVE) {
            return !jdbc.queryForList("SELECT 1 FROM broadcasts WHERE stream_id = ? FOR KEY SHARE", targetId).isEmpty();
        }
        long id;
        try {
            id = Long.parseLong(targetId);
        } catch (NumberFormatException e) {
            // 보고 문은 19자리까지 받는다. bigint 밖이면 그런 줄은 없다(SQL로 넘기면 CAST가 터져 500).
            return false;
        }
        String table = kind == ThumbnailKind.CARD ? "jump_cards" : "clips";
        return !jdbc.queryForList("SELECT 1 FROM " + table + " WHERE id = ? FOR KEY SHARE", id).isEmpty();
    }

    /**
     * 일꾼이 올렸다는 보고를 적는다. 키는 받지 않고 clip이 정한다({@link ThumbnailKind#keyOf}). 같은 보고가 두 번 와도(SQS는 최소
     * 한 번) 같은 값이 된다.
     *
     * <p>🔴 <b>더 이른 장면이면 덮지 않는다.</b> 라이브는 1분마다 주문이 나가고 일꾼이 늦을 수 있어, 앞 주문의 보고가 뒤 주문보다
     * 늦게 오면 시각이 거꾸로 간다. 그때는 줄을 그대로 둔다(파일은 이미 같은 키에 덮였을 수 있지만 다음 분에 다시 덮인다).
     *
     * @return 줄이 바뀌었으면 {@code true}
     */
    public boolean saveCaptured(ThumbnailKind kind, String targetId, Instant capturedAt, Instant now) {
        return jdbc.update("""
                INSERT INTO thumbnails (kind, target_id, s3_key, captured_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (kind, target_id) DO UPDATE
                   SET s3_key = EXCLUDED.s3_key,
                       captured_at = EXCLUDED.captured_at,
                       updated_at = EXCLUDED.updated_at
                 WHERE thumbnails.captured_at IS NULL OR thumbnails.captured_at <= EXCLUDED.captured_at""",
                kind.value(), targetId, kind.keyOf(targetId), Timestamp.from(capturedAt), Timestamp.from(now)) > 0;
    }
}
