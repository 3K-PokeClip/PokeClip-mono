package com.pokeclip.clip.upload.retention;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 올린 유튜브 영상 번호의 보관(POK-423, 처리방침 13절). 언제 지울지는 auth가 정한다(연동 기록이 auth에 있다). 이 표는 그 말을 따른다.
 *
 * <p>🔴 지운 줄은 「올림」 그대로 둔다(V222 주석): 상태를 바꾸면 같은 영상을 다시 올릴 자리가 열린다.
 */
@Repository
public class YoutubeVideoStore {

    private final JdbcTemplate jdbc;

    YoutubeVideoStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 30일 확인을 기다리는 줄 하나. */
    public record Due(long id, String videoId, String channelId) {
    }

    /**
     * 이 회원이 이 채널에 올린 영상과 <b>채널을 모르는 영상</b>의 번호를 지운다. 채널을 모르는 줄(사람이 푼 줄 · 채널 칸이 생기기 전 줄)을
     * 같이 지우는 이유: auth가 이 말을 보내는 것은 그 회원의 살아 있는 연동이 이 채널이 아닐 때뿐이라, 그 줄이 지금 채널 것이라는 근거가
     * 없다. 처리방침을 지키는 쪽으로 지운다(대가: 채널을 바꾼 회원의 옛 업로드 링크가 사라진다). 몇 번 불러도 같다.
     *
     * <p>{@code before}(auth가 지우기로 정한 시각)보다 뒤에 주문한 줄은 남긴다: 그 사이 같은 채널로 다시 연동해 올린 영상까지 지우지 않게.
     *
     * @return 지운 줄 수
     */
    public int forget(String owner, String channelId, Instant before, Instant now) {
        return jdbc.update("""
                UPDATE clip_uploads
                SET youtube_video_id = NULL, youtube_channel_id = NULL, video_forgotten_at = ?
                WHERE channel_owner = ? AND youtube_video_id IS NOT NULL AND created_at < ?
                  AND (youtube_channel_id = ? OR youtube_channel_id IS NULL)""",
                Timestamp.from(now), owner, Timestamp.from(before), channelId);
    }

    /** 확인할 때가 된 주인(오래 안 본 것부터). */
    public List<String> dueOwners(Instant before, int limit) {
        return jdbc.queryForList("""
                SELECT channel_owner FROM clip_uploads
                WHERE youtube_video_id IS NOT NULL AND COALESCE(video_checked_at, created_at) < ?
                GROUP BY channel_owner ORDER BY min(COALESCE(video_checked_at, created_at)) LIMIT ?""",
                String.class, Timestamp.from(before), limit);
    }

    /** 이 주인의 확인할 때가 된 줄(오래 안 본 것부터). */
    public List<Due> due(String owner, Instant before, int limit) {
        return jdbc.query("""
                SELECT id, youtube_video_id, youtube_channel_id FROM clip_uploads
                WHERE channel_owner = ? AND youtube_video_id IS NOT NULL AND COALESCE(video_checked_at, created_at) < ?
                ORDER BY COALESCE(video_checked_at, created_at), id LIMIT ?""",
                (rs, i) -> new Due(rs.getLong(1), rs.getString(2), rs.getString(3)),
                owner, Timestamp.from(before), limit);
    }

    /**
     * 확인했다. {@code fillChannel}이 있으면 채널을 모르는 줄에 채운다(그 채널 토큰으로 보였다 = 그 채널 것이다). 사이에 지워진 줄
     * (영상 번호 없음)은 건드리지 않는다.
     */
    public void checked(List<Long> ids, String fillChannel, Instant now) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.update("""
                UPDATE clip_uploads SET video_checked_at = ?, youtube_channel_id = COALESCE(youtube_channel_id, ?)
                WHERE id = ANY(?) AND youtube_video_id IS NOT NULL""",
                Timestamp.from(now), fillChannel, ids.toArray(Long[]::new));
    }

    /** 유튜브에 없는 영상의 번호를 지운다. 사이에 이미 지워진 줄은 그대로다. */
    public int forgetIds(List<Long> ids, Instant now) {
        if (ids.isEmpty()) {
            return 0;
        }
        return jdbc.update("""
                UPDATE clip_uploads SET youtube_video_id = NULL, youtube_channel_id = NULL, video_forgotten_at = ?
                WHERE id = ANY(?) AND youtube_video_id IS NOT NULL""",
                Timestamp.from(now), ids.toArray(Long[]::new));
    }
}
