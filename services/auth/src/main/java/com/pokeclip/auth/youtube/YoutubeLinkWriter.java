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

import java.time.Instant;
import java.util.UUID;

/**
 * 연동 행의 저장만 담당한다. {@code YoutubeLinkService}에서 떼어낸 이유는 {@code ChzzkLinkWriter}와 같다 —
 * &#64;Transactional은 프록시로 동작해서 같은 클래스의 메서드를 직접 부르면 무시된다.
 * 서비스는 외부 HTTP(교환·채널 목록)를 트랜잭션 밖에서 하고, 저장만 여기서 트랜잭션이다.
 */
@Component
@RequiredArgsConstructor
public class YoutubeLinkWriter {

    private static final Logger log = LoggerFactory.getLogger(YoutubeLinkWriter.class);

    private final YoutubeChannelLinkRepository links;
    private final SecretStore secretStore;
    private final UserRepository users;
    private final YoutubeTokenDiscarder discarder;
    private final YoutubeCleanupExecutor cleanup;

    /**
     * 회원 행 락 → 채널 중복 확인 → (살아있는 내 연동 폐기) → secrets put 2 → INSERT. 한 커밋.
     * 커밋 뒤: 옛 secrets 삭제 → 로그. <b>옛 토큰 revoke는 없다</b>(아래 {@link #closeAlive} 참고).
     *
     * <p>채널 중복은 DB 부분 유니크(uq_youtube_links_alive_channel)가 최종 방어다 — 앱 락은
     * 인스턴스가 여럿이면 성립하지 않는다. 그런데도 앞서 조회로 한 번 거르는 이유는 로그 위생이다:
     * 유니크 위반이 나면 Hibernate가 "Key (channel_id)=(…)"를 그대로 찍는데 channelId는 로그에 안 찍는다.
     *
     * <p>둘 다 이 트랜잭션을 롤백한다. put은 REQUIRED라 롤백에 같이 딸려가 고아 secret이 안 남는다.
     */
    @Transactional
    public YoutubeChannelLink create(Long userId, YoutubeChannel selected, YoutubeTokens tokens) {
        users.findByIdForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException("사용자가 없다 userId=" + userId));
        // 시각은 락 뒤에 잡는다 — 요청 시작 시각(구글 HTTP 전)을 쓰면 그 사이 다른 경로가 먼저 커밋한 행보다
        // 새 행의 created_at이 앞서, "회원별 최신 행"(GET 상태·resolve NOT_LINKED)이 살아있는 행이 아니게 된다.
        Instant now = Instant.now();
        links.findByChannelIdAndRevokedAtIsNull(selected.channelId())
                .filter(other -> !other.getUserId().equals(userId))
                .ifPresent(other -> {
                    throw new YoutubeLinkException(YoutubeLinkFailure.CHANNEL_ALREADY_LINKED, "다른 계정에 묶인 채널이다");
                });
        // 재연동 — 옛 행을 닫되 옛 토큰은 구글에 그대로 둔다(revokeOldToken=false).
        closeAlive(userId, now, "auth.youtube.link.relinked", false);
        String accessRef = "youtube-access:" + UUID.randomUUID();
        String refreshRef = "youtube-refresh:" + UUID.randomUUID();
        secretStore.put(accessRef, tokens.accessToken());
        secretStore.put(refreshRef, tokens.refreshToken());
        return links.saveAndFlush(YoutubeChannelLink.of(userId, selected.channelId(), selected.channelName(),
                tokens.scope(), accessRef, refreshRef, now.plus(tokens.expiresIn()), now));
    }

    /**
     * 사용자 해제. 회원 행 락 → 살아있는 행 revoke(USER_UNLINKED) → 커밋 뒤 정리.
     * 살아있는 행이 없으면 아무것도 안 한다(204 멱등).
     *
     * <p><b>여기서만</b> 옛 토큰을 구글에서 철회한다 — 사용자 의도가 「구글 쪽 허락도 지워라」다.
     */
    @Transactional
    public void revoke(Long userId, Instant now) {
        users.findByIdForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException("사용자가 없다 userId=" + userId));
        closeAlive(userId, now, "auth.youtube.link.unlinked", true);
    }

    /**
     * 업로드 대상 재선택. 회원 행 락 → 살아있는 행 조회(없으면 NOT_LINKED) → 새 채널의 타인 점유 확인(409)
     * → UPDATE. 한 커밋. 토큰은 계정 단위라 손대지 않는다.
     */
    @Transactional
    public YoutubeChannelLink selectChannel(Long userId, YoutubeChannel channel) {
        users.findByIdForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException("사용자가 없다 userId=" + userId));
        YoutubeChannelLink link = links.findByUserIdAndRevokedAtIsNull(userId)
                .orElseThrow(() -> new YoutubeLinkException(YoutubeLinkFailure.NOT_LINKED, "살아있는 연동이 없다"));
        links.findByChannelIdAndRevokedAtIsNull(channel.channelId())
                .filter(other -> !other.getUserId().equals(userId))
                .ifPresent(other -> {
                    throw new YoutubeLinkException(YoutubeLinkFailure.CHANNEL_ALREADY_LINKED, "다른 계정에 묶인 채널이다");
                });
        link.selectChannel(channel.channelId(), channel.channelName());
        // 커밋 전에 찍으면 롤백 시 거짓 알리바이가 된다(auth/CLAUDE.md 「일어났다는 로그」).
        logAfterCommit(() -> log.info("auth.youtube.link.channel_selected userId={}", userId));
        return link;
    }

    /**
     * 살아있는 내 연동을 닫고, 커밋 뒤에 secrets 삭제 → 로그 → (해제일 때만) 옛 토큰 revoke. 락 뒤에 부른다.
     *
     * <p>🔴 <b>{@code revokeOldToken}이 갈래를 가른다 — 치지직과 다른 자리다.</b> 치지직은 어느 경로에서든
     * 옛 토큰을 revoke했지만, 구글 revoke는 「그 쌍」이 아니라 그 사용자가 이 프로젝트에 준 <b>동의 전부</b>를
     * 죽인다. 그래서 <b>재연동에서 부르면 방금 저장한 새 토큰까지 죽는다</b>(표는 ACTIVE인데 첫 갱신이
     * invalid_grant → BROKEN). 재연동은 새 동의가 옛 grant를 대체하므로 따로 죽일 필요도 없다.
     * 사용자 해제(DELETE)만 true다.
     *
     * <p>그래서 <b>revoke하지 않는 갈래는 옛 토큰 원문을 읽지도 않는다</b> — 치지직이 커밋 전에 하던
     * {@code secretStore.get} 두 번이 재연동 경로에서 사라진다.
     *
     * <p>정리는 afterCommit에서 <b>제출만</b> 하고 전용 스레드({@link YoutubeCleanupExecutor})가 돈다 —
     * afterCommit 안에서 REQUIRES_NEW delete를 직접 부르면 원 커넥션을 쥔 채 두 번째를 요구해 풀 데드락이 된다.
     */
    private void closeAlive(Long userId, Instant now, String event, boolean revokeOldToken) {
        links.findByUserIdAndRevokedAtIsNull(userId).ifPresent(old -> {
            // 정리 시점에는 secrets를 지운 뒤라 못 읽는다 — 필요한 갈래만 커밋 전에 읽어 둔다.
            String oldAccess = revokeOldToken ? secretStore.get(old.getAccessTokenRef()).orElse(null) : null;
            String oldRefresh = revokeOldToken ? secretStore.get(old.getRefreshTokenRef()).orElse(null) : null;
            links.revokeAlive(userId, now, RevokeReason.USER_UNLINKED);
            String accessRef = old.getAccessTokenRef();
            String refreshRef = old.getRefreshTokenRef();
            cleanup.afterCommit(userId, () -> cleanupOld(userId, accessRef, refreshRef, oldAccess, oldRefresh, event));
        });
    }

    /**
     * 정리 잡 본문(전용 스레드). DB 효과 먼저(delete는 REQUIRES_NEW라 커밋 뒤에도 실제로 지워진다),
     * 외부 best-effort는 마지막. 단 delete가 던져도(SecretStore가 원격 구현이면 흔하다) revoke는 반드시
     * 시도한다 — 안 그러면 해제한 토큰이 구글에 살아남는다. delete의 예외는 잡이 {@code cleanup.failed}
     * WARN으로 남긴다. package-private은 단위 테스트용.
     *
     * <p>옛 토큰 원문이 둘 다 null이면 revoke 갈래가 아니다(재연동) — 아무것도 안 부른다.
     */
    void cleanupOld(Long userId, String accessRef, String refreshRef, String oldAccess, String oldRefresh,
                    String event) {
        try {
            secretStore.delete(accessRef);
            secretStore.delete(refreshRef);
            log.info("{} userId={}", event, userId);
        } finally {
            if (oldAccess != null || oldRefresh != null) {
                discarder.discard(userId, oldAccess, oldRefresh);
            }
        }
    }

    /**
     * "일어났다"는 로그는 커밋 뒤에만 — 커밋 전에 찍으면 롤백 시 거짓 알리바이다. 같은 스레드 동기
     * afterCommit이라 MDC 상관 ID도 살아 있다({@code ChzzkTokenRefresher.logAfterCommit}과 같은 모양).
     * 정리 큐로 보내지 않는다 — 그 큐는 revoke(최대 5s)와 공유되고 거부될 수 있다.
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
