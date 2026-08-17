package com.pokeclip.auth.chzzk;

import java.time.Instant;

/**
 * 갱신 결과 + 그 시점 행의 스냅샷(access 원문 포함). resolve는 스냅샷만 쓰고 행도 secrets도
 * 두 번째로 읽지 않는다 — 락 안에서 읽은 것이 답이다. 락 밖에서 다시 읽으면 그 사이 해제·재연동
 * 커밋과 정리 스레드의 delete가 끼어 "행은 있는데 secret 없음"(500)이 될 수 있다.
 * REFRESHED·SKIPPED_FRESH일 때만 snapshot이 있고 나머지는 null.
 *
 * <p>{@code {}}에 넣지 않는다 — accessToken 원문·channelId. SecretLeakTest가 {@code RefreshResult[}·{@code LinkSnapshot[}을 금지한다.
 */
public record RefreshResult(RefreshOutcome outcome, LinkSnapshot snapshot) {

    public record LinkSnapshot(String channelId, String accessToken, Instant accessExpiresAt) {
    }

    static RefreshResult of(RefreshOutcome outcome) {
        return new RefreshResult(outcome, null);
    }

    static RefreshResult of(RefreshOutcome outcome, ChzzkChannelLink link, String accessToken) {
        return new RefreshResult(outcome,
                new LinkSnapshot(link.getChannelId(), accessToken, link.getAccessExpiresAt()));
    }
}
