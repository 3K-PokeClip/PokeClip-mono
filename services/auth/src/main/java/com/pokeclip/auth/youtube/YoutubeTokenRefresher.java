package com.pokeclip.auth.youtube;

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
 * 유튜브 토큰 갱신기. 갱신만 안다 — 누가 부르는지(점검 스케줄러·resolve)는 모른다.
 *
 * <p><b>트랜잭션 최상단이어야 한다.</b> 호출부에 {@code @Transactional}을 붙이면 거부 경로의 정리가
 * 상위 롤백에 딸려가고 외부 HTTP가 상위 트랜잭션 수명에 묶인다(TokenService.rotate 함정 —
 * 테스트는 그래도 통과한다).
 *
 * <p>치지직({@code ChzzkTokenRefresher})과 갈리는 곳 둘: <b>① refresh는 재사용형</b>이라 응답에
 * 없는 것이 정상이고 그때는 기존 것을 유지한다(put이 1회로 준다) <b>② 거부 정리에서 revoke를 부른다</b> —
 * 이미 죽은 grant라 부작용이 없다(계획 2절 결정 8의 「BROKEN 정리」 갈래).
 */
@Component
@RequiredArgsConstructor
public class YoutubeTokenRefresher {

    private static final Logger log = LoggerFactory.getLogger(YoutubeTokenRefresher.class);

    private final UserRepository users;
    private final YoutubeChannelLinkRepository links;
    private final SecretStore secretStore;
    private final YoutubeOAuthClient oauthClient;
    private final YoutubeTokenDiscarder discarder;
    private final YoutubeCleanupExecutor cleanup;

    /**
     * 남은 수명이 minRemaining보다 짧을 때만 구글을 부른다. 회원 행 락으로 직렬화한다.
     *
     * <p>구글 refresh는 재사용형이라 치지직처럼 「일회용을 두 번 소비」하는 사고는 없다. 그래도 락이 필요하다 —
     * 겹치면 같은 회원의 access를 같은 초에 두 번 발급받아 하나가 즉시 고아가 되고, 그만큼 할당량을 쓰며,
     * 무엇보다 <b>해제·재연동과 엇갈려 닫힌 행에 새 토큰을 써 넣을 수 있다.</b>
     * 락 <b>뒤에</b> 행을 다시 읽는 이유도 그것이다(락 전에 읽은 엔티티는 1차 캐시가 옛 상태를 준다).
     */
    @Transactional
    public RefreshResult refreshIfExpiringWithin(Long userId, Duration minRemaining) {
        Instant now = Instant.now();
        if (users.findByIdForUpdate(userId).isEmpty()) {
            return RefreshResult.of(RefreshOutcome.NOT_LINKED);
        }
        Optional<YoutubeChannelLink> found = links.findByUserIdAndRevokedAtIsNull(userId);   // 락 뒤 재읽기
        if (found.isEmpty()) {
            return RefreshResult.of(RefreshOutcome.NOT_LINKED);
        }
        YoutubeChannelLink link = found.get();
        if (link.getAccessExpiresAt().isAfter(now.plus(minRemaining))) {
            // access 원문도 락 안에서 읽어 스냅샷에 싣는다 — 호출부(resolve)가 락 밖에서 다시 읽지 않게.
            String accessToken = secretStore.get(link.getAccessTokenRef())
                    .orElseThrow(() -> new IllegalStateException("연동 행은 있는데 access secret이 없다 userId=" + userId));
            return RefreshResult.of(RefreshOutcome.SKIPPED_FRESH, link, accessToken);
        }
        // 행은 있는데 secret이 없으면 우리 저장소가 어긋난 것 — 500이 맞다(StreamKeyService.materialOf 선례).
        String refreshToken = secretStore.get(link.getRefreshTokenRef())
                .orElseThrow(() -> new IllegalStateException("연동 행은 있는데 refresh secret이 없다 userId=" + userId));
        YoutubeTokens tokens;
        try {
            tokens = oauthClient.refresh(refreshToken);
        } catch (YoutubeRejectedException e) {
            return reject(link, userId, refreshToken, now, e.status());
        } catch (YoutubeUnavailableException e) {
            // 5xx·타임아웃·429·408·invalid_client·403 할당량, 그리고 2xx 뒤 파싱 실패. 행은 그대로 두고 다음 틱에.
            // 특히 invalid_client(우리 앱 설정 문제)와 403 할당량을 BROKEN으로 닫으면 회원 전원이 재동의해야 한다.
            log.warn("auth.youtube.link.refresh_failed userId={} causeType={}", userId, e.causeType());
            return RefreshResult.of(RefreshOutcome.UNAVAILABLE);
        }
        // access는 항상 덮어쓴다. refresh는 응답에 있을 때만 — 없는 것이 정상이고(구글은 기존 것을 계속 쓰게 한다)
        // 그때 null로 덮어쓰면 다음 갱신이 불가능해져 연동이 통째로 죽는다. 참조는 그대로, 원문만 바뀐다.
        secretStore.put(link.getAccessTokenRef(), tokens.accessToken());
        if (tokens.refreshToken() != null) {
            secretStore.put(link.getRefreshTokenRef(), tokens.refreshToken());
        }
        link.refreshed(now.plus(tokens.expiresIn()), tokens.scope(), now);   // scope도 null이면 기존 값 유지
        logAfterCommit(() -> log.info("auth.youtube.link.refreshed userId={}", userId));
        return RefreshResult.of(RefreshOutcome.REFRESHED, link, tokens.accessToken());
    }

    /**
     * 갱신 거부 — 대개 {@code invalid_grant}(사용자가 구글 쪽에서 권한을 끊었거나 테스트 모드 7일 만료)다.
     * 다시 시도해도 같으므로 행을 닫고, <b>커밋 뒤</b>에 secrets 삭제 → 로그 → revoke를 전용 스레드에서 한다.
     *
     * <p>여기서 revoke를 부르는 것은 안전하다 — 이미 죽은 grant라 그 사용자의 다른 연동을 끊을 수 없다.
     * (재연동·연동 실패 정리에서 부르면 <b>멀쩡한 연동이 죽는다</b>. 갈래가 왜 넷인지는 계획 2절 결정 8.)
     *
     * <p>삭제를 afterCommit 안에서 직접 하지 않는 이유는 {@code YoutubeLinkWriter.closeAlive}와 같다 —
     * REQUIRES_NEW delete가 원 커넥션을 쥔 채 두 번째를 요구해 풀 데드락이 된다.
     */
    private RefreshResult reject(YoutubeChannelLink link, Long userId, String refreshToken, Instant now, int status) {
        link.revoke(now, RevokeReason.REFRESH_REJECTED);
        String accessRef = link.getAccessTokenRef();
        String refreshRef = link.getRefreshTokenRef();
        cleanup.afterCommit(userId, () -> {
            try {
                secretStore.delete(accessRef);   // REQUIRES_NEW라 커밋 뒤에도 실제로 지워진다
                secretStore.delete(refreshRef);
                log.warn("auth.youtube.link.refresh_rejected userId={} status={}", userId, status);
            } finally {
                // delete가 던져도 revoke는 시도한다 — 안 그러면 죽은 grant의 흔적이 구글에 남는다.
                discarder.discard(userId, null, refreshToken);   // 한 번이면 충분하다(grant 전체가 죽는다)
            }
        });
        return RefreshResult.of(RefreshOutcome.REJECTED);
    }

    /**
     * "일어났다"는 로그는 커밋 뒤에만 — 커밋 전에 찍으면 롤백 시 거짓 알리바이. 같은 스레드 동기 afterCommit이라
     * MDC 상관 ID도 살아 있다. 정리 큐로 보내지 않는다 — 그 큐는 revoke와 공유되고 거부될 수 있다.
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
