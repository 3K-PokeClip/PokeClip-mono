package com.pokeclip.clip.upload;

import com.pokeclip.clip.upload.UploadInfo.Thumbnail;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 「렌더 뒤 업로드」 의도({@code upload_requests}, POK-291). 편집본 판 하나에 한 줄이고, 같은 판에 다시 누르면 덮어쓴다.
 * JPA를 안 쓴다: 덮어쓰기가 {@code ON CONFLICT DO UPDATE} 한 문장이라 엔티티로는 두 왕복이 된다.
 */
@Repository
public class UploadRequestStore {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    private static final String UPSERT = """
            INSERT INTO upload_requests (recipe_id, recipe_version, requested_by, title, description, tags, privacy_status,
                                         made_for_kids, thumbnail_source, thumbnail_offset_ms, thumbnail_s3_key,
                                         thumbnail_content_type, updated_at)
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (recipe_id, recipe_version) DO UPDATE SET
                requested_by = EXCLUDED.requested_by, title = EXCLUDED.title, description = EXCLUDED.description,
                tags = EXCLUDED.tags, privacy_status = EXCLUDED.privacy_status, made_for_kids = EXCLUDED.made_for_kids,
                thumbnail_source = EXCLUDED.thumbnail_source, thumbnail_offset_ms = EXCLUDED.thumbnail_offset_ms,
                thumbnail_s3_key = EXCLUDED.thumbnail_s3_key, thumbnail_content_type = EXCLUDED.thumbnail_content_type,
                updated_at = now()""";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    UploadRequestStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** 저장된 의도 한 줄. */
    public record Stored(String requestedBy, UploadInfo info) {
    }

    /** 판 하나의 좌표. */
    public record Key(long recipeId, int recipeVersion) {
    }

    /**
     * 마지막 누름이 이긴다. 호출자가 트랜잭션을 연다.
     *
     * <p>먼저 방송 줄을 {@code FOR KEY SHARE}로 잡는다. 탈퇴 정리는 방송 줄을 {@code FOR UPDATE}로 잡고 시작하므로, 정리가 먼저면
     * 여기서 기다리고 이쪽이 먼저면 정리가 이 커밋 뒤에 이 줄까지 보고 지운다. 안 잡으면 정리가 의도 줄을 지운 직후 이 줄이 들어와
     * 편집본 지우기가 외래키로 죽는다(이 표는 편집본만 가리켜 방송 줄을 안 건드린다).
     */
    public void upsert(String streamId, long recipeId, int recipeVersion, String requestedBy, UploadInfo info) {
        lockBroadcast(streamId);
        Thumbnail thumbnail = info.thumbnail();
        jdbc.update(UPSERT, recipeId, recipeVersion, requestedBy, info.title(), info.description(),
                mapper.writeValueAsString(info.tags()), info.privacyStatus(), info.madeForKids(), thumbnail.source(),
                thumbnail.offsetMs(), thumbnail.s3Key(), thumbnail.contentType());
    }

    /**
     * 방송 줄을 {@code FOR KEY SHARE}로 잡는다(위 이유). 「영상 만들기」는 영상 줄을 잠그기 <b>전</b>에 이것부터 부른다: 잠금 순서
     * {@code broadcasts → clips → upload_requests}가 탈퇴 정리({@code broadcasts → render_jobs → clips})와 같은 방향이어야 교착이 없다.
     * 같은 트랜잭션에서 다시 잡아도 된다.
     */
    public void lockBroadcast(String streamId) {
        jdbc.queryForList("SELECT stream_id FROM broadcasts WHERE stream_id = ? FOR KEY SHARE", streamId);
    }

    /**
     * 렌더 성공 트랜잭션이 읽는다. 잠그는 이유: 같은 판에 「영상 만들기」가 동시에 와서 덮어쓰는 중이면 그것이 끝난 값을 읽어야
     * 「마지막 누름」으로 올린다.
     */
    public Optional<Stored> findForUpdate(long recipeId, int recipeVersion) {
        return jdbc.query("SELECT * FROM upload_requests WHERE recipe_id = ? AND recipe_version = ? FOR UPDATE",
                (rs, n) -> new Stored(rs.getString("requested_by"), info(rs)), recipeId, recipeVersion).stream().findFirst();
    }

    /** 화면용 요약을 판 여럿에 한 번에. 없는 판은 맵에 없다. */
    public Map<Key, UploadRequestBrief> briefs(Collection<Key> keys) {
        Map<Key, UploadRequestBrief> found = new HashMap<>();
        if (keys.isEmpty()) {
            return found;
        }
        List<Key> list = List.copyOf(keys);
        Array recipeIds = array("bigint", list.stream().map(Key::recipeId).toArray());
        Array versions = array("integer", list.stream().map(Key::recipeVersion).toArray());
        jdbc.query("""
                SELECT r.recipe_id, r.recipe_version, r.title, r.privacy_status, r.thumbnail_source
                  FROM upload_requests r
                  JOIN unnest(?, ?) AS k(recipe_id, recipe_version)
                    ON r.recipe_id = k.recipe_id AND r.recipe_version = k.recipe_version""",
                rs -> {
                    found.put(new Key(rs.getLong("recipe_id"), rs.getInt("recipe_version")),
                            new UploadRequestBrief(rs.getString("title"), rs.getString("privacy_status"),
                                    rs.getString("thumbnail_source")));
                }, recipeIds, versions);
        return found;
    }

    private UploadInfo info(ResultSet rs) throws SQLException {
        Thumbnail thumbnail = new Thumbnail(rs.getString("thumbnail_source"), rs.getObject("thumbnail_offset_ms", Long.class),
                rs.getString("thumbnail_s3_key"), rs.getString("thumbnail_content_type"));
        return new UploadInfo(rs.getString("title"), rs.getString("description"),
                mapper.readValue(rs.getString("tags"), STRINGS), rs.getString("privacy_status"),
                rs.getBoolean("made_for_kids"), thumbnail);
    }

    private Array array(String type, Object[] values) {
        return jdbc.execute((ConnectionCallback<Array>) c -> c.createArrayOf(type, values));
    }
}
