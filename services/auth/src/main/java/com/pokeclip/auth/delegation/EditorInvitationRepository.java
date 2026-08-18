package com.pokeclip.auth.delegation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface EditorInvitationRepository extends JpaRepository<EditorInvitation, Long> {

    Optional<EditorInvitation> findByStreamerIdAndInviteeIdAndStatus(
            Long streamerId, Long inviteeId, InvitationStatus status);

    /** 살아있는 초대 = PENDING + 미만료. 상한 20을 세는 기준이다. */
    int countByStreamerIdAndStatusAndExpiresAtAfter(
            Long streamerId, InvitationStatus status, Instant now);

    List<EditorInvitation> findByStreamerIdOrderByCreatedAtDesc(Long streamerId);

    List<EditorInvitation> findByInviteeIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
            Long inviteeId, InvitationStatus status, Instant now);

    /**
     * 재초대. 살아있는 초대의 기한만 민다 — 새 행을 만들지 않는다.
     * 이미 처리된 초대는 status 조건에 걸려 되살아나지 않는다.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE EditorInvitation i SET i.expiresAt = :expiresAt, i.updatedAt = :now "
            + "WHERE i.id = :id AND i.status = com.pokeclip.auth.delegation.InvitationStatus.PENDING")
    int extend(@Param("id") Long id, @Param("expiresAt") Instant expiresAt, @Param("now") Instant now);

    /**
     * 상태를 바꾸는 유일한 통로. 조건을 DB에 넘겨 읽고-쓰기 사이의 틈을 없앤다 —
     * 취소와 수락이 겹쳐도 하나만 성공한다. 바뀐 행이 0이면 호출부가 사유를 판정한다.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE EditorInvitation i SET i.status = :next, i.respondedAt = :now, i.updatedAt = :now "
            + "WHERE i.id = :id AND i.status = com.pokeclip.auth.delegation.InvitationStatus.PENDING "
            + "AND i.expiresAt >= :now")
    int respond(@Param("id") Long id, @Param("next") InvitationStatus next, @Param("now") Instant now);

    /**
     * 취소만 만료를 보지 않는다. 스트리머가 만료된 초대를 거둬들이는 것은 막을 이유가 없고,
     * 막으면 살아있는 초대 슬롯이 만료 뒤에도 목록에 남아 혼란만 준다.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE EditorInvitation i SET i.status = com.pokeclip.auth.delegation.InvitationStatus.CANCELED, "
            + "i.respondedAt = :now, i.updatedAt = :now "
            + "WHERE i.id = :id AND i.status = com.pokeclip.auth.delegation.InvitationStatus.PENDING")
    int cancel(@Param("id") Long id, @Param("now") Instant now);
}
