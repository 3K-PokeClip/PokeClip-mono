package com.pokeclip.clip.broadcast;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * 방송마다 켜진 플랫폼 묶음 {@code broadcast_platforms}(V220, POK-397). 수집기가 그 플랫폼에서 방송을 확인하면 적는다.
 * {@code broadcasts.platform}(편지가 정한 첫 값)은 건드리지 않는다.
 */
@Repository
public class BroadcastPlatformStore {

    /** PostgreSQL 외래키 위반. */
    private static final String FOREIGN_KEY_VIOLATION = "23503";

    private final JdbcTemplate jdbc;

    BroadcastPlatformStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 그 방송에 그 플랫폼이 켜져 있었다고 적는다. 같은 것을 몇 번 불러도 줄은 하나고, {@code last_seen_at}은 앞으로만 간다
     * (늦게 도착한 이른 알림이 되돌리지 않는다). {@code first_seen_at}은 처음 값 그대로다.
     *
     * <p>방송 줄이 없으면 아무것도 안 적는다. 문장 하나로 「있으면 적기」를 하므로 확인과 적기 사이에 끼어들 틈이 없다.
     * 다만 탈퇴 정리가 방송 줄을 {@code FOR UPDATE}로 잡고 지우는 중이면, 이 문장은 외래키 검사에서 그 줄을 기다렸다가
     * 정리가 커밋된 뒤 외래키 위반을 맞는다(계획 검증 실측). 그것도 「방송이 없다」와 같은 뜻이라 {@code false}로 접는다.
     *
     * @return 적었으면 {@code true}, 방송이 없으면 {@code false}
     */
    public boolean markSeen(String streamId, String platform, Instant seenAt) {
        try {
            return jdbc.update("""
                    INSERT INTO broadcast_platforms (stream_id, platform, first_seen_at, last_seen_at)
                    SELECT b.stream_id, ?, ?, ? FROM broadcasts b WHERE b.stream_id = ?
                    ON CONFLICT (stream_id, platform) DO UPDATE
                       SET last_seen_at = GREATEST(broadcast_platforms.last_seen_at, EXCLUDED.last_seen_at)""",
                    platform, Timestamp.from(seenAt), Timestamp.from(seenAt), streamId) > 0;
        } catch (DataIntegrityViolationException e) {
            if (e.getMostSpecificCause() instanceof SQLException sql && FOREIGN_KEY_VIOLATION.equals(sql.getSQLState())) {
                return false;
            }
            throw e;
        }
    }
}
