package com.pokeclip.clip.upload;

import java.time.Instant;
import java.util.List;

/**
 * 화면에 내주는 업로드 한 줄. 🔴 이어 올리기 주소({@code session_uri})는 <b>없다</b>: 그 주소 자체가 올리기 권한이다.
 * 설명도 안 싣는다(POK-291): 보관함 목록 폴링이 무거워진다. 칸은 더하기만 한다(2번 화면과의 계약).
 *
 * @param status        {@link UploadStatus} 소문자
 * @param videoId       올렸을 때만. 주소는 {@code https://youtu.be/{videoId}}
 * @param privacyStatus 고른 공개 범위(POK-291). 우리가 실어 보낸 값이다(스튜디오에서 바꾸면 모른다)
 * @param categoryId    고른 유튜브 분류 번호(POK-381)
 * @param publishAt     예약 공개 시각(POK-381). 예약이 아니면 {@code null}
 * @param thumbnail     썸네일 고르기와 결과(POK-291)
 * @param resolvedBy    「확인 필요」를 사람이 풀었으면 그 사람(POK-333). 일꾼이 끝냈으면 {@code null}
 * @param resolvedAt    그 시각. {@code resolvedBy}와 같이 차고 같이 빈다
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
                             Thumbnail thumbnail,
                             String resolvedBy,
                             Instant resolvedAt,
                             String categoryId,
                             Instant publishAt) {

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
                new Thumbnail(upload.getThumbnailSource(), upload.getThumbnailStatus(), upload.getThumbnailErrorCode()),
                upload.getResolvedBy(), upload.getResolvedAt(), upload.getCategoryId(), upload.getPublishAt());
    }
}
