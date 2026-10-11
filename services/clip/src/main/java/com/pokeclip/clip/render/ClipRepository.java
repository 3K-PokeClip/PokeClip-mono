package com.pokeclip.clip.render;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ClipRepository extends JpaRepository<Clip, Long> {

    /** 방송 번호를 같이 건다 — 번호만으로 찾으면 남의 방송 영상이 내 방송 경로로 열린다({@code RecipeRepository}와 같은 규칙). */
    Optional<Clip> findByIdAndStreamId(Long id, String streamId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Clip c where c.id = :id")
    Optional<Clip> findByIdForUpdate(@Param("id") Long id);

    /** 같은 편집본 같은 판의 <b>진행 중</b> 영상. 부분 색인 {@code uq_clips_open_recipe}가 보장하듯 많아야 하나다. */
    @Query(value = """
            SELECT * FROM clips
             WHERE recipe_id = :recipeId AND recipe_version = :recipeVersion
               AND status IN ('queued', 'rendering')
             LIMIT 1
            """, nativeQuery = true)
    Optional<Clip> findOpen(@Param("recipeId") long recipeId, @Param("recipeVersion") int recipeVersion);

    /**
     * 같은 편집본 같은 판의 <b>진행 중</b> 영상 줄을 잠그고 번호만 돌려준다(POK-291 로컬 리뷰 1라운드). 잠금 세기는
     * {@link #findByIdForUpdate}와 같다(JPA의 비관적 쓰기 = {@code FOR NO KEY UPDATE}): 렌더 성공 보고가 그 줄을 쥐고 있으면 끝날 때까지
     * 기다리고, 그 사이 완성·실패가 됐으면 조건을 다시 보아 빈 값이다. 엔티티가 아니라 번호인 이유: 같은 트랜잭션에 이미 올라온
     * 엔티티가 있으면 잠금 조회도 그 옛 상태를 돌려준다.
     */
    @Query(value = """
            SELECT id FROM clips
             WHERE recipe_id = :recipeId AND recipe_version = :recipeVersion
               AND status IN ('queued', 'rendering')
               FOR NO KEY UPDATE
            """, nativeQuery = true)
    Optional<Long> lockOpenId(@Param("recipeId") long recipeId, @Param("recipeVersion") int recipeVersion);

    /** 같은 편집본 같은 판의 가장 최근 영상(POK-291). 「영상 만들기」가 진행 중·이미 완성·다시 만들기를 가른다. */
    @Query(value = """
            SELECT * FROM clips
             WHERE recipe_id = :recipeId AND recipe_version = :recipeVersion
             ORDER BY id DESC
             LIMIT 1
            """, nativeQuery = true)
    Optional<Clip> findLatestOfVersion(@Param("recipeId") long recipeId, @Param("recipeVersion") int recipeVersion);
}
