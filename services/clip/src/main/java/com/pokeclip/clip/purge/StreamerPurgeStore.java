package com.pokeclip.clip.purge;

import com.pokeclip.clip.upload.UploadThumbnailStore;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 탈퇴한 스트리머 명부와 지우기 SQL(POK-256). JPA를 안 쓴다: 지우기는 표 아홉을 묶음으로 지우는 일이라
 * 엔티티를 한 줄씩 읽어 오면 그 수만큼 왕복한다.
 *
 * <p>🔴 <b>지우는 순서가 외래키 순서다.</b> 표 사이 외래키에 {@code ON DELETE}가 하나도 없어 부모를 먼저 지우면
 * 트랜잭션이 통째로 죽는다. 시험의 {@code 방송과_카드를_비운다}와 같은 순서다.
 */
@Repository
public class StreamerPurgeStore {

    private final JdbcTemplate jdbc;

    StreamerPurgeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 명부에 적는다. 이미 있으면 다시 열어 정리기가 한 번 더 훑게 한다(재요청은 「아직 남았을 수 있다」는 뜻이다). */
    public void request(String streamerId, Instant now) {
        jdbc.update("""
                INSERT INTO purged_streamers (streamer_id, requested_at) VALUES (?, ?)
                ON CONFLICT (streamer_id) DO UPDATE SET completed_at = NULL""",
                streamerId, Timestamp.from(now));
    }

    /** 버린 편지의 물리 키를 명부에 더하고, 없던 키면 줄을 다시 연다. */
    public void rememberSegmentKey(String streamerId, String key) {
        jdbc.update("""
                UPDATE purged_streamers SET segment_keys = array_append(segment_keys, ?), completed_at = NULL
                 WHERE streamer_id = ? AND NOT (? = ANY(segment_keys))""", key, streamerId, key);
    }

    public boolean isPurged(String streamerId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM purged_streamers WHERE streamer_id = ?)", Boolean.class, streamerId));
    }

    /**
     * 정리기가 할 일. 안 끝난 줄과, 끝났는데 방송 줄이 다시 생긴 스트리머(편지 처리기가 명부를 보기 직전에
     * 들어온 편지)다. 둘째 갈래가 없으면 그런 방송은 영영 남는다.
     */
    public List<String> due(int limit) {
        // 끝난 뒤에도 녹화 조각 줄이 다시 생겼는지 본다(기한 없이). 영상 서버는 장부를 따로 쓰므로, 탈퇴 순간 붙어 있던
        // 녹화가 정리 뒤에 줄을 더할 수 있다(PR #220 codex P1). 장부가 없는 배포(media 없음)에서는 그 갈래를 뺀다.
        String lateSegments = tableExists("stream_segments")
                // 기한을 두면 탈퇴 뒤 그보다 오래 이어진 녹화의 줄이 영영 남는다(PR #220 codex 2판). 열쇠마다 PK 색인 한 번이라 싸다.
                ? " OR EXISTS (SELECT 1 FROM stream_segments s WHERE s.stream_id = ANY(p.segment_keys))"
                : "";
        return jdbc.queryForList("""
                SELECT p.streamer_id FROM purged_streamers p
                 WHERE p.completed_at IS NULL
                    OR EXISTS (SELECT 1 FROM broadcasts b WHERE b.streamer_id = p.streamer_id)""" + lateSegments + """

                 ORDER BY p.requested_at
                 LIMIT ?""", String.class, limit);
    }

    /** 창고가 없어 녹화 조각을 안 지우는 배포에서, 정리기가 그 줄을 「남은 일」로 계속 잡지 않게 열쇠를 비운다. */
    public void clearSegmentKeys(String streamerId) {
        jdbc.update("UPDATE purged_streamers SET segment_keys = '{}' WHERE streamer_id = ?", streamerId);
    }

    /**
     * 표에서 지운다. 지울 파일 주소는 먼저 명부로 옮긴다: 표 줄이 사라지면 주소를 다시 알 길이 없다.
     * 호출자가 트랜잭션을 연다.
     *
     * <p>방송·렌더 주문·완성 영상·카드 줄을 이 순서로 {@code FOR UPDATE}로 잡는다. 그 사이 새 카드·편집본·업로드가 외래키로 붙으면
     * 마지막 방송 지우기가 외래키 위반으로 죽기 때문이다(자식 INSERT는 부모 줄에 KEY SHARE를 걸어 여기서 기다린다).
     */
    public Detached detach(String streamerId) {
        jdbc.queryForList("SELECT streamer_id FROM purged_streamers WHERE streamer_id = ? FOR UPDATE", streamerId);
        List<String> streamIds = new ArrayList<>();
        List<String> ingestKeys = new ArrayList<>();
        jdbc.query("SELECT stream_id, COALESCE(ingest_stream_id, stream_id) AS ingest_key FROM broadcasts "
                + "WHERE streamer_id = ? ORDER BY stream_id FOR UPDATE", rs -> {
            streamIds.add(rs.getString("stream_id"));
            ingestKeys.add(rs.getString("ingest_key"));
        }, streamerId);

        Array streams = textArray(streamIds);
        // 🔴 렌더 주문 줄을 완성 영상 줄보다 먼저 잠근다. 렌더 보고(JobEventService)가 주문 → 영상 순서로 잠그므로,
        // 반대로 잡으면 탈퇴 정리와 늦게 온 보고가 서로를 기다리다 교착으로 한쪽이 끊긴다.
        jdbc.queryForList("SELECT id FROM render_jobs WHERE clip_id IN (SELECT id FROM clips WHERE stream_id = ANY(?)) "
                + "ORDER BY id FOR UPDATE", streams);
        List<String> clipIds = jdbc.queryForList(
                "SELECT id::text FROM clips WHERE stream_id = ANY(?) ORDER BY id FOR UPDATE", String.class, streams);
        // 카드도 잡는다. 썸네일 보고가 대상 줄을 FOR KEY SHARE로 잡고 적으므로, 여기서 먼저 잡아야 그 보고가 정리 뒤로 밀려
        // 「없음」을 본다(ThumbnailRepository.saveIfTargetExists).
        List<String> cardIds = jdbc.queryForList(
                "SELECT id::text FROM jump_cards WHERE stream_id = ANY(?) ORDER BY id FOR UPDATE", String.class, streams);

        List<String> prefixes = new ArrayList<>();
        clipIds.forEach(id -> {
            prefixes.add("clips/" + id + "/");
            prefixes.add("thumbnails/clip/" + id + ".jpg");
        });
        cardIds.forEach(id -> prefixes.add("thumbnails/card/" + id + ".jpg"));
        streamIds.forEach(id -> prefixes.add("thumbnails/live/" + id + ".jpg"));
        // 사용자가 올린 썸네일 그림(POK-291). 영상 번호가 아니라 스트리머 접두사 하나다: 렌더가 롤백돼 아무 줄도 안 가리키는
        // 그림까지 덮는다. 방송이 하나도 없어도 넣는다(그림은 방송 줄보다 오래 남을 수 있다).
        prefixes.add(UploadThumbnailStore.prefixOf(streamerId));

        jdbc.update("""
                UPDATE purged_streamers
                   SET pending_prefixes = ARRAY(SELECT DISTINCT unnest(pending_prefixes || ?::text[])),
                       segment_keys = ARRAY(SELECT DISTINCT unnest(segment_keys || ?::text[]))
                 WHERE streamer_id = ?""", textArray(prefixes), textArray(ingestKeys), streamerId);

        Array clips = textArray(clipIds);
        jdbc.update("""
                DELETE FROM thumbnails
                 WHERE (kind = 'live' AND target_id = ANY(?))
                    OR (kind = 'card' AND target_id = ANY(?))
                    OR (kind = 'clip' AND target_id = ANY(?))""", streams, textArray(cardIds), clips);
        jdbc.update("DELETE FROM render_job_events WHERE job_id IN "
                + "(SELECT id FROM render_jobs WHERE clip_id = ANY(CAST(? AS bigint[])))", clips);
        jdbc.update("DELETE FROM render_jobs WHERE clip_id = ANY(CAST(? AS bigint[]))", clips);
        jdbc.update("DELETE FROM clip_uploads WHERE clip_id = ANY(CAST(? AS bigint[]))", clips);
        jdbc.update("DELETE FROM clips WHERE stream_id = ANY(?)", streams);
        // 「렌더 뒤 업로드」 의도(POK-291)는 편집본의 자식이다. 잠그지 않고 지우기만 한다: 렌더 보고(render_jobs → clips →
        // upload_requests)와 같은 순서라 교착이 없고, 새 의도는 방송 줄 KEY SHARE를 먼저 잡아 위 FOR UPDATE 뒤로 밀린다.
        jdbc.update("DELETE FROM upload_requests WHERE recipe_id IN (SELECT id FROM recipes WHERE stream_id = ANY(?))", streams);
        jdbc.update("DELETE FROM recipes WHERE stream_id = ANY(?)", streams);
        jdbc.update("DELETE FROM jump_cards WHERE stream_id = ANY(?)", streams);
        jdbc.update("DELETE FROM broadcast_events WHERE stream_id = ANY(?)", streams);
        jdbc.update("DELETE FROM broadcasts WHERE stream_id = ANY(?)", streams);
        return new Detached(streamIds.size(), clipIds.size(), cardIds.size());
    }

    public List<String> pendingPrefixes(String streamerId) {
        return jdbc.queryForObject("SELECT pending_prefixes FROM purged_streamers WHERE streamer_id = ?",
                (rs, n) -> List.of((String[]) rs.getArray(1).getArray()), streamerId);
    }

    public void prefixDone(String streamerId, String prefix) {
        jdbc.update("UPDATE purged_streamers SET pending_prefixes = array_remove(pending_prefixes, ?) "
                + "WHERE streamer_id = ?", prefix, streamerId);
    }

    public void clearPrefixes(String streamerId) {
        jdbc.update("UPDATE purged_streamers SET pending_prefixes = '{}' WHERE streamer_id = ?", streamerId);
    }

    public List<String> segmentKeys(String streamerId) {
        return jdbc.queryForObject("SELECT segment_keys FROM purged_streamers WHERE streamer_id = ?",
                (rs, n) -> List.of((String[]) rs.getArray(1).getArray()), streamerId);
    }

    /** 1번 장부가 이 DB에 있나. media 없는 배포(CI·로컬 일부)에는 없다. */
    public boolean tableExists(String table) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean.class, table));
    }

    /** 녹화 조각 한 묶음. 재생용 사본 키가 있으면 같이 싣는다. */
    public List<SegmentRow> segmentBatch(List<String> keys, int limit) {
        return jdbc.query("""
                SELECT stream_id, seq, s3_key, playback_s3_key FROM stream_segments
                 WHERE stream_id = ANY(?) ORDER BY stream_id, seq LIMIT ?""",
                (rs, n) -> new SegmentRow(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4)),
                textArray(keys), limit);
    }

    public int deleteSegments(List<SegmentRow> rows) {
        return jdbc.update("""
                DELETE FROM stream_segments s
                 USING unnest(?::text[], CAST(? AS bigint[])) AS d(stream_id, seq)
                 WHERE s.stream_id = d.stream_id AND s.seq = d.seq""",
                textArray(rows.stream().map(SegmentRow::streamId).toList()),
                textArray(rows.stream().map(r -> Long.toString(r.seq())).toList()));
    }

    /** 회차 머리 파일(init). 회차 줄(1번 stream_sessions)은 남긴다: 다른 1번 표가 외래키로 걸려 있고 회원 정보가 없다. */
    public List<String> initKeys(List<String> keys) {
        return jdbc.queryForList("SELECT init_s3_key FROM stream_sessions WHERE stream_id = ANY(?) "
                + "AND init_s3_key IS NOT NULL", String.class, textArray(keys));
    }

    public void complete(String streamerId, Instant now) {
        jdbc.update("UPDATE purged_streamers SET completed_at = ? WHERE streamer_id = ?",
                Timestamp.from(now), streamerId);
    }

    private Array textArray(List<String> values) {
        return jdbc.execute((ConnectionCallback<Array>) c -> c.createArrayOf("text", values.toArray()));
    }

    public record Detached(int broadcasts, int clips, int cards) {
    }

    public record SegmentRow(String streamId, long seq, String s3Key, String playbackS3Key) {
    }
}
