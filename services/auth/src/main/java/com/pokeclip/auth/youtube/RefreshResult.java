package com.pokeclip.auth.youtube;

import java.time.Instant;

/**
 * 갱신 결과 + 그 시점 행의 스냅샷(access 원문 포함). resolve는 스냅샷만 쓰고 행도 secrets도 두 번째로
 * 읽지 않는다 — 락 안에서 읽은 것이 답이다. 락 밖에서 다시 읽으면 그 사이 해제·재연동 커밋과 정리
 * 스레드의 delete가 끼어 "행은 있는데 secret 없음"(500)이 될 수 있다.
 * REFRESHED·SKIPPED_FRESH일 때만 snapshot이 있고 나머지는 null.
 *
 * <p>{@code lastStatus}는 <b>NOT_LINKED일 때만</b> 채운다 — 살아있는 연동이 없을 때 「애초에 안 한 것」과
 * 「사용자가 해제한 것」과 「갱신이 거부된 것」을 가르는 재료다. <b>이것도 락 안에서 읽는다</b>:
 * 호출부가 락 밖에서 마지막 행을 다시 읽으면 그 사이 커밋된 <b>새 연동(ACTIVE)</b>을 집어
 * UNLINKED로 오분류한다(로컬 리뷰 2026-08-24). 마지막 행 자체가 없으면 null이다.
 *
 * <p>{@code {}}에 넣지 않는다 — accessToken 원문·channelId.
 */
public record RefreshResult(RefreshOutcome outcome, LinkSnapshot snapshot, LinkStatus lastStatus) {

    public record LinkSnapshot(String channelId, String accessToken, Instant accessExpiresAt) {
    }

    static RefreshResult of(RefreshOutcome outcome) {
        return new RefreshResult(outcome, null, null);
    }

    /** NOT_LINKED 전용 — 락 안에서 본 마지막 행의 상태를 함께 싣는다(행이 없으면 null). */
    static RefreshResult of(RefreshOutcome outcome, LinkStatus lastStatus) {
        return new RefreshResult(outcome, null, lastStatus);
    }

    static RefreshResult of(RefreshOutcome outcome, YoutubeChannelLink link, String accessToken) {
        return new RefreshResult(outcome,
                new LinkSnapshot(link.getChannelId(), accessToken, link.getAccessExpiresAt()), null);
    }
}
