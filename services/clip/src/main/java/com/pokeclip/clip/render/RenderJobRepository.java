package com.pokeclip.clip.render;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RenderJobRepository extends JpaRepository<RenderJob, UUID> {

    /** 보고를 반영하는 동안 같은 주문의 다른 보고를 세운다 — 토큰 발급·판정이 한 줄씩 서야 겹친 STARTED 둘이 같은 순번을 못 받는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from RenderJob j where j.id = :id")
    Optional<RenderJob> findByIdForUpdate(@Param("id") UUID id);

    Optional<RenderJob> findByClipId(long clipId);

    /** 보관함 목록(POK-243)이 한 장의 영상들에 딸린 주문을 한 번에 읽는다 — 줄마다 묻지 않는다. */
    List<RenderJob> findByClipIdIn(Collection<Long> clipIds);

    /** 아직 큐가 안 받은 주문 중 {@code before}보다 먼저 만든 것(outbox 재전송 대상). 방금 만든 것은 첫 발행이 진행 중이라 뺀다. */
    @Query("select j from RenderJob j where j.publishedAt is null and j.createdAt < :before order by j.createdAt")
    List<RenderJob> findUnpublishedBefore(@Param("before") Instant before);
}
