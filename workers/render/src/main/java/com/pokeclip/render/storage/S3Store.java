package com.pokeclip.render.storage;

import com.pokeclip.render.job.ErrorCode;
import com.pokeclip.render.job.RenderFailure;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.nio.file.Path;

/**
 * S3 읽고 쓰기. 일꾼이 우리 저장소에 닿는 유일한 길이다(workers/README.md: DB 직접 접속 금지).
 *
 * <p>조각이 없으면(404) {@code SOURCE_EXPIRED}다. 주문서엔 있는데 실물이 없다(계약1 3절). 다시 해도 같으니 종결한다.
 * 그 밖의 S3 오류는 일시 실패로 줄에 다시 맡긴다.
 */
public class S3Store {

    private final S3Client s3;

    public S3Store(S3Client s3) {
        this.s3 = s3;
    }

    public void download(String bucket, String key, Path target) {
        try {
            s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build(), target);
        } catch (NoSuchKeyException e) {
            throw RenderFailure.permanent(ErrorCode.SOURCE_EXPIRED, "영상 조각이 저장소에 없다");
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw RenderFailure.permanent(ErrorCode.SOURCE_EXPIRED, "영상 조각이 저장소에 없다");
            }
            throw RenderFailure.transientFailure("영상 조각을 못 받았다(S3 " + e.statusCode() + ")", e);
        } catch (SdkException e) {
            throw RenderFailure.transientFailure("영상 조각을 못 받았다", e);
        }
    }

    public void upload(String bucket, String key, Path file, String contentType) {
        try {
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
                    RequestBody.fromFile(file));
        } catch (SdkException e) {
            throw RenderFailure.transientFailure("완성 영상을 못 올렸다", e);
        }
    }
}
