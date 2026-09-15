package com.pokeclip.clip.render;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 주문 하나({@code render_jobs} 한 줄). 번호는 우리가 만든 UUID(계약1 jobId)라 {@code @GeneratedValue}가 없다.
 *
 * <p>상태를 바꾸는 메서드는 전부 package-private이고 {@link JobEventService}만 부른다 — 계약1 상태머신이 그 한 곳에
 * 있어야 「어느 보고가 무엇을 바꾸나」를 표 하나로 읽을 수 있다.
 */
@Entity
@Table(name = "render_jobs")
public class RenderJob {

    @Id
    private UUID id;

    @Column(name = "clip_id", nullable = false)
    private long clipId;

    @Convert(converter = RenderJobStatusConverter.class)
    @Column(name = "status", nullable = false, length = 16)
    private RenderJobStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "execution_token")
    private UUID executionToken;

    @Column(name = "attempt_ordinal", nullable = false)
    private int attemptOrdinal;

    @Column(name = "progress_percent", nullable = false)
    private int progressPercent;

    @Column(name = "progress_stage", length = 64)
    private String progressStage;

    @Column(name = "last_event_at")
    private Instant lastEventAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RenderJob() {
    }

    static RenderJob queued(UUID id, long clipId, String payload) {
        RenderJob job = new RenderJob();
        job.id = id;
        job.clipId = clipId;
        job.status = RenderJobStatus.QUEUED;
        job.payload = payload;
        job.attemptOrdinal = 0;
        job.progressPercent = 0;
        job.createdAt = Instant.now();
        job.updatedAt = job.createdAt;
        return job;
    }

    void published() {
        this.publishedAt = Instant.now();
        this.updatedAt = this.publishedAt;
    }

    /** 새 실행을 연다 — 새 토큰, 순번 +1, 진행률 0. 옛 토큰의 보고는 이 순간부터 409다. */
    UUID start(Instant at) {
        this.executionToken = UUID.randomUUID();
        this.attemptOrdinal++;
        this.progressPercent = 0;
        this.progressStage = null;
        this.status = RenderJobStatus.STARTED;
        touch(at);
        return this.executionToken;
    }

    /** 같은 토큰 안에서 단조 — 뒤로 가는 값은 무시한다(계약1 4절). */
    void progress(Integer percent, String stage, Instant at) {
        if (percent != null && percent > this.progressPercent) {
            this.progressPercent = Math.min(percent, 100);
        }
        if (stage != null) {
            this.progressStage = stage.substring(0, Math.min(stage.length(), 64));
        }
        touch(at);
    }

    void succeeded(Instant at) {
        this.status = RenderJobStatus.SUCCEEDED;
        this.progressPercent = 100;
        touch(at);
    }

    void failed(Instant at) {
        this.status = RenderJobStatus.FAILED;
        touch(at);
    }

    private void touch(Instant at) {
        this.lastEventAt = at;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public long getClipId() {
        return clipId;
    }

    public RenderJobStatus getStatus() {
        return status;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public UUID getExecutionToken() {
        return executionToken;
    }

    public int getAttemptOrdinal() {
        return attemptOrdinal;
    }

    public int getProgressPercent() {
        return progressPercent;
    }

    public String getProgressStage() {
        return progressStage;
    }

    public Instant getLastEventAt() {
        return lastEventAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
