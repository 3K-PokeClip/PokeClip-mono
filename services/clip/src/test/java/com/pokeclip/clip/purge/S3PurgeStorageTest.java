package com.pokeclip.clip.purge;

import com.pokeclip.clip.support.LocalStackFixture;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** 진짜 S3 규약(LocalStack)으로 잰다. 접두사가 이웃 번호를 잡지 않는지, 천 개 넘는 묶음을 나눠 지우는지. */
class S3PurgeStorageTest {

    private static final String 영상_창고 = "purge-clips-test";
    private static final String 조각_창고 = "purge-segments-test";

    private final S3Client s3 = LocalStackFixture.s3();
    private final S3PurgeStorage storage = new S3PurgeStorage(s3, 영상_창고, 조각_창고);

    @Test
    void 접두사_아래만_지우고_번호가_이어지는_이웃은_남긴다() {
        put(영상_창고, "clips/12/t1/vert.mp4");
        put(영상_창고, "clips/12/t2/vert.srt");
        put(영상_창고, "clips/123/t1/vert.mp4");
        put(영상_창고, "thumbnails/card/12.jpg");

        storage.deleteOutputPrefix("clips/12/");
        storage.deleteOutputPrefix("thumbnails/card/1.jpg");

        assertThat(keys(영상_창고, "")).containsExactlyInAnyOrder("clips/123/t1/vert.mp4", "thumbnails/card/12.jpg");
    }

    @Test
    void 천_개가_넘는_키를_나눠_지우고_없는_키는_성공으로_넘긴다() {
        List<String> keys = IntStream.range(0, 1003).mapToObj(i -> "streams/K/" + i + ".m4s").toList();
        keys.forEach(key -> put(조각_창고, key));
        put(조각_창고, "streams/OTHER/1.m4s");

        storage.deleteSegmentObjects(keys);
        storage.deleteSegmentObjects(List.of("streams/K/없는.m4s"));

        assertThat(keys(조각_창고, "streams/")).containsExactly("streams/OTHER/1.m4s");
    }

    private void put(String bucket, String key) {
        LocalStackFixture.putObject(bucket, key, new byte[]{1}, "application/octet-stream");
    }

    private List<String> keys(String bucket, String prefix) {
        return s3.listObjectsV2Paginator(b -> b.bucket(bucket).prefix(prefix)).contents().stream()
                .map(S3Object::key).toList();
    }
}
