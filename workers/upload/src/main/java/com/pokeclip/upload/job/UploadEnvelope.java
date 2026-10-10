package com.pokeclip.upload.job;

import java.util.List;

/**
 * clip이 줄에 실은 업로드 주문서(clip {@code UploadRequestService.payload}). 토큰은 없다.
 *
 * @param channelOwnerUserId auth 회원 번호. 이 번호로 유튜브 토큰을 묻는다(방송의 스트리머, ADR-010 Path A)
 * @param privacyStatus      스트리머가 고른 공개 범위({@code private}·{@code unlisted}·{@code public}, ADR-084)
 * @param tags               유튜브 태그. 없으면 빈 목록
 * @param madeForKids        아동용 영상이라고 스스로 밝히는가
 * @param thumbnail          붙일 썸네일. 없으면 null(유튜브가 고른다)
 */
public record UploadEnvelope(long uploadId, long channelOwnerUserId, String bucket, String s3Key,
                             String title, String description, String privacyStatus, List<String> tags,
                             boolean madeForKids, Thumbnail thumbnail) {

    public UploadEnvelope {
        tags = List.copyOf(tags);
    }

    /** 썸네일을 어디서 가져오나(POK-291 명세 §4). */
    public sealed interface Thumbnail {

        /** 완성 영상의 {@code offsetMs} 자리 장면. 일꾼이 받은 mp4에서 ffmpeg로 뽑는다. */
        record Scene(long offsetMs) implements Thumbnail { }

        /** 사용자가 올린 그림. 창고에서 받는다. */
        record File(String bucket, String s3Key, String contentType) implements Thumbnail { }
    }
}
