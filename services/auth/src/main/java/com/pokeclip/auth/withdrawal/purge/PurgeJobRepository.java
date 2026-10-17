package com.pokeclip.auth.withdrawal.purge;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 탈퇴 정리 알림 장부(POK-256). JDBC다: 탈퇴 본체가 영속성 컨텍스트를 비우고 다시 읽는 규칙에 끼어들지 않는다.
 */
@Repository
public class PurgeJobRepository {

    public enum Target { CLIP, COLLECTOR }

    /** @param createdAt 탈퇴 시각. 수집기가 이것을 지우는 범위의 기준으로 쓴다(도착 시각이 아니다) */
    public record Job(long id, long userId, Target target, String channelId, int attempts, Instant createdAt) {
    }

    private final JdbcTemplate jdbc;

    PurgeJobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 탈퇴 트랜잭션 안에서 부른다. clip 한 줄 + 이 회원이 연동했던 치지직 채널마다 수집기 한 줄.
     *
     * <p>🔴 <b>지금 다른 회원이 살아 있는 연동으로 쥔 채널은 뺀다.</b> 같은 사람이 새 구글 계정으로 같은 채널을
     * 연동했을 수 있다. 그 채널의 채팅을 지우면 탈퇴와 무관한 사람의 기록이 사라진다.
     * 연동 줄은 폐기돼도 남으므로(revoked_at만 채운다) 과거에 연동했던 채널까지 다 잡힌다.
     */
    @Transactional
    public void enqueue(long userId, Instant now) {
        Timestamp at = Timestamp.from(now);
        jdbc.update("""
                INSERT INTO withdrawal_purge_jobs (user_id, target, created_at, next_attempt_at)
                VALUES (?, 'CLIP', ?, ?) ON CONFLICT DO NOTHING""", userId, at, at);
        jdbc.update("""
                INSERT INTO withdrawal_purge_jobs (user_id, target, channel_id, created_at, next_attempt_at)
                SELECT DISTINCT ?, 'COLLECTOR', l.channel_id, ?::timestamptz, ?::timestamptz
                  FROM chzzk_channel_links l
                 WHERE l.user_id = ?
                   AND NOT EXISTS (SELECT 1 FROM chzzk_channel_links o
                                    WHERE o.channel_id = l.channel_id AND o.user_id <> ? AND o.revoked_at IS NULL)
                ON CONFLICT DO NOTHING""", userId, at, at, userId, userId);
    }

    /**
     * 때가 된 줄을 집는다. 집은 줄은 {@code lease}만큼 뒤로 미뤄 둔다: 보내는 동안 다른 인스턴스(롤링 배포)가 같은 줄을
     * 집지 않게 하고, 보내다 프로세스가 죽어도 그 시각이 지나면 다시 집힌다. HTTP는 이 트랜잭션 밖에서 보낸다.
     */
    @Transactional
    public List<Job> claim(Instant now, Duration lease, int limit) {
        List<Job> jobs = jdbc.query("""
                SELECT id, user_id, target, channel_id, attempts, created_at FROM withdrawal_purge_jobs
                 WHERE done_at IS NULL AND next_attempt_at <= ?
                 ORDER BY next_attempt_at LIMIT ? FOR UPDATE SKIP LOCKED""",
                (rs, n) -> new Job(rs.getLong(1), rs.getLong(2), Target.valueOf(rs.getString(3)),
                        rs.getString(4), rs.getInt(5), rs.getTimestamp(6).toInstant()),
                Timestamp.from(now), limit);
        for (Job job : jobs) {
            jdbc.update("UPDATE withdrawal_purge_jobs SET next_attempt_at = ? WHERE id = ?",
                    Timestamp.from(now.plus(lease)), job.id());
        }
        return jobs;
    }

    public void done(long id, Instant now) {
        jdbc.update("UPDATE withdrawal_purge_jobs SET done_at = ?, attempts = attempts + 1 WHERE id = ?",
                Timestamp.from(now), id);
    }

    public void retryLater(long id, Instant next) {
        jdbc.update("UPDATE withdrawal_purge_jobs SET attempts = attempts + 1, next_attempt_at = ? WHERE id = ?",
                Timestamp.from(next), id);
    }
}
