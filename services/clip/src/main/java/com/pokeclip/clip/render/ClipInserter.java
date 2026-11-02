package com.pokeclip.clip.render;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.OptionalLong;

/**
 * 완성 영상 줄을 <b>선점하며</b> 넣는다. 같은 편집본 같은 판의 진행 중 줄이 이미 있으면 안 넣고 빈손으로 돌아온다 —
 * 예외가 아니라 <b>반환값</b>으로 가른다(POK-82 함정: 예외로 가르면 저장 실패와 중복이 같은 예외가 된다).
 *
 * <p>{@code ON CONFLICT}의 대상이 <b>부분 색인</b>이라 그 술어를 그대로 적어야 한다 — 빠뜨리면 PostgreSQL이
 * 「일치하는 유일 제약이 없다」로 거부한다.
 */
@Repository
public class ClipInserter {

    private static final String INSERT_IF_NO_OPEN = """
            INSERT INTO clips (stream_id, recipe_id, recipe_version, requested_by, status, updated_at)
            VALUES (?, ?, ?, ?, 'queued', now())
            ON CONFLICT (recipe_id, recipe_version) WHERE status IN ('queued', 'rendering') DO NOTHING
            RETURNING id""";

    private final JdbcTemplate jdbc;

    ClipInserter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return 새로 넣은 줄의 번호. 진행 중 줄이 이미 있으면 비어 있다 */
    public OptionalLong insertIfNoOpen(String streamId, long recipeId, int recipeVersion, String requestedBy) {
        List<Long> ids = jdbc.query(INSERT_IF_NO_OPEN, (rs, i) -> rs.getLong("id"),
                streamId, recipeId, recipeVersion, requestedBy);
        return ids.isEmpty() ? OptionalLong.empty() : OptionalLong.of(ids.getFirst());
    }
}
