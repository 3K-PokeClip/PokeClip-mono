package com.pokeclip.clip.segment;

import com.pokeclip.clip.broadcast.Broadcast;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 방송마다 <b>시각 기준점</b>을 조각 장부에서 읽는다(POK-255). 기준점 = 카드·조각의 ms({@code streamTimestampMs}·
 * {@code window}·{@code start_pts_ms})가 0이 되는 순간의 절대 시각이다. 웹은 이 값에 ms를 더해 편집본 컷(절대 시각)을 만들고,
 * 지난 방송 채팅 시점·차트 자리를 영상과 맞춘다. 전에는 로컬 전용 녹화 재생 서버의 첫 구간 시각을 썼고, 그것이 없으면(dev·운영)
 * 방송 시작 편지 시각으로 대신해 <b>수십 초</b> 어긋났다(2026-09-17 실측 32초).
 *
 * <p><b>계산</b>: 조각 하나의 절대 시각({@code playback_pdt}, 아직 빈 줄은 {@code start_wall_utc}) − 그 조각의
 * {@code start_pts_ms}. 조각이 이어지는 동안은 어느 조각으로 재도 같은 값이다({@code playback_pdt}는 조각 길이를 그대로 더해
 * 간다, 렌더 일꾼 {@code ClipRenderer.spacing} 주석의 실측). 그래서 <b>{@code start_pts_ms}가 가장 작은 조각</b>(첫 조각)으로 잰다 —
 * 녹화 재생 서버의 첫 구간과 같은 자리다.
 *
 * <p><b>방송의 시간 안 조각만 본다.</b> 장부의 {@code stream_id}는 지금 스트림키라(POK-233 전) 같은 스트리머의 다른 방송 조각도
 * 같은 이름으로 쌓인다. 시작 편지 앞 10분 ~ 종료 뒤 10분으로 거른다(시작 편지와 첫 조각은 수십 초 갈린다). 조각이 없으면 기준점도
 * 없다({@code null}) — 지어내지 않는다. 화면은 그때 방송 시작 시각으로 대신한다.
 *
 * <p>이 표의 소유는 1번(Media)이고 clip은 <b>읽기만</b> 한다({@link StreamSegmentReader}와 같다).
 */
@Component
public class TimelineOriginReader {

    /** 시작 편지·종료 편지와 조각 시각의 여유. 방송 사이 간격이 이보다 짧으면 앞 방송 끝 조각이 섞일 수 있지만, 그 조각은 pts가 커서 뽑히지 않는다 */
    static final Duration MARGIN = Duration.ofMinutes(10);

    /**
     * 페이지의 방송 전부를 한 번에 잰다(방송마다 한 번씩 물으면 한 장에 최대 100번 왕복이다). 방송마다 조건에 맞는 조각 중
     * {@code start_pts_ms}가 가장 작은 하나를 {@code LATERAL}로 고른다.
     */
    private static final String ORIGINS = """
            SELECT b.stream_id, o.origin_ms
              FROM unnest(?::text[], ?::timestamptz[], ?::timestamptz[]) AS b(stream_id, lo, hi)
              CROSS JOIN LATERAL (
                    SELECT (EXTRACT(EPOCH FROM COALESCE(s.playback_pdt, s.start_wall_utc)) * 1000)::bigint
                           - s.start_pts_ms AS origin_ms
                      FROM stream_segments s
                     WHERE s.stream_id = b.stream_id
                       AND (b.lo IS NULL OR COALESCE(s.playback_pdt, s.start_wall_utc) >= b.lo)
                       AND (b.hi IS NULL OR COALESCE(s.playback_pdt, s.start_wall_utc) <= b.hi)
                     ORDER BY s.start_pts_ms, s.seq
                     LIMIT 1) o""";

    private final JdbcTemplate jdbc;

    TimelineOriginReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return 방송 번호 → 기준점. 조각이 없는 방송은 맵에 없다 */
    public Map<String, Instant> originsOf(List<Broadcast> broadcasts) {
        Map<String, Instant> origins = new HashMap<>();
        if (broadcasts.isEmpty()) {
            return origins;
        }
        String[] ids = new String[broadcasts.size()];
        Timestamp[] lows = new Timestamp[broadcasts.size()];
        Timestamp[] highs = new Timestamp[broadcasts.size()];
        for (int i = 0; i < broadcasts.size(); i++) {
            Broadcast b = broadcasts.get(i);
            ids[i] = b.getStreamId();
            lows[i] = b.getStartedAt() == null ? null : Timestamp.from(b.getStartedAt().minus(MARGIN));
            highs[i] = b.getEndedAt() == null ? null : Timestamp.from(b.getEndedAt().plus(MARGIN));
        }
        jdbc.query(con -> {
            var ps = con.prepareStatement(ORIGINS);
            ps.setArray(1, con.createArrayOf("text", ids));
            ps.setArray(2, con.createArrayOf("timestamptz", lows));
            ps.setArray(3, con.createArrayOf("timestamptz", highs));
            return ps;
        }, rs -> {
            origins.put(rs.getString("stream_id"), Instant.ofEpochMilli(rs.getLong("origin_ms")));
        });
        return origins;
    }
}
