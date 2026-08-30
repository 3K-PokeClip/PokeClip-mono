package com.pokeclip.auth.delegation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * <b>{@code clearAutomatically = true} 셋에 대하여</b> — 셋의 처지가 다르다.
 *
 * <p>{@code respond}는 <b>재어진다.</b> {@code accept}가 한 트랜잭션 안에서 읽고
 * (mine) → UPDATE → 다시 읽으므로(reasonFor) 1차 캐시가 겹친다. 이 옵션을 떼면
 * {@code InvitationStaleCacheTest}가 빨간불이 되고, 그때 나오는 답이
 * <b>「취소된 초대를 기한이 지났다고 답한다」</b>({@code expected: INVITATION_NOT_PENDING
 * but was: INVITATION_EXPIRED})다. 다만 <b>읽은 뒤 UPDATE 전에 상태가 바뀐 경우에만</b>
 * 결과가 갈리므로, 그 사이를 일부러 만드는 그 테스트 하나만 이걸 잰다.
 *
 * <p><b>{@code extend}와 {@code cancel}은 처지가 다르다</b>(transaction-auditor 라운드 3).
 * 둘 다 지금은 떼도 전체가 초록이지만 이유가 같지 않다.
 *
 * <ul>
 * <li>{@code extend} — <b>소비자가 있다.</b> {@code extendExisting}이 UPDATE 직후
 *     {@code findById}로 다시 읽는다. 지금 초록인 것은 {@code invite}에 트랜잭션이 없어
 *     그 재조회가 새 EntityManager에서 돌기 때문일 뿐이고, <b>재는 방법은 있다</b> —
 *     {@code InvitationStaleCacheTest}가 쓴 TransactionTemplate 수법으로 바깥 트랜잭션을
 *     열면 연장 전 기한을 읽는 것이 관측된다. 안 넣은 것은 오늘 열리는 프로덕션 경로가
 *     없어서지 못 재서가 아니다.</li>
 * <li>{@code cancel} — <b>소비자가 아예 없다.</b> UPDATE가 0행이면 고정 사유를 던질 뿐
 *     {@code reasonFor} 같은 재조회를 하지 않는다. 읽는 쪽이 없으니 이 옵션이 결과를
 *     바꿀 수 없다.</li>
 * </ul>
 *
 * <b>둘 다 지우지 않는다</b> — 호출부가 언젠가 바깥 트랜잭션 안으로 들어가거나
 * {@code cancel}에 재조회가 붙으면 respond와 똑같은 함정이 열린다.
 *
 * <p>InvitationWriter의 「겹치는 방어」와 같은 모양이다 —
 * <b>한쪽을 지워도 초록인 것을 근거로 나머지를 지우지 않는다.</b>
 */
public interface EditorInvitationRepository extends JpaRepository<EditorInvitation, Long> {

    Optional<EditorInvitation> findByStreamerIdAndInviteeIdAndStatus(
            Long streamerId, Long inviteeId, InvitationStatus status);

    /**
     * 살아있는 초대 = PENDING + 미만료. 상한 20을 세는 기준이다.
     *
     * <p><b>After가 아니라 GreaterThanEqual이다.</b> Spring Data의 {@code After}는 strict
     * {@code >}라 {@code now == expiresAt}인 경계에서 이 초대를 안 센다. 그러면
     * {@link EditorInvitation#view}·{@code respond}(둘 다 경계를 살아있다고 본다)와
     * 판정이 갈려, 상한 테스트가 경계 시각을 쓰는 순간 단언이 자동으로 참이 된다.
     */
    int countByStreamerIdAndStatusAndExpiresAtGreaterThanEqual(
            Long streamerId, InvitationStatus status, Instant now);

    List<EditorInvitation> findByStreamerIdOrderByCreatedAtDesc(Long streamerId);

    /** 경계를 살아있다고 보는 이유는 위 count와 같다 — 네 자리가 같은 뜻이어야 한다. */
    List<EditorInvitation> findByInviteeIdAndStatusAndExpiresAtGreaterThanEqualOrderByCreatedAtDesc(
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

    /**
     * 이 회원이 낀 살아있는 초대를 <b>양쪽 방향 모두</b> 거둬들인다(탈퇴).
     *
     * <p>🔴 <b>받은 쪽이 탈퇴해도 {@code CANCELED}다</b>(PRD D9). {@code DECLINED}로 적으면
     * 스트리머 화면에 「거절함」으로 보이는데, 사람이 한 응답과 계정이 사라진 것은 다른 사건이다 —
     * 위임에 {@code WITHDRAWAL}을 만든 이유와 같다.
     * 새 상태를 안 만든 근거: 웹이 {@code CANCELED}를 화면에 안 그린다(보낸 목록에서 PENDING만 거른다).
     * <b>그리기 시작하면 그때 새 값을 넣는다.</b>
     *
     * <p>만료를 보지 않는 것은 위 {@code cancel}과 같다 — 거둬들이는 것을 막을 이유가 없다.
     * {@code status = PENDING}은 남긴다: 이미 응답한 초대를 되돌리면 <b>수락까지 끝나 위임을 만든
     * 초대가 「취소됨」이 되어</b> 살아있는 위임의 부모가 CANCELED인 모순이 남는다.
     *
     * <p>🔴 회원 조건이 한 줄에 두 갈래인 것은 {@code EditorDelegationRepository.revokeAllOfUser}와 같다 —
     * 그쪽 주석의 경고가 여기에도 그대로 걸린다.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE EditorInvitation i SET i.status = com.pokeclip.auth.delegation.InvitationStatus.CANCELED, "
            + "i.respondedAt = :now, i.updatedAt = :now "
            + "WHERE i.status = com.pokeclip.auth.delegation.InvitationStatus.PENDING "
            + "AND (i.streamerId = :userId OR i.inviteeId = :userId)")
    int cancelAllOfUser(@Param("userId") Long userId, @Param("now") Instant now);
}
