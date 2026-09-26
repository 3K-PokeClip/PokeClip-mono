package com.pokeclip.clip.upload;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

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
}
