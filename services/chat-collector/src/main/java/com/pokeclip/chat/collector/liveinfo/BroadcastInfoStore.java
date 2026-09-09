package com.pokeclip.chat.collector.liveinfo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * {@code broadcast_info} 읽고 쓰기(POK-234 태스크 10). 쓰는 쪽은 PR-C의 수집기가,
 * 읽는 쪽은 {@link BroadcastInfoController}가 쓴다.
 *
 * <p><b>제목·태그를 로그에 싣지 않는다.</b> 그래서 이 클래스에는 로거가 없다 —
 * 나중에 로거를 다는 사람이 「무엇을 실을 수 있나」를 여기서 다시 판단하게 된다.
 */
@Component
public class BroadcastInfoStore {

    private static final String COLUMNS =
            "stream_id, channel_id, observed_at, live_title, tags, category, concurrent_users";

    private static final String INSERT = """
            INSERT INTO broadcast_info
              (stream_id, channel_id, observed_at, live_title, tags, category, concurrent_users)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    /**
     * 최신 한 줄. <b>{@code id}까지 보는 이유</b>는 같은 시각에 두 줄이 들어갈 수 있기 때문이다
     * (관측 주기가 짧아지거나 시계가 되감기면). 시각만 보면 어느 줄이 나올지 실행마다 갈린다.
     */
    private static final String LATEST = "SELECT " + COLUMNS + """
             FROM broadcast_info
            WHERE stream_id = ?
            ORDER BY observed_at DESC, id DESC
            LIMIT 1
            """;

    /**
     * 구간 훑기. <b>내림차순으로 뽑아 뒤집는다</b> — 상한에 걸릴 때 잘려 나가는 쪽이
     * <b>먼 과거</b>여야 한다. 오름차순 {@code LIMIT}으로 자르면 최근이 통째로 사라지는데,
     * 화면은 그것을 「최근에 아무 관측이 없었다」로 그린다.
     */
    private static final String SERIES = "SELECT " + COLUMNS + """
             FROM broadcast_info
            WHERE stream_id = ? AND observed_at >= ?
            ORDER BY observed_at DESC, id DESC
            LIMIT ?
            """;

    private static final RowMapper<BroadcastInfo> MAPPER = BroadcastInfoStore::toInfo;

    private final JdbcTemplate jdbc;

    public BroadcastInfoStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * {@code tags}가 {@code TEXT[]}라 {@link java.sql.Connection#createArrayOf}가 필요하고,
     * 그것을 얻으려고 {@code PreparedStatementCreator}를 쓴다 — 배열을 문자열로 만들어 넘기면
     * 태그에 쉼표·중괄호가 든 순간 조용히 갈라진다.
     *
     * <p>🔴 <b>{@code null} 원소를 걸러서 넣는다</b>({@link #nonNullTags}). PostgreSQL
     * {@code TEXT[]}는 원소 NULL을 <b>허용</b>하므로 그대로 넣으면 저장은 성공하고
     * <b>읽기가 터진다</b> — 아래 {@link #toInfo}의 대칭 처방과 같이 본다.
     */
    public void insert(BroadcastInfo info) {
        jdbc.update(connection -> {
            java.sql.PreparedStatement ps = connection.prepareStatement(INSERT);
            ps.setString(1, info.streamId());
            ps.setString(2, info.channelId());
            ps.setTimestamp(3, Timestamp.from(info.observedAt()));
            ps.setString(4, info.title());
            ps.setArray(5, connection.createArrayOf("text", nonNullTags(info.tags())));
            ps.setString(6, info.category());
            if (info.viewers() == null) {
                ps.setNull(7, java.sql.Types.INTEGER);
            } else {
                ps.setInt(7, info.viewers());
            }
            return ps;
        });
    }

    public Optional<BroadcastInfo> latest(String streamId) {
        return jdbc.query(LATEST, MAPPER, streamId).stream().findFirst();
    }

    /** @param max 이 값보다 많으면 <b>먼 과거부터</b> 잘린다(위 {@code SERIES} 주석) */
    public List<BroadcastInfo> series(String streamId, Instant since, int max) {
        List<BroadcastInfo> newestFirst =
                new ArrayList<>(jdbc.query(SERIES, MAPPER, streamId, Timestamp.from(since), max));
        Collections.reverse(newestFirst);
        return newestFirst;
    }

    /**
     * 🔴 <b>쓰는 쪽과 읽는 쪽을 나란히 고쳤다.</b> 한쪽만 막으면 반쪽이 남는다 —
     * 읽기만 고치면 앞으로 들어올 NULL 원소가 표에 계속 쌓이고, 쓰기만 고치면
     * <b>이미 들어간 행</b>이 영영 500을 낸다.
     *
     * <p>버리는 쪽으로 정한 이유: 이름 없는 태그는 화면에 그릴 것이 없다. 빈 문자열로
     * 바꾸면 화면에 빈 딱지가 뜨는데 그것은 「태그가 있다」는 거짓 신호다.
     */
    private static String[] nonNullTags(List<String> tags) {
        if (tags == null) {
            return new String[0];
        }
        return tags.stream().filter(java.util.Objects::nonNull).toArray(String[]::new);
    }

    /**
     * 🔴 <b>{@code List.of}를 쓰지 않는다 — 그것이 원소 {@code null}에 NPE를 던진다</b>
     * (실 PG 재현: {@code ARRAY['a',NULL]::text[]} 한 줄이면 {@code latest}가 통째로 500).
     * 저장이 성공한 뒤 읽기만 터지므로 <b>그 행이 최신인 동안 그 방송의 창구가 영구히 죽는다</b>,
     * 그리고 clip이 5xx를 {@code collector_unavailable}로 접어 화면에는
     * 「수집 서버가 아프다」로 보인다 — 실제로는 한 줄의 데이터 이상이다.
     */
    private static BroadcastInfo toInfo(ResultSet rs, int rowNum) throws SQLException {
        Array tags = rs.getArray("tags");
        return new BroadcastInfo(
                rs.getString("stream_id"),
                rs.getString("channel_id"),
                rs.getTimestamp("observed_at").toInstant(),
                rs.getString("live_title"),
                tags == null ? List.of()
                        : List.of(nonNullTags(java.util.Arrays.asList((String[]) tags.getArray()))),
                rs.getString("category"),
                rs.getObject("concurrent_users", Integer.class));
    }
}
