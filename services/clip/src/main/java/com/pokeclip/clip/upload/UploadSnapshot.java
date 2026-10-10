package com.pokeclip.clip.upload;

import java.time.Instant;
import java.util.List;

/**
 * 화면에 내주는 업로드 한 줄. 🔴 이어 올리기 주소({@code session_uri})는 <b>없다</b>: 그 주소 자체가 올리기 권한이다.
 * 설명도 안 싣는다(POK-291): 보관함 목록 폴링이 무거워진다. 칸은 더하기만 한다(2번 화면과의 계약).
 *
 * @param status        {@link UploadStatus} 소문자
 * @param videoId       올렸을 때만. 주소는 {@code https://youtu.be/{videoId}}
 * @param privacyStatus 고른 공개 범위(POK-291). 유튜브가 그대로 쓴다(감사 전 잠금이 안 걸린다, 2026-10-11 실측)
 * @param thumbnail     썸네일 고르기와 결과(POK-291)
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
                             Instant updatedAt,
                             String privacyStatus,
                             boolean madeForKids,
                             List<String> tags,
                             Thumbnail thumbnail) {

    public record Error(String code, String message) {
    }

    /**
     * @param source    {@code none}·{@code scene}·{@code file}
     * @param status    {@code none}·{@code pending}·{@code set}·{@code failed}
     * @param errorCode {@code failed}일 때 일꾼이 보고한 코드(예: {@code THUMBNAIL_FORBIDDEN})
     */
    public record Thumbnail(String source, String status, String errorCode) {
    }

    public static UploadSnapshot of(ClipUpload upload) {
        return new UploadSnapshot(upload.getId(), upload.getClipId(), upload.getOutputId(), upload.getTitle(),
                upload.getStatus().dbValue(), upload.getYoutubeVideoId(),
                upload.getErrorCode() == null ? null : new Error(upload.getErrorCode(), upload.getErrorMessage()),
                upload.getRequestedBy(), upload.getCreatedAt(), upload.getUpdatedAt(),
                upload.getPrivacyStatus(), upload.isMadeForKids(), upload.getTags(),
                new Thumbnail(upload.getThumbnailSource(), upload.getThumbnailStatus(), upload.getThumbnailErrorCode()));
    }
}
