package com.pokeclip.auth.delegation;

import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class InvitationService {

    private static final Logger log = LoggerFactory.getLogger(InvitationService.class);

    /** 사용자 결정(2026-08-18). 재초대하면 이 값으로 다시 늘어난다. */
    static final Duration VALID_FOR = Duration.ofDays(7);
    /**
     * 스트리머당 살아있는 초대 상한. 넘으면 취소로 자리를 비운다.
     *
     * <p><b>근사값이다 — 동시 요청에서는 넘는다.</b> 「세고 나서 쓴다」 사이에 락이 없다.
     * 같은 상대는 부분 유니크 인덱스가 막지만 <b>서로 다른 상대</b>면 안 걸린다.
     * 실측: PENDING 19개를 심고 서로 다른 상대 8명에게 8스레드로 동시 초대 →
     * <b>살아있는 초대 27개, 거부 0건</b>(transaction-auditor 라운드 2).
     *
     * <p>일부러 이대로 둔다. 상한은 자원 가드지 보안 경계가 아니고, 초과분은 동시 요청 수만큼
     * 유한하며, 권한 없는 편집자가 생기지도 않는다. 정확히 막으려면
     * {@code users} 행 락({@code findByIdForUpdate})으로 직렬화해야 하는데, 그 락은
     * TokenService.rotate · StreamKeyService.rotate · ChzzkTokenRefresher가 이미 쓰고 있어
     * 초대까지 얹으면 대기 그래프만 넓어진다. 상한 하나에 치를 값이 아니다.
     */
    static final int MAX_PENDING = 20;

    private final EditorInvitationRepository invitations;
    private final EditorDelegationRepository delegations;
    private final InvitationWriter writer;
    private final UserRepository users;

    /**
     * 이메일로 계정을 찾아 초대한다. 살아있는 초대가 이미 있으면 새 행을 만들지 않고
     * 기한만 민다 — 버튼을 연타하거나 같은 요청이 두 번 와도 결과가 같다.
     *
     * <p><b>이 메서드에 &#64;Transactional을 걸지 않는다.</b> 동시 요청 둘이 같은 상대를
     * 초대하면 부분 유니크 인덱스가 하나를 거부하는데, 진 쪽이 상대가 만든 행을 재조회해
     * 기한을 밀어야 한다(UserService.findOrCreate와 같은 구조).
     *
     * <p>다만 <b>여기에 &#64;Transactional을 걸어도 지금은 안 죽는다 — 실측했다</b>
     * (2026-08-18). InvitationWriter.create가 REQUIRES_NEW라 바깥 트랜잭션을 매달아 두고
     * 별도 세션에서 실패하기 때문이다. 둘을 같이 없애야(여기에 &#64;Transactional +
     * create를 REQUIRED로) 재조회가 오염된 세션에서 돌아 죽는다. 겹치는 방어를 일부러
     * 둘 다 둔 것이니 <b>한쪽만 지우고 초록인 것을 근거로 나머지를 지우지 않는다.</b>
     *
     * <p>"이미 편집자"는 여기 조회로 대부분 걸러지고, 조회와 수락 사이의 경합으로 뚫린
     * 극소수는 수락 시점에 위임 유일 인덱스가 막는다.
     */
    public EditorInvitation invite(Long streamerId, String email) {
        User invitee = users.findByEmail(email.toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new DelegationException(
                        DelegationFailure.INVITEE_NOT_FOUND, "그 이메일로 가입한 계정이 없다"));
        if (invitee.getId().equals(streamerId)) {
            throw new DelegationException(DelegationFailure.SELF_INVITE, "자기 자신은 초대할 수 없다");
        }
        requireNotEditor(streamerId, invitee.getId());

        Instant now = Instant.now();
        Instant expiresAt = now.plus(VALID_FOR);
        Optional<EditorInvitation> existing = pendingBetween(streamerId, invitee.getId());
        if (existing.isPresent()) {
            EditorInvitation row = existing.get();
            // 만료된 행을 되살리는 것도 살아있는 자리를 새로 차지하는 것이라 같은 문을 지나야 한다.
            // 안 그러면 만료 행을 쌓아 두고 순차로 되살려 상한을 임의로 넘길 수 있다.
            // 살아있는 행을 연장하는 경우는 자기 자신이 이미 세어져 있어 여기 안 걸린다 —
            // 그래서 view()로 만료인 것만 검사한다(경계 판정을 엔티티와 한 곳에서 쓴다).
            if (row.view(now) == InvitationView.EXPIRED) {
                requireCapacity(streamerId, now);
            }
            EditorInvitation extended = extendIfStillPending(row.getId(), expiresAt, now, streamerId);
            if (extended != null) {
                return extended;
            }
            // 0행 = 사전 조회와 UPDATE 사이에 상대가 응답했다. 처리된 초대를 그대로 돌려주면
            // 스트리머 화면엔 "보냈다"인데 상대 초대함엔 없다. 아래로 흘려 새 초대를 만든다 —
            // 거절·취소 뒤 재초대는 허용된 동작이고(부분 유니크가 PENDING만 본다).
            //
            // 다만 그 응답이 "수락"이었으면 위임이 방금 생겼다. 그때 새 초대를 만들면
            // 그 초대는 영원히 수락 불가다 — 매번 uq_delegations_alive_pair에 걸려 롤백된다.
            // 그래서 만들기 전에 위임을 다시 본다.
            requireNotEditor(streamerId, invitee.getId());
        }

        requireCapacity(streamerId, now);

        return createOrExtend(streamerId, invitee.getId(), expiresAt, now);
    }

    /** 사전 조회와 저장 사이에 위임이 생길 수 있어 두 번 부른다. */
    private void requireNotEditor(Long streamerId, Long inviteeId) {
        if (delegations.existsByStreamerIdAndEditorIdAndRevokedAtIsNull(streamerId, inviteeId)) {
            throw new DelegationException(DelegationFailure.ALREADY_EDITOR, "이미 편집자다");
        }
    }

    /** 살아있는 초대 = PENDING + 미만료. 새 자리를 차지하는 경로마다 이 문을 지난다. */
    private void requireCapacity(Long streamerId, Instant now) {
        if (invitations.countByStreamerIdAndStatusAndExpiresAtGreaterThanEqual(
                streamerId, InvitationStatus.PENDING, now) >= MAX_PENDING) {
            throw new DelegationException(DelegationFailure.TOO_MANY_PENDING, "살아있는 초대가 상한에 찼다");
        }
    }

    /**
     * 저장을 시도하고, 부분 유니크 인덱스에 졌으면 상대가 만든 행을 읽어 기한을 민다.
     * 두 요청 다 201을 받고 사물함에는 한 장만 남는다 — 결과가 같아야 한다.
     *
     * <p><b>두 번으로 끊는다.</b> 경합에 져서 재조회한 초대마저 그 사이 응답되면 한 번 더
     * 만들어 본다. 재귀로 두면 경합이 반복될 때 끝나지 않는다.
     */
    private EditorInvitation createOrExtend(Long streamerId, Long inviteeId,
                                            Instant expiresAt, Instant now) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                EditorInvitation created = writer.create(streamerId, inviteeId, expiresAt, now);
                log.info("auth.invitation.created streamerId={} invitationId={}", streamerId, created.getId());
                return created;
            } catch (DataIntegrityViolationException e) {
                // 경합에 졌다. 위 트랜잭션은 롤백됐으니 여기서 새로 읽는다.
                //
                // 이 orElseThrow는 루프를 통째로 벗어난다 — 재조회가 빈손이면 두 번째 시도를
                // 못 쓰고 1회에 끝난다. for(<2)를 보고 "2회 재시도"로 읽으면 안 된다.
                // 재조회가 빈손인 경우는 create에 지고 그 찰나에 상대가 응답한 이중 경합인데,
                // 그때는 PENDING이 없어 새로 만들 수 있는 상태인데도 409로 답한다(한 번 더
                // 누르면 성공한다). 데이터도 카운터도 안 깨져 그대로 뒀다.
                EditorInvitation other = pendingBetween(streamerId, inviteeId).orElseThrow(() -> e);
                EditorInvitation extended = extendIfStillPending(other.getId(), expiresAt, now, streamerId);
                if (extended != null) {
                    return extended;
                }
            }
        }
        throw new DelegationException(
                DelegationFailure.INVITATION_NOT_PENDING, "경합이 반복돼 초대를 확정하지 못했다");
    }

    /**
     * 기한을 민다. <b>0행이면 null이다</b> — 그 사이 상대가 응답했다는 뜻이고,
     * 처리된 초대를 "연장됐다"로 돌려주면 안 된다. 호출부가 새로 만들지 판단한다.
     */
    private EditorInvitation extendIfStillPending(Long invitationId, Instant expiresAt,
                                                  Instant now, Long streamerId) {
        if (writer.extend(invitationId, expiresAt, now) == 0) {
            return null;
        }
        log.info("auth.invitation.extended streamerId={} invitationId={}", streamerId, invitationId);
        return invitations.findById(invitationId).orElseThrow();
    }

    private Optional<EditorInvitation> pendingBetween(Long streamerId, Long inviteeId) {
        return invitations.findByStreamerIdAndInviteeIdAndStatus(
                streamerId, inviteeId, InvitationStatus.PENDING);
    }
}
