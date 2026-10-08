package com.pokeclip.chat.collector.purge;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 탈퇴 채널 명부와 지우기 SQL(POK-256). 지우기는 전부 {@code id IN (… LIMIT ?)} 묶음이다: 채팅 표는 방송 하나에
 * 수십만 줄이라 DELETE 하나로 지우면 그 사이 적재가 표 잠금에 밀리고 바구니가 차 라이브 채팅이 버려진다.
 */
@Repository
public class ChatPurgeStore {

    /** 표·시각 칸 짝. 채널로 지울 때와 기한으로 지울 때 같은 셋을 돈다. */
    enum Table {
        CHAT_MESSAGES("chat_messages", "received_at"),
        CHAT_DONATIONS("chat_donations", "received_at"),
        BROADCAST_INFO("broadcast_info", "observed_at");

        final String name;
        final String timeColumn;

        Table(String name, String timeColumn) {
            this.name = name;
            this.timeColumn = timeColumn;
        }
    }

    private final JdbcTemplate jdbc;

    ChatPurgeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 명부에 적는다. 이미 있으면 요청 시각을 새로 잡고 다시 연다: 그 뒤에 받은 채팅도 지울 대상이다. */
    public void request(String channelId, Instant now) {
        jdbc.update("""
                INSERT INTO purged_channels (channel_id, requested_at) VALUES (?, ?)
                ON CONFLICT (channel_id) DO UPDATE SET requested_at = EXCLUDED.requested_at, completed_at = NULL""",
                channelId, Timestamp.from(now));
    }

    public record Due(String channelId, Instant requestedAt) {
    }

    public List<Due> due(int limit) {
        return jdbc.query("SELECT channel_id, requested_at FROM purged_channels WHERE completed_at IS NULL "
                        + "ORDER BY requested_at LIMIT ?",
                (rs, n) -> new Due(rs.getString(1), rs.getTimestamp(2).toInstant()), limit);
    }

    /** 이 채널에서 {@code before} 전에 받은 줄을 한 묶음 지운다. 지운 수가 {@code limit}보다 작으면 다 지운 것이다. */
    public int deleteChannelBatch(Table table, String channelId, Instant before, int limit) {
        return jdbc.update("DELETE FROM " + table.name + " WHERE id IN (SELECT id FROM " + table.name
                        + " WHERE channel_id = ? AND " + table.timeColumn + " < ? LIMIT ?)",
                channelId, Timestamp.from(before), limit);
    }

    /**
     * 기한이 지난 줄을 한 묶음 지운다. 채팅은 방송 번호가 있는 줄(V305 색인)과 없는 옛 줄(V312 색인)을 따로 지운다:
     * 둘을 OR로 묶으면 어느 부분 색인도 못 탄다.
     *
     * <p>후원·방송 정보는 시각 색인이 없다(두면 기존 조회가 그것을 골라 탄다, V312 머리말). 대신 <b>번호(id) 순 앞쪽 한 묶음</b>
     * 중 기한이 지난 것만 지운다. 두 표는 받는 즉시 적어 번호 순서가 곧 받은 순서라, 앞쪽이 기한 안이면 뒤도 기한 안이다.
     * 번호와 시각이 살짝 어긋난 줄은 앞 줄이 기한을 넘을 때 같이 지워진다(늦어야 그 차이만큼).
     */
    public int deleteExpiredBatch(Table table, Instant cutoff, int limit) {
        if (table == Table.CHAT_MESSAGES) {
            int withStream = jdbc.update("DELETE FROM chat_messages WHERE id IN (SELECT id FROM chat_messages "
                    + "WHERE stream_id IS NOT NULL AND received_at < ? LIMIT ?)", Timestamp.from(cutoff), limit);
            int withoutStream = jdbc.update("DELETE FROM chat_messages WHERE id IN (SELECT id FROM chat_messages "
                    + "WHERE stream_id IS NULL AND received_at < ? LIMIT ?)", Timestamp.from(cutoff), limit);
            return Math.max(withStream, withoutStream);
        }
        return jdbc.update("DELETE FROM " + table.name + " WHERE id IN (SELECT id FROM (SELECT id, " + table.timeColumn
                + " AS at FROM " + table.name + " ORDER BY id LIMIT ?) head WHERE head.at < ?)", limit, Timestamp.from(cutoff));
    }

    public void complete(String channelId, Instant now) {
        jdbc.update("UPDATE purged_channels SET completed_at = ? WHERE channel_id = ? AND completed_at IS NULL",
                Timestamp.from(now), channelId);
    }
}
