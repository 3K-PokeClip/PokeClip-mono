package com.pokeclip.auth.soop;

import com.pokeclip.auth.streamkey.secret.SecretStore;
import com.pokeclip.auth.user.ActiveUserGuard;
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
        closeAndPurge(userId, now, RevokeReason.USER_UNLINKED, "auth.soop.link.relinked");   // 재연동이면 옛 행을 닫는다
        String accessRef = "soop-access:" + UUID.randomUUID();
        String refreshRef = "soop-refresh:" + UUID.randomUUID();
        secretStore.put(accessRef, tokens.accessToken());
        secretStore.put(refreshRef, tokens.refreshToken());
        return links.saveAndFlush(SoopChannelLink.of(userId, identity.soopUserId(), identity.nickname(),
                tokens.scope(), accessRef, refreshRef, now.plus(tokens.expiresIn()), now));
    }

    /**
     * 살아 있는 내 연동을 닫고(아이디·닉네임도 같은 문장에서 비운다) 커밋 뒤에 secrets를 지운다. 락 뒤에 부른다.
     *
     * <p>옛 행은 락 뒤에 읽는다(락 전 엔티티 읽기 금지). ref 둘은 닫기 <b>전에</b> 읽어 둔다: 닫기 쿼리가
     * 영속성 컨텍스트를 비워 그 뒤엔 엔티티가 떨어진다.
     *
     * <p>정리는 afterCommit에서 <b>제출만</b> 하고 전용 스레드({@link SoopCleanupExecutor})가 돈다: afterCommit 안에서
     * REQUIRES_NEW delete를 직접 부르면 풀 데드락이 된다. 치지직과 달리 정리 잡에 외부 HTTP가 없다.
     */
    private void closeAndPurge(Long userId, Instant now, RevokeReason reason, String event) {
        links.findByUserIdAndRevokedAtIsNull(userId).ifPresent(old -> {
            String accessRef = old.getAccessTokenRef();
            String refreshRef = old.getRefreshTokenRef();
            links.closeAndPurgeAlive(userId, now, reason);
            cleanup.afterCommit(userId, () -> {
                secretStore.delete(accessRef);
                secretStore.delete(refreshRef);
                log.info("{} userId={}", event, userId);
            });
        });
    }
}
