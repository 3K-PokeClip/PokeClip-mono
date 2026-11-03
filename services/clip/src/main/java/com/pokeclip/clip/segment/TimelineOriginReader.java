package com.pokeclip.clip.segment;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.segment.RoundRows.Round;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
 * <p><b>방송의 시간 안 첫 조각을 쓴다.</b> 장부의 {@code stream_id}는 물리 키(오늘은 스트림키)라 같은 스트리머의 다른 방송 조각도
 * 같은 이름으로 쌓이고, {@code start_pts_ms}는 방송을 넘어 계속 커진다(media가 마지막 행에서 이어 받는다). 그래서 앞 방송 꼬리 조각이
 * 섞이지 않게 <b>시작 편지 앞 2분 이후의 첫 조각</b>(seq 순)을 쓴다. 시작 시각을 모르면 재지 않는다. 조각은 방송의 물리 키로 찾는다(POK-233). 회차 번호로 바뀌어도
 * 같은 스트리머의 방송은 같은 물리 키를 나눠 쓰므로 이 시간 경계가 계속 앞뒤 방송을 가른다. 조각이 없으면
 * 기준점도 없다({@code null}): 지어내지 않는다. 화면은 그때 방송 시작 시각으로 대신한다.
 *
 * <p>범위와 순서를 <b>벽시계 칸으로</b> 잡는다. {@code (stream_id, start_wall_utc)} 색인의 범위 조건이 되고, 벽시계와 재생 시각은
 * 수 초 안이라 여유에 든다. 🔴 모양이 계획을 가른다(로컬 리뷰 1·2라운드, 한 키 30만 줄 실측): 범위를 재생 시각({@code COALESCE(pdt, wall)})
 * 으로 걸거나 {@code (b.lo IS NULL OR wall >= b.lo)}처럼 OR로 걸면 색인 조건이 못 되고, {@code ORDER BY seq}면 PK를 처음부터
 * 훑는다(버퍼 78,860 · 241ms). 빈 경계를 {@code ±infinity}로 채우고 벽시계 순으로 정렬하면 색인 범위 스캔이다(버퍼 89 · 0.23ms).
 * 벽시계는 조각 경계에서 조금 거꾸로 갈 수 있지만 첫 조각을 고르는 데는 충분하다.
 *
 * <p><b>회차가 있는 방송은 회차의 첫 귀속 행을 쓴다</b>(POK-196 · r6 S-c1). 행을 고르는 범위만 {@link RoundRows}의 회차로
 * 바꿨고 값의 식은 그대로다 — 회차 머리 앞 비귀속 행과 앞 방송 꼬리가 첫 조각으로 뽑히지 않는다. 위 시간창은 머리 세션 행이 없는
 * 옛 방송의 대체 경로로만 남는다. 🔴 「기준점 + 카드 ms = 목록 PDT」 는 회차 안 {@code PDT − start_pts} 가 상수인 행(판 2 ·
 * c0 이후 판 NULL)에서만 선다 — 출시 이미지가 쓴 판 NULL 행은 아니다(설계 r7 8.2). 그래서 카드 → 플레이어 · 편집기 자리는 이
 * 값이 아니라 카드 응답의 {@code playerAtMs} · {@code fileHead}(행별 변환)로 가고, 이 값은 지난 방송 마크의 왕복과 채팅 ·
 * 카테고리 커서에 남는다.
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
              FROM unnest(?::text[], ?::text[], ?::timestamptz[], ?::timestamptz[]) AS b(stream_id, ingest_key, lo, hi)
              CROSS JOIN LATERAL (
                    SELECT (EXTRACT(EPOCH FROM COALESCE(s.playback_pdt, s.start_wall_utc)) * 1000)::bigint
                           - s.start_pts_ms AS origin_ms
                      FROM public.stream_segments s
                     WHERE s.stream_id = b.ingest_key
                       AND s.start_wall_utc >= COALESCE(b.lo, '-infinity'::timestamptz)
                       AND s.start_wall_utc <= COALESCE(b.hi, 'infinity'::timestamptz)
                     ORDER BY s.start_wall_utc, s.seq
                     LIMIT 1) o""";

    /**
     * 회차가 있는 방송 전부를 한 번에 잰다. 방송마다 회차 세션마다 귀속된 첫 행을 고르고 그 가운데 seq 최소를 쓴다. 회차 세션은
     * (방송 번호, 세션) 쌍 배열로 받는다 — 방송마다 세션 수가 달라 2차원 배열로 못 싣는다. 🔴 세션마다 따로 고르는 모양이
     * 계획을 가른다: 세션 목록을 {@code IN} 으로 걸고 seq 순 첫 행을 찾으면 PK 를 그 키 이력의 처음부터 훑는다(2만 행 시드에서
     * 실측 — {@code RoundRowsPlanTest}). 세션마다 고르면 {@code stream_segments_session_idx (stream_id, session_id, seq)} 의
     * 첫 항목 하나씩이다. 판 NULL 행의 µs 값은 ms 로 <b>내린다</b>({@code floor}) — 계약 6-6-5 {@code ⌊PDT⌋ − start_pts} 와
     * 카드 장면 · 소리 주소의 ms 내림 축에 맞춘다({@code ::bigint} 만 걸면 반올림이라 1ms 갈린다). 위 {@link #ORIGINS} 는
     * develop 식 그대로다.
     */
    static final String ORIGINS_BY_ROUND = """
            SELECT b.stream_id, o.origin_ms
              FROM unnest(?::text[], ?::text[]) AS b(stream_id, ingest_key)
              CROSS JOIN LATERAL (
                    SELECT f.origin_ms
                      FROM unnest(?::text[], ?::text[]) AS m(stream_id, session_id)
                      CROSS JOIN LATERAL (
                            SELECT s.seq,
                                   floor(EXTRACT(EPOCH FROM COALESCE(s.playback_pdt, s.start_wall_utc)) * 1000)::bigint
                                   - s.start_pts_ms AS origin_ms
                              FROM public.stream_segments s
                             WHERE s.stream_id = b.ingest_key AND s.session_id = m.session_id
                             ORDER BY s.seq
                             LIMIT 1) f
                     WHERE m.stream_id = b.stream_id
                     ORDER BY f.seq
                     LIMIT 1) o""";

    private final JdbcTemplate jdbc;
    private final RoundRows rounds;

    TimelineOriginReader(JdbcTemplate jdbc, RoundRows rounds) {
        this.jdbc = jdbc;
        this.rounds = rounds;
    }

    /**
     * 조각은 물리 키({@code ingestKey})로 찾고 맵은 방송 번호로 돌려준다(POK-233). 둘이 갈리는 줄에서 맵을 물리 키로 채우면
     * 목록 응답이 방송 번호로 꺼내다 못 찾는다.
     *
     * @return 방송 번호 → 기준점. 귀속 행(옛 방송은 창 안 조각)이 없는 방송과 <b>회차도 시작 시각도 모르는 방송</b>(종료
     *         편지가 먼저 온 자리표시)은 맵에 없다. 시작 시각이 없으면 아래 경계가 없어 같은 키의 앞 방송 조각이 첫 조각으로
     *         뽑힌다
     */
    public Map<String, Instant> originsOf(List<Broadcast> page) {
        Map<String, Instant> origins = new HashMap<>();
        Map<String, Round> found = rounds.roundsOf(page);
        List<Broadcast> byRound = new ArrayList<>();
        List<Broadcast> byWindow = new ArrayList<>();
        for (Broadcast b : page) {
            Round round = found.get(b.getStreamId());
            if (round instanceof RoundRows.Members) {
                byRound.add(b);
            } else if (round instanceof RoundRows.TimeWindow) {
                byWindow.add(b);
            }
        }
        readByRound(byRound, found, origins);
        readByWindow(byWindow, found, origins);
        return origins;
    }

    private void readByRound(List<Broadcast> broadcasts, Map<String, Round> found, Map<String, Instant> origins) {
        if (broadcasts.isEmpty()) {
            return;
        }
        String[] ids = broadcasts.stream().map(Broadcast::getStreamId).toArray(String[]::new);
        String[] keys = broadcasts.stream().map(Broadcast::ingestKey).toArray(String[]::new);
        List<String> memberOf = new ArrayList<>();
        List<String> memberSession = new ArrayList<>();
        for (Broadcast b : broadcasts) {
            for (String session : ((RoundRows.Members) found.get(b.getStreamId())).sessionIds()) {
                memberOf.add(b.getStreamId());
                memberSession.add(session);
            }
        }
        jdbc.query(con -> {
            var ps = con.prepareStatement(ORIGINS_BY_ROUND);
            ps.setArray(1, con.createArrayOf("text", ids));
            ps.setArray(2, con.createArrayOf("text", keys));
            ps.setArray(3, con.createArrayOf("text", memberOf.toArray()));
            ps.setArray(4, con.createArrayOf("text", memberSession.toArray()));
            return ps;
        }, rs -> {
            origins.put(rs.getString("stream_id"), Instant.ofEpochMilli(rs.getLong("origin_ms")));
        });
    }

    private void readByWindow(List<Broadcast> broadcasts, Map<String, Round> found, Map<String, Instant> origins) {
        if (broadcasts.isEmpty()) {
            return;
        }
        String[] ids = new String[broadcasts.size()];
        String[] keys = new String[broadcasts.size()];
        Timestamp[] lows = new Timestamp[broadcasts.size()];
        Timestamp[] highs = new Timestamp[broadcasts.size()];
        for (int i = 0; i < broadcasts.size(); i++) {
            RoundRows.TimeWindow w = (RoundRows.TimeWindow) found.get(broadcasts.get(i).getStreamId());
            ids[i] = broadcasts.get(i).getStreamId();
            keys[i] = w.ingestKey();
            lows[i] = Timestamp.from(w.lo());
            highs[i] = w.hi() == null ? null : Timestamp.from(w.hi());
        }
        jdbc.query(con -> {
            var ps = con.prepareStatement(ORIGINS);
            ps.setArray(1, con.createArrayOf("text", ids));
            ps.setArray(2, con.createArrayOf("text", keys));
            ps.setArray(3, con.createArrayOf("timestamptz", lows));
            ps.setArray(4, con.createArrayOf("timestamptz", highs));
            return ps;
        }, rs -> {
            origins.put(rs.getString("stream_id"), Instant.ofEpochMilli(rs.getLong("origin_ms")));
        });
    }
}
