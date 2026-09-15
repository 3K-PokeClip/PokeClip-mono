package com.pokeclip.clip.render;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 완성 영상 한 벌({@code clips} 한 줄). <b>INSERT는 이 엔티티로 하지 않는다</b> — 「같은 편집본 같은 판의 진행 중
 * 주문은 하나」를 부분 UNIQUE 색인과 {@code ON CONFLICT DO NOTHING}으로 가르는데 JPA save는 그 반환값을 못 준다
 * ({@link ClipInserter}). 그래서 {@code @GeneratedValue}가 없다. 상태 갱신은 이 엔티티로 한다.
 */
@Entity
@Table(name = "clips")
public class Clip {

    @Id
    private Long id;

    @Column(name = "stream_id", nullable = false, length = 128)
    private String streamId;

    @Column(name = "recipe_id", nullable = false)
    private long recipeId;

    @Column(name = "recipe_version", nullable = false)
    private int recipeVersion;

    @Column(name = "requested_by", nullable = false, length = 128)
    private String requestedBy;

    @Convert(converter = ClipStatusConverter.class)
    @Column(name = "status", nullable = false, length = 16)
    private ClipStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "outputs", columnDefinition = "jsonb")
    private String outputs;

    @Column(name = "error_code", length = 32)
    private String errorCode;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Clip() {
    }

    void rendering() {
        this.status = ClipStatus.RENDERING;
        this.updatedAt = Instant.now();
    }

    void rendered(String outputsJson) {
        this.status = ClipStatus.RENDERED;
        this.outputs = outputsJson;
        this.errorCode = null;
        this.errorMessage = null;
        this.updatedAt = Instant.now();
    }

    /** 코드 32자·메시지 512자로 자른다 — 일꾼이 보낸 자유 문자열이라 길이가 우리 손에 없다. 안 자르면 칸 길이에서 500이 나고 실패 사실이 안 남는다(1판 codex). */
    void failed(String code, String message) {
        this.status = ClipStatus.FAILED;
        this.errorCode = code == null ? null : code.substring(0, Math.min(code.length(), 32));
        this.errorMessage = message == null ? null : message.substring(0, Math.min(message.length(), 512));
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getStreamId() {
        return streamId;
    }

    public long getRecipeId() {
        return recipeId;
    }

    public int getRecipeVersion() {
        return recipeVersion;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public ClipStatus getStatus() {
        return status;
    }

    public String getOutputs() {
        return outputs;
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
