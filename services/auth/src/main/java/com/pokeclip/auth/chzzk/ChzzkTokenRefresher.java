package com.pokeclip.auth.chzzk;

import com.pokeclip.auth.streamkey.secret.SecretStore;
import com.pokeclip.auth.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 치지직 토큰 갱신기. 갱신만 안다 — 누가 부르는지(스케줄러·resolve)는 모른다.
 *
 * <p><b>트랜잭션 최상단이어야 한다.</b> 호출부(스케줄러·resolve)에 {@code @Transactional}을 붙이면
 * 4xx 경로의 revoke가 상위 롤백에 딸려가고 외부 HTTP가 상위 트랜잭션 수명에 묶인다
 * (TokenService.rotate 함정 — 테스트는 그래도 통과한다).
 */
@Component
@RequiredArgsConstructor
public class ChzzkTokenRefresher {

    private static final Logger log = LoggerFactory.getLogger(ChzzkTokenRefresher.class);

    private final UserRepository users;
    private final ChzzkChannelLinkRepository links;
    private final SecretStore secretStore;
    private final ChzzkOAuthClient oauthClient;
    private final ChzzkCleanupExecutor cleanup;

    /**
     * 남은 수명이 minRemaining보다 짧을 때만 치지직을 부른다. 회원 행 락으로 직렬화.
     *
     * <p>refresh는 일회용이다. 락 <b>뒤에</b> 행을 다시 읽어야 경합 상대가 방금 갱신한 것이 보인다 —
     * 락 전에 읽은 엔티티는 1차 캐시가 옛 상태를 주고, 그러면 같은 refresh를 두 번 소비한다.
     *
     * <p>저장 창 — 치지직 응답 수신(새 refresh가 서버에서 유효해진 시점)과 우리 커밋 사이는
     * <b>완전히 닫을 수 없다.</b> 같은 트랜잭션·같은 커밋으로 창을 ms로 줄이고, 그 사이에
     * 프로세스가 죽어 새 토큰이 새면 다음 갱신이 옛 refresh로 4xx를 받아 BROKEN이 된다 —
     * 조용히 틀린 상태로 남지 않는다.
     */
    @Transactional
    public RefreshResult refreshIfExpiringWithin(Long userId, Duration minRemaining) {
        Instant now = Instant.now();
        if (users.findByIdForUpdate(userId).isEmpty()) {
            return RefreshResult.of(RefreshOutcome.NOT_LINKED);
        }
        Optional<ChzzkChannelLink> found = links.findByUserIdAndRevokedAtIsNull(userId);   // 락 뒤 재읽기
        if (found.isEmpty()) {
            return RefreshResult.of(RefreshOutcome.NOT_LINKED);
        }
        ChzzkChannelLink link = found.get();
        if (link.getAccessExpiresAt().isAfter(now.plus(minRemaining))) {
            // access 원문도 락 안에서 읽어 스냅샷에 싣는다 — 호출부(resolve)가 락 밖에서 다시 읽지 않게.
            String accessToken = secretStore.get(link.getAccessTokenRef())
                    .orElseThrow(() -> new IllegalStateException("연동 행은 있는데 access secret이 없다 userId=" + userId));
            return RefreshResult.of(RefreshOutcome.SKIPPED_FRESH, link, accessToken);
        }
        // 행은 있는데 secret이 없으면 우리 저장소가 어긋난 것 — 500이 맞다(StreamKeyService.materialOf 선례).
        String refreshToken = secretStore.get(link.getRefreshTokenRef())
                .orElseThrow(() -> new IllegalStateException("연동 행은 있는데 refresh secret이 없다 userId=" + userId));
        ChzzkTokens tokens;
        try {
            tokens = oauthClient.refresh(refreshToken);
        } catch (ChzzkRejectedException e) {
            // 철회·만료·scope 변경 — 다시 시도해도 같다. 닫고, 커밋 뒤에 secrets를 지운다.
            // 삭제는 전용 스레드에서(ChzzkCleanupExecutor) — afterCommit 안의 REQUIRES_NEW는 커넥션을 하나 더 잡는다.
            link.revoke(now, RevokeReason.REFRESH_REJECTED);
            int status = e.status();
            String accessRef = link.getAccessTokenRef();
            String refreshRef = link.getRefreshTokenRef();
            cleanup.afterCommit(userId, () -> {
                secretStore.delete(accessRef);   // REQUIRES_NEW라 커밋 뒤에도 실제로 지워진다
                secretStore.delete(refreshRef);
                log.warn("auth.chzzk.link.refresh_rejected userId={} status={}", userId, status);
            });
            return RefreshResult.of(RefreshOutcome.REJECTED);
        } catch (ChzzkUnavailableException e) {
            // 2xx 뒤 파싱 실패(MalformedResponse)도 여기로 온다 — refresh가 실제 소비됐다면 다음 틱에 옛 refresh가
            // 4xx를 받아 BROKEN으로 수렴하고, 새 쌍은 치지직 만료까지 미참조 잔존(파싱 못 하는 응답은 어차피 저장
            // 불가라 최종 결과는 같다). 프록시 200 HTML(미소비)까지 즉시 BROKEN으로 만들지 않기 위해 UNAVAILABLE로 둔다.
            log.warn("auth.chzzk.link.refresh_failed userId={} causeType={}", userId, e.causeType());
            return RefreshResult.of(RefreshOutcome.UNAVAILABLE);   // 행 무변경. 다음 틱에 다시
        }
        // 같은 ref에 덮어쓴다 — 참조는 그대로, 원문만 바뀐다. put 2 + refreshed가 한 커밋.
        secretStore.put(link.getAccessTokenRef(), tokens.accessToken());
        secretStore.put(link.getRefreshTokenRef(), tokens.refreshToken());
        link.refreshed(now.plus(tokens.expiresIn()), tokens.scope(), now);
        logAfterCommit(() -> log.info("auth.chzzk.link.refreshed userId={}", userId));
        return RefreshResult.of(RefreshOutcome.REFRESHED, link, tokens.accessToken());
    }

    /**
     * "일어났다"는 로그는 커밋 뒤에만 — 커밋 전에 찍으면 롤백 시 거짓 알리바이. 같은 스레드 동기 afterCommit이라
     * MDC 상관 ID도 살아 있다(auth/CLAUDE.md, TokenService.logAfterCommit과 같은 모양). 정리 큐(ChzzkCleanupExecutor)로
     * 보내지 않는다 — 그 큐는 revoke(최대 5s×2)와 공유되고 거부될 수 있다. 로그는 DB·HTTP를 안 하므로 커넥션 문제도 없다.
     */
    private static void logAfterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
