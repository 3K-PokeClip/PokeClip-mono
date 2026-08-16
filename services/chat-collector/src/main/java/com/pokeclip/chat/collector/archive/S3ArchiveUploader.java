package com.pokeclip.chat.collector.archive;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** PUT 한 번. 실패는 SdkException(연결·시한·서비스 오류 전부의 부모)을 ArchiveUploadException으로 감싼다. 키·본문을 로그에 안 찍는다. */
public final class S3ArchiveUploader implements ArchiveUploader {

    private final S3Client s3;
    private final String bucket;

    public S3ArchiveUploader(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Override
    public void upload(ArchiveObject object) throws ArchiveUploadException {
        try {
            s3.putObject(PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(object.key())
                            .contentType("application/x-ndjson")
                            .contentLength((long) object.bytes().length)
                            .build(),
                    RequestBody.fromBytes(object.bytes()));
        } catch (SdkException e) {
            throw new ArchiveUploadException(e);
        }
    }
}
