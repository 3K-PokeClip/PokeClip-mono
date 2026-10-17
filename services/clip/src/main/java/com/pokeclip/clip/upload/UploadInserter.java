package com.pokeclip.clip.upload;

import com.pokeclip.clip.upload.UploadInfo.Thumbnail;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.OptionalLong;

/**
 * 업로드 줄을 <b>선점하며</b> 넣는다({@code ClipInserter}와 같은 모양). 같은 영상 같은 벌에 살아 있는(실패 아닌) 줄이 있으면
 * 안 넣고 빈손으로 돌아온다: 더블클릭·두 번 누름이 영상을 둘 만들지 않는 첫 방어선이다.
 *
 * <p>주문서는 번호가 있어야 쓸 수 있어 넣은 뒤 같은 트랜잭션에서 채운다({@link #fillPayload}).
 *
 * <p>🔴 <b>판 단위 중복(같은 편집본 같은 판의 <i>다른</i> 영상)은 이 색인이 못 막는다</b>(POK-291). 그 판정은 넣기 전에
 * {@link #lockVersion}을 잡고 {@code ClipUploadRepository.findActiveForVersion}으로 본다. 업로드 줄을 넣는 자리 넷(옛 문 ·
 * 다시 시도 · 렌더 성공 자동 · 「영상 만들기」의 이미 완성 갈래)이 전부 그 순서를 지난다.
 */
@Repository
public class UploadInserter {

    private static final String INSERT_IF_NO_ACTIVE = """
            INSERT INTO clip_uploads (clip_id, output_id, requested_by, channel_owner, title, description, status, payload,
                                      tags, privacy_status, made_for_kids, thumbnail_source, thumbnail_offset_ms,
                                      thumbnail_s3_key, thumbnail_content_type, thumbnail_status, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, 'queued', '{}'::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (clip_id, output_id) WHERE status <> 'failed' DO NOTHING
            RETURNING id""";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    UploadInserter(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** @return 새로 넣은 줄의 번호. 살아 있는 줄이 이미 있으면 비어 있다 */
    OptionalLong insertIfNoActive(long clipId, String outputId, String requestedBy, String channelOwner, UploadInfo info) {
        Thumbnail thumbnail = info.thumbnail();
        List<Long> ids = jdbc.query(INSERT_IF_NO_ACTIVE, (rs, i) -> rs.getLong("id"),
                clipId, outputId, requestedBy, channelOwner, info.title(), info.description(),
                mapper.writeValueAsString(info.tags()), info.privacyStatus(), info.madeForKids(), thumbnail.source(),
                thumbnail.offsetMs(), thumbnail.s3Key(), thumbnail.contentType(), thumbnail.initialStatus());
        return ids.isEmpty() ? OptionalLong.empty() : OptionalLong.of(ids.getFirst());
    }

    void fillPayload(long uploadId, String payload) {
        jdbc.update("UPDATE clip_uploads SET payload = ?::jsonb WHERE id = ?", payload, uploadId);
    }

    /**
     * 편집본 판 하나의 업로드 판정을 한 줄로 세운다(트랜잭션 끝까지). 「판에 살아 있는 업로드가 있나」를 보고 넣는 사이에 다른 요청이
     * 같은 판의 다른 영상으로 끼어들면 둘 다 「없다」를 보고 영상이 둘 뜬다. 표 줄 잠금이 아니라 권고 잠금인 이유: 옛 문·다시 시도에는
     * 잠글 의도 줄이 없고, 편집본 줄을 잠그면 편집 저장·탈퇴 정리와 잠금 순서가 엉킨다.
     *
     * <p>잠금 순서: 렌더 보고({@code render_jobs → clips → upload_requests}) 뒤에 이것을 잡는다. 이것을 잡은 뒤에는
     * {@code clip_uploads}에 넣기만 한다(그 영상 줄에 KEY SHARE). 반대 순서로 잡는 자리가 없다.
     */
    void lockVersion(long recipeId, int recipeVersion) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(?, ?)", (int) (recipeId ^ (recipeId >>> 32)), recipeVersion);
    }
}
