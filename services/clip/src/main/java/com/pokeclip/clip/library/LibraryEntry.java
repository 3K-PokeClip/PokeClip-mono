package com.pokeclip.clip.library;

import com.pokeclip.clip.recipe.RecipeDocument.Cut;
import com.pokeclip.clip.render.ClipSnapshot;

import java.time.Instant;

/**
 * 보관함 목록 한 줄 — 편집본 하나와 그 원본 방송, 그리고 가장 최근 영상. 2번(보관함 화면)과의 계약이라 칸 이름을 바꾸지 않는다.
 *
 * <p>편집본 본문(계약6 JSON)은 안 싣는다 — 자막이 많은 편집본은 수십 KB라 한 장 스무 줄이 무거워진다. 상세({@link LibraryDetail})가 준다.
 *
 * @param status {@link LibraryStatus#param()} — 화면의 거르기 값과 같은 문자열
 * @param cut 구간. 템플릿이면 {@code null}
 * @param latestClip 가장 최근에 만든 영상(주문 문의 봉투 그대로). 한 번도 안 만들었으면 {@code null}.
 *                   🔴 {@code recipeVersion}이 이 줄의 것과 다를 수 있다 — 그때 {@code status}는 {@code editing}이다
 */
public record LibraryEntry(long recipeId,
                           String streamId,
                           String creatorId,
                           int recipeVersion,
                           Cut cut,
                           String status,
                           BroadcastSummary broadcast,
                           ClipSnapshot latestClip,
                           Instant createdAt,
                           Instant updatedAt) {

    /** 원본 방송 요약. 화면의 「8월 31일 라이브」·원본 만료 D-day 재료. 방송 목록 줄과 칸 이름이 같다. */
    public record BroadcastSummary(String status, Instant startedAt, Instant endedAt, Instant vodExpiresAt) {
    }
}
