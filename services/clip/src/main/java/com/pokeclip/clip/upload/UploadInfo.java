package com.pokeclip.clip.upload;

import java.time.Instant;
import java.util.List;

/**
 * 유튜브에 올릴 정보 한 벌(POK-291). 검사를 지난 값만 담는다({@link UploadInfoParser}가 만든다).
 * 의도 표({@code upload_requests})·업로드 줄({@code clip_uploads})·주문서가 같은 모양을 나눠 쓴다.
 *
 * @param categoryId    유튜브 분류 번호(POK-381). 고르지 않으면 {@link #GAMING}
 * @param privacyStatus {@code private}·{@code unlisted}·{@code public}
 * @param publishAt     예약 공개 시각(POK-381). 있으면 {@code privacyStatus}는 {@code private}다. 없으면 {@code null}
 */
public record UploadInfo(String title,
                         String description,
                         List<String> tags,
                         String categoryId,
                         String privacyStatus,
                         Instant publishAt,
                         boolean madeForKids,
                         Thumbnail thumbnail) {

    public static final String PRIVATE = "private";
    /** 게임. POK-381 전에는 일꾼이 이 값을 박아 보냈다. */
    public static final String GAMING = "20";

    public UploadInfo {
        tags = List.copyOf(tags);
    }

    /** 사용자가 올린 그림을 창고에 둔 뒤 그 자리를 채운다. */
    public UploadInfo withThumbnail(Thumbnail thumbnail) {
        return new UploadInfo(title, description, tags, categoryId, privacyStatus, publishAt, madeForKids, thumbnail);
    }

    /**
     * 썸네일 고르기. {@code none}이면 유튜브가 고른다.
     *
     * @param offsetMs    {@code scene}일 때만. 완성 영상 첫 장면 기준 ms
     * @param s3Key       {@code file}일 때만. 창고에 둔 뒤에 채워진다
     * @param contentType {@code file}일 때만. {@code image/jpeg}·{@code image/png}(첫 바이트로 판정한 값)
     */
    public record Thumbnail(String source, Long offsetMs, String s3Key, String contentType) {

        public static final String NONE = "none";
        public static final String SCENE = "scene";
        public static final String FILE = "file";

        public static Thumbnail none() {
            return new Thumbnail(NONE, null, null, null);
        }

        public static Thumbnail scene(long offsetMs) {
            return new Thumbnail(SCENE, offsetMs, null, null);
        }

        public static Thumbnail file(String s3Key, String contentType) {
            return new Thumbnail(FILE, null, s3Key, contentType);
        }

        public boolean isFile() {
            return FILE.equals(source);
        }

        /** 업로드 줄의 첫 썸네일 상태. 붙일 것이 없으면 {@code none}, 있으면 결과 전 {@code pending}. */
        String initialStatus() {
            return NONE.equals(source) ? "none" : "pending";
        }
    }
}
