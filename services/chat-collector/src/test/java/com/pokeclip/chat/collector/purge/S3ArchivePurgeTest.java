package com.pokeclip.chat.collector.purge;

import com.pokeclip.chat.collector.support.LocalStackFixture;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** 진짜 S3 규약(LocalStack). 채널 접두사가 번호가 이어지는 이웃 채널을 안 잡는지, 천 개 넘게 나눠 지우는지. */
class S3ArchivePurgeTest {

    private final S3ArchivePurge purge = new S3ArchivePurge(LocalStackFixture.S3, LocalStackFixture.BUCKET);

    @Test
    void 그_채널_원본만_지우고_번호가_이어지는_이웃_채널은_남긴다() {
        List<String> gone = IntStream.range(0, 1003).mapToObj(i -> "chat/purge-ch/2026-10-08/01/" + i + ".jsonl").toList();
        gone.forEach(this::put);
        put("chat/purge-ch2/2026-10-08/01/0100-a.jsonl");

        purge.deleteChannel("purge-ch");
        purge.deleteChannel("purge-none");

        assertThat(keys("chat/purge-ch")).containsExactly("chat/purge-ch2/2026-10-08/01/0100-a.jsonl");
    }

    private void put(String key) {
        LocalStackFixture.S3.putObject(b -> b.bucket(LocalStackFixture.BUCKET).key(key), RequestBody.fromString("{}"));
    }

    private List<String> keys(String prefix) {
        return LocalStackFixture.S3.listObjectsV2Paginator(b -> b.bucket(LocalStackFixture.BUCKET).prefix(prefix))
                .contents().stream().map(S3Object::key).toList();
    }
}
