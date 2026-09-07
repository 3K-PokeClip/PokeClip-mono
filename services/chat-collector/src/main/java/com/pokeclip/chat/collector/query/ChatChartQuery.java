package com.pokeclip.chat.collector.query;

import com.pokeclip.chat.collector.sync.SyncProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 구간별 채팅·후원 건수(POK-234). 되감기 화면의 채팅량 그래프가 이것을 그린다.
 *
 * <p><b>시각 축은 목록 창구와 같다</b> — {@link ChatWindowQuery} 머리의 표가 정본이다.
 * 여기 복사하지 않는다(한쪽만 낡는다). 요약하면: 창은 {@code +offset}으로 찾고
 * <b>{@code start}는 표 축 그대로 내보낸다.</b>
 *
 * <p>집계를 DB에 맡기는 이유는 8시간 방송의 채팅 수십만 건을 앱으로 끌어오지 않으려는 것이다.
 * 대신 <b>빈 구간은 DB가 안 준다</b>(GROUP BY는 없는 것을 못 센다) — 그래서 앱이 미리 채운다.
 */
@Component
public class ChatChartQuery {

    /**
     * <b>package-private인 이유는 검사가 이 문자열 자체를 {@code EXPLAIN} 하기 위해서다</b>
     * (문항 8) — 목록 창구와 같은 규칙이다.
     *
     * <p>{@code make_interval(secs => ?::double precision)}에 캐스트를 붙인 것은 이름 붙인 인자에
     * 타입 없는 파라미터를 주면 PostgreSQL이 함수를 못 고르기 때문이다.
     */
    static final String CHATS = """
            SELECT date_bin(make_interval(secs => ?::double precision), message_time, ?) AS b,
                   count(*) AS n
              FROM chat_messages
             WHERE stream_id = ? AND message_time >= ? AND message_time < ?
             GROUP BY b ORDER BY b
            """;

    static final String DONATIONS = """
            SELECT date_bin(make_interval(secs => ?::double precision), received_at, ?) AS b,
                   count(*) AS n
              FROM chat_donations
             WHERE stream_id = ? AND received_at >= ? AND received_at < ?
             GROUP BY b ORDER BY b
            """;

    private final JdbcTemplate jdbc;
    private final SyncProperties sync;

    public ChatChartQuery(JdbcTemplate jdbc, SyncProperties sync) {
        this.jdbc = jdbc;
        this.sync = sync;
    }

    /** @param bucketSeconds 창구가 이미 {5,10,30,60}으로 걸렀다. 점 수 상한도 창구가 본다 */
    public ChatChartPage count(String streamId, String channelId, WindowRequest window, int bucketSeconds) {
        long offset = sync.offsetFor(channelId);
        Instant lo = align(window.from().plusMillis(offset));
        // 🔴 끝도 자른다. 안 자르면 나노초가 실린 to가 마지막 구간 시작보다 「조금」 뒤라
        // 데이터가 있을 수 없는 빈 구간이 하나 더 붙는다(실측: 20초 창에 구간 셋).
        Instant hi = align(window.to().plusMillis(offset));

        Map<Instant, long[]> acc = new TreeMap<>();
        for (Instant t = lo; t.isBefore(hi); t = t.plusSeconds(bucketSeconds)) {
            acc.put(t, new long[2]);
        }
        fill(CHATS, acc, 0, streamId, lo, hi, bucketSeconds);
        fill(DONATIONS, acc, 1, streamId, lo, hi, bucketSeconds);

        List<ChartBucket> buckets = acc.entrySet().stream()
                .map(e -> new ChartBucket(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .toList();
        return new ChatChartPage(bucketSeconds, buckets, offset);
    }

    /**
     * 🔴 <b>{@code computeIfAbsent}다 — 계획 검증 F10.</b> 미리 채운 맵에서 바로 꺼내면
     * 키가 하나만 어긋나도 <b>NPE → 500</b>이다. 아래 {@link #align}이 그 어긋남을 없애지만,
     * 그래도 못 찾은 구간을 <b>버리지 않고 넣는다</b> — 세어진 채팅을 조용히 잃는 것보다
     * 구간이 하나 더 생기는 편이 정직하다.
     */
    private void fill(String sql, Map<Instant, long[]> acc, int slot,
                      String streamId, Instant lo, Instant hi, int bucketSeconds) {
        jdbc.query(sql, rs -> {
            Instant start = align(rs.getTimestamp(1).toInstant());
            acc.computeIfAbsent(start, k -> new long[2])[slot] = rs.getLong(2);
        }, bucketSeconds, Timestamp.from(lo), streamId, Timestamp.from(lo), Timestamp.from(hi));
    }

    /**
     * 🔴 <b>미리 채울 때와 결과를 받을 때 같은 함수로 자른다</b>(계획 검증 F10).
     * {@code Instant.parse}는 나노초를 받는데 PostgreSQL {@code timestamptz}는
     * <b>마이크로초까지만</b> 저장한다 — 안 자르면 {@code date_bin}이 돌려주는 구간 시작이
     * 미리 채운 맵의 키와 안 맞는다.
     *
     * <p>밀리초로 자르는 것은 이 서버의 시각이 전부 밀리초 단위여서다 — 채팅 시각
     * ({@code messageTime}, epoch ms) · 보정값(ms) · 커서(ms)가 모두 그렇다. 잘려 나가는
     * 1밀리초 미만은 창의 시작을 그만큼 앞당기는데, 보정값이 초 단위인 축에서 뜻이 없다.
     */
    private static Instant align(Instant at) {
        return at.truncatedTo(ChronoUnit.MILLIS);
    }
}
