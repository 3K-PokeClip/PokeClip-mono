package com.pokeclip.clip.thumbnail;

import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.time.Duration;

/**
 * 사진의 S3 미리서명 주소를 만든다. 완성 영상 주소({@code ClipFileSigner})와 같은 창고·같은 방식이다. 서명은 계산만 하고 네트워크를
 * 안 탄다. 주소는 로그에 찍지 않는다.
 */
public class ThumbnailSigner {

    private final S3Presigner presigner;
    private final String bucket;
    private final Duration ttl;

    ThumbnailSigner(S3Presigner presigner, String bucket, Duration ttl) {
        this.presigner = presigner;
        this.bucket = bucket;
        this.ttl = ttl;
    }

    public String sign(String key) {
        return presigner.presignGetObject(request -> request
                        .signatureDuration(ttl)
                        .getObjectRequest(get -> get.bucket(bucket).key(key)))
                .url().toString();
    }
}
