package com.pokeclip.clip.upload;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ClipUploadRepository extends JpaRepository<ClipUpload, Long> {

    /** 일꾼 보고를 한 줄씩 세운다: 겹친 두 일꾼의 주소 기록이 둘 다 「처음」으로 읽히면 영상이 둘 생길 수 있다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from ClipUpload u where u.id = :id")
    Optional<ClipUpload> findByIdForUpdate(@Param("id") long id);

    /** 그 영상 그 벌의 살아 있는(실패 아닌) 업로드. 부분 UNIQUE {@code uq_clip_uploads_active}가 많아야 하나를 보장한다. */
    @Query(value = """
            SELECT * FROM clip_uploads
             WHERE clip_id = :clipId AND output_id = :outputId AND status <> 'failed'
             LIMIT 1
            """, nativeQuery = true)
    Optional<ClipUpload> findActive(@Param("clipId") long clipId, @Param("outputId") String outputId);

    /**
     * 같은 편집본 같은 판의 영상들(재렌더로 여럿일 수 있다) 중 어느 것에든 살아 있는(실패 아닌) 업로드(POK-291).
     * 「판 하나는 채널에 하나」의 판정이다. 부르기 전에 {@link UploadInserter#lockVersion}을 잡는다.
     */
    @Query(value = """
            SELECT u.* FROM clip_uploads u
              JOIN clips c ON c.id = u.clip_id
             WHERE c.recipe_id = :recipeId AND c.recipe_version = :recipeVersion AND u.status <> 'failed'
             ORDER BY u.id DESC
             LIMIT 1
            """, nativeQuery = true)
    Optional<ClipUpload> findActiveForVersion(@Param("recipeId") long recipeId, @Param("recipeVersion") int recipeVersion);

    Optional<ClipUpload> findFirstByClipIdOrderByIdDesc(long clipId);

    /** 영상 여러 벌의 가장 최근 업로드를 한 번에: 보관함 목록이 줄마다 묻지 않게. */
    @Query(value = """
            SELECT DISTINCT ON (clip_id) * FROM clip_uploads
             WHERE clip_id IN (:clipIds)
             ORDER BY clip_id, id DESC
            """, nativeQuery = true)
    List<ClipUpload> findLatestByClipIdIn(@Param("clipIds") Collection<Long> clipIds);

    @Query("select u from ClipUpload u where u.publishedAt is null and u.createdAt < :before order by u.createdAt")
    List<ClipUpload> findUnpublishedBefore(@Param("before") Instant before);
}
