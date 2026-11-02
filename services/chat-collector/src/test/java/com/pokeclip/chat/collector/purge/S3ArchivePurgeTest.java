package com.pokeclip.chat.collector.purge;

import com.pokeclip.chat.collector.Platform;
import com.pokeclip.chat.collector.archive.ArchivableChat;
import com.pokeclip.chat.collector.archive.ArchiveKey;
import com.pokeclip.chat.collector.archive.ArchiveObject;
import com.pokeclip.chat.collector.archive.MinuteBatcher;
import com.pokeclip.chat.collector.archive.S3ArchiveUploader;
import com.pokeclip.chat.collector.support.LocalStackFixture;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 진짜 S3 규약(LocalStack). 채널 접두사가 번호가 이어지는 이웃 채널을 안 잡는지, 천 개 넘게 나눠 지우는지, 플랫폼 폴더를 가르는지.
 *
 * <p>앞 두 갈래의 키는 플랫폼 폴더가 생기기 전의 옛 경로({@code chat/{channelId}/…})다. 치지직 정리는 옛 경로도 지운다(POK-397).
 */
class S3ArchivePurgeTest {

    private final S3ArchivePurge purge = new S3ArchivePurge(LocalStackFixture.S3, LocalStackFixture.BUCKET);

    private static final Instant 창_끝 = Instant.parse("2026-10-08T02:00:00Z");

    @Test
    void 그_채널_원본만_지우고_번호가_이어지는_이웃_채널은_남긴다() {
        List<String> gone = IntStream.range(0, 1003)
                .mapToObj(i -> "chat/purge-ch/2026-10-08/01/0130-run" + i + ".jsonl").toList();
        gone.forEach(this::put);
        put("chat/purge-ch2/2026-10-08/01/0100-a.jsonl");

        purge.deleteChannel(Platform.CHZZK, "purge-ch", 창_끝);
        purge.deleteChannel(Platform.CHZZK, "purge-none", 창_끝);

        assertThat(keys("chat/purge-ch")).containsExactly("chat/purge-ch2/2026-10-08/01/0100-a.jsonl");
    }

    /** 창이 닫힌 뒤의 분은 같은 채널을 새로 연동한 사람의 것일 수 있다(PR #220 codex P1). 모양이 다른 키도 안 건드린다. */
    @Test
    void 창_뒤의_분과_모양이_다른_키는_남긴다() {
        put("chat/purge-w/2026-10-08/01/0159-a.jsonl");
        put("chat/purge-w/2026-10-08/02/0200-a.jsonl");
        put("chat/purge-w/2026-10-09/00/0000-a.jsonl");
        put("chat/purge-w/손으로올린.txt");

        purge.deleteChannel(Platform.CHZZK, "purge-w", 창_끝);

        assertThat(keys("chat/purge-w/")).containsExactlyInAnyOrder("chat/purge-w/2026-10-08/02/0200-a.jsonl",
                "chat/purge-w/2026-10-09/00/0000-a.jsonl", "chat/purge-w/손으로올린.txt");
    }

    /** 🔴 같은 아이디가 두 플랫폼에 있다. 치지직 정리는 치지직 새 폴더와 옛 경로를 지우고 SOOP 폴더는 남긴다. */
    @Test
    void 치지직_정리는_새_폴더와_옛_경로를_지우고_SOOP_폴더는_남긴다() {
        put("chat/CHZZK/purge-pl/2026-10-08/01/0130-a.jsonl");
        put("chat/purge-pl/2026-10-08/01/0131-a.jsonl");
        put("chat/SOOP/purge-pl/2026-10-08/01/0132-a.jsonl");

        purge.deleteChannel(Platform.CHZZK, "purge-pl", 창_끝);

        assertThat(keys("chat/CHZZK/purge-pl/")).isEmpty();
        assertThat(keys("chat/purge-pl/")).as("옛 경로의 치지직 파일이 남았다").isEmpty();
        assertThat(keys("chat/SOOP/purge-pl/")).as("SOOP 파일이 지워졌다")
                .containsExactly("chat/SOOP/purge-pl/2026-10-08/01/0132-a.jsonl");
    }

    /** SOOP 정리는 SOOP 폴더만 지운다. 옛 경로 파일은 전부 치지직이라 건드리지 않는다. */
    @Test
    void SOOP_정리는_SOOP_폴더만_지운다() {
        put("chat/CHZZK/purge-ps/2026-10-08/01/0130-a.jsonl");
        put("chat/purge-ps/2026-10-08/01/0131-a.jsonl");
        put("chat/SOOP/purge-ps/2026-10-08/01/0132-a.jsonl");

        purge.deleteChannel(Platform.SOOP, "purge-ps", 창_끝);

        assertThat(keys("chat/SOOP/purge-ps/")).isEmpty();
        assertThat(keys("chat/CHZZK/purge-ps/")).containsExactly("chat/CHZZK/purge-ps/2026-10-08/01/0130-a.jsonl");
        assertThat(keys("chat/purge-ps/")).containsExactly("chat/purge-ps/2026-10-08/01/0131-a.jsonl");
    }

    /**
     * 정리 시험이 키를 손으로 넣으면 「쓰는 쪽과 지우는 쪽의 접두사가 같은가」를 못 잰다. 그래서 아카이버가 키를 짓는 길
     * ({@link MinuteBatcher} → {@link ArchiveKey})로 파일을 만들고 정리가 그것을 찾는지 본다. 채널에 경로 문자를 섞어 다듬기까지 잰다.
     */
    @Test
    void 아카이버가_쓴_파일을_정리가_지운다() throws Exception {
        String channel = "purge a/b";
        long minute = Instant.parse("2026-10-08T01:30:00Z").toEpochMilli();
        MinuteBatcher batcher = new MinuteBatcher("t4", Duration.ofSeconds(2));
        batcher.accept(List.of(new ArchivableChat(Platform.CHZZK, channel, minute + 1, "{}"),
                new ArchivableChat(Platform.SOOP, channel, minute + 2, "{}")), minute + 10);
        S3ArchiveUploader uploader = new S3ArchiveUploader(LocalStackFixture.S3, LocalStackFixture.BUCKET);
        for (ArchiveObject object : batcher.closeAll()) {
            uploader.upload(object);
        }
        assertThat(keys(ArchiveKey.channelPrefix(Platform.CHZZK, channel))).as("올리기가 안 됐으면 아래는 저절로 참이다").hasSize(1);

        purge.deleteChannel(Platform.CHZZK, channel, 창_끝);

        assertThat(keys(ArchiveKey.channelPrefix(Platform.CHZZK, channel))).isEmpty();
        assertThat(keys(ArchiveKey.channelPrefix(Platform.SOOP, channel))).hasSize(1);
    }

    private void put(String key) {
        LocalStackFixture.S3.putObject(b -> b.bucket(LocalStackFixture.BUCKET).key(key), RequestBody.fromString("{}"));
    }

    private List<String> keys(String prefix) {
        return LocalStackFixture.S3.listObjectsV2Paginator(b -> b.bucket(LocalStackFixture.BUCKET).prefix(prefix))
                .contents().stream().map(S3Object::key).toList();
    }
}
