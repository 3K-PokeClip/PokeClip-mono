package com.pokeclip.auth.chzzk;

import com.pokeclip.auth.streamkey.secret.SecretStore;
import com.pokeclip.auth.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * 연동 행의 저장만 담당한다. ChzzkLinkService에서 떼어낸 이유는 StreamKeyCreator와 같다 —
 * &#64;Transactional은 프록시로 동작해서 같은 클래스의 메서드를 직접 부르면 무시된다.
 * 서비스는 외부 HTTP(교환·me)를 트랜잭션 밖에서 하고, 저장만 여기서 트랜잭션이다.
 */
@Component
@RequiredArgsConstructor
public class ChzzkLinkWriter {

    private static final Logger log = LoggerFactory.getLogger(ChzzkLinkWriter.class);

    private final ChzzkChannelLinkRepository links;
    private final SecretStore secretStore;
    private final UserRepository users;
    private final ChzzkTokenDiscarder discarder;
    private final ChzzkCleanupExecutor cleanup;

    /**
     * 회원 행 락 → 채널 중복 확인 → (살아있는 내 연동 폐기) → secrets put 2 → INSERT. 한 커밋.
     * 커밋 뒤: 옛 secrets 삭제 → 로그 → 옛 토큰 revoke(best-effort, 마지막).
     *
     * <p>채널 중복은 DB 부분 유니크(uq_chzzk_links_alive_channel)가 최종 방어다 — 앱 락은
     * 인스턴스가 여럿이면 성립하지 않는다. 그런데도 앞서 조회로 한 번 거르는 이유는 로그
     * 위생이다: 유니크 위반이 나면 Hibernate(SqlExceptionHelper)가 "Key (channel_id)=(…)"를
     * 그대로 찍는다 — channelId는 로그에 안 찍는다는 규칙에 걸린다. 조회로 걸러진 경우는
     * ChzzkLinkException으로, 경합으로 조회를 통과한 극히 드문 경우만 DataIntegrityViolationException
     * 으로 나가고 호출부는 둘을 같게(409) 다룬다. 후자에서는 그 Hibernate 한 줄이 남는다.
     *
     * <p>둘 다 이 트랜잭션을 롤백한다. put은 REQUIRED라 롤백에 같이 딸려가 고아 secret이 안 남는다.
     */
    @Transactional
    public ChzzkChannelLink create(Long userId, ChzzkMe me, ChzzkTokens tokens, Instant now) {
        users.findByIdForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException("사용자가 없다 userId=" + userId));
        links.findByChannelIdAndRevokedAtIsNull(me.channelId())
                .filter(other -> !other.getUserId().equals(userId))
                .ifPresent(other -> {
                    throw new ChzzkLinkException(ChzzkLinkFailure.CHANNEL_ALREADY_LINKED, "다른 계정에 묶인 채널이다");
                });
        closeAlive(userId, now, "auth.chzzk.link.relinked");   // 재연동이면 옛 행을 닫는다
        String accessRef = "chzzk-access:" + UUID.randomUUID();
        String refreshRef = "chzzk-refresh:" + UUID.randomUUID();
        secretStore.put(accessRef, tokens.accessToken());
        secretStore.put(refreshRef, tokens.refreshToken());
        return links.saveAndFlush(ChzzkChannelLink.of(userId, me.channelId(), me.channelName(), tokens.scope(),
                accessRef, refreshRef, now.plus(tokens.expiresIn()), now));
    }

    /**
     * 사용자 해제. 회원 행 락 → 살아있는 행 revoke(USER_UNLINKED) → 커밋 뒤 정리.
     * 살아있는 행이 없으면 아무것도 안 한다(204 멱등). 재연동의 "옛 행 폐기"와 같은 코드다.
     */
    @Transactional
    public void revoke(Long userId, Instant now) {
        users.findByIdForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException("사용자가 없다 userId=" + userId));
        closeAlive(userId, now, "auth.chzzk.link.unlinked");
    }

    /**
     * 살아있는 내 연동을 닫고, 커밋 뒤에 secrets 삭제 → 로그 → 옛 토큰 revoke. 락 뒤에 부른다.
     *
     * <p>옛 행은 락 뒤에 읽는다(락 전 엔티티 읽기 금지). 옛 토큰 원문은 커밋 전에 읽어 둔다 —
     * 정리 시점에는 secrets를 지운 뒤라 못 읽는다.
     *
     * <p>정리는 afterCommit에서 <b>제출만</b> 하고 전용 스레드({@link ChzzkCleanupExecutor})가 돈다 —
     * afterCommit 안에서 REQUIRES_NEW delete를 직접 부르면 원 커넥션을 쥔 채 두 번째를 요구해 풀 데드락이 된다.
     */
    private void closeAlive(Long userId, Instant now, String event) {
        links.findByUserIdAndRevokedAtIsNull(userId).ifPresent(old -> {
            String oldAccess = secretStore.get(old.getAccessTokenRef()).orElse(null);
            String oldRefresh = secretStore.get(old.getRefreshTokenRef()).orElse(null);
            links.revokeAlive(userId, now, RevokeReason.USER_UNLINKED);
            cleanup.afterCommit(userId, () -> {
                // DB 효과 먼저(delete는 REQUIRES_NEW라 커밋 뒤에도 실제로 지워진다), 외부 best-effort는 마지막 —
                // revoke가 타임아웃까지 매달려도 delete는 이미 끝났다.
                secretStore.delete(old.getAccessTokenRef());
                secretStore.delete(old.getRefreshTokenRef());
                log.info("{} userId={}", event, userId);
                if (oldAccess != null && oldRefresh != null) {
                    discarder.discard(userId, oldAccess, oldRefresh);
                }
            });
        });
    }
}
