package com.pokeclip.auth.soop;

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
 * 연동 행의 저장만 담당한다. {@code chzzk/ChzzkLinkWriter}의 쌍둥이다. 서비스에서 떼어낸 이유도 같다:
 * {@code @Transactional}은 프록시로 동작해서 같은 클래스의 메서드를 직접 부르면 무시된다. 서비스는 외부 HTTP를
 * 트랜잭션 밖에서 하고 저장만 여기서 트랜잭션이다.
 *
 * <p>치지직과 다른 점 둘: ① 닫기는 엔티티가 아니라 {@link SoopChannelLinkRepository#closeAndPurgeAlive} 한 문장이고
 * 같은 문장에서 SOOP 아이디·닉네임을 비운다(약관 제11조 ⑰) ② 커밋 뒤 정리에 외부 HTTP가 없다(SOOP 폐기 API 없음).
 */
@Component
@RequiredArgsConstructor
public class SoopLinkWriter {

    private static final Logger log = LoggerFactory.getLogger(SoopLinkWriter.class);

    private final SoopChannelLinkRepository links;
    private final SecretStore secretStore;
    private final UserRepository users;
    private final ActiveUserGuard activeUserGuard;
    private final SoopCleanupExecutor cleanup;

    /**
     * 회원 행 락(살아 있는 회원만) → 방송국 중복 확인 → (살아 있는 내 연동 닫기) → secrets put 2 → INSERT. 한 커밋.
     * 커밋 뒤: 옛 secrets 삭제 → 로그.
     *
     * <p>중복은 DB 부분 유니크(uq_soop_links_alive_soop_user)가 최종 방어다. 앞서 조회로 거르는 이유는 치지직과 같은
     * 로그 위생이다: 유니크 위반이 나면 Hibernate(SqlExceptionHelper)가 {@code Key (soop_user_id)=(…)}를 그대로
     * 찍는다(SOOP 아이디는 로그에 안 찍는다). 조회로 걸러진 경우는 {@link SoopLinkException}으로, 경합으로 조회를
     * 통과한 극히 드문 경우만 {@code DataIntegrityViolationException}으로 나가고 호출부는 둘을 같게(409) 다룬다.
     * <b>후자에서는 그 Hibernate 한 줄이 남는다</b>(치지직과 같은 한계).
     *
     * <p>둘 다 이 트랜잭션을 롤백한다. put은 REQUIRED라 롤백에 같이 딸려가 고아 secret이 안 남는다.
     *
     * <p>🔴 「살아 있는 회원만」은 락과 함께 본다(치지직과 같은 이유: 이 호출 앞에 외부 HTTP가 셋 있어 그 사이
     * 탈퇴가 커밋될 수 있다. 확인이 락과 함께라 탈퇴와 직렬화되고 창이 없다).
     */
    @Transactional
    public SoopChannelLink create(Long userId, SoopIdentity identity, SoopTokens tokens) {
        activeUserGuard.requireAliveWithLock(userId, "soop.link.create");
        // 시각은 락 뒤에 잡는다(치지직 codex): HTTP 전에 잡으면 동시 재연동에서 「회원별 최신 행」이 닫힌 행이 된다.
        Instant now = Instant.now();
        links.findBySoopUserIdAndRevokedAtIsNull(identity.soopUserId())
                .filter(other -> !other.getUserId().equals(userId))
                .ifPresent(other -> {
                    throw new SoopLinkException(SoopLinkFailure.CHANNEL_ALREADY_LINKED, "다른 계정에 묶인 방송국이다");
                });
        closeAndPurge(userId, now, RevokeReason.USER_UNLINKED, () -> log.info("auth.soop.link.relinked userId={}", userId));   // 재연동이면 옛 행을 닫는다
        String accessRef = "soop-access:" + UUID.randomUUID();
        String refreshRef = "soop-refresh:" + UUID.randomUUID();
        secretStore.put(accessRef, tokens.accessToken());
        secretStore.put(refreshRef, tokens.refreshToken());
        return links.saveAndFlush(SoopChannelLink.of(userId, identity.soopUserId(), identity.nickname(),
                tokens.scope(), accessRef, refreshRef, now.plus(tokens.expiresIn()), now));
    }

    /**
     * 사용자 해제. 회원 행 락 → 살아 있는 행 닫기(USER_UNLINKED, 아이디·닉네임도 같은 문장에서 비운다) → 커밋 뒤 정리.
     * 살아 있는 행이 없으면 아무것도 안 한다(204 멱등). 재연동의 「옛 행 닫기」와 같은 코드다. 외부 HTTP는 없다.
     *
     * <p>🔴 <b>여기에는 「살아 있는 회원만」을 넣지 않는다, 일부러다.</b> 탈퇴가 익명화({@code User.withdraw})
     * <b>전에</b> 이 메서드를 부른다. 넣으면 <b>탈퇴가 자기 가드에 막혀</b> 연동을 가진 회원이 탈퇴를 못 한다.
     * 「그때는 아직 {@code deleted_at}이 비어 있다」는 <b>순서에 기댄 성질</b>이다(치지직 {@code ChzzkLinkWriter.revoke}와
     * 같은 이유, 그쪽은 {@code WithdrawnWriteGuardChannelLinkTest}가 못박는다).
     */
    @Transactional
    public void revoke(Long userId, Instant now) {
        users.findByIdForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException("사용자가 없다 userId=" + userId));
        closeAndPurge(userId, now, RevokeReason.USER_UNLINKED, () -> log.info("auth.soop.link.unlinked userId={}", userId));
    }

    /**
     * 살아 있는 내 연동을 닫고(아이디·닉네임도 같은 문장에서 비운다) 커밋 뒤에 secrets를 지운다. 락 뒤에 부른다.
     *
     * <p>옛 행은 락 뒤에 읽는다(락 전 엔티티 읽기 금지). ref 둘은 닫기 <b>전에</b> 읽어 둔다: 닫기 쿼리가
     * 영속성 컨텍스트를 비워 그 뒤엔 엔티티가 떨어진다.
     *
     * <p>정리는 afterCommit에서 <b>제출만</b> 하고 전용 스레드({@link SoopCleanupExecutor})가 돈다: afterCommit 안에서
     * REQUIRES_NEW delete를 직접 부르면 풀 데드락이 된다. 치지직과 달리 정리 잡에 외부 HTTP가 없다.
     *
     * <p>닫는 자리 넷(재연동 · 해제 · 갱신 거절 {@link SoopTokenRefresher} · 탈퇴)이 모두 이것 하나를 쓴다. 그래서
     * 부분 삭제 결함(감사 1회차 중대 1)을 한 번 고치면 넷이 같이 닫힌다. package-private은 갱신기가 같은 트랜잭션
     * (그쪽의 회원 행 락 안)에서 부르기 위해서다. {@code done}은 secrets를 다 지운 뒤에 찍을 「일어났다」 로그다.
     */
    void closeAndPurge(Long userId, Instant now, RevokeReason reason, Runnable done) {
        links.findByUserIdAndRevokedAtIsNull(userId).ifPresent(old -> {
            String accessRef = old.getAccessTokenRef();
            String refreshRef = old.getRefreshTokenRef();
            links.closeAndPurgeAlive(userId, now, reason);
            cleanup.afterCommit(userId, () -> purgeSecrets(accessRef, refreshRef, done));
        });
    }

    /**
     * 정리 잡 본문(전용 스레드). 닫는 자리(재연동·해제, 뒤에 갱신 거절·탈퇴)가 전부 이 하나를 쓴다.
     *
     * <p>🔴 둘을 각각 시도한다: 하나가 던져도 나머지는 지운다. 한 흐름으로 두면 첫 실패가 둘째를 건너뛰어
     * refresh 원문이 secrets에 영구히 남는다(유튜브 {@code YoutubeLinkWriter.cleanupOld}가 봇 리뷰 PR #116에서
     * 고친 자리, SOOP은 고치기 전 치지직 모양을 복제했다가 감사 1회차가 재현했다). 예외는 마지막에 다시 올려
     * 잡이 {@code cleanup.failed} WARN으로 남기게 한다.
     */
    private void purgeSecrets(String accessRef, String refreshRef, Runnable done) {
        RuntimeException deleteFailure = deleteQuietly(accessRef, null);
        deleteFailure = deleteQuietly(refreshRef, deleteFailure);
        if (deleteFailure != null) {
            throw deleteFailure;
        }
        done.run();
    }

    /** 지우고, 실패하면 예외를 모아 둔다(먼저 난 것을 유지한다). 나머지 삭제를 막지 않으려고. */
    private RuntimeException deleteQuietly(String ref, RuntimeException earlier) {
        try {
            secretStore.delete(ref);
            return earlier;
        } catch (RuntimeException e) {
            return earlier != null ? earlier : e;
        }
    }
}
