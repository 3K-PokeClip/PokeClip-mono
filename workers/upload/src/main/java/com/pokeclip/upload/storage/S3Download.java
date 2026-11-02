package com.pokeclip.upload.storage;

import software.amazon.awssdk.services.s3.S3Client;

import java.nio.file.Files;
import java.nio.file.Path;

/** 완성 영상을 임시 파일로 받는다. 크기를 먼저 알아야 이어 올리기를 시작할 수 있어 통째로 받는다(3분 클립이 수십 MB). */
public class S3Download {

    private final S3Client s3;

    public S3Download(S3Client s3) {
        this.s3 = s3;
    }

    /** @return 받은 바이트 수 */
    public long download(String bucket, String key, Path target) {
        try {
            Files.deleteIfExists(target);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("임시 파일을 못 지웠다", e);
        }
        s3.getObject(b -> b.bucket(bucket).key(key), target);
        try {
            return Files.size(target);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("받은 파일 크기를 못 읽었다", e);
        }
    }
}
