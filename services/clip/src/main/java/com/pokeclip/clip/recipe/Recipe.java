package com.pokeclip.clip.recipe;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * 편집 기록 한 벌({@code recipes} 한 줄). {@code JumpCard}와 달리 <b>JPA로 쓴다</b> — 이 표에는 순번 트리거가
 * 없고 DB 시계가 필요한 칸도 없어 네이티브 SQL을 고를 이유가 없다. {@code Broadcast}와 같은 모양이다.
 *
 * <p>jsonb 칸 셋은 {@link RecipeDocument}의 조각을 직렬화한 문자열이다. 이 엔티티는 그 안을 해석하지 않는다 —
 * 읽고 쓰는 것은 {@link RecipeService}가 한다.
 */
@Entity
@Table(name = "recipes")
public class Recipe {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stream_id", nullable = false, length = 128)
    private String streamId;

    @Column(name = "creator_id", nullable = false, length = 128)
    private String creatorId;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Column(name = "recipe_version", nullable = false)
    private int recipeVersion;

    @Column(name = "cut_in_at_ms")
    private Long cutInAtMs;

    @Column(name = "cut_out_at_ms")
    private Long cutOutAtMs;

    // @JdbcTypeCode가 없으면 드라이버가 varchar로 보내 jsonb 칸에 못 들어간다 — 값이 null이어도 터지고
    // ddl-auto=validate는 못 잡는다(Broadcast.trackManifest·JumpCard.evidence와 같은 함정).
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "outputs", nullable = false, columnDefinition = "jsonb")
    private String outputs;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "audio", nullable = false, columnDefinition = "jsonb")
    private String audio;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "subtitles", columnDefinition = "jsonb")
    private String subtitles;

    // Broadcast와 달리 앱이 채운다 — DB DEFAULT에 맡기면 save 뒤 엔티티의 이 칸이 null이라 응답에 못 싣는다.
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Recipe() {
    }

    /** 첫 저장. {@code recipeVersion}은 1에서 시작한다. */
    static Recipe create(String streamId, String creatorId, int schemaVersion,
                         Long cutInAtMs, Long cutOutAtMs, String outputs, String audio, String subtitles) {
        Recipe recipe = new Recipe();
        recipe.streamId = streamId;
        recipe.creatorId = creatorId;
        recipe.schemaVersion = schemaVersion;
        recipe.recipeVersion = 1;
        recipe.cutInAtMs = cutInAtMs;
        recipe.cutOutAtMs = cutOutAtMs;
        recipe.outputs = outputs;
        recipe.audio = audio;
        recipe.subtitles = subtitles;
        recipe.createdAt = Instant.now();
        recipe.updatedAt = recipe.createdAt;
        return recipe;
    }

    /**
     * 내용을 통째로 갈아 끼우고 판을 하나 올린다. <b>부분 수정은 없다</b> — 편집기는 늘 전체를 보내고,
     * 칸 하나만 받는 문을 열면 「나머지는 그대로」의 뜻이 두 벌이 된다. {@code creatorId}는 안 바뀐다.
     */
    void replace(int schemaVersion, Long cutInAtMs, Long cutOutAtMs, String outputs, String audio, String subtitles) {
        this.schemaVersion = schemaVersion;
        this.recipeVersion++;
        this.cutInAtMs = cutInAtMs;
        this.cutOutAtMs = cutOutAtMs;
        this.outputs = outputs;
        this.audio = audio;
        this.subtitles = subtitles;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getStreamId() {
        return streamId;
    }

    public String getCreatorId() {
        return creatorId;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public int getRecipeVersion() {
        return recipeVersion;
    }

    public Long getCutInAtMs() {
        return cutInAtMs;
    }

    public Long getCutOutAtMs() {
        return cutOutAtMs;
    }

    public String getOutputs() {
        return outputs;
    }

    public String getAudio() {
        return audio;
    }

    public String getSubtitles() {
        return subtitles;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
