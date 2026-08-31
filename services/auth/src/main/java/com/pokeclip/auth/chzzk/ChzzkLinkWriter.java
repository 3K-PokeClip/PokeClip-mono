package com.pokeclip.auth.chzzk;

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
    private final ActiveUserGuard activeUserGuard;
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
    public ChzzkChannelLink create(Long userId, ChzzkMe me, ChzzkTokens tokens) {
        activeUserGuard.requireAliveWithLock(userId, "chzzk.link.create");
        // 시각은 락 뒤에 잡는다 — 요청 시작 시각(치지직 HTTP 전)을 쓰면 그 사이 다른 경로가 먼저 커밋한 행보다
        // 새 행의 created_at이 앞서, "회원별 최신 행"(GET 상태·resolve NOT_LINKED)이 살아있는 행이 아니게 된다.
        Instant now = Instant.now();
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
            String accessRef = old.getAccessTokenRef();
            String refreshRef = old.getRefreshTokenRef();
            cleanup.afterCommit(userId, () -> cleanupOld(userId, accessRef, refreshRef, oldAccess, oldRefresh, event));
        });
    }

    /**
     * 정리 잡 본문(전용 스레드). DB 효과 먼저(delete는 REQUIRES_NEW라 커밋 뒤에도 실제로 지워진다), 외부
     * best-effort는 마지막 — revoke가 타임아웃까지 매달려도 delete는 이미 끝났다. 단 delete가 던져도
     * (SecretStore가 원격 구현이면 흔하다) revoke는 반드시 시도한다 — 안 그러면 옛 토큰이 치지직에 살아남는다.
     * delete의 예외는 잡(ChzzkCleanupExecutor.Job)이 {@code cleanup.failed} WARN으로 남긴다. package-private은 단위 테스트용.
     */
    void cleanupOld(Long userId, String accessRef, String refreshRef, String oldAccess, String oldRefresh, String event) {
        try {
            secretStore.delete(accessRef);
            secretStore.delete(refreshRef);
            log.info("{} userId={}", event, userId);
        } finally {
            if (oldAccess != null && oldRefresh != null) {
                discarder.discard(userId, oldAccess, oldRefresh);
            }
        }
    }
}
