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
     * <p>{@code MAX}가 아니라 {@code COALESCE(MAX(...), 0)}이다 — 줄이 없으면 {@code MAX}는
     * {@code NULL}이고 그것을 {@code long}으로 읽으면 0이 되지만, 명시하지 않으면 다음 사람이
     * 「없을 때 뭐가 나오지」를 다시 조사한다.
     */
    private static final String LATE_ARRIVALS = """
            SELECT count(*)                                            AS total,
                   count(*) FILTER (WHERE delay_ms > ?)                AS beyond_grace,
                   count(*) FILTER (WHERE delay_ms > ?)                AS beyond_window_and_grace,
                   COALESCE(MAX(delay_ms), 0)                          AS max_delay_ms
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
        return jdbc.queryForObject(LATE_ARRIVALS, (rs, n) -> new LateArrivalCount(
                        rs.getLong("total"), rs.getLong("beyond_grace"),
                        rs.getLong("beyond_window_and_grace"), rs.getLong("max_delay_ms")),
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
     * @return 그 창에 채팅이 없으면 빈 값. 집계된 창이라 정상적으로는 늘 값이 있다
     */
    public Optional<Instant> lastReceivedAt(String streamId, Instant from, Instant to) {
        Timestamp last = jdbc.queryForObject(LAST_RECEIVED, Timestamp.class,
                streamId, Timestamp.from(from), Timestamp.from(to));
        return Optional.ofNullable(last).map(Timestamp::toInstant);
    }

    /** 경계는 집계와 같다 — {@code >= from AND < to}. 다르면 다른 창의 채팅이 섞인다. */
    private static final String LAST_RECEIVED = """
            SELECT max(received_at)
              FROM chat_messages
             WHERE stream_id = ?
               AND message_time >= ? AND message_time < ?
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
