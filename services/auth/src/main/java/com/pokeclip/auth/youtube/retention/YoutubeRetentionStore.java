package com.pokeclip.auth.youtube.retention;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 유튜브 데이터 보관의 표 쪽(POK-423). JDBC다: 연동 엔티티의 영속성 컨텍스트 규칙(락 뒤에 다시 읽기)에 끼어들지 않는다.
 * 채널 번호는 사람을 잇는 값이라 로그에 안 찍는다(auth/CLAUDE.md).
 */
@Repository
public class YoutubeRetentionStore {

    /**
     * @param createdAt clip이 지우는 범위의 끝. 그 뒤에 주문한 업로드는 남는다
     */
    public record ForgetJob(long id, long userId, String channelId, Instant createdAt, int attempts) {
    }

    private final JdbcTemplate jdbc;

    YoutubeRetentionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 채널 정보를 지울 차례가 된 닫힌 줄. 해제({@code USER_UNLINKED})는 {@code unlinkedBefore}, 그 밖(구글 거절 · 철회)은
     * {@code brokenBefore}보다 먼저 닫힌 것. 오래 닫힌 것부터.
     */
    public List<Long> dueClosed(Instant unlinkedBefore, Instant brokenBefore, int limit) {
        return jdbc.queryForList("""
                SELECT id FROM youtube_channel_links
                 WHERE revoked_at IS NOT NULL AND channel_id IS NOT NULL
                   AND ((revoke_reason = 'USER_UNLINKED' AND revoked_at < ?)
                        OR (revoke_reason IS DISTINCT FROM 'USER_UNLINKED' AND revoked_at < ?))
                 ORDER BY revoked_at LIMIT ?""",
                Long.class, Timestamp.from(unlinkedBefore), Timestamp.from(brokenBefore), limit);
    }

    /**
     * 닫힌 줄 하나의 채널 번호 · 이름을 지운다. 그 회원의 살아 있는 연동이 <b>같은 채널이 아니면</b> clip에 그 채널의 영상 번호를
     * 지우라고 장부에 적는다. 같은 채널이면(기한 안에 다시 연동했다) 영상 번호는 살아 있는 연동의 것이라 남긴다.
     *
     * <p>줄을 잠그고 채널이 아직 남았는지 다시 본다: 같은 줄을 두 인스턴스가 집어도 한 번만 한다. 장부와 지우기가 한 트랜잭션이라
     * 「채널은 지웠는데 영상 번호 지우기를 못 적었다」가 없다.
     *
     * @return 장부에 적었나
     */
    @Transactional
    public boolean closeOut(long linkId, Instant now) {
        List<Object[]> rows = jdbc.query("""
                SELECT user_id, channel_id FROM youtube_channel_links
                 WHERE id = ? AND revoked_at IS NOT NULL AND channel_id IS NOT NULL FOR UPDATE""",
                (rs, n) -> new Object[]{rs.getLong(1), rs.getString(2)}, linkId);
        if (rows.isEmpty()) {
            return false;
        }
        long userId = (Long) rows.getFirst()[0];
        String channelId = (String) rows.getFirst()[1];
        List<String> alive = jdbc.queryForList(
                "SELECT channel_id FROM youtube_channel_links WHERE user_id = ? AND revoked_at IS NULL", String.class, userId);
        boolean forget = alive.isEmpty() || !channelId.equals(alive.getFirst());
        if (forget) {
            Timestamp at = Timestamp.from(now);
            jdbc.update("""
                    INSERT INTO youtube_video_forget_jobs (user_id, channel_id, created_at, next_attempt_at)
                    VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING""", userId, channelId, at, at);
        }
        jdbc.update("UPDATE youtube_channel_links SET channel_id = NULL, channel_name = NULL WHERE id = ?", linkId);
        return forget;
    }

    /**
     * 때가 된 장부 줄을 집는다. 집은 줄은 {@code lease}만큼 미뤄 둔다(탈퇴 장부와 같은 이유: 다른 인스턴스가 같은 줄을 안 집고, 보내다
     * 죽어도 그 시각 뒤 다시 집힌다). HTTP는 이 트랜잭션 밖에서 보낸다.
     */
    @Transactional
    public List<ForgetJob> claim(Instant now, Duration lease, int limit) {
        List<ForgetJob> jobs = jdbc.query("""
                SELECT id, user_id, channel_id, created_at, attempts FROM youtube_video_forget_jobs
                 WHERE next_attempt_at <= ? ORDER BY next_attempt_at LIMIT ? FOR UPDATE SKIP LOCKED""",
                (rs, n) -> new ForgetJob(rs.getLong(1), rs.getLong(2), rs.getString(3),
                        rs.getTimestamp(4).toInstant(), rs.getInt(5)),
                Timestamp.from(now), limit);
        for (ForgetJob job : jobs) {
            jdbc.update("UPDATE youtube_video_forget_jobs SET next_attempt_at = ? WHERE id = ?",
                    Timestamp.from(now.plus(lease)), job.id());
        }
        return jobs;
    }

    /** clip이 받았다. 줄째 지운다: 채널 번호를 남기지 않는다. */
    public void sent(long jobId) {
        jdbc.update("DELETE FROM youtube_video_forget_jobs WHERE id = ?", jobId);
    }

    public void retryLater(long jobId, Instant next) {
        jdbc.update("UPDATE youtube_video_forget_jobs SET attempts = attempts + 1, next_attempt_at = ? WHERE id = ?",
                Timestamp.from(next), jobId);
    }

    /** 채널 정보를 다시 받을 때가 된 살아 있는 연동의 회원(오래 안 받은 것부터). */
    public List<Long> dueChannelRefresh(Instant before, int limit) {
        return jdbc.queryForList("""
                SELECT user_id FROM youtube_channel_links
                 WHERE revoked_at IS NULL AND COALESCE(channel_checked_at, created_at) < ?
                 ORDER BY COALESCE(channel_checked_at, created_at) LIMIT ?""",
                Long.class, Timestamp.from(before), limit);
    }

    /**
     * 다시 받은 채널 이름을 적는다. 그 사이 해제 · 다른 채널로 재연동했으면 아무것도 안 바뀐다(살아 있는 그 채널 줄만 고친다).
     * {@code name}이 {@code null}이면 이름은 그대로 두고 받은 시각만 적는다(목록에 그 채널이 없었다: 매 바퀴 다시 묻지 않게).
     */
    public int channelRefreshed(long userId, String channelId, String name, Instant now) {
        return jdbc.update("""
                UPDATE youtube_channel_links SET channel_name = COALESCE(?, channel_name), channel_checked_at = ?
                 WHERE user_id = ? AND channel_id = ? AND revoked_at IS NULL""",
                name, Timestamp.from(now), userId, channelId);
    }
}
