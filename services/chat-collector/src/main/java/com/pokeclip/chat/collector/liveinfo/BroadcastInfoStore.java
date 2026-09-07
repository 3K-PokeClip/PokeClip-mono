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
     */
    public void insert(BroadcastInfo info) {
        jdbc.update(connection -> {
            java.sql.PreparedStatement ps = connection.prepareStatement(INSERT);
            ps.setString(1, info.streamId());
            ps.setString(2, info.channelId());
            ps.setTimestamp(3, Timestamp.from(info.observedAt()));
            ps.setString(4, info.title());
            ps.setArray(5, connection.createArrayOf("text",
                    info.tags() == null ? new String[0] : info.tags().toArray(new String[0])));
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

    private static BroadcastInfo toInfo(ResultSet rs, int rowNum) throws SQLException {
        Array tags = rs.getArray("tags");
        return new BroadcastInfo(
                rs.getString("stream_id"),
                rs.getString("channel_id"),
                rs.getTimestamp("observed_at").toInstant(),
                rs.getString("live_title"),
                tags == null ? List.of() : List.of((String[]) tags.getArray()),
                rs.getString("category"),
                rs.getObject("concurrent_users", Integer.class));
    }
}
