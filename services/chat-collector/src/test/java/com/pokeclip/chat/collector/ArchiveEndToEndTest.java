package com.pokeclip.chat.collector;

import com.pokeclip.chat.collector.archive.ArchivableChat;
import com.pokeclip.chat.collector.archive.ArchiveConfiguration;
import com.pokeclip.chat.collector.archive.ChatArchive;
import com.pokeclip.chat.collector.archive.JsonLinesEncoder;
import com.pokeclip.chat.collector.fake.FakeChzzkBehavior;
import com.pokeclip.chat.collector.fake.FakeChzzkTest;
import com.pokeclip.chat.collector.persist.ChatBuffer;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import com.pokeclip.chat.collector.support.TestPersistence;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.pokeclip.chat.collector.support.LocalStackFixture.download;
import static com.pokeclip.chat.collector.support.LocalStackFixture.listKeys;
import static com.pokeclip.chat.collector.support.LocalStackFixture.localStackProperties;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 끝에서 끝 — 가짜 치지직 → 러너 → 진짜 {@link com.pokeclip.chat.collector.archive.ChatArchiver} → 가짜 S3(LocalStack).
 * 내려받아 원문과 <b>바이트 단위로</b> 대조한다. 아카이브는 운영과 같은 {@link ArchiveConfiguration#chatArchive}로
 * 조립한다(runId·시한·백오프까지 같다).
 *
 * <p>DB는 안 쓴다 — 바구니는 아무도 안 읽는 {@link TestPersistence#unusedBuffer()}다. 그래서 영구 정지 경로에서는
 * 그 바구니를 손으로 비운다(아래 주석).
 */
@FakeChzzkTest
class ArchiveEndToEndTest extends IntegrationTestSupport {

    @LocalServerPort int port;
    @Autowired FakeChzzkBehavior behavior;
    @Autowired RestClient.Builder restClientBuilder;

    private CollectorRunner runner;
    private ChatArchive archive;
    private ChatBuffer dbBuffer;

    @AfterEach
    void tearDown() {
        if (runner != null) runner.stop();
        behavior.reset();
    }

    @Test
    void 받은_채팅이_종료_때_파일로_올라가고_내려받으면_건수와_raw가_원문과_같다() throws Exception {
        List<String> sent = new ArrayList<>();
        start();
        String channel = "e2e" + UUID.randomUUID().toString().substring(0, 6);
        for (int i = 0; i < 30; i++) {
            String inner = "{\"channelId\":\"" + channel + "\",\"senderChannelId\":\"S" + i + "\","
                    + "\"content\":\"line " + i + " ㅋㅋ\\n줄바꿈 \\\"따옴표\\\" 😀\",\"messageTime\":" + (1_754_300_000_000L + i) + "}";
            sent.add(inner);
            behavior.emitChat(inner);
        }
        awaitReceived(30);
        runner.stop();                                    // 열린 창을 닫아 올린다

        List<String> keys = listKeys("chat/" + channel + "/");
        assertThat(keys).as("이 채널의 파일이 최소 하나(같은 분이면 하나)").isNotEmpty();
        assertThat(keys).allMatch(k -> k.endsWith("-" + archive.counters().runId() + ".jsonl"));

        List<ArchivableChat> lines = new ArrayList<>();
        for (String key : keys) {
            for (String line : new String(download(key), StandardCharsets.UTF_8).split("\n")) {
                if (!line.isEmpty()) lines.add(JsonLinesEncoder.decodeLine(channel, line));
            }
        }
        assertThat(lines).hasSize(30);
        assertThat(lines).extracting(ArchivableChat::raw).containsExactlyElementsOf(sent);   // 순서·바이트 동일
        assertThat(lines).extracting(ArchivableChat::receivedAtMillis).isSorted();
        assertThat(archive.counters().archivedCount()).isEqualTo(30);
        assertThat(archive.counters().uploadedCount()).isEqualTo(keys.size());
        assertThat(archive.counters().pendingCount()).isZero();
        assertThat(archive.counters().droppedObjectsCount()).isZero();
    }

    @Test
    void 판정_줄에_카운터_여섯과_runId가_실리고_등식이_닫힌다() throws Exception {
        try (LogCaptor captor = new LogCaptor()) {
            start();
            // 채널은 난수다 — 버킷이 JVM 전체 공유라 고정 이름이면 다른 검사가 같은 접두를 쓰는 순간
            // 이 listKeys가 남의 파일까지 세어 여기서 빨강이 난다(원인은 이 파일 밖에 있다).
            String channel = "vdt" + UUID.randomUUID().toString().substring(0, 6);
            for (int i = 0; i < 5; i++) {
                behavior.emitChat("{\"channelId\":\"" + channel + "\",\"senderChannelId\":\"S\",\"content\":\"x\",\"messageTime\":" + (1_754_300_000_000L + i) + "}");
            }
            awaitReceived(5);
            runner.stop();
            String verdict = captor.messages().stream().filter(m -> m.startsWith("chat.session.verdict")).findFirst().orElseThrow();
            // uploaded는 5건이 분 경계에 걸치면 2다 — 실제 파일 수와 대조한다(plan-critic 사소-10).
            int files = listKeys("chat/" + channel + "/").size();
            assertThat(files).isBetween(1, 2);
            assertThat(verdict).contains("archived=5").contains("archiveBufferDropped=0")
                    .contains("uploaded=" + files).contains("pending=0").contains("droppedObjects=0")
                    .contains("droppedMessages=0")
                    .contains("archiveRunId=" + archive.counters().runId());
        }
    }

    @Test
    void 영구_정지_경로에서도_열린_창이_올라가고_판정_줄에_카운터가_있다() throws Exception {
        try (LogCaptor captor = new LogCaptor()) {
            start();
            String channel = "rvk" + UUID.randomUUID().toString().substring(0, 6);
            behavior.emitChat("{\"channelId\":\"" + channel + "\",\"senderChannelId\":\"S\",\"content\":\"x\",\"messageTime\":1754300000000}");
            awaitReceived(1);
            // DB 바구니를 비운다 — 아무도 안 읽는 unusedBuffer에 1건이 남아 있으면 영구 정지 루프가
            // awaitBufferDrained(30s)에서 30초를 세고 판정이 그 뒤에야 온다(plan-critic 실측 30,094ms).
            // 진짜 persister는 1초 안에 비우지만 이 테스트는 DB를 안 쓴다.
            dbBuffer.drain(Integer.MAX_VALUE);
            behavior.emitSystem("{\"type\":\"revoked\",\"data\":{}}");
            awaitUntil(Duration.ofSeconds(5), () -> captor.messages().stream().anyMatch(m -> m.startsWith("chat.session.verdict")));
            assertThat(listKeys("chat/" + channel + "/")).hasSize(1);
            String verdict = captor.messages().stream().filter(m -> m.startsWith("chat.session.verdict")).findFirst().orElseThrow();
            assertThat(verdict).contains("archived=1").contains("uploaded=1");
        }
    }

    // ── 도우미 (CollectorArchiveWiringTest와 같은 모양) ──

    /**
     * {@code run()}으로 띄운다 — {@code start()}는 수립 실패를 밖으로 던진다.
     * 러너는 exit 없는 패키지 생성자라 영구 정지 테스트에서도 JVM은 안전하다({@code CollectorApplication} 주석 참고).
     */
    private CollectionStatus start() {
        CollectionStatus status = new CollectionStatus();
        archive = new ArchiveConfiguration().chatArchive(localStackProperties());
        dbBuffer = TestPersistence.unusedBuffer();
        runner = new CollectorRunner(
                new ChzzkProperties(true, "test-only-token", "http://localhost:" + port, Duration.ofSeconds(5),
                        Duration.ofSeconds(30), Duration.ofSeconds(60)),
                status, restClientBuilder, dbBuffer, TestPersistence.disabledPersister(), archive, () -> { });
        runner.run(null);
        return status;
    }

    private void awaitReceived(long count) throws Exception {
        awaitUntil(Duration.ofSeconds(5), () -> runner.metrics().totalReceived() >= count);
    }
}
