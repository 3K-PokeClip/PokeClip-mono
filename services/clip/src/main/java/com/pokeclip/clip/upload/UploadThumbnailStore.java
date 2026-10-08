package com.pokeclip.clip.upload;

import com.pokeclip.clip.upload.UploadErrors.ThumbnailStoreUnavailableException;
import com.pokeclip.clip.upload.UploadErrors.UnsupportedImageException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.InputStream;
import java.util.Arrays;
import java.util.UUID;

/**
 * 사용자가 올린 썸네일 그림을 완성 영상 창고(CLIPS_BUCKET)에 둔다(POK-291). <b>clip이 S3에 쓰는 유일한 자리다.</b>
 * 업로드 줄이 켜져 있을 때만 빈이 생긴다({@link UploadConfiguration}): 꺼져 있으면 주문 문이 그 전에 503이다.
 *
 * <p>키: {@code upload-thumbnails/{streamerUserId}/{uuid}.{jpg|png}}. 스트리머 접두사라 탈퇴 정리가 접두사 하나로 지운다
 * (렌더가 롤백돼 아무도 안 가리키는 그림까지). 영상 번호 아래({@code clips/{id}/})에 못 두는 이유: 그림은 영상 줄이 생기기 전에 온다.
 */
public class UploadThumbnailStore {

    public static final String JPEG = "image/jpeg";
    public static final String PNG = "image/png";

    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    private final S3Client s3;
    private final String bucket;

    UploadThumbnailStore(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    public String bucket() {
        return bucket;
    }

    /**
     * 첫 바이트로 형식을 판정한다. 올린 쪽이 밝힌 Content-Type·파일 이름은 안 믿는다: 그 글자가 창고에 실리면 나중에
     * 유튜브와 일꾼이 그것을 기준으로 읽는다.
     *
     * @throws UnsupportedImageException JPEG·PNG가 아니다 (415)
     */
    public static String contentTypeOf(byte[] head) {
        if (startsWith(head, JPEG_MAGIC)) {
            return JPEG;
        }
        if (startsWith(head, PNG_MAGIC)) {
            return PNG;
        }
        throw new UnsupportedImageException();
    }

    /** 스트리머 접두사. 탈퇴 정리가 이 접두사 하나를 명부에 옮긴다. */
    public static String prefixOf(String streamerUserId) {
        return "upload-thumbnails/" + streamerUserId + "/";
    }

    /**
     * @return 둔 키
     * @throws ThumbnailStoreUnavailableException 창고가 못 받았다 (503)
     */
    public String put(String streamerUserId, String contentType, InputStream body, long size) {
        String key = prefixOf(streamerUserId) + UUID.randomUUID() + (PNG.equals(contentType) ? ".png" : ".jpg");
        try {
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
                    RequestBody.fromInputStream(body, size));
            return key;
        } catch (RuntimeException e) {
            throw new ThumbnailStoreUnavailableException(e);
        }
    }

    private static boolean startsWith(byte[] head, byte[] magic) {
        return head.length >= magic.length && Arrays.equals(head, 0, magic.length, magic, 0, magic.length);
    }
}
