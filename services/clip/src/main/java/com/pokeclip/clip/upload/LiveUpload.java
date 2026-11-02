package com.pokeclip.clip.upload;

import java.time.Instant;

/**
 * 보관함 줄에 싣는 「살아 있는 업로드」 하나(POK-334). 편집본의 <b>모든 판</b>에서 실패가 아닌 업로드다: 대기 · 올리는 중 ·
 * 확인 필요 · 올림. 새 판 영상을 주문해 {@code latestClip}이 바뀌어도 옛 판으로 올린 영상이 여기 남는다.
 *
 * <p>「살아 있다」의 기준은 {@link ClipUploadRepository#findActiveForVersion}과 같다(실패만 뺀다). 칸은 더하기만 한다(2번 화면과의 계약).
 * 🔴 이어 올리기 주소({@code session_uri})는 싣지 않는다: 그 주소 자체가 올리기 권한이다({@link UploadSnapshot}과 같은 이유).
 *
 * @param clipId        이 업로드가 올린 영상 번호. 옛 판 영상을 조회하는 문의 열쇠다
 * @param recipeVersion 그 영상을 만든 편집본 판
 * @param status        {@link UploadStatus} 소문자. {@code failed}는 안 온다
 * @param videoId       올렸을 때만. 주소는 {@code https://youtu.be/{videoId}}
 */
public record LiveUpload(long id,
                         long clipId,
                         int recipeVersion,
                         String outputId,
                         String title,
                         String status,
                         String videoId,
                         Instant createdAt,
                         Instant updatedAt) {

    /** 질의가 뽑는 줄: 어느 편집본 것인지를 붙여 묶는다. JPQL 생성자 식이 부르므로 공개다. */
    public record Row(long recipeId, LiveUpload upload) {

        public Row(long recipeId, long id, long clipId, int recipeVersion, String outputId, String title, String status,
                   String videoId, Instant createdAt, Instant updatedAt) {
            this(recipeId, new LiveUpload(id, clipId, recipeVersion, outputId, title, status, videoId, createdAt, updatedAt));
        }
    }
}
