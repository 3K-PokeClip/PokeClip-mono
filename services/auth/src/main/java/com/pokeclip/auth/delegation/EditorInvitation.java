package com.pokeclip.auth.delegation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 초대 한 건. 스트리머가 보내고 편집자가 응답한다.
 *
 * <p>만료를 상태 컬럼에 두지 않는다 — 상태로 두면 만료 시각에 값을 바꿔주는 배치가
 * 필요하다. {@link #view(Instant)}가 조회 시점에 판정한다(ChzzkChannelLink와 같은 원칙).
 */
@Entity
@Table(name = "editor_invitations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EditorInvitation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "streamer_id", nullable = false)
    private Long streamerId;

    @Column(name = "invitee_id", nullable = false)
    private Long inviteeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private InvitationStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static EditorInvitation of(Long streamerId, Long inviteeId, Instant expiresAt, Instant now) {
        EditorInvitation i = new EditorInvitation();
        i.streamerId = streamerId;
        i.inviteeId = inviteeId;
        i.status = InvitationStatus.PENDING;
        i.expiresAt = expiresAt;
        i.createdAt = now;
        i.updatedAt = now;
        return i;
    }

    /**
     * <b>프로덕션에서 부르지 않는다 — 엔티티 단위 테스트가 상태를 만들 때만 쓴다.</b>
     * 실제 상태 전이는 조건을 건 UPDATE({@code respond}·{@code cancel}) 하나뿐이다.
     * 이걸 서비스에서 부르면 읽고-쓰기 사이에 틈이 생겨 취소와 수락이 겹칠 때
     * 둘 다 성공한다 — 조건부 UPDATE로 없앤 경합이 그대로 돌아온다.
     */
    void markResponded(InvitationStatus responded, Instant now) {
        this.status = responded;
        this.respondedAt = now;
        this.updatedAt = now;
    }

    /** 기한과 같은 시각은 아직 살아 있다. 지난 뒤부터 만료다. */
    public InvitationView view(Instant now) {
        if (status != InvitationStatus.PENDING) {
            return InvitationView.valueOf(status.name());
        }
        return now.isAfter(expiresAt) ? InvitationView.EXPIRED : InvitationView.PENDING;
    }
}
