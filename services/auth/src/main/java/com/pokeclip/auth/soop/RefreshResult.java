package com.pokeclip.auth.soop;

import java.time.Instant;

/**
 * 갱신 결과 + 그 시점 행의 스냅샷(access 원문 포함). resolve는 스냅샷만 쓰고 행도 secrets도 두 번째로 읽지 않는다
 * (치지직 {@code RefreshResult}와 같은 이유: 락 밖에서 다시 읽으면 그 사이 해제가 끼어 500이 된다).
 * REFRESHED·SKIPPED_FRESH일 때만 snapshot이 있고 나머지는 null.
 *
 * <p>{@code {}}에 넣지 않는다: record의 toString이 access 원문·SOOP 아이디를 찍는다.
 */
public record RefreshResult(RefreshOutcome outcome, LinkSnapshot snapshot) {

    public record LinkSnapshot(String soopUserId, String accessToken, Instant accessExpiresAt) {
    }

    static RefreshResult of(RefreshOutcome outcome) {
        return new RefreshResult(outcome, null);
    }

    static RefreshResult of(RefreshOutcome outcome, SoopChannelLink link, String accessToken) {
        return new RefreshResult(outcome,
                new LinkSnapshot(link.getSoopUserId(), accessToken, link.getAccessExpiresAt()));
    }
}
