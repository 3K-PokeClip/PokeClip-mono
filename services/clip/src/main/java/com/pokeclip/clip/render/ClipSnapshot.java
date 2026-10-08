package com.pokeclip.clip.render;

import com.pokeclip.clip.upload.UploadRequestBrief;
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
 * @param uploadRequest 이 영상의 판(편집본·판 번호)에 걸린 「렌더 뒤 업로드」 의도 요약(POK-291). 없으면 {@code null}.
 *                      완성과 업로드 줄은 한 트랜잭션이라, 완성인데 이것이 있고 {@code upload}가 없으면 자동 업로드를 건너뛴
 *                      것이다(업로드 줄 꺼짐 · 같은 판이 이미 올라감). 화면은 「업로드는 시작되지 않았어요」로 보고 묻기를 멈춘다
 *                      (보관함의 「업로드」가 다시 시도 문으로 저장된 정보로 올린다)
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
                           UploadSnapshot upload,
                           UploadRequestBrief uploadRequest) {

    /** 일꾼이 마지막으로 보고한 진행. 주문만 됐으면 0·null. */
    public record Progress(int percent, String stage, int attempt, UUID jobId) {
    }

    public record Error(String code, String message) {
    }
}
