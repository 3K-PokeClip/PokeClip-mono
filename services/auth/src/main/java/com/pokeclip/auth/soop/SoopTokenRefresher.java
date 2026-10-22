package com.pokeclip.auth.soop;

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
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * SOOP 토큰 갱신기. {@code chzzk/ChzzkTokenRefresher}의 쌍둥이다. 갱신만 안다(누가 부르는지는 모른다).
 *
 * <p><b>트랜잭션 최상단이어야 한다.</b> 호출부(스케줄러·resolve)에 {@code @Transactional}을 붙이면 4xx 경로의
 * 닫기·파기가 상위 롤백에 딸려가고 외부 HTTP가 상위 트랜잭션 수명에 묶인다(테스트는 그래도 통과한다).
 *
 * <p>치지직과 다른 점 셋: ① 점검({@link #refreshForCheck})은 만료가 멀어도 마지막 확인이 staleness를 넘으면 갱신해
 * 본다(SOOP은 철회를 알려 주지 않아 갱신 거절로만 안다) ② 4xx는 엔티티가 아니라 {@link SoopLinkWriter#closeAndPurge}로
 * 닫고 아이디·닉네임까지 같은 문장에서 비운다 ③ 정리에 외부 HTTP가 없다(폐기 API 없음).
 *
 * <p>🔴 연속 거절 브레이크는 여기가 아니라 스케줄러에만 있다. resolve(수집기가 부르는 즉석 갱신)의 4xx는 한 사람 일이라
 * 그대로 파기한다.
 */
@Component
@RequiredArgsConstructor
public class SoopTokenRefresher {

    private static final Logger log = LoggerFactory.getLogger(SoopTokenRefresher.class);

    private final UserRepository users;
    private final SoopChannelLinkRepository links;
    private final SecretStore secretStore;
    private final SoopOAuthClient oauthClient;
    private final SoopLinkWriter writer;
    private final SoopProperties properties;

    /** resolve용. 남은 수명이 minRemaining보다 짧을 때만 SOOP을 부른다. 신선도는 안 본다. */
    @Transactional
    public RefreshResult refreshIfExpiringWithin(Long userId, Duration minRemaining) {
        return refresh(userId, minRemaining, false);
    }

    /** 스케줄러용. 만료 임박(refreshAhead)이거나 마지막 확인이 staleness를 넘었으면 SOOP을 부른다. */
    @Transactional
    public RefreshResult refreshForCheck(Long userId) {
        return refresh(userId, properties.refreshAhead(), true);
    }

    /**
     * 회원 행 락으로 직렬화한다. refresh는 일회용으로 다룬다: 락 <b>뒤에</b> 행을 다시 읽어야 경합 상대가 방금 갱신한
     * 것이 보인다(락 전에 읽은 엔티티는 1차 캐시가 옛 상태를 준다). 락을 잡는 자리는 여기 하나다(가드 밖 락 명부 한 줄).
     *
     * <p>저장 창(SOOP 응답 수신과 우리 커밋 사이)은 완전히 닫을 수 없다. 치지직과 같이 한 커밋으로 ms까지 줄이고, 새면
     * 다음 갱신이 옛 refresh로 4xx를 받아 BROKEN으로 수렴한다.
     */
    private RefreshResult refresh(Long userId, Duration minRemaining, boolean checkStaleness) {
        // DB(timestamptz)는 마이크로초까지다. 리눅스 시계는 나노초라 자르지 않으면 스냅샷의 만료 시각이 저장값과 어긋난다.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        if (users.findByIdForUpdate(userId).isEmpty()) {
            return RefreshResult.of(RefreshOutcome.NOT_LINKED);
        }
        Optional<SoopChannelLink> found = links.findByUserIdAndRevokedAtIsNull(userId);   // 락 뒤 재읽기
        if (found.isEmpty()) {
            return RefreshResult.of(RefreshOutcome.NOT_LINKED);
        }
        SoopChannelLink link = found.get();
        boolean expiring = !link.getAccessExpiresAt().isAfter(now.plus(minRemaining));
        boolean stale = checkStaleness
                && !link.getLastRefreshedAt().isAfter(now.minus(properties.check().staleness()));
        if (!expiring && !stale) {
            String accessToken = secretStore.get(link.getAccessTokenRef())
                    .orElseThrow(() -> new IllegalStateException("연동 행은 있는데 access secret이 없다 userId=" + userId));
            return RefreshResult.of(RefreshOutcome.SKIPPED_FRESH, link, accessToken);
        }
        String refreshToken = secretStore.get(link.getRefreshTokenRef())
                .orElseThrow(() -> new IllegalStateException("연동 행은 있는데 refresh secret이 없다 userId=" + userId));
        SoopTokens tokens;
        try {
            tokens = oauthClient.refresh(refreshToken);
        } catch (SoopRejectedException e) {
            // 철회·만료. 다시 시도해도 같다. 닫고(아이디·닉네임도 비운다) 커밋 뒤에 secrets를 지운다(약관 제11조 ⑰).
            // 닫는 문장이 영속성 컨텍스트를 비우므로 이 뒤로 link를 쓰지 않는다.
            int status = e.status();
            writer.closeAndPurge(userId, now, RevokeReason.REFRESH_REJECTED,
                    () -> log.warn("auth.soop.link.refresh_rejected userId={} status={}", userId, status));
            return RefreshResult.of(RefreshOutcome.REJECTED);
        } catch (SoopUnavailableException e) {
            // 2xx 뒤 형식 오류도 여기로 온다. refresh가 실제로 소비됐다면 다음 갱신이 4xx → BROKEN으로 수렴한다
            // (치지직과 같은 판단: 프록시 200 HTML까지 즉시 BROKEN으로 만들지 않는다).
            log.warn("auth.soop.link.refresh_failed userId={} causeType={}", userId, e.causeType());
            return RefreshResult.of(RefreshOutcome.UNAVAILABLE);
        }
        // 같은 ref에 덮어쓴다. 응답에 refresh가 없으면 옛 것을 그대로 둔다(null로 덮으면 다음 갱신이 못 돈다).
        secretStore.put(link.getAccessTokenRef(), tokens.accessToken());
        if (tokens.refreshToken() != null) {
            secretStore.put(link.getRefreshTokenRef(), tokens.refreshToken());
        }
        link.refreshed(now.plus(tokens.expiresIn()), tokens.scope(), now);
        logAfterCommit(() -> log.info("auth.soop.link.refreshed userId={}", userId));
        return RefreshResult.of(RefreshOutcome.REFRESHED, link, tokens.accessToken());
    }

    /** 「일어났다」 로그는 커밋 뒤에만(치지직과 같다). 같은 스레드 동기라 MDC 상관 ID가 살아 있다. */
    private static void logAfterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
