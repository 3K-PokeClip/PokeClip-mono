package com.pokeclip.clip.render;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 받은 보고 장부({@code render_job_events}). 같은 {@code (jobId, eventId)}가 다시 오면 <b>그때 준 응답을 그대로</b>
 * 돌려주려고 상태 코드와 본문을 함께 적는다(계약1 4절 replay).
 */
@Repository
public class RenderJobEventStore {

    /** 그때 돌려준 응답. */
    public record Stored(int status, String body) {
    }

    private final JdbcTemplate jdbc;

    RenderJobEventStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Stored> find(UUID jobId, UUID eventId) {
        List<Stored> rows = jdbc.query(
                "SELECT response_status, response_body::text FROM render_job_events WHERE job_id = ? AND event_id = ?",
                (rs, i) -> new Stored(rs.getInt(1), rs.getString(2)), jobId, eventId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public void save(UUID jobId, UUID eventId, String eventType, int status, String body) {
        jdbc.update("INSERT INTO render_job_events (job_id, event_id, event_type, response_status, response_body)"
                        + " VALUES (?, ?, ?, ?, CAST(? AS jsonb))",
                jobId, eventId, eventType, status, body);
    }
}
