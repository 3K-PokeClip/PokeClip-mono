package com.pokeclip.auth.youtube;

import com.pokeclip.auth.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 「이 토큰을 구글에서 철회해도 되는가」를 한 번의 일관된 읽기로 판정한다.
 *
 * <p>구글 revoke는 그 <b>구글 계정</b>이 이 프로젝트에 준 동의 <b>전부</b>를 죽인다. 그래서 판단 범위가
 * revoke의 영향 범위와 같아야 하는데, 봇 리뷰(PR #116)가 우리 가드의 범위가 좁다는 것을 둘 짚었다:
 *
 * <ul>
 *   <li><b>시점</b> — 재연동이 아직 <b>커밋 전</b>이면 조회에 안 보인다. 회원 행 락으로 그 트랜잭션과
 *       직렬화해 커밋을 기다린 뒤 본다({@code YoutubeLinkWriter.create}·{@code revoke}가 같은 락을 쥔다).</li>
 *   <li><b>대상</b> — 「이 회원의 살아있는 행」만 보면 <b>같은 구글 계정</b>을 쓰는 다른 회원의 멀쩡한
 *       토큰을 죽인다(409 경로가 정확히 그것이다 — 채널이 같으면 계정도 같다).</li>
 * </ul>
 *
 * <p><b>왜 별도 빈인가</b> — 락은 {@code PESSIMISTIC_WRITE}라 트랜잭션이 필요한데,
 * {@link YoutubeTokenDiscarder}는 그 뒤에 구글 HTTP(최대 5초)를 부른다. 한 메서드에 두면 커넥션을 쥔 채
 * 외부 호출을 기다려 풀이 마른다. 자기 호출은 프록시를 안 타므로(같은 클래스의 {@code @Transactional}은
 * 무시된다) 아예 빈을 나눴다 — <b>트랜잭션은 여기서 끝나고 revoke는 그 밖에서 돈다.</b>
 */
@Component
@RequiredArgsConstructor
public class YoutubeDiscardGuard {

    private final UserRepository users;
    private final YoutubeChannelLinkRepository links;

    /**
     * 버리면 안 되는 상황인지. 락을 잡은 뒤 둘을 <b>같은 스냅샷에서</b> 본다.
     *
     * @param channelId 그 토큰이 가리키는 채널. 모르는 갈래(교환 실패·scope 부족)는 null — 그때는 채널 검사를 건너뛴다
     */
    @Transactional
    public boolean blocksDiscard(Long userId, String channelId) {
        // 락이 먼저다. 진행 중인 create·revoke가 있으면 그 커밋까지 기다렸다가 읽는다.
        // 회원이 없으면(계정 삭제 등) 잠글 것도 없고 지킬 연동도 없다 — 그대로 버린다.
        if (users.findByIdForUpdate(userId).isEmpty()) {
            return false;
        }
        if (links.findByUserIdAndRevokedAtIsNull(userId).isPresent()) {
            return true;
        }
        // 채널이 남에게 살아있으면 그 사람의 구글 계정이 곧 이 토큰의 계정이다 — 남의 연동을 끊게 된다.
        return channelId != null && links.findByChannelIdAndRevokedAtIsNull(channelId).isPresent();
    }
}
