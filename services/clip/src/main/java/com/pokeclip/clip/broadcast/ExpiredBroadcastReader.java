package com.pokeclip.clip.broadcast;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 보관 기한이 지난 방송과 그 방송의 「지워도 되는 조각 구간」을 읽는다(POK-335, 계약-세그먼트인덱스 6-2).
 *
 * <p>🔴 <b>조각 장부는 방송 번호가 아니라 물리 키 + 시각으로 쌓인다.</b> 한 스트리머의 여러 방송이 같은 물리 키를 나눠 쓰므로,
 * 같은 물리 키의 방송을 시간 순으로 나란히 놓고 자르는 점 하나(뒤 방송 자리 − 2분)로 칸을 나눈다:
 * <pre>
 *   구간 = [max(앞 방송 끝, 이 방송 자리 − 2분), 뒤 방송 자리 − 2분)
 *   앞 방송 없음 = 처음부터(null) · 앞 방송이 안 끝났으면(종료 편지를 놓친 줄) 이 방송 자리 − 2분부터
 *   뒤 방송 없음 = 끝 + 10분(끝이 비었으면 자리 + 10분) · 끝이 시작보다 앞이면(겹친 방송) 빈 구간(아무것도 안 지움)
 *   뒤 방송이 시작을 모르면(자리표시) 둘 중 이른 쪽: 끝 + 10분 · 뒤 방송 자리 − 2분
 * </pre>
 * 방송의 「자리」는 시작 시각, 없으면(자리표시) 끝 시각, 그것도 없으면 줄이 생긴 시각이다. 같은 자리는 줄 번호 순이다.
 * 2분은 조각이 시작 편지보다 먼저 생기는 여유이고, 10분은 끝 편지 뒤 남는 조각의 여유다(시각 기준점 POK-255와 같은 창).
 * 자리표시의 자리(끝 시각)는 「그 방송이 여기서 시작했다」가 아니라 시작을 몰라 세운 표시라, 바로 앞 방송의 구간을 거기까지
 * 늘리면 자리표시 방송의 조각을 일찍 지운다(PR #348 codex P1). 그래서 앞 방송은 자기 끝 + 10분에서 멈추고 그 사이는 어느 구간에도
 * 안 든다(적게 지우는 쪽).
 * 끝이 빈 채 기한만 찬 줄(지금 코드로는 안 생기지만 손 보정 · 치우개가 기한만 채우면)은 자리 + 10분까지만 지운다: 끝을 몰라
 * 「끝없이」를 낼 수는 없고, 적게 지우는 쪽이 안전하다(PR #348 claude).
 * 이렇게 자르면 조각 하나가 정확히 한 방송에만 속하고, 안 만료된 이웃 방송의 조각(자리 − 2분부터)은 어느 구간에도 안 든다.
 *
 * <p>렌더가 열려 있는(주문됨 · 만드는 중) 방송은 안 낸다: 주문서에 조각 키를 실어 일꾼이 나중에 읽으므로, 그 사이 1번이 지우면
 * 정상 접수된 렌더가 원본 없이 실패한다(PR #348 codex). 끝나면(완성 · 실패 · 실패 큐 정리) 다음에 나온다. 이어받기 표시가 그 줄을
 * 지나쳤으면 1번의 하루 한 번 처음부터 훑기가 거둔다. 만료 뒤 새 렌더 주문은 410이라 열린 렌더는 만료 전 주문뿐이다.
 *
 * <p>시각은 조각 장부의 벽시계 칸({@code start_wall_utc})으로 쓰라고 계약에 적는다. 이 클래스는 구간만 계산하고 장부를 안 읽는다.
 */
@Component
public class ExpiredBroadcastReader {

    /**
     * 이어받기는 (기한, 줄 번호)로 한다. 기한은 마이크로초까지 같은지 봐야 해서 epoch 마이크로초로 받는다(ms로 내리면 같은 ms의 다른
     * 기한이 경계에서 빠지거나 겹친다). 첫 장은 기한 −∞ · 줄 번호 0이다.
     */
    static final String SQL = """
            WITH page AS (
                SELECT b.id, b.stream_id, b.started_at, b.ended_at, b.vod_expires_at, b.created_at,
                       COALESCE(b.ingest_stream_id, b.stream_id) AS ingest_key,
                       COALESCE(b.started_at, b.ended_at, b.created_at) AS pos
                  FROM broadcasts b
                 WHERE b.vod_expires_at IS NOT NULL
                   AND b.vod_expires_at < ?
                   AND (b.vod_expires_at, b.id) > (TIMESTAMPTZ 'epoch' + ? * INTERVAL '1 microsecond', ?)
                   AND NOT EXISTS (SELECT 1 FROM clips c
                                    WHERE c.stream_id = b.stream_id AND c.status IN ('queued', 'rendering'))
                 ORDER BY b.vod_expires_at, b.id
                 LIMIT ?
            )
            SELECT p.id, p.stream_id, p.ingest_key, p.started_at, p.ended_at, p.vod_expires_at,
                   (EXTRACT(EPOCH FROM p.vod_expires_at) * 1000000)::bigint AS expires_micros,
                   CASE WHEN prev.id IS NULL THEN NULL
                        WHEN prev.ended_at IS NULL THEN p.pos - INTERVAL '2 minutes'
                        ELSE GREATEST(prev.ended_at, p.pos - INTERVAL '2 minutes') END AS segments_from,
                   CASE WHEN nxt.id IS NULL THEN COALESCE(p.ended_at, p.pos) + INTERVAL '10 minutes'
                        WHEN nxt.started_at IS NULL THEN LEAST(COALESCE(p.ended_at, p.pos) + INTERVAL '10 minutes',
                                                              nxt.pos - INTERVAL '2 minutes')
                        ELSE nxt.pos - INTERVAL '2 minutes' END AS segments_until
              FROM page p
              LEFT JOIN LATERAL (
                    SELECT o.id, o.ended_at
                      FROM broadcasts o
                     WHERE COALESCE(o.ingest_stream_id, o.stream_id) = p.ingest_key
                       AND (COALESCE(o.started_at, o.ended_at, o.created_at), o.id) < (p.pos, p.id)
                     ORDER BY COALESCE(o.started_at, o.ended_at, o.created_at) DESC, o.id DESC
                     LIMIT 1) prev ON true
              LEFT JOIN LATERAL (
                    SELECT o.id, o.started_at, COALESCE(o.started_at, o.ended_at, o.created_at) AS pos
                      FROM broadcasts o
                     WHERE COALESCE(o.ingest_stream_id, o.stream_id) = p.ingest_key
                       AND (COALESCE(o.started_at, o.ended_at, o.created_at), o.id) > (p.pos, p.id)
                     ORDER BY COALESCE(o.started_at, o.ended_at, o.created_at), o.id
                     LIMIT 1) nxt ON true
             ORDER BY p.vod_expires_at, p.id
            """;

    private final JdbcTemplate jdbc;

    ExpiredBroadcastReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param before   이 시각보다 기한이 앞인 방송만(같으면 안 냄)
     * @param afterMicros 이어받기 기한(epoch 마이크로초). 첫 장이면 {@link Long#MIN_VALUE}
     * @param afterId  이어받기 줄 번호. 첫 장이면 0
     * @param limit    읽을 줄 수
     */
    public List<ExpiredBroadcastRow> read(Instant before, long afterMicros, long afterId, int limit) {
        // 첫 장: 기한 −∞ 대신 1년 1월 1일 앞으로 둔다(마이크로초 long으로 −∞를 못 적는다). 기한은 늘 그보다 늦다
        long from = afterMicros == Long.MIN_VALUE ? -62_135_596_800_000_000L : afterMicros;
        return jdbc.query(SQL, ExpiredBroadcastReader::row, Timestamp.from(before), from, afterId, limit);
    }

    private static ExpiredBroadcastRow row(ResultSet rs, int n) throws SQLException {
        Instant from = instant(rs, "segments_from");
        Instant until = instant(rs, "segments_until");
        // 겹친 방송: 끝이 시작보다 앞이면 빈 구간으로 낸다(아무것도 안 지움)
        if (from != null && until.isBefore(from)) {
            until = from;
        }
        return new ExpiredBroadcastRow(rs.getLong("id"), rs.getString("stream_id"), rs.getString("ingest_key"),
                instant(rs, "started_at"), instant(rs, "ended_at"), instant(rs, "vod_expires_at"),
                rs.getLong("expires_micros"), from, until);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
