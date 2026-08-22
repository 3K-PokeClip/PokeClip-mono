package com.pokeclip.auth.delegation;

import com.pokeclip.auth.delegation.api.dto.DelegationResponse;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DelegationService {

    private static final Logger log = LoggerFactory.getLogger(DelegationService.class);

    private final EditorDelegationRepository delegations;
    private final UserRepository users;

    /** 내 편집자들. 살아있는 것만. */
    @Transactional(readOnly = true)
    public List<DelegationResponse> asStreamer(Long streamerId) {
        return toResponses(
                delegations.findByStreamerIdAndRevokedAtIsNullOrderByGrantedAtDesc(streamerId),
                EditorDelegation::getEditorId);
    }

    /** 내가 맡은 스트리머들. 살아있는 것만. */
    @Transactional(readOnly = true)
    public List<DelegationResponse> asEditor(Long editorId) {
        return toResponses(
                delegations.findByEditorIdAndRevokedAtIsNullOrderByGrantedAtDesc(editorId),
                EditorDelegation::getStreamerId);
    }

    /**
     * clip이 묻는 「이 사람과 이 스트리머는 무슨 사이인가」. 회원 표는 보지 않는다 —
     * 번호가 같으면 OWNER, 살아있는 위임이 있으면 EDITOR, 나머지 전부 NONE.
     *
     * <p><b>인자 순서가 곧 방향이다.</b> 위임은 스트리머 → 편집자 한 방향이고 조회 인자가 둘 다
     * Long이라 바꿔 넣어도 컴파일러가 못 잡는다. {@code DelegationResolveTest.방향이_뒤집히면_NONE}이
     * 이 줄을 잰다.
     */
    @Transactional(readOnly = true)
    public DelegationRelation relationOf(Long userId, Long streamerUserId) {
        if (userId.equals(streamerUserId)) {
            return DelegationRelation.OWNER;
        }
        if (delegations.existsByStreamerIdAndEditorIdAndRevokedAtIsNull(streamerUserId, userId)) {
            return DelegationRelation.EDITOR;
        }
        return DelegationRelation.NONE;
    }

    /**
     * 스트리머와 편집자가 같은 엔드포인트를 쓴다. 부른 사람이 그 행의 어느 쪽인지로
     * revoked_by가 갈린다 — 내보낸 것과 나간 것은 다른 사건이라 구분해 남긴다.
     *
     * <p>둘 다 아니면 404다. 남의 위임은 존재 여부를 알려주지 않는다.
     *
     * <p><b>「누가 끊었나」는 한 번 정해지면 안 바뀐다.</b> 스트리머가 내보낸 뒤 편집자가 같은
     * 행을 호출해 EDITOR로 덮어쓰면 쫓겨난 사람이 「내가 나갔다」로 이력을 고칠 수 있다.
     * 막는 것이 둘이다 — 조회({@code findByIdAndRevokedAtIsNull})와 revoke JPQL의
     * {@code revokedAt IS NULL}.
     *
     * <p><b>둘은 겹치지만 막는 진입점이 다르다.</b> 조회는 <b>이 서비스 경로만</b> 막고,
     * JPQL 조건은 <b>리포지토리를 직접 부르는 모든 경로</b>를 막는다. 그래서 이 메서드로만
     * 재면 한쪽을 지워도 나머지가 막아 초록이지만(둘 다 빼면 두 번째 해제가 404가 아니라
     * <b>204</b>로 성공하고 revoked_by가 덮어써진다), <b>각각을 재는 테스트가 따로 있다</b> —
     * {@code DelegationRevokeQueryTest}가 리포지토리를 직접 불러 앞단 조회를 건너뛴다.
     * 소유 조건과 {@code revokedAt IS NULL} 둘 다 그 방식으로 계측된다.
     *
     * <p><b>판정 기준을 남긴다</b>(transaction-auditor 라운드 3): 「겹치는 방어라 단독으로는
     * 못 잰다」는 <b>두 방어가 같은 지점을 막을 때만</b> 성립한다. 막는 지점이 다르면 각각을
     * 재는 테스트가 존재한다. 여기가 그 경우였는데 내가 「단독으로는 안 재어진다」로
     * 잘못 결론지어 두 라운드 동안 구멍이 남아 있었다. <b>「못 찾았다」를 「없다」로 적지 않는다.</b>
     */
    @Transactional
    public void revoke(Long actorId, Long delegationId) {
        EditorDelegation row = delegations.findByIdAndRevokedAtIsNull(delegationId)
                .filter(d -> d.getStreamerId().equals(actorId) || d.getEditorId().equals(actorId))
                .orElseThrow(() -> new DelegationException(
                        DelegationFailure.DELEGATION_NOT_FOUND, "없거나 내 위임이 아니다"));
        RevokedBy by = row.getStreamerId().equals(actorId) ? RevokedBy.STREAMER : RevokedBy.EDITOR;
        if (delegations.revoke(delegationId, actorId, by, Instant.now()) == 0) {
            throw new DelegationException(DelegationFailure.DELEGATION_NOT_FOUND, "이미 끊긴 위임이다");
        }
        // 같은 엔드포인트를 양쪽이 쓰므로 actorId가 없으면 누가 눌렀는지 로그만으론 모른다.
        // 바로 위 auth.delegation.granted가 두 주체를 다 남기는 것과 모양을 맞춘다.
        log.info("auth.delegation.revoked delegationId={} actorId={} by={}", delegationId, actorId, by);
    }

    /** 행마다 조회하면 N+1이다. 한 번에 읽어 id로 맞춘다. */
    private List<DelegationResponse> toResponses(List<EditorDelegation> rows,
                                                 Function<EditorDelegation, Long> counterpart) {
        Map<Long, User> byId = users.findAllById(rows.stream().map(counterpart).toList()).stream()
                .collect(Collectors.toMap(User::getId, u -> u));
        return rows.stream()
                .map(d -> {
                    Long id = counterpart.apply(d);
                    return new DelegationResponse(d.getId(), id, byId.get(id).getName(), d.getGrantedAt());
                })
                .toList();
    }
}
