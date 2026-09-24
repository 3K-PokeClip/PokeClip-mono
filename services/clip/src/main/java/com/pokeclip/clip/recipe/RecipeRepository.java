package com.pokeclip.clip.recipe;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RecipeRepository extends JpaRepository<Recipe, Long> {

    /** 만든 순서. 페이징이 없다 — 레시피는 사람이 손으로 만드는 것이라 방송 하나에 수십 벌이다(README). */
    List<Recipe> findAllByStreamIdOrderByIdAsc(String streamId);

    /**
     * <b>방송 번호를 같이 건다.</b> 번호만으로 찾으면 남의 방송 레시피가 내 방송 경로로 열린다 —
     * 자격 판정은 경로의 방송에 대해 했지 레시피의 방송에 대해 한 것이 아니다.
     */
    Optional<Recipe> findByIdAndStreamId(Long id, String streamId);

    /**
     * 고치는 동안 같은 레시피의 다른 수정을 세운다. 없으면 두 편집자가 같은 판을 읽고 둘 다 +1을 써서
     * <b>판이 하나 사라진다</b>(둘 다 2가 된다). 렌더 주문이 판 번호로 레시피를 가리키므로 번호가 겹치면 안 된다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Recipe r where r.id = :id and r.streamId = :streamId")
    Optional<Recipe> findByIdAndStreamIdForUpdate(@Param("id") Long id, @Param("streamId") String streamId);
}
