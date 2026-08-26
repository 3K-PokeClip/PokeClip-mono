package com.pokeclip.clip.delegation;

import com.pokeclip.clip.broadcast.BroadcastRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 「이 회원이 이 방송을 볼 수 있나」를 판정하는 <b>유일한 자리</b>. 사람 문 다섯이 나눠 쓴다.
 *
 * <p><b>판정 순서가 계약이다</b> — 방송이 있나 → 번호를 숫자로 바꿀 수 있나 → auth에 묻는다.
 * 바꾸면 없는 방송에도 auth를 두드리게 되고, 그것은 auth의 {@code NONE} 카운터를 오염시킨다
 * (그 숫자는 「Media가 보내는 번호가 우리 회원 번호가 아니다」를 드러내려고 있는 장치다 —
 * README auth 절).
 *
 * <p><b>{@code OWNER}와 {@code EDITOR}를 가르지 않는다</b>(PRD 결정). 편집자가 카드를 집어
 * 편집하고 숨기는 것이 그 사람의 본업이다. 가르고 싶어지면 그때 새 규칙을 얹는다 —
 * 좁은 것을 넓히는 편이 반대보다 안전하다.
 *
 * <p><b>{@code @Transactional}이 없다.</b> auth 왕복이 최대 7초인데 트랜잭션 안에서 돌면
 * 그동안 커넥션을 쥔다. 선례인 {@code SegmentQueryService}도 같은 이유로 없다.
 */
@Component
public class BroadcastAccessGuard {

    private static final Logger log = LoggerFactory.getLogger(BroadcastAccessGuard.class);

    private final BroadcastRepository broadcasts;
    private final DelegationResolveClient delegation;

    BroadcastAccessGuard(BroadcastRepository broadcasts, DelegationResolveClient delegation) {
        this.broadcasts = broadcasts;
        this.delegation = delegation;
    }

    /**
     * 통과하면 아무것도 안 한다.
     *
     * @param requesterSubject JWT {@code sub} — 우리가 발급·검증한 토큰의 회원 번호(문자열)
     * @throws AccessErrors.NotViewableException 방송이 없거나 볼 자격이 없다 (404, 같은 본문)
     * @throws AccessErrors.AuthUnavailableException 자격을 물어보지 못했다 (503)
     */
    public void requireViewable(String requesterSubject, String streamId) {
        // ① 방송이 먼저다 — auth에 물어볼 스트리머 번호가 이 줄에 있다.
        //
        // 🔴 엔티티가 아니라 스칼라로 뽑는다. 부르는 쪽이 같은 트랜잭션 안에서 방송을 다시
        //    읽는 자리가 있고(통로 열기), 여기서 엔티티를 올려 두면 그 재조회가 1차 캐시의
        //    낡은 인스턴스를 받는다 — findStreamerIdByStreamId 주석에 재현 기록이 있다.
        String streamerId = broadcasts.findStreamerIdByStreamId(streamId)
                .orElseThrow(() -> new AccessErrors.NotViewableException("broadcast_not_found"));

        long userId = parseNumeric(requesterSubject, "subject_not_numeric", streamId);
        long streamerUserId = parseNumeric(streamerId, "streamer_id_not_numeric", streamId);

        // ② 인자 순서가 (요청자, 스트리머)다. 뒤집으면 스트리머가 자기 방송을 못 보고
        //    증상은 「권한 없음」으로 나온다 — 둘 다 long이라 컴파일러가 안 잡는다.
        ResolveResult relation = delegation.resolve(userId, streamerUserId);
        if (relation == ResolveResult.UNAVAILABLE) {
            // 판정 불가는 거절이다(PRD). 통과로 접으면 auth가 죽은 동안 남남이 남의 방송을 본다.
            throw new AccessErrors.AuthUnavailableException();
        }
        if (relation == ResolveResult.NONE) {
            throw new AccessErrors.NotViewableException("relation_none");
        }
    }

    /**
     * 못 바꾸면 <b>ERROR</b>를 남기고 「볼 수 없다」로 접는다. 주인이 자기 방송을 못 보는데
     * 화면에는 「없는 방송」이라고 나오므로 이 로그가 유일한 발견 수단이다.
     *
     * <p><b>값 자체는 안 찍는다</b> — 어떤 쓰레기가 왔는지가 아니라 어느 방송이 아픈지가
     * 진단이고, {@code streamer_id}는 큐로 받은 값이라 개행이 섞이면 로그 한 줄이 여러 줄로
     * 쪼개져 없던 기록을 위조할 수 있다. {@code streamId}는 싣는데, 이 메서드가 <b>방송 조회가
     * 성공한 뒤에만</b> 불리므로 명부에 실재하는 값이다.
     *
     * <p>{@code SegmentQueryService.parseNumeric}과 같은 모양이고 로그 이름만 다르다
     * ({@code clip.access.*} · {@code clip.segment.*}). 한쪽을 고치면 다른 쪽도 본다 —
     * 합치지 않은 것은 자리가 갈리면 어느 문이 아픈지가 안 보이기 때문이다.
     */
    private long parseNumeric(String value, String reason, String streamId) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            log.error("clip.access.identity_not_numeric reason={} streamId={}", reason, streamId);
            throw new AccessErrors.NotViewableException(reason);
        }
    }
}
