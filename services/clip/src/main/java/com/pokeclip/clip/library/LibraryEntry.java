package com.pokeclip.clip.library;

import com.pokeclip.clip.recipe.RecipeDocument.Cut;
import com.pokeclip.clip.render.ClipSnapshot;
import com.pokeclip.clip.upload.UploadRequestBrief;

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
 * @param thumbnailUrl {@code latestClip}의 사진 주소(POK-277, 수명 60분 미리서명). 영상 구간 안 최고 점수 카드 장면, 없으면 가운데.
 *                     영상이 없거나 아직 안 찍었으면 {@code null}
 * @param uploadRequest 이 편집본 <b>지금 판</b>의 「렌더 뒤 업로드」 의도 요약(POK-291). 없으면 {@code null}.
 *                      업로드 줄이 아직 없을 때 화면이 제목을 이것으로 보인다. 「업로드 실패」 상태는 서버가 안 만든다:
 *                      화면이 {@code rendered && latestClip.upload.status == failed}로 만든다
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
                           Instant updatedAt,
                           String thumbnailUrl,
                           UploadRequestBrief uploadRequest) {

    public LibraryEntry withThumbnailUrl(String url) {
        return new LibraryEntry(recipeId, streamId, creatorId, recipeVersion, cut, status, broadcast, latestClip, createdAt,
                updatedAt, url, uploadRequest);
    }

    /** 원본 방송 요약. 화면의 「8월 31일 라이브」·원본 만료 D-day 재료. 방송 목록 줄과 칸 이름이 같다. */
    public record BroadcastSummary(String status, Instant startedAt, Instant endedAt, Instant vodExpiresAt) {
    }
}
