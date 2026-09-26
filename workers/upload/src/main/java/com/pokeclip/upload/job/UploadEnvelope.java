package com.pokeclip.upload.job;

/**
 * clip이 줄에 실은 업로드 주문서(clip {@code UploadRequestService.payload}). 토큰은 없다.
 *
 * @param channelOwnerUserId auth 회원 번호. 이 번호로 유튜브 토큰을 묻는다(방송의 스트리머, ADR-010 Path A)
 * @param privacyStatus      지금은 늘 {@code private}(ADR-010)
 */
public record UploadEnvelope(long uploadId, long channelOwnerUserId, String bucket, String s3Key,
                             String title, String description, String privacyStatus) {
}
