package com.pokeclip.auth.youtube;

import com.pokeclip.auth.streamkey.secret.SecretStore;
import com.pokeclip.auth.user.ActiveUserGuard;
import com.pokeclip.auth.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
    private final ActiveUserGuard activeUserGuard;
    private final YoutubeCleanupExecutor cleanup;
    private final YoutubeTokenDiscarder discarder;

    /**
     * 회원 행 락 → 채널 중복 확인 → (살아있는 내 연동 폐기) → secrets put 2 → INSERT. 한 커밋.
     * 커밋 뒤: 옛 secrets 삭제 → 로그. <b>재연동은 구글 revoke를 안 보낸다</b>(새 토큰까지 죽는다, 아래 {@link #closeAlive} 참고).
     *
     * <p>채널 중복은 DB 부분 유니크(uq_youtube_links_alive_channel)가 최종 방어다 — 앱 락은
     * 인스턴스가 여럿이면 성립하지 않는다. 그런데도 앞서 조회로 한 번 거르는 이유는 로그 위생이다:
     * 유니크 위반이 나면 Hibernate가 "Key (channel_id)=(…)"를 그대로 찍는데 channelId는 로그에 안 찍는다.
     *
     * <p>둘 다 이 트랜잭션을 롤백한다. put은 REQUIRED라 롤백에 같이 딸려가 고아 secret이 안 남는다.
     *
     * <p>🔴 <b>「살아있는 회원만」이다</b>(PR #148 codex C3, 재현함). 행의 <b>존재만</b> 보면 탈퇴한
     * 회원에게 살아있는 연동이 새로 생긴다 — 그 행은 resolve가 그대로 보고, 그 행이 가리키는
     * secrets(OAuth 원문 둘)는 탈퇴 정리가 이미 지나가서 <b>영구 고아</b>이며, 회원은 전면 차단 필터
     * 때문에 그 연동을 <b>볼 수도 끊을 수도 없다.</b> 창은 넷 중 가장 넓다 — 이 호출 앞에 외부 HTTP가
     * 둘 있어 최대 십수 초다.
     *
     * <p>확인이 <b>락과 함께</b>라 여기에는 「읽고 나서 쓴다」 사이의 창이 없다. 어차피 잡던 락이고
     * ({@code FOR NO KEY UPDATE}) 탈퇴도 같은 락을 잡으므로 <b>직렬화된다.</b> 조회도 안 는다.
     */
    @Transactional
    public YoutubeChannelLink create(Long userId, YoutubeChannel selected, YoutubeTokens tokens) {
        activeUserGuard.requireAliveWithLock(userId, "youtube.link.create");
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
     * <p><b>구글에는 아무것도 보내지 않는다</b> — 왜인지는 {@link #closeAlive} javadoc에 있다.
     *
     * <p>🔴 <b>여기에는 「살아있는 회원만」을 넣지 않는다 — 일부러다.</b> 탈퇴가 익명화
     * ({@code User.withdraw}) <b>전에</b> 이 메서드를 부른다. 넣으면 <b>탈퇴가 자기 가드에 막혀</b>
     * 연동을 가진 회원이 탈퇴를 못 한다. 「그때는 아직 {@code deleted_at}이 비어 있다」는
     * <b>순서에 기댄 성질</b>이라 {@code WithdrawnWriteGuardChannelLinkTest.연동을_가진_회원의_탈퇴는_자기_가드에_안_막힌다}가
     * 못박는다 — 익명화를 앞으로 옮기는 사람은 그 검사가 먼저 빨간불이 된다.
     */
    @Transactional
    public void revoke(Long userId, Instant now) {
        users.findByIdForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException("사용자가 없다 userId=" + userId));
        closeAlive(userId, now, "auth.youtube.link.unlinked", true);
    }

    /**
     * 살아있는 내 연동을 닫고, 커밋 뒤에 (해제면 구글 revoke →) secrets 삭제 → 로그. 락 뒤에 부른다.
     *
     * <p>🔴 <b>해제(사용자 {@code DELETE} · 탈퇴)는 구글에 revoke를 보낸다</b>(2026-10-24 사용자 재결정, POK-423 · ADR-116,
     * 유튜브 개발자 정책 III.D.2.c). 그 전(2026-08-24 · ADR-052)에는 안 보냈다. 이유는 아직 참이고 대가로 받아들였다:
     *
     * <ul>
     *   <li><b>ⓐ 구글 revoke는 계정 단위다.</b> 「그 토큰 쌍」이 아니라 그 <b>구글 계정</b>이 우리 앱에 준 동의 전부를 죽인다
     *       (실측 A ⑥). 해제한 회원은 다음 로그인 · 재연동 때 구글 동의 화면을 한 번 더 본다. 같은 구글 계정으로 다른 채널을
     *       연동한 <b>다른 회원</b>의 grant도 함께 죽는다(다음 갱신에서 BROKEN, 다시 연동해야 한다).</li>
     *   <li><b>ⓑ 해제 직후 같은 회원이 다시 연동하면</b> 밀린 revoke가 새 grant를 죽인다. 그래서 정리 잡이 revoke 직전에
     *       「그 회원의 살아 있는 연동이 생겼나」를 보고 생겼으면 안 보낸다({@link #revokeThenCleanUp}). 확인과 발사 사이의 좁은
     *       창은 남는다(락 안에서 외부 HTTP를 기다리지 않으려고 받아들였다).</li>
     * </ul>
     *
     * <p><b>재연동은 안 보낸다</b>: 새 동의가 옛 grant를 대체하고, 보내면 방금 저장한 새 토큰이 죽는다.
     *
     * <p>정리는 afterCommit에서 <b>제출만</b> 하고 전용 스레드({@link YoutubeCleanupExecutor})가 돈다 —
     * afterCommit 안에서 REQUIRES_NEW delete를 직접 부르면 원 커넥션을 쥔 채 두 번째를 요구해 풀 데드락이 된다.
     * revoke 실패(5xx · 시간 초과)는 갱신 거부 자리와 같이 best-effort다: WARN {@code orphan_token}을 남기고 다시 보내지 않는다
     * (다시 보내려면 토큰 원문을 남겨야 하는데 처리방침은 해제하면 바로 지운다. 사용자는 구글 계정 설정에서 직접 철회할 수 있다).
     */
    private void closeAlive(Long userId, Instant now, String event, boolean revokeAtGoogle) {
        links.findByUserIdAndRevokedAtIsNull(userId).ifPresent(old -> {
            links.revokeAlive(userId, now, RevokeReason.USER_UNLINKED);
            String accessRef = old.getAccessTokenRef();
            String refreshRef = old.getRefreshTokenRef();
            cleanup.afterCommit(userId, revokeAtGoogle
                    ? () -> revokeThenCleanUp(userId, accessRef, refreshRef, event)
                    : () -> cleanupOld(userId, accessRef, refreshRef, event));
        });
    }

    /**
     * 해제의 정리 잡(전용 스레드). refresh 원문을 읽어 두고 <b>secrets를 먼저 지운 뒤</b> 구글에 revoke를 보낸다. 순서가 뒤라야 종료
     * 유예(유튜브 4초)에 잘려도 남는 것이 보내지 못한 revoke뿐이다(비밀값은 이미 지웠다). 🔴 그 사이 같은 회원이 다시 연동했으면 revoke를
     * 건너뛴다: 계정 단위라 새 grant까지 죽는다(같은 구글 계정일 수 있다).
     */
    void revokeThenCleanUp(Long userId, String accessRef, String refreshRef, String event) {
        String refresh = null;
        try {
            refresh = secretStore.get(refreshRef).orElse(null);
        } catch (RuntimeException e) {
            log.warn("auth.youtube.link.orphan_token userId={} causeType={}", userId, e.getClass().getSimpleName());
        }
        try {
            cleanupOld(userId, accessRef, refreshRef, event);
        } finally {
            if (refresh != null) {
                if (links.findByUserIdAndRevokedAtIsNull(userId).isPresent()) {
                    log.info("auth.youtube.link.unlink_revoke_skipped_relinked userId={}", userId);
                } else {
                    discarder.discard(userId, refresh);
                }
            }
        }
    }

    /**
     * 정리 잡 본문(전용 스레드). secrets 두 개를 지우고 「정리까지 끝났다」를 로그로 남긴다.
     * 구글 호출은 없다(해제는 {@link #revokeThenCleanUp}이 먼저 revoke를 보내고 이것을 부른다).
     * package-private은 단위 테스트용.
     */
    void cleanupOld(Long userId, String accessRef, String refreshRef, String event) {
        // 둘을 각각 시도한다 — 하나가 던져도 나머지는 지운다. 한 try로 묶으면 첫 실패가 둘째를
        // 건너뛰어 그 비밀이 secrets에 영구히 남는다(봇 리뷰 PR #116). 예외는 마지막에 다시 올려
        // 잡이 cleanup.failed WARN으로 남기게 한다.
        RuntimeException deleteFailure = deleteQuietly(accessRef, null);
        deleteFailure = deleteQuietly(refreshRef, deleteFailure);
        if (deleteFailure != null) {
            throw deleteFailure;
        }
        log.info("{} userId={}", event, userId);
    }

    /** 지우고, 실패하면 예외를 모아 둔다(먼저 난 것을 유지한다) — 나머지 삭제를 막지 않으려고. */
    private RuntimeException deleteQuietly(String ref, RuntimeException earlier) {
        try {
            secretStore.delete(ref);
            return earlier;
        } catch (RuntimeException e) {
            return earlier != null ? earlier : e;
        }
    }

}
