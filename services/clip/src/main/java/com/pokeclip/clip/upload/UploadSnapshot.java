package com.pokeclip.clip.upload;

import java.time.Instant;

/**
 * 화면에 내주는 업로드 한 줄. 🔴 이어 올리기 주소({@code session_uri})는 <b>없다</b>: 그 주소 자체가 올리기 권한이다.
 *
 * @param status  {@link UploadStatus} 소문자
 * @param videoId 올렸을 때만. 주소는 {@code https://youtu.be/{videoId}}
 */
public record UploadSnapshot(long id,
                             long clipId,
                             String outputId,
                             String title,
                             String status,
                             String videoId,
                             Error error,
                             String requestedBy,
                             Instant createdAt,
                             Instant updatedAt) {

    public record Error(String code, String message) {
    }

    public static UploadSnapshot of(ClipUpload upload) {
        return new UploadSnapshot(upload.getId(), upload.getClipId(), upload.getOutputId(), upload.getTitle(),
                upload.getStatus().dbValue(), upload.getYoutubeVideoId(),
                upload.getErrorCode() == null ? null : new Error(upload.getErrorCode(), upload.getErrorMessage()),
                upload.getRequestedBy(), upload.getCreatedAt(), upload.getUpdatedAt());
    }
}
