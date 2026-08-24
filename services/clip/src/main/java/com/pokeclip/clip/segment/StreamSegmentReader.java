package com.pokeclip.clip.segment;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code stream_segments}를 <b>읽기만</b> 한다. 이 표의 소유는 1번(Media)이고 clip은
 * 계약 4절이 허용한 SELECT만 쓴다(ADR-030) — <b>여기에 INSERT·UPDATE·DELETE를 만들지 않는다.</b>
 * JPA 엔티티로 매핑하지 않는 것도 같은 이유다(엔티티를 두면 flush 한 번에 남의 표에 쓰게 된다).
 */
@Component
public class StreamSegmentReader {

    /**
     * 구간 겹침 조건. <b>{@code BETWEEN}이 아니다</b> — 계약-세그먼트인덱스 2절이
     * 2026-08-14에 정정했다. {@code BETWEEN}은 클립 시작을 걸치는 첫 조각을 빠뜨려
     * 클립 머리가 최대 4초(조각 하나) 잘린다.
     *
     * <p>미만/초과인 이유는 <b>경계에 닿기만 한 조각은 겹침 길이가 0</b>이라 프레임을
     * 하나도 주지 않기 때문이다.
     *
     * <p>상태로 거르지 않는다 — pending 조각이 목록에 실려 와야 조립기가 「중간에서 끊긴다」를
     * 판정할 수 있다.
     */
    private static final String OVERLAPPING = """
            SELECT seq, start_pts_ms, duration_ms, s3_key, upload_state, is_discontinuity
              FROM stream_segments
             WHERE stream_id = ?
               AND start_pts_ms < ?
               AND start_pts_ms + duration_ms > ?
             ORDER BY seq""";

    private final JdbcTemplate jdbc;

    StreamSegmentReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * {@code [startMs, endMs)}와 겹치는 조각을 {@code seq} 오름차순으로 준다.
     *
     * <p><b>거르지 않은 전부다</b> — {@code uploaded}가 아닌 조각도 그대로 실린다.
     * 무엇을 쓸 수 있는지는 조립기가 정한다.
     *
     * <p>인자 순서에 주의: SQL 물음표는 {@code streamId} → {@code endMs} → {@code startMs}다.
     */
    public List<StreamSegmentRow> findOverlapping(String streamId, long startMs, long endMs) {
        return jdbc.query(OVERLAPPING,
                (rs, rowNum) -> new StreamSegmentRow(
                        rs.getLong("seq"),
                        rs.getLong("start_pts_ms"),
                        rs.getInt("duration_ms"),
                        rs.getString("s3_key"),
                        rs.getString("upload_state"),
                        rs.getBoolean("is_discontinuity")),
                streamId, endMs, startMs);
    }
}
