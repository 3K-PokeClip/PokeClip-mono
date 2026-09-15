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
}
