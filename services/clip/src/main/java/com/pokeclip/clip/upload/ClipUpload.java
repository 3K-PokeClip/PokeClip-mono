package com.pokeclip.clip.upload;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

/**
 * 업로드 주문 한 줄({@code clip_uploads}). 새 줄은 {@link UploadInserter}가 SQL로 넣는다(부분 UNIQUE에 걸리면 조용히 비켜야 해서).
 * 상태를 바꾸는 메서드는 package-private이고 {@link UploadReportService}·{@link UploadDlqReconciler}만 부른다.
 */
@Entity
@Table(name = "clip_uploads")
public class ClipUpload {

    @Id
    private Long id;

    @Column(name = "clip_id", nullable = false)
    private long clipId;

    @Column(name = "output_id", nullable = false, length = 32)
    private String outputId;

    @Column(name = "requested_by", nullable = false, length = 128)
    private String requestedBy;

    @Column(name = "channel_owner", nullable = false, length = 128)
    private String channelOwner;

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    @Column(name = "description", nullable = false, length = 5000)
    private String description;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "session_uri", length = 2048)
    private String sessionUri;

    @Column(name = "attempt_ordinal", nullable = false)
    private int attemptOrdinal;

    @Column(name = "youtube_video_id", length = 32)
    private String youtubeVideoId;

    @Column(name = "error_code", length = 32)
    private String errorCode;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // ── POK-291: 고른 정보와 썸네일. 새 줄은 UploadInserter가 SQL로 채운다 ──

    /** 문자열 배열(JSON). 읽을 때 {@link #getTags()}가 푼다. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags", nullable = false, columnDefinition = "jsonb")
    private String tags;

    @Column(name = "privacy_status", nullable = false, length = 8)
    private String privacyStatus;

    @Column(name = "made_for_kids", nullable = false)
    private boolean madeForKids;

    @Column(name = "thumbnail_source", nullable = false, length = 8)
    private String thumbnailSource;

    @Column(name = "thumbnail_offset_ms")
    private Long thumbnailOffsetMs;

    @Column(name = "thumbnail_s3_key", length = 512)
    private String thumbnailS3Key;

    @Column(name = "thumbnail_content_type", length = 32)
    private String thumbnailContentType;

    /** {@code none}·{@code pending}·{@code set}·{@code failed}. 영상 상태와 따로 간다. */
    @Column(name = "thumbnail_status", nullable = false, length = 8)
    private String thumbnailStatus;

    @Column(name = "thumbnail_error_code", length = 32)
    private String thumbnailErrorCode;

    protected ClipUpload() {
    }

    void published() {
        this.publishedAt = Instant.now();
        this.updatedAt = this.publishedAt;
    }

    /** 일꾼이 잡았다. 올리는 중으로 두고 순번을 올린다. */
    void started() {
        this.status = UploadStatus.UPLOADING.dbValue();
        this.attemptOrdinal++;
        this.updatedAt = Instant.now();
    }

    /** 처음 적는 주소만 남는다(CAS). @return 남은 주소: 부른 쪽은 자기가 만든 것이 아니면 버리고 이것을 쓴다 */
    String recordSession(String uri) {
        if (this.sessionUri == null) {
            this.sessionUri = uri;
            this.updatedAt = Instant.now();
        }
        return this.sessionUri;
    }

    void uploaded(String videoId) {
        this.status = UploadStatus.UPLOADED.dbValue();
        this.youtubeVideoId = videoId;
        this.errorCode = null;
        this.errorMessage = null;
        this.updatedAt = Instant.now();
    }

    /**
     * 일꾼이 보고한 썸네일 결과를 적는다(POK-291). 영상이 올라간 순간에만 부른다. 결과가 없거나 모르는 값이면, 붙일 것이 있었던
     * 줄({@code pending})은 「보고 안 됨」으로 닫는다(옛 일꾼): 그대로 두면 화면이 영원히 「붙이는 중」으로 본다.
     *
     * @param outcome {@code SET}·{@code FAILED}·{@code NONE} 또는 {@code null}(칸 없음·모르는 값)
     * @param code    {@code FAILED}일 때의 코드. 모양이 틀렸으면 {@code null}
     */
    void thumbnailReported(String outcome, String code) {
        if ("SET".equals(outcome)) {
            this.thumbnailStatus = "set";
            this.thumbnailErrorCode = null;
        } else if ("FAILED".equals(outcome)) {
            this.thumbnailStatus = "failed";
            this.thumbnailErrorCode = code;
        } else if ("pending".equals(this.thumbnailStatus)) {
            this.thumbnailStatus = "failed";
            this.thumbnailErrorCode = "THUMBNAIL_NOT_REPORTED";
        }
    }

    void failed(String code, String message) {
        this.status = UploadStatus.FAILED.dbValue();
        this.errorCode = code;
        this.errorMessage = message;
        this.updatedAt = Instant.now();
    }

    void checking(String code, String message) {
        this.status = UploadStatus.CHECKING.dbValue();
        this.errorCode = code;
        this.errorMessage = message;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public long getClipId() {
        return clipId;
    }

    public String getOutputId() {
        return outputId;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public String getChannelOwner() {
        return channelOwner;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public UploadStatus getStatus() {
        return UploadStatus.of(status);
    }

    public String getPayload() {
        return payload;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    /** 🔴 응답·로그에 내보내지 않는다: 이 주소 자체가 올리기 권한이다. 일꾼 문만 돌려준다. */
    public String getSessionUri() {
        return sessionUri;
    }

    public int getAttemptOrdinal() {
        return attemptOrdinal;
    }

    public String getYoutubeVideoId() {
        return youtubeVideoId;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<String> getTags() {
        return tags == null ? List.of() : TAGS_READER.readValue(tags, STRINGS);
    }

    public String getPrivacyStatus() {
        return privacyStatus;
    }

    public boolean isMadeForKids() {
        return madeForKids;
    }

    public String getThumbnailSource() {
        return thumbnailSource;
    }

    public Long getThumbnailOffsetMs() {
        return thumbnailOffsetMs;
    }

    public String getThumbnailS3Key() {
        return thumbnailS3Key;
    }

    public String getThumbnailContentType() {
        return thumbnailContentType;
    }

    public String getThumbnailStatus() {
        return thumbnailStatus;
    }

    public String getThumbnailErrorCode() {
        return thumbnailErrorCode;
    }

    /** 이 줄에 적힌 고른 정보 한 벌. 다시 시도가 이것을 그대로 새 줄로 옮긴다. */
    UploadInfo info() {
        return new UploadInfo(title, description, getTags(), privacyStatus, madeForKids,
                new UploadInfo.Thumbnail(thumbnailSource, thumbnailOffsetMs, thumbnailS3Key, thumbnailContentType));
    }

    private static final ObjectMapper TAGS_READER = new ObjectMapper();
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };
}
