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
 * <p><b>계산</b>: 방송의 첫 조각의 절대 시각({@code playback_pdt}, 아직 빈 줄은 {@code start_wall_utc}) − 그 조각의
 * {@code start_pts_ms}. {@code playback_pdt}와 {@code start_pts_ms}는 둘 다 조각 길이를 그대로 더해 가서 <b>한 재생 회차 안에서는
 * 이 차가 일정하다</b>(2026-09-28 로컬 실방송 135조각 실측: 차이 0ms. 벽시계로 재면 9분에 2.1초 흔들렸다). 렌더가 자르는 축이
 * {@code playback_pdt}라 이 값에 카드 ms를 더하면 그 영상 프레임이 나온다.
 *
 * <p>🔴 <b>한계 — 셋 다 같은 뿌리(재생 회차가 바뀌면 차가 달라진다)</b>: ① 방송 중 재접속으로 회차가 끊기면 {@code playback_pdt}가
 * 벽시계로 다시 맞춰져 그 뒤 카드가 앞 회차에서 쌓인 차만큼(9분에 2초 꼴) 어긋난다. ② 같은 키로 재시작 편지가 오면 방송 줄의
 * {@code started_at}이 새 시각으로 덮여 기준점이 재시작 쪽으로 옮겨가고, 그 전 카드가 같은 만큼 어긋난다. ③ 끊고 2분 안에 다시
 * 켜면 앞 꼬리 조각이 섞인다. 시작 편지와 첫 조각의 관계는 1번 발행 코드가 이 저장소에 없어 코드로 증명하지 못했고 근거는 실측 한 번
 * (첫 조각이 32초 뒤)이다. 조각마다 바꾸는 것은 별도 카드.
 *
 * <p><b>방송의 시간 안 첫 조각을 쓴다.</b> 장부의 {@code stream_id}는 지금 스트림키라(POK-233 전) 같은 스트리머의 다른 방송 조각도
 * 같은 이름으로 쌓이고, {@code start_pts_ms}는 방송을 넘어 계속 커진다(media가 마지막 행에서 이어 받는다). 그래서 앞 방송 꼬리 조각이
 * 섞이지 않게 <b>시작 편지 앞 2분 이후의 첫 조각</b>(seq 순)을 쓴다. 시작 시각을 모르면 재지 않는다. 지금은 스트림키당 방송 줄이 하나라(clip
 * {@code broadcasts.stream_id} UNIQUE) 섞일 일이 없지만, POK-233(회차 번호) 때 {@code session_id}로 바꿔야 한다. 조각이 없으면
 * 기준점도 없다({@code null}): 지어내지 않는다. 화면은 그때 방송 시작 시각으로 대신한다.
 *
 * <p>범위와 순서를 <b>벽시계 칸으로</b> 잡는다. {@code (stream_id, start_wall_utc)} 색인의 범위 조건이 되고, 벽시계와 재생 시각은
 * 수 초 안이라 여유에 든다. 🔴 모양이 계획을 가른다(로컬 리뷰 1·2라운드, 한 키 30만 줄 실측): 범위를 재생 시각({@code COALESCE(pdt, wall)})
 * 으로 걸거나 {@code (b.lo IS NULL OR wall >= b.lo)}처럼 OR로 걸면 색인 조건이 못 되고, {@code ORDER BY seq}면 PK를 처음부터
 * 훑는다(버퍼 78,860 · 241ms). 빈 경계를 {@code ±infinity}로 채우고 벽시계 순으로 정렬하면 색인 범위 스캔이다(버퍼 89 · 0.23ms).
 * 벽시계는 조각 경계에서 조금 거꾸로 갈 수 있지만 첫 조각을 고르는 데는 충분하다.
 *
 * <p>이 표의 소유는 1번(Media)이고 clip은 <b>읽기만</b> 한다({@link StreamSegmentReader}와 같다).
 */
@Component
public class TimelineOriginReader {

    /** 시작 편지보다 먼저 쓰인 조각을 잡는 여유. 넓으면 앞 방송 꼬리 조각이 섞인다(pts가 방송을 넘어 이어져 그 조각이 먼저다) */
    static final Duration BEFORE_START = Duration.ofMinutes(2);

    /** 종료 편지 뒤에 올라온 조각을 잡는 여유. 첫 조각만 쓰므로 끝 쪽은 넉넉해도 된다 */
    static final Duration AFTER_END = Duration.ofMinutes(10);

    /**
     * 페이지의 방송 전부를 한 번에 잰다(방송마다 한 번씩 물으면 한 장에 최대 100번 왕복이다). 방송마다 범위 안 첫 조각(seq 순)
     * 하나를 {@code LATERAL}로 고른다.
     */
    static final String ORIGINS = """
            SELECT b.stream_id, o.origin_ms
              FROM unnest(?::text[], ?::timestamptz[], ?::timestamptz[]) AS b(stream_id, lo, hi)
              CROSS JOIN LATERAL (
                    SELECT (EXTRACT(EPOCH FROM COALESCE(s.playback_pdt, s.start_wall_utc)) * 1000)::bigint
                           - s.start_pts_ms AS origin_ms
                      FROM stream_segments s
                     WHERE s.stream_id = b.stream_id
                       AND s.start_wall_utc >= COALESCE(b.lo, '-infinity'::timestamptz)
                       AND s.start_wall_utc <= COALESCE(b.hi, 'infinity'::timestamptz)
                     ORDER BY s.start_wall_utc, s.seq
                     LIMIT 1) o""";

    private final JdbcTemplate jdbc;

    TimelineOriginReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return 방송 번호 → 기준점. 조각이 없는 방송과 <b>시작 시각을 모르는 방송</b>(종료 편지가 먼저 온 자리표시)은 맵에 없다.
     *         시작 시각이 없으면 아래 경계가 없어 같은 키의 앞 방송 조각이 첫 조각으로 뽑힌다(PR #202 codex)
     */
    public Map<String, Instant> originsOf(List<Broadcast> page) {
        Map<String, Instant> origins = new HashMap<>();
        List<Broadcast> broadcasts = page.stream().filter(b -> b.getStartedAt() != null).toList();
        if (broadcasts.isEmpty()) {
            return origins;
        }
        String[] ids = new String[broadcasts.size()];
        Timestamp[] lows = new Timestamp[broadcasts.size()];
        Timestamp[] highs = new Timestamp[broadcasts.size()];
        for (int i = 0; i < broadcasts.size(); i++) {
            Broadcast b = broadcasts.get(i);
            ids[i] = b.getStreamId();
            lows[i] = Timestamp.from(b.getStartedAt().minus(BEFORE_START));
            highs[i] = b.getEndedAt() == null ? null : Timestamp.from(b.getEndedAt().plus(AFTER_END));
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
