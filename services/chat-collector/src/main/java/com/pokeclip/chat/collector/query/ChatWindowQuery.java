package com.pokeclip.chat.collector.query;

import com.pokeclip.chat.collector.query.ChatWindowCursor.Cursor;
import com.pokeclip.chat.collector.sync.SyncProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 시각 범위로 채팅과 후원을 한 목록으로 준다(POK-234).
 *
 * <h2>🔴 시각 축 — 두 창구가 같은 규칙을 쓴다 (계획 검증 F4)</h2>
 * <table>
 *   <tr><th>무엇</th><th>축</th></tr>
 *   <tr><td>요청 {@code from}·{@code to}</td><td><b>화면 축</b>(되감기 재생축 절대시각)</td></tr>
 *   <tr><td>표를 찾을 때</td><td>{@code from + appliedOffsetMs} ~ {@code to + appliedOffsetMs}</td></tr>
 *   <tr><td>응답의 모든 시각</td><td><b>표 축(원본) 그대로.</b> 화면 위치는 {@code 시각 − appliedOffsetMs}</td></tr>
 * </table>
 * 차트({@link ChatChartQuery})도 같다. <b>한쪽만 미리 빼서 내보내면</b> 프론트가 한쪽만 되돌려
 * 3.9초 어긋난다 — 계약 한 줄로 적히는 규칙이 하나여야 하는 이유다.
 *
 * <h2>정렬과 커서</h2>
 * 정렬은 {@code (시각, 종류, id)} 오름차순이고 {@code chat < donation}이다. 표가 둘이라
 * 한 축에 세우는 일은 <b>우리가</b> 한다 — DB의 {@code UNION ALL}로 묶지 않은 이유는 두 표의
 * 시각 축이 다르고(문항 9) 색인도 각자 타는 편이 낫기 때문이다.
 */
@Component
public class ChatWindowQuery {

    /**
     * <b>package-private인 이유는 검사가 이 문자열 자체를 {@code EXPLAIN} 하기 위해서다</b>
     * (문항 8). 검사가 SQL을 손으로 베끼면 사본만 색인을 타는지 재고 운영 질의는 아무도 안 본다 —
     * POK-219가 그렇게 데였다.
     *
     * <p>{@code (message_time, id) > (?, ?)}가 커서다. 캐스트를 명시하는 것은 행 비교의
     * 파라미터 타입을 PostgreSQL이 못 정하는 경우를 없애려는 것이다.
     */
    static final String CHATS = """
            SELECT 'c' AS kind, id, message_time AS t, nickname, sender_channel_id, user_role,
                   content, NULL::bigint AS amount, NULL::varchar AS dtype
              FROM chat_messages
             WHERE stream_id = ? AND message_time >= ? AND message_time < ?
               AND (message_time, id) > (?::timestamptz, ?::bigint)
             ORDER BY message_time, id
             LIMIT ?
            """;

    static final String DONATIONS = """
            SELECT 'd' AS kind, id, received_at AS t, donator_nickname, donator_channel_id,
                   NULL::varchar AS user_role, donation_text, pay_amount, donation_type
              FROM chat_donations
             WHERE stream_id = ? AND received_at >= ? AND received_at < ?
               AND (received_at, id) > (?::timestamptz, ?::bigint)
             ORDER BY received_at, id
             LIMIT ?
            """;

    static final String CHAT = "chat";
    static final String DONATION = "donation";

    private static final RowMapper<ChatWindowItem> MAPPER = ChatWindowQuery::toItem;

    private final JdbcTemplate jdbc;
    private final SyncProperties sync;

    public ChatWindowQuery(JdbcTemplate jdbc, SyncProperties sync) {
        this.jdbc = jdbc;
        this.sync = sync;
    }

    /**
     * @param kinds {@code chat}·{@code donation} 중 원하는 것. 둘 다면 한 목록으로 병합한다
     * @param limit 한 장의 최대 건수. 상한 판정은 창구가 이미 했다
     * @param after 앞 장의 마지막 항목. 첫 장이면 {@code null}
     */
    public ChatWindowPage find(String streamId, String channelId, WindowRequest window,
                               Set<String> kinds, int limit, Cursor after) {
        long offset = sync.offsetFor(channelId);
        Timestamp lo = Timestamp.from(window.from().plusMillis(offset));
        Timestamp hi = Timestamp.from(window.to().plusMillis(offset));

        List<ChatWindowItem> merged = new ArrayList<>();
        if (kinds.contains(CHAT)) {
            merged.addAll(rows(CHATS, streamId, lo, hi, after, "c", limit + 1));
        }
        if (kinds.contains(DONATION)) {
            merged.addAll(rows(DONATIONS, streamId, lo, hi, after, "d", limit + 1));
        }
        merged.sort(Comparator.comparing(ChatWindowItem::time)
                .thenComparing(ChatWindowItem::kind)
                .thenComparingLong(ChatWindowItem::id));

        boolean more = merged.size() > limit;
        List<ChatWindowItem> page = List.copyOf(merged.subList(0, Math.min(limit, merged.size())));
        String next = null;
        if (more) {
            ChatWindowItem last = page.getLast();
            next = ChatWindowCursor.encode(last.time().toEpochMilli(),
                    CHAT.equals(last.kind()) ? "c" : "d", last.id());
        }
        return new ChatWindowPage(page, next, offset);
    }

    private List<ChatWindowItem> rows(String sql, String streamId, Timestamp lo, Timestamp hi,
                                      Cursor after, String tableKind, int limit) {
        return jdbc.query(sql, MAPPER, streamId, lo, hi,
                afterTime(after), afterId(after, tableKind), limit);
    }

    /** 첫 장이면 epoch다 — 창의 아래 경계가 이미 걸러 주므로 이 값이 결과를 넓히지 않는다. */
    private static Timestamp afterTime(Cursor after) {
        return after == null ? Timestamp.from(Instant.EPOCH)
                : Timestamp.from(Instant.ofEpochMilli(after.timeMillis()));
    }

    /**
     * 🔴 <b>네 갈래 — 계획 검증 F1(치명).</b> 하나라도 틀리면 재출력 + 페이징 순환이다.
     * 정렬이 {@code (시각, 종류, id)}이고 {@code 'c' < 'd'}이므로,
     * <b>같은 시각의 채팅은 전부 그 시각의 후원보다 앞이다.</b>
     *
     * <table>
     *   <tr><th>커서 종류</th><th>대상 표</th><th>넘길 id</th><th>왜</th></tr>
     *   <tr><td>(없음)</td><td>둘 다</td><td>{@code 0}</td><td>첫 장</td></tr>
     *   <tr><td>{@code c}</td><td>채팅</td><td>{@code id0}</td><td>그 시각 그 id 다음부터</td></tr>
     *   <tr><td>{@code c}</td><td>후원</td><td>{@code 0}</td><td>그 시각 후원은 아직 하나도 안 나갔다</td></tr>
     *   <tr><td><b>{@code d}</b></td><td><b>채팅</b></td><td><b>{@link Long#MAX_VALUE}</b></td>
     *       <td>🔴 그 시각 채팅은 이미 전부 나갔다. id를 그대로 주거나 0을 주면 그 시각 채팅이
     *           통째로 재출력되고 <b>다음 커서가 앞 장 커서로 되돌아가 두 장을 영원히 왕복한다</b>
     *           (실 PG 재현)</td></tr>
     *   <tr><td>{@code d}</td><td>후원</td><td>{@code id0}</td><td>그 시각 그 id 다음부터</td></tr>
     * </table>
     *
     * <p>{@code kinds}가 한쪽뿐인데 커서 종류가 다른 쪽인 경우도 같은 표를 따른다 —
     * 갈래를 따로 두면 「채팅만 달라」는 요청이 커서 규칙을 안 지키게 된다.
     */
    private static long afterId(Cursor after, String tableKind) {
        if (after == null) {
            return 0L;
        }
        if (after.kind().equals(tableKind)) {
            return after.id();
        }
        return "d".equals(after.kind()) ? Long.MAX_VALUE : 0L;
    }

    private static ChatWindowItem toItem(ResultSet rs, int rowNum) throws SQLException {
        boolean chat = "c".equals(rs.getString(1));
        Object amount = rs.getObject(8);
        return new ChatWindowItem(
                chat ? CHAT : DONATION,
                rs.getLong(2),
                rs.getTimestamp(3).toInstant(),
                // 후원은 치지직이 시각을 안 준다 — 우리가 받은 시각뿐이라 축이 다르다(문항 9).
                chat ? "message" : "received",
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                amount == null ? null : rs.getLong(8),
                rs.getString(9));
    }
}
