package com.pokeclip.clip.upload;

import com.pokeclip.clip.upload.UploadInfo.Thumbnail;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;

/**
 * 업로드 주문서(clip → 업로드 일꾼, {@code jobs-upload}). <b>쓰는 자리가 이 하나다</b>: 옛 문·다시 시도·렌더 성공 자동이 모두 부른다.
 * 자리가 갈리면 한쪽만 새 칸을 빠뜨린다.
 *
 * <p>{@code schemaVersion}은 1 그대로다(POK-291). 새 칸(tags·madeForKids·thumbnail)은 선택 칸이라 옛 일꾼은 모르는 칸으로 넘긴다.
 * 판을 올리면 옛 일꾼이 주문서를 못 읽는다며 지우고, clip 줄은 {@code queued}로 영원히 남는다.
 * 🔴 다만 넘긴다는 것이 무해하다는 뜻은 아니다: 옛 일꾼은 {@code madeForKids}를 읽지 않고 {@code false}로 덮어 올리고 태그·썸네일을
 * 버린다. 그래서 배포 순서는 업로드 일꾼 → auth → clip → web이다(services/README 「유튜브 업로드 주문」 절).
 *
 * <p>토큰은 안 싣는다: 일꾼이 올리기 직전에 auth 창구에 묻는다(주문서는 로그·실패 큐에 남는다).
 */
final class UploadPayload {

    private UploadPayload() {
    }

    /** 올릴 영상 파일. 일꾼이 보고한 산출물(계약1 result) 중 {@code kind=video}. */
    record Source(String bucket, String s3Key, String outputId) {
    }

    static String build(ObjectMapper mapper, long uploadId, long clipId, String streamId, String channelOwner,
                        Source source, UploadInfo info, String thumbnailBucket, Instant requestedAt) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", 1);
        root.put("jobType", "UPLOAD");
        root.put("uploadId", String.valueOf(uploadId));
        root.put("clipId", String.valueOf(clipId));
        root.put("streamId", streamId);
        // auth 회원 번호(문자열). 일꾼이 이 번호로 유튜브 토큰을 묻는다.
        root.put("channelOwnerUserId", channelOwner);
        ObjectNode src = root.putObject("source");
        src.put("bucket", source.bucket());
        src.put("s3Key", source.s3Key());
        src.put("outputId", source.outputId());
        ObjectNode video = root.putObject("video");
        video.put("title", info.title());
        video.put("description", info.description());
        // 주문한 사람(스트리머·편집자)이 고른 값(ADR-084). 유튜브가 이 범위를 쓰는 사정은 services/README.md 업로드 절.
        video.put("privacyStatus", info.privacyStatus());
        ArrayNode tags = video.putArray("tags");
        info.tags().forEach(tags::add);
        video.put("madeForKids", info.madeForKids());
        Thumbnail thumbnail = info.thumbnail();
        switch (thumbnail.source()) {
            case Thumbnail.SCENE -> {
                ObjectNode node = root.putObject("thumbnail");
                node.put("source", Thumbnail.SCENE);
                node.put("offsetMs", thumbnail.offsetMs());
            }
            case Thumbnail.FILE -> {
                ObjectNode node = root.putObject("thumbnail");
                node.put("source", Thumbnail.FILE);
                node.put("bucket", thumbnailBucket);
                node.put("s3Key", thumbnail.s3Key());
                node.put("contentType", thumbnail.contentType());
            }
            default -> root.putNull("thumbnail");
        }
        root.put("requestedAt", requestedAt.toString());
        return mapper.writeValueAsString(root);
    }
}
