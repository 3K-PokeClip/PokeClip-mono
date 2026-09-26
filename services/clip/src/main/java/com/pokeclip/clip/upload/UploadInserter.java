package com.pokeclip.clip.upload;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.OptionalLong;

/**
 * 업로드 줄을 <b>선점하며</b> 넣는다({@code ClipInserter}와 같은 모양). 같은 영상 같은 벌에 살아 있는(실패 아닌) 줄이 있으면
 * 안 넣고 빈손으로 돌아온다: 더블클릭·두 번 누름이 영상을 둘 만들지 않는 첫 방어선이다.
 *
 * <p>주문서는 번호가 있어야 쓸 수 있어 넣은 뒤 같은 트랜잭션에서 채운다({@link #fillPayload}).
 */
@Repository
public class UploadInserter {

    private static final String INSERT_IF_NO_ACTIVE = """
            INSERT INTO clip_uploads (clip_id, output_id, requested_by, channel_owner, title, description, status, payload, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, 'queued', '{}'::jsonb, now())
            ON CONFLICT (clip_id, output_id) WHERE status <> 'failed' DO NOTHING
            RETURNING id""";

    private final JdbcTemplate jdbc;

    UploadInserter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return 새로 넣은 줄의 번호. 살아 있는 줄이 이미 있으면 비어 있다 */
    OptionalLong insertIfNoActive(long clipId, String outputId, String requestedBy, String channelOwner,
                                  String title, String description) {
        List<Long> ids = jdbc.query(INSERT_IF_NO_ACTIVE, (rs, i) -> rs.getLong("id"),
                clipId, outputId, requestedBy, channelOwner, title, description);
        return ids.isEmpty() ? OptionalLong.empty() : OptionalLong.of(ids.getFirst());
    }

    void fillPayload(long uploadId, String payload) {
        jdbc.update("UPDATE clip_uploads SET payload = ?::jsonb WHERE id = ?", payload, uploadId);
    }
}
