package com.pokeclip.clip.upload;

import java.time.Instant;

/**
 * 화면에 내주는 「렌더 뒤 업로드」 의도 요약(POK-291). 영상 조회({@code ClipSnapshot.uploadRequest})·보관함 줄이 싣는다.
 * 설명·태그는 안 싣는다: 목록 폴링이 무거워진다. 화면이 창을 다시 열 때 이 값들로 채운다 — 카테고리·예약 시각(POK-381)이
 * 빠지면 다시 연 창이 게임·「비공개」로 열려, 그대로 다시 누르면 같은 판의 예약이 지워진다(PR #301 리뷰).
 *
 * @param publishAt 예약 공개 시각. 예약이 아니면 {@code null}
 */
public record UploadRequestBrief(String title, String privacyStatus, String thumbnailSource, String categoryId,
                                 Instant publishAt) {
}
