package com.pokeclip.chat.collector;

import com.pokeclip.chat.collector.archive.ArchivableChat;
import com.pokeclip.chat.collector.archive.ArchiveConfiguration;
import com.pokeclip.chat.collector.archive.ChatArchive;
import com.pokeclip.chat.collector.archive.JsonLinesEncoder;
import com.pokeclip.chat.collector.fake.FakeChzzkBehavior;
import com.pokeclip.chat.collector.fake.FakeChzzkTest;
import com.pokeclip.chat.collector.persist.ChatBuffer;
import com.pokeclip.chat.collector.persist.DonationBuffer;
import com.pokeclip.chat.collector.session.SessionKey;
import com.pokeclip.chat.collector.session.SessionRegistry;
import com.pokeclip.chat.collector.status.DonationSubscriptions;
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
import java.time.Instant;
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

    /**
     * 🔴 <b>검산 등식을 숫자로 계산해 단언하는 시험이 저장소에 0개였다</b>
     * (POK-234 감사 라운드 2 C6). 그래서 후원에 {@code archive.offer(...)}를 더해
     * 등식을 후원 수만큼 벌려도 <b>모듈 전체가 초록</b>이었다.
     *
     * <p>기존 판정 줄 검사들은 {@code contains("archived=5")}처럼 <b>글자를 찾는다</b> —
     * 그러면 「이 판에서 5가 맞다」는 확인일 뿐 <b>등식 자체</b>는 아무도 안 지킨다.
     * 등식이 벌어지면 운영자가 판정 줄로 유실을 검산할 수 없게 되는데, 그것이
     * 아카이브 카운터가 존재하는 이유 전부다.
     *
     * <p><b>후원을 섞는 것이 요점이다.</b> {@code received}는 채팅만 세는데
     * {@code archived}는 「퍼간 건수」를 그대로 세므로, 후원이 아카이브에 들어가는 순간
     * 좌우가 후원 수만큼 영구히 갈린다(계획 검증 F3).
     *
     * <p><b>등록부로 연다</b> — 옛 경로는 방송 번호가 없어 후원이 {@code stream_id} 가드에
     * 먼저 걸려 되돌아간다. 그러면 아카이브 갈래를 아예 안 지나 이 검사가 무의미해진다.
     */
    // 문항 2: 「등식이 닫힌다」만 보면 <b>양쪽이 다 0</b>인 판에서도 참이다 —
    //         archived가 실제로 4인 것을 같이 못박는다.
    // 문항 9(값의 축이 둘): 후원과 채팅은 같은 소켓으로 오지만 세는 축이 다르다.
    //         한 축(채팅)만 쏘면 그 갈림이 성립하지 않아 이 검사가 아무것도 안 잰다.
    @Test
    void 후원을_섞어도_검산_등식이_숫자로_닫힌다() throws Exception {
        archive = new ArchiveConfiguration().chatArchive(localStackProperties());
        DonationBuffer donationBuffer = new DonationBuffer(1_000);
        SessionRegistry registry = new SessionRegistry(
                new ChzzkProperties(true, "설정-토큰-쓰면-안-된다", "http://localhost:" + port,
                        Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofMillis(60)),
                restClientBuilder, TestPersistence.unusedBuffer(),
                TestPersistence.disabledPersister(), archive,
                new DonationSubscriptions(), donationBuffer);
        try {
            String channel = "eqn" + UUID.randomUUID().toString().substring(0, 6);
            assertThat(registry.open(
                    new SessionKey("s-eqn", 91L, channel, Instant.EPOCH), "tok-eqn")).isTrue();

            // 🔴 <b>후원을 먼저 쏜다</b>(C1과 같은 이유). 프레임은 순서대로 오고 처리도
            // 수신 스레드 하나라, 뒤에 쏜 채팅이 아카이브에 실렸다는 것이 곧 앞의 후원이
            // 이미 그 갈래를 지나갔다는 뜻이다 — 「아직 처리 전이라 0이었다」를 막는다.
            for (int i = 0; i < 3; i++) {
                behavior.emitDonationTo("tok-eqn", "{\"donationType\":\"CHAT\",\"channelId\":\""
                        + channel + "\",\"donatorChannelId\":\"D" + i + "\",\"donatorNickname\":\"n\","
                        + "\"payAmount\":\"1000\",\"donationText\":\"t\"}");
            }
            for (int i = 0; i < 4; i++) {
                behavior.emitChatTo("tok-eqn", "{\"channelId\":\"" + channel
                        + "\",\"senderChannelId\":\"S\",\"content\":\"x\",\"messageTime\":"
                        + (1_754_300_000_000L + i) + "}");
            }
            awaitUntil(Duration.ofSeconds(10), () -> donationBuffer.size() == 3
                    && archive.counters().archivedCount() >= 4);

            long received = registry.receivedTotal();
            long archived = archive.counters().archivedCount();
            long archiveDropped = archive.counters().archiveBufferDroppedCount();

            assertThat(received)
                    .as("양성 대조 — 채팅이 안 왔으면 아래 등식은 0=0으로 저절로 참이다")
                    .isEqualTo(4);
            assertThat(archived)
                    .as("후원 3건이 아카이브에 들어가면 여기가 7이 되어 등식이 그만큼 벌어진다")
                    .isEqualTo(4);
            assertThat(archived + archiveDropped)
                    .as("received = archived + archiveBufferDropped — 이 등식이 아카이브 카운터의 존재 이유다")
                    .isEqualTo(received);
        } finally {
            registry.closeAll();
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
                        Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofMillis(60)),
                status, restClientBuilder, dbBuffer, TestPersistence.disabledPersister(), archive, () -> { });
        runner.run(null);
        return status;
    }

    private void awaitReceived(long count) throws Exception {
        awaitUntil(Duration.ofSeconds(5), () -> runner.metrics().totalReceived() >= count);
    }
}
