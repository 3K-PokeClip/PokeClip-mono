package com.pokeclip.clip.purge;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.util.ArrayList;
import java.util.List;

/**
 * S3 묶음 지우기(한 번에 1,000개)로 지운다. 묶음 지우기는 일부만 실패해도 응답이 200이라
 * 응답의 {@code errors}를 직접 봐야 한다. 안 보면 남은 파일을 지운 것으로 세고 명부를 닫는다.
 */
class S3PurgeStorage implements PurgeStorage {

    /** S3 DeleteObjects 한 번의 상한. */
    static final int MAX_KEYS_PER_DELETE = 1000;

    private final S3Client s3;
    private final String outputBucket;
    private final String segmentBucket;

    S3PurgeStorage(S3Client s3, String outputBucket, String segmentBucket) {
        this.s3 = s3;
        this.outputBucket = outputBucket;
        this.segmentBucket = segmentBucket;
    }

    @Override
    public void deleteOutputPrefix(String prefix) {
        ListObjectsV2Request request = ListObjectsV2Request.builder().bucket(outputBucket).prefix(prefix).build();
        List<String> keys = new ArrayList<>();
        for (S3Object object : s3.listObjectsV2Paginator(request).contents()) {
            keys.add(object.key());
        }
        deleteAll(outputBucket, keys);
    }

    @Override
    public boolean deletesSegments() {
        return segmentBucket != null && !segmentBucket.isBlank();
    }

    @Override
    public void deleteSegmentObjects(List<String> keys) {
        deleteAll(segmentBucket, keys);
    }

    private void deleteAll(String bucket, List<String> keys) {
        for (int from = 0; from < keys.size(); from += MAX_KEYS_PER_DELETE) {
            List<ObjectIdentifier> chunk = keys.subList(from, Math.min(keys.size(), from + MAX_KEYS_PER_DELETE)).stream()
                    .map(key -> ObjectIdentifier.builder().key(key).build())
                    .toList();
            DeleteObjectsResponse response = s3.deleteObjects(b -> b.bucket(bucket)
                    .delete(Delete.builder().objects(chunk).quiet(true).build()));
            if (response.hasErrors() && !response.errors().isEmpty()) {
                // 키는 안 싣는다. 녹화 키에는 방송 경로가 들어 있다.
                throw new IllegalStateException("S3 묶음 지우기 일부 실패: " + response.errors().size() + "개, 첫 코드="
                        + response.errors().getFirst().code());
            }
        }
    }
}
