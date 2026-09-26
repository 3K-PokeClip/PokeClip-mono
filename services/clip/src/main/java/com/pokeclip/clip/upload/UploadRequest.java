package com.pokeclip.clip.upload;

/**
 * 업로드 주문 본문. 형식 검사는 {@link UploadRequestService}가 한다(칸 이름만 싣는 400).
 *
 * @param description 없으면 빈 설명
 * @param outputId    없으면 그 영상의 유일한 영상 파일. 여럿이면 필수다
 */
public record UploadRequest(String title, String description, String outputId) {
}
