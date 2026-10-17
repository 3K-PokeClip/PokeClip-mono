package com.pokeclip.chat.collector.purge;

import com.pokeclip.chat.collector.support.LocalStackFixture;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** 진짜 S3 규약(LocalStack). 채널 접두사가 번호가 이어지는 이웃 채널을 안 잡는지, 천 개 넘게 나눠 지우는지. */
class S3ArchivePurgeTest {

    private final S3ArchivePurge purge = new S3ArchivePurge(LocalStackFixture.S3, LocalStackFixture.BUCKET);

    private static final Instant 창_끝 = Instant.parse("2026-10-08T02:00:00Z");

    @Test
    void 그_채널_원본만_지우고_번호가_이어지는_이웃_채널은_남긴다() {
        List<String> gone = IntStream.range(0, 1003)
                .mapToObj(i -> "chat/purge-ch/2026-10-08/01/0130-run" + i + ".jsonl").toList();
        gone.forEach(this::put);
        put("chat/purge-ch2/2026-10-08/01/0100-a.jsonl");

        purge.deleteChannel("purge-ch", 창_끝);
        purge.deleteChannel("purge-none", 창_끝);

        assertThat(keys("chat/purge-ch")).containsExactly("chat/purge-ch2/2026-10-08/01/0100-a.jsonl");
    }

    /** 창이 닫힌 뒤의 분은 같은 채널을 새로 연동한 사람의 것일 수 있다(PR #220 codex P1). 모양이 다른 키도 안 건드린다. */
    @Test
    void 창_뒤의_분과_모양이_다른_키는_남긴다() {
        put("chat/purge-w/2026-10-08/01/0159-a.jsonl");
        put("chat/purge-w/2026-10-08/02/0200-a.jsonl");
        put("chat/purge-w/2026-10-09/00/0000-a.jsonl");
        put("chat/purge-w/손으로올린.txt");

        purge.deleteChannel("purge-w", 창_끝);

        assertThat(keys("chat/purge-w/")).containsExactlyInAnyOrder("chat/purge-w/2026-10-08/02/0200-a.jsonl",
                "chat/purge-w/2026-10-09/00/0000-a.jsonl", "chat/purge-w/손으로올린.txt");
    }

    private void put(String key) {
        LocalStackFixture.S3.putObject(b -> b.bucket(LocalStackFixture.BUCKET).key(key), RequestBody.fromString("{}"));
    }

    private List<String> keys(String prefix) {
        return LocalStackFixture.S3.listObjectsV2Paginator(b -> b.bucket(LocalStackFixture.BUCKET).prefix(prefix))
                .contents().stream().map(S3Object::key).toList();
    }
}
