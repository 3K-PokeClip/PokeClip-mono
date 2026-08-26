package com.pokeclip.chat.detector.metrics;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
// RECEIVED_SLACK이 쓴다. 계획 검증이 이 줄이 없어 컴파일이 깨지는 것을 잡았다(F1).
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * {@code chat_messages}를 읽는다. <b>쓰지 않는다.</b>
 *
 * <p>그 표는 수집 서버가 만들지만 chat 계열의 공동 소유다 — V301 주석이 "collector가 쓰고
 * detector가 읽는다, 한 소유자의 두 프로세스"라고 못박아 뒀다. 담당이 다른 서버의 표를
 * 직접 읽지 않는다는 규칙(ADR-022)은 <b>담당이 다를 때</b>의 규칙이고 여기 해당하지 않는다.
 *
 * <p><b>수집 경로를 되부르지 않는다</b>(POK-137 완료 조건). 흐름은 한 방향이다.
 */
@Component
public class ChatWindowReader {

    /**
     * 시각 칸이 둘인데 쓰임이 다르다. <b>「최근에 왔나」는 우리 시계({@code received_at})로
     * 재야 한다</b> — {@code message_time}은 치지직 시계라 우리와 어긋날 수 있고, 그 어긋남이
     * 방송을 통째로 안 보이게 만들 수 있다.
     *
     * <p>🔴 <b>기존 인덱스로는 안 된다.</b> {@code idx_chat_messages_stream_received}는
     * {@code (stream_id, received_at)}이라 선두 칸이 {@code stream_id}인데 이 조회는
     * {@code received_at}만 건다 — 계획 검증(F7)이 {@code EXPLAIN}으로 확인했다
     * (60만 행에서 {@code Parallel Seq Scan}, 19ms). 매초 돌고 표는 계속 쌓이며,
     * <b>공유 DB라 이웃 서비스 조회에도 부담이 간다.</b>
     *
     * <p>그래서 수집 서버 대역에 {@code V305}로 인덱스를 새로 만든다(태스크 2).
     * <b>표가 있는 곳에 인덱스도 둔다</b> — 판별 서버 대역에 넣으면 그 표를 보는 사람이
     * 인덱스가 어디서 왔는지 못 찾는다(사용자 결정).
     *
     * <p>🔴 <b>이 내성은 여기까지다 — 아래 {@code COUNT_WINDOWS}에는 없다.</b> 시계가
     * 어긋난 방송은 <b>활성으로 뽑히지만 집계가 0줄</b>이 된다. 자세한 것은 그쪽 javadoc에 적었다.
     * <b>「한쪽이 틀렸다」고 보고 두 조회를 같은 칸으로 통일하지 마라</b> — 칸이 다른 것은
     * 의도이고, 비대칭은 칸이 아니라 <b>범위를 잡는 방식</b>에서 온다.
     */
    private static final String ACTIVE_STREAMS = """
            SELECT DISTINCT stream_id
              FROM chat_messages
             WHERE stream_id IS NOT NULL
               AND received_at >= ?
             ORDER BY stream_id
            """;

    /**
     * <b>WHERE에 시각 칸 둘을 다 건다.</b> {@code received_at}은 인덱스를 타기 위해 넉넉한
     * 범위로, {@code message_time}은 창 경계를 정확히 자르기 위해.
     *
     * <p>{@code received_at}만 쓰면 전달 지연만큼 창이 밀리고, {@code message_time}만 쓰면
     * 인덱스를 못 타 방송 전체를 훑는다.
     *
     * <p>여유 {@code RECEIVED_SLACK}는 「채팅이 찍힌 뒤 우리에게 오기까지」의 상한을 넉넉히
     * 잡은 값이다. 재연결 중이면 전달 지연이 초 단위로 늘 수 있다.
     *
     * <p>🔴 <b>시계 어긋남 내성이 활성 판단과 다르다(비대칭).</b> {@code ACTIVE_STREAMS}는
     * {@code received_at}으로 재서 치지직 시계가 얼마나 어긋나든 방송을 놓치지 않는다.
     * 그런데 여기 넘어오는 {@code from}·{@code to}는 태스크 6이 <b>우리 시계</b>
     * ({@code Instant.now()})에서 만들고 그 폭이 {@code collect-lookback}(기본 1분)이다.
     * 그래서 <b>치지직 시각이 1분 넘게 어긋난 방송은 활성 목록에는 있는데 여기서 0줄이 나온다.</b>
     * 되돌아보는 폭이 계속 앞으로 가므로 <b>나중에 메워지지도 않는다.</b> 오류도 로그도 없다.
     *
     * <p>구조를 안 바꾸는 이유 셋(오케스트레이터 결정, 2026-08-26) — ① 폭을 15분으로 되돌리면
     * 계획 검증 F8이 재발한다(100 방송에서 1초 주기를 못 지킨다, 실측) ② 틀리는 방향이
     * <b>안전한 쪽</b>이다(카드를 안 낸다) ③ 1분 넘는 시계 어긋남이 실제로 일어난다는 실측이
     * 없다(저장소 실측은 중앙값 175ms · −39~−70ms). <b>태스크 6이 「활성인데 집계 0줄」을
     * 로그로 남긴다</b> — 실제로 일어나면 그때 보이게.
     *
     * <p>🔴 <b>{@code from}·{@code to}가 창 눈금에 정렬돼 있어야 한다(호출자 책임).</b>
     * 안 맞으면 양 끝이 <b>부분 창</b>으로 집계되고, {@code ON CONFLICT DO NOTHING}이 그 반쪽
     * 값을 <b>영구 고정</b>한다(뒤에 제대로 세어도 안 덮인다). 전용 DB 재현: 5초 창에
     * {@code from=12:00:02, to=12:00:07}을 주면 눈금 두 개가 각각 2건·1건으로 갈린다.
     * 태스크 6의 {@code collect()}가 {@link WindowGrid#closedWindowsBetween}으로 정렬해서 부른다.
     *
     * <p>🔴 <b>눈금 산식이 자바와 규칙이 다르다.</b> PostgreSQL의 {@code numeric::bigint}는
     * 절사가 아니라 <b>반올림</b>이다(PG 17 실측: {@code 4999.6::bigint = 5000},
     * {@code 4999.4::bigint = 4999}). {@link WindowGrid#floorTo}는 내림이다. 지금은 갈라지지
     * 않는다 — 수집 서버가 {@code Instant.ofEpochMilli}로 <b>ms에 잘라</b> 넣어 소수 ms가
     * 아예 없기 때문이다(1ms 간격 10,001개 표본에서 두 식의 결과가 <b>0건</b> 달랐다).
     * <b>소수 ms가 들어오는 날 살아난다</b> — 그때는 {@code trunc(...)}를 씌워 내림으로 맞춘다
     * (그 수정이 지금 데이터에는 무해한 것도 같은 표본으로 확인했다).
     */
    private static final String COUNT_WINDOWS = """
            SELECT (EXTRACT(EPOCH FROM message_time) * 1000)::bigint / ? * ? AS window_start_ms,
                   count(*)                          AS message_count,
                   count(DISTINCT sender_channel_id) AS chatter_count
              FROM chat_messages
             WHERE stream_id = ?
               AND received_at  >= ? AND received_at  <  ?
               AND message_time >= ? AND message_time <  ?
             GROUP BY 1
             ORDER BY 1
            """;

    /** 채팅이 찍힌 시각과 우리가 받은 시각의 차를 넉넉히 잡은 여유. 실측 중앙값은 175ms다. */
    private static final Duration RECEIVED_SLACK = Duration.ofMinutes(5);

    private final JdbcTemplate jdbc;

    public ChatWindowReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 전달 지연 분포. <b>절댓값을 쓰지 않는다</b> — 지연의 부호는 환경에 따라 뒤집히고
     * (수집 서버가 시계 오프셋 혼입으로 −39~−70ms를 실측했다), 음수를 접으면 멀쩡한 채팅이
     * 「늦었다」로 잡힌다. 늦은 것은 <b>양수 방향으로만</b> 늦은 것이다.
     *
     * <p>🔴 <b>줄이 없을 때 0을 실어 보내지 않는다.</b> 예전에는 {@code COALESCE(MAX(...), 0)}
     * 이었는데, 그러면 {@link LateArrivalCount#EMPTY}가 {@code Long.MIN_VALUE}인 것이
     * <b>합산에서 무력해진다</b> — {@code Math.max(MIN_VALUE, 0)}이 0이라, 지연이 전부 음수인
     * 방송이 섞여 있어도 최댓값이 0으로 깔린다. 계획 검증 F12를 자바 쪽만 고치고
     * <b>SQL 쪽을 안 봐서</b> 한 자리가 남아 있었다(감사가 읽다 찾았다).
     *
     * <p><b>그때 「도달 못 한다」던 이유가 설정값 우연이었다</b> — {@code late-report-interval}(10분)이
     * {@code active-stream-window}(60초)보다 커서 활성 방송은 늘 줄이 있었다. 두 설정 사이에
     * 교차 검사가 없어 앞의 값을 뒤의 값 아래로 내리면 되살아난다. <b>지금은 뿌리를 고쳤으므로
     * 그 관계에 기대지 않는다.</b>
     */
    private static final String LATE_ARRIVALS = """
            SELECT count(*)                                            AS total,
                   count(*) FILTER (WHERE delay_ms > ?)                AS beyond_grace,
                   count(*) FILTER (WHERE delay_ms > ?)                AS beyond_window_and_grace,
                   MAX(delay_ms)                                       AS max_delay_ms
              FROM (SELECT EXTRACT(EPOCH FROM (received_at - message_time)) * 1000 AS delay_ms
                      FROM chat_messages
                     WHERE stream_id = ?
                       AND received_at >= ? AND received_at < ?) d
            """;

    /**
     * @param graceMs           유예. 이보다 늦으면 놓칠 수 있었다
     * @param windowAndGraceMs  발행 창 + 유예. 이보다 늦으면 반드시 놓쳤다
     */
    public LateArrivalCount lateArrivals(String streamId, Instant since, Instant until,
                                         long graceMs, long windowAndGraceMs) {
        return jdbc.queryForObject(LATE_ARRIVALS, (rs, n) -> {
                    // 줄이 없으면 MAX는 NULL이다. rs.getLong은 그것을 0으로 주므로 직접 가른다 —
                    // 「잰 것이 없다」와 「최댓값이 0이다」는 다르다.
                    //
                    // 🔴 wasNull()은 <b>바로 앞에 읽은 칸</b>을 가리킨다. 다른 getLong을 먼저
                    // 부르면 그쪽 칸의 답이 온다 — 처음에 그렇게 써서 검사가 잡았다.
                    long max = rs.getLong("max_delay_ms");
                    boolean 잰_것이_없다 = rs.wasNull();
                    return new LateArrivalCount(rs.getLong("total"), rs.getLong("beyond_grace"),
                            rs.getLong("beyond_window_and_grace"),
                            잰_것이_없다 ? Long.MIN_VALUE : max);
                },
                graceMs, windowAndGraceMs, streamId, Timestamp.from(since), Timestamp.from(until));
    }

    /** 이 시각 이후로 채팅이 온 방송 번호들. 끝난 방송은 채팅이 끊겨 저절로 빠진다. */
    public List<String> activeStreams(Instant since) {
        return jdbc.queryForList(ACTIVE_STREAMS, String.class, Timestamp.from(since));
    }

    /**
     * 그 창의 채팅 중 <b>가장 늦게 우리에게 닿은 시각</b>. 시각 축 표 <b>3번</b>(우리 구간 지연)이
     * 쓰는 값이다.
     *
     * <p><b>왜 필요한가.</b> 「우리 구간」은 <b>우리가 그 창을 다 받은 순간</b>부터 재야 한다.
     * 창이 닫힌 시각({@code message_time} 눈금)부터 재면 그 안에 <b>전달 지연과 시계 어긋남</b>이
     * 섞이고, 그러면 시청자가 늦게 쳤다는 이유로 우리 목표가 실패한다 — PRD의 사용자 결정이
     * 정확히 그것을 막으려고 「판정은 우리 손 안에만 건다」로 내려졌다(감사 2회차 R-2).
     *
     * <p><b>발행 직전에 카드당 한 번만 부른다.</b> 집계({@code countWindows})는 한 바퀴에 300번
     * 도는 뜨거운 자리라 거기에 칸을 얹지 않았고, {@code chat_metrics}에 칸을 더하지도 않았다 —
     * 이번 PR이 만든 표를 이번 PR이 고치는 이력을 남기지 않으려고. 카드는 급증한 창에만 나가므로
     * 이 조회의 빈도는 변환 창구 호출과 같다. {@code idx_chat_messages_stream_received}를 탄다.
     *
     * <h3>🔴 {@code countedUntil} 상한이 반드시 있어야 한다</h3>
     *
     * 이 조회는 <b>발행 직전</b>에 도는데, 그때는 집계가 끝난 지 시간이 좀 지났다(발행권을 잡고
     * 실행기로 넘어가고 clip 재시도까지 끼면 초 단위다). 그 사이 <b>같은 창에 늦은 채팅이 더
     * 도착하면</b> 상한이 없을 때 {@code max}가 뒤로 밀린다.
     *
     * <p>그 채팅은 {@code ON CONFLICT DO NOTHING} 때문에 <b>판정에 쓰이지도 않은</b> 채팅이다.
     * 그런데도 「우리가 다 받은 시각」을 밀어 {@code ourLatencyMs}를 <b>작게</b> 만든다 —
     * {@code max}는 늘기만 하므로 오차가 한 방향이고, <b>목표를 재는 숫자가 늘 낙관적으로</b>
     * 틀린다(감사 2회차 뒤 지적, 내가 코드로 확인).
     *
     * <p>그래서 <b>집계에 쓰인 채팅만</b> 보도록 자른다. 상한은 발행권을 잡은 시각이다 —
     * 집계와 발행권 잡기가 <b>같은 바퀴</b>라({@code collect} 직후 {@code detectAndPublish})
     * 밀리초 차이의 촘촘한 상한이다.
     *
     * <h3>어긋나는 방향 — 세 갈래다</h3>
     *
     * 실제 집계 조회는 바퀴 시각보다 <b>조금 뒤</b>에 돈다. 그 틈에 도착한 채팅은 집계엔
     * 들어가고 여기선 빠지므로, 그런 채팅이 그 창에
     *
     * <ul>
     *   <li><b>없으면</b>(대부분) — 정확하다</li>
     *   <li><b>일부면</b> — {@code max}가 조금 일러 우리 구간이 <b>실제보다 길게</b> 나온다.
     *       낙관이 아니라 <b>비관</b> 쪽이다</li>
     *   <li><b>전부면</b> — 「길게」가 아니라 <b>빈손</b>이 되어 {@code unknown}으로 나간다.
     *       {@code d > grace}인 채팅만으로 창이 채워지고 그것들이 전부 그 밀리초 폭 틈에
     *       도착해야 하는, 극히 드문 경합이다</li>
     * </ul>
     *
     * <p><b>세 갈래 어디에도 낙관은 없다.</b> 그것이 이 상한을 고른 이유다.
     *
     * <h3>🔴 {@code chat_metrics.created_at}을 상한으로 쓰지 마라 — 더 정확해 보이지만 아니다</h3>
     *
     * 「진짜 집계 시각」은 그 칸이니 그쪽이 맞아 보인다. <b>주 근거는 방향이 고정되지 않는다는
     * 것이다.</b> PostgreSQL {@code now()}는 문장 시각이 아니라 <b>트랜잭션 시작 시각</b>이라,
     * {@code created_at}의 위치가 <b>트랜잭션 경계</b>라는 무관해 보이는 결정에 딸려 움직인다
     * (2026-08-26 실측, 감사자와 각자 다른 컨테이너에서 교차 확인).
     *
     * <table>
     *   <tr><th>경계</th><th>{@code created_at}의 위치</th><th>지연 오차</th></tr>
     *   <tr><td>오토커밋(지금)</td><td>집계 조회보다 <b>+2.003초</b> 뒤</td><td><b>낙관</b></td></tr>
     *   <tr><td>{@code @Transactional}로 감싸면</td><td>집계 조회보다 <b>앞</b>(차이 0.000초)</td><td>비관</td></tr>
     * </table>
     *
     * <p>지금 경계에서는 <b>낙관</b> 쪽이라 그것만으로도 못 쓴다 — 판정에 안 쓰인 채팅이
     * {@code max}를 밀어 <b>우리를 좋게 보이게 한다.</b> 그게 이 상한이 애초에 고친 병이다.
     *
     * <p>🔴 <b>그런데 이 서버에는 {@code @Transactional}이 한 개도 없다</b> — 즉 그 경계가
     * <b>아무 데도 안 적혀 있다.</b> 한 바퀴를 트랜잭션으로 묶는 것은 있을 법한 변경이고,
     * 그때 지연 숫자가 <b>조용히 부호를 바꾼다.</b> 지금 상한(바퀴 시각)은 그 결정과 무관하게
     * 늘 비관 쪽이라 <b>우연히 옳은 것이 아니라 구조적으로 옳다.</b>
     *
     * @return 그 창에 <b>상한 안에</b> 도착한 채팅이 없으면 빈 값. 위 「전부」 갈래가 그것이고
     *         정상 운영에서는 거의 오지 않는다 — <b>다만 「도달 불가」는 아니다</b>
     */
    public Optional<Instant> lastReceivedAt(String streamId, Instant from, Instant to, Instant countedUntil) {
        Timestamp last = jdbc.queryForObject(LAST_RECEIVED, Timestamp.class,
                streamId, Timestamp.from(from), Timestamp.from(to), Timestamp.from(countedUntil));
        return Optional.ofNullable(last).map(Timestamp::toInstant);
    }

    /** 창 경계는 집계와 같다 — {@code >= from AND < to}. 다르면 다른 창의 채팅이 섞인다. */
    private static final String LAST_RECEIVED = """
            SELECT max(received_at)
              FROM chat_messages
             WHERE stream_id = ?
               AND message_time >= ? AND message_time < ?
               AND received_at  <  ?
            """;

    /** {@code [from, to)} 구간을 창 크기로 묶어 센다. 채팅이 없는 창은 줄이 안 나온다. */
    public List<MetricRow> countWindows(String streamId, long windowSizeMs, Instant from, Instant to) {
        return jdbc.query(COUNT_WINDOWS,
                (rs, rowNum) -> new MetricRow(streamId, windowSizeMs,
                        rs.getLong("window_start_ms"),
                        rs.getInt("message_count"),
                        rs.getInt("chatter_count")),
                windowSizeMs, windowSizeMs, streamId,
                Timestamp.from(from.minus(RECEIVED_SLACK)), Timestamp.from(to.plus(RECEIVED_SLACK)),
                Timestamp.from(from), Timestamp.from(to));
    }
}
