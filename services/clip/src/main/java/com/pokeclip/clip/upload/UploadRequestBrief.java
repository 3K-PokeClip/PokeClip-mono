package com.pokeclip.clip.upload;

/**
 * 화면에 내주는 「렌더 뒤 업로드」 의도 요약(POK-291). 영상 조회({@code ClipSnapshot.uploadRequest})·보관함 줄이 싣는다.
 * 설명·태그는 안 싣는다: 목록 폴링이 무거워지고, 화면이 다시 열 때 채울 칸은 이 셋이다.
 */
public record UploadRequestBrief(String title, String privacyStatus, String thumbnailSource) {
}
