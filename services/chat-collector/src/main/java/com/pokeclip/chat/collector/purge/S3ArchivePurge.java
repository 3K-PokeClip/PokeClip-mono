package com.pokeclip.chat.collector.purge;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.util.ArrayList;
import java.util.List;

/**
 * 채널 접두사 아래를 1,000개씩 지운다. 키 모양은 {@code ArchiveKey}가 정한다({@code chat/{channelId}/날짜/시/…}).
 * 끝의 {@code /} 덕분에 번호가 이어지는 이웃 채널({@code abc}와 {@code abcd})은 안 걸린다.
 *
 * <p>🔴 묶음 지우기는 일부만 실패해도 200이다. 응답의 {@code errors}를 직접 봐야 한다.
 */
class S3ArchivePurge implements ArchivePurge {

    private final S3Client s3;
    private final String bucket;

    S3ArchivePurge(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Override
    public void deleteChannel(String channelId) {
        List<ObjectIdentifier> chunk = new ArrayList<>();
        for (S3Object object : s3.listObjectsV2Paginator(b -> b.bucket(bucket).prefix("chat/" + channelId + "/")).contents()) {
            chunk.add(ObjectIdentifier.builder().key(object.key()).build());
            if (chunk.size() == 1000) {
                delete(chunk);
                chunk = new ArrayList<>();
            }
        }
        if (!chunk.isEmpty()) {
            delete(chunk);
        }
    }

    private void delete(List<ObjectIdentifier> chunk) {
        DeleteObjectsResponse response = s3.deleteObjects(b -> b.bucket(bucket)
                .delete(Delete.builder().objects(chunk).quiet(true).build()));
        if (response.hasErrors() && !response.errors().isEmpty()) {
            throw new IllegalStateException("S3 묶음 지우기 일부 실패: " + response.errors().size() + "개, 첫 코드="
                    + response.errors().getFirst().code());
        }
    }
}
