package com.pokeclip.clip.library;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 보관함 조회. <b>상태를 파생하는 규칙이 여기 SQL 한 곳에 있다</b> — 목록의 거르기와 응답의 {@code status}가 같은 식을
 * 지나야 「거른 값과 보이는 값이 다른」 줄이 안 생긴다. 자바에서 다시 계산하지 않는다.
 *
 * <p>JPA가 아니라 SQL인 이유 — 세 표({@code recipes}·{@code broadcasts}·{@code clips})를 한 번에 읽고 「편집본마다 가장 최근
 * 영상 하나」는 {@code LATERAL}이라야 한 질의로 된다. 엔티티는 번호로 다시 읽는다({@link LibraryService}).
 *
 * <p>정렬·이어받기가 둘 다 편집본 번호다({@code BroadcastRepository.findPage}와 같은 이유) — 「최근 편집순」은 고칠 때마다
 * 자리가 바뀌어 이어받기 기준으로 쓰면 중복·누락이 난다. 화면이 다른 순서를 원하면 한 장 안에서 다시 정렬한다.
 */
@Repository
class LibraryQuery {

    /**
     * 상태 규칙. 지금 판({@code recipe_version})의 영상이 없으면 편집 중이다 — 영상을 만든 뒤 편집본을 또 고쳤으면
     * 옛 영상이 있어도 편집 중이다(그 영상은 {@code latestClip}으로 같이 나가니 화면이 내려받기는 보여줄 수 있다).
     * 주문됨·만드는 중은 화면에서 한 칸이라 {@code rendering}으로 접는다.
     */
    private static final String STATUS_CASE = """
            CASE WHEN c.id IS NULL OR c.recipe_version <> r.recipe_version THEN 'editing'
                 WHEN c.status IN ('queued', 'rendering') THEN 'rendering'
                 ELSE c.status END""";

    private static final String SELECT = """
            SELECT r.id AS recipe_id, r.stream_id, b.status AS broadcast_status, b.started_at, b.ended_at, b.vod_expires_at,
                   c.id AS clip_id, """ + STATUS_CASE + """
             AS library_status
              FROM recipes r
              JOIN broadcasts b ON b.stream_id = r.stream_id
              LEFT JOIN LATERAL (SELECT id, recipe_version, status FROM clips
                                  WHERE recipe_id = r.id ORDER BY id DESC LIMIT 1) c ON TRUE
            """;

    private static final RowMapper<LibraryRow> ROW = (rs, i) -> new LibraryRow(
            rs.getLong("recipe_id"), rs.getString("stream_id"), rs.getString("broadcast_status"),
            instant(rs, "started_at"), instant(rs, "ended_at"), instant(rs, "vod_expires_at"),
            rs.getObject("clip_id", Long.class), rs.getString("library_status"));

    private final NamedParameterJdbcTemplate jdbc;

    LibraryQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 한 장. {@code streamerIds}는 auth가 준 「볼 수 있는 스트리머」 번호를 문자열로 바꾼 것이다(방송 목록과 같은 변환·같은 한계).
     *
     * @param status {@code null}이면 전부
     * @param afterId {@code null}이면 첫 장
     */
    List<LibraryRow> findPage(List<String> streamerIds, LibraryStatus status, Long afterId, int limit) {
        // 상태 조건은 바깥에서 건다 — CASE 식을 WHERE에 한 번 더 적으면 규칙이 두 벌이 된다.
        String sql = "SELECT * FROM (" + SELECT + """
                     WHERE b.streamer_id IN (:streamerIds)
                       AND (CAST(:afterId AS BIGINT) IS NULL OR r.id < CAST(:afterId AS BIGINT))
                ) x
                 WHERE (CAST(:status AS VARCHAR) IS NULL OR x.library_status = CAST(:status AS VARCHAR))
                 ORDER BY x.recipe_id DESC
                 LIMIT :limit
                """;
        return jdbc.query(sql, new MapSqlParameterSource()
                .addValue("streamerIds", streamerIds)
                .addValue("afterId", afterId)
                .addValue("status", status == null ? null : status.param())
                .addValue("limit", limit), ROW);
    }

    /** 편집본 하나. 자격은 안 본다 — 부르는 쪽이 이 줄의 방송으로 판정한다. */
    Optional<LibraryRow> findOne(long recipeId) {
        List<LibraryRow> rows = jdbc.query(SELECT + " WHERE r.id = :id",
                new MapSqlParameterSource("id", recipeId), ROW);
        return rows.stream().findFirst();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
