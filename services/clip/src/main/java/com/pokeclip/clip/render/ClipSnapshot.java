package com.pokeclip.clip.render;

import com.pokeclip.clip.upload.UploadSnapshot;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * 주문·조회 문이 돌려주는 완성 영상 한 벌. 2번(보관함 화면)과의 계약이라 칸 이름을 바꾸지 않는다.
 *
 * @param status {@link ClipStatus} 소문자 — 화면의 거르기 값
 * @param outputs 완성이면 일꾼이 보고한 산출물 목록(계약1 result) 그대로, 아니면 {@code null}. {@code s3Key}가 들어 있다 —
 *                화면은 그것으로 영상을 직접 못 받고(창고는 비공개) 출입증 문이 따로 필요하다(POK-243 이후)
 * @param upload 가장 최근 유튜브 업로드(POK-220). 한 번도 안 올렸으면 {@code null}
 */
public record ClipSnapshot(long id,
                           String streamId,
                           long recipeId,
                           int recipeVersion,
                           String requestedBy,
                           String status,
                           Progress progress,
                           JsonNode outputs,
                           Error error,
                           Instant createdAt,
                           Instant updatedAt,
                           UploadSnapshot upload) {

    /** 일꾼이 마지막으로 보고한 진행. 주문만 됐으면 0·null. */
    public record Progress(int percent, String stage, int attempt, UUID jobId) {
    }

    public record Error(String code, String message) {
    }
}
