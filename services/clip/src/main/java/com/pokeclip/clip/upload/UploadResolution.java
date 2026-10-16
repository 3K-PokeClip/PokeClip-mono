package com.pokeclip.clip.upload;

/**
 * 「확인 필요」 업로드를 푸는 요청 본문(POK-333).
 *
 * @param result  {@code uploaded}(채널에 있다) · {@code not_uploaded}(채널에 없다)
 * @param videoId {@code uploaded}일 때만. 영상 번호 그대로이거나 그 영상의 유튜브 주소(youtu.be · watch · shorts · 스튜디오)
 */
public record UploadResolution(String result, String videoId) {
}
