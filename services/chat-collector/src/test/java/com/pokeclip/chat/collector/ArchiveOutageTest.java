package com.pokeclip.chat.collector;

import com.pokeclip.chat.collector.archive.ArchivableChat;
import com.pokeclip.chat.collector.archive.ArchiveBuffer;
import com.pokeclip.chat.collector.archive.ArchiveObject;
import com.pokeclip.chat.collector.archive.ChatArchive;
import com.pokeclip.chat.collector.archive.ChatArchiver;
import com.pokeclip.chat.collector.archive.MinuteBatcher;
import com.pokeclip.chat.collector.archive.MutableClock;
import com.pokeclip.chat.collector.archive.PendingUploads;
import com.pokeclip.chat.collector.archive.S3ArchiveUploader;
import com.pokeclip.chat.collector.fake.FakeChzzkBehavior;
import com.pokeclip.chat.collector.fake.FakeChzzkTest;
import com.pokeclip.chat.collector.reconnect.ReconnectPolicy;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import com.pokeclip.chat.collector.support.StallingTcpProxy;
import com.pokeclip.chat.collector.support.TestPersistence;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static com.pokeclip.chat.collector.support.LocalStackFixture.BUCKET;
import static com.pokeclip.chat.collector.support.LocalStackFixture.listKeys;
import static com.pokeclip.chat.collector.support.LocalStackFixture.s3ClientVia;
import static com.pokeclip.chat.collector.support.LocalStackFixture.stallingProxy;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 창고가 막혀도 수신은 안 멈춘다 — 반개방(연결은 받고 응답 없음) → 회복.
 *
 * <p>둘로 가른다(plan-critic 중대-3·5): <b>(a)</b> 아카이버 단독 + {@link MutableClock} — 열쇠(받은 시각)와
 * 유예 판정 시계가 같아 분을 손으로 넘길 수 있다. 반개방 → 시한 안 실패 → 백오프 중에도 퍼가기 → 회복 →
 * 전부 올라감. <b>(b)</b> 러너 + 진짜 아카이버(실제 시계) — 창이 안 닫히니 업로드 시도를 만들려고 대기 줄에
 * 파일 하나를 미리 세워 두고, 반개방에 매달리는 동안에도 수신·퍼가기가 계속되는지를 잰다.
 * 러너 + MutableClock으로 합치면 안 된다 — 러너의 receivedAt(실제 시계)이 열쇠라 같은 실제 분의 창들이 같은
 * 키로 닫혀 S3에서 덮어써진다(plan-critic 단위 재현 same=true).
 *
 * <p><b>스톨은 기다려서 확인한다.</b> {@code pending}·{@code archived}는 틱 안에서 PUT보다 <i>앞</i>에 오르므로
 * 그 직후에 {@code stalledAt()}을 보면 PUT 바이트가 아직 프록시에 안 닿은 창이 있다(첫 실행에서 (b)가 그렇게
 * 빨강이었다). 그래서 {@code stalledAt != null}을 대기 조건에 넣거나 먼저 기다린다.
 * {@code awaitUntil}은 시한이 차면 그냥 돌아온다 — 그 뒤에 <b>반드시</b> 같은 값을 단언한다. 안 그러면 시한을
 * 지운 자기검사가 초록으로 지나간다.
 */
@FakeChzzkTest
class ArchiveOutageTest extends IntegrationTestSupport {

    private static final long T0 = Instant.parse("2026-08-15T10:23:00Z").toEpochMilli();

    @LocalServerPort int port;
    @Autowired FakeChzzkBehavior behavior;
    @Autowired RestClient.Builder restClientBuilder;

    private CollectorRunner runner;

    @AfterEach
    void tearDown() {
        if (runner != null) runner.stop();
        behavior.reset();
    }

    /** (a) 아카이버 단독 — 열쇠도 유예도 같은 MutableClock. 반개방 → 시한 안 실패 → 백오프 중 퍼가기 → 회복 → 전부 올라감. */
    @Test
    void 창고가_반개방이면_시한_안에_실패해_대기_줄에_서고_백오프_중에도_퍼가며_회복하면_전부_올라간다() throws Exception {
        MutableClock clock = new MutableClock(T0);
        String channel = "outa" + UUID.randomUUID().toString().substring(0, 6);
        try (LogCaptor captor = new LogCaptor();
             StallingTcpProxy proxy = stallingProxy();
             S3Client s3 = s3ClientVia(proxy)) {
            ChatArchiver archiver = new ChatArchiver(new ArchiveBuffer(10_000), new MinuteBatcher("outa", Duration.ofSeconds(2)),
                    new PendingUploads(60), new S3ArchiveUploader(s3, BUCKET),
                    new ReconnectPolicy(Duration.ofMillis(200), Duration.ofSeconds(1)), clock);
            archiver.start();

            // ① 정상: 10:23 창 10건 → 시계를 10:24:02로 → 파일 1개 올라감
            for (int i = 0; i < 10; i++) archiver.offer(new ArchivableChat(channel, T0 + i, "{\"i\":" + i + "}"));
            clock.set(T0 + 62_000);
            awaitUntil(Duration.ofSeconds(5), () -> archiver.uploadedCount() == 1);
            assertThat(archiver.uploadedCount()).isEqualTo(1);

            // ② 반개방: 응답을 삼킨다. 10:24 창 40건 → 시계 10:25:02 → 닫힘 → 시도 → 4초 안에 실패
            proxy.stallResponsesAfter("PUT /");
            for (int i = 0; i < 40; i++) archiver.offer(new ArchivableChat(channel, T0 + 60_000 + i, "{\"i\":" + i + "}"));
            clock.set(T0 + 122_000);
            awaitUntil(Duration.ofSeconds(8), () -> archiver.pendingCount() >= 1 && archiver.archivedCount() == 50
                    && proxy.stalledAt() != null);
            assertThat(archiver.archivedCount()).isEqualTo(50);
            assertThat(proxy.stalledAt()).as("스톨이 실제로 걸렸어야 시한을 잰 것이다").isNotNull();
            // 백오프 중(다음 시도 전) 10:25 창 20건이 온다 — 퍼가기가 계속돼야 archivedCount가 오른다.
            // 반개방 중에는 시도 한 번이 최대 4초 매달리고 그동안 틱이 못 돈다(같은 스레드) — 그래서
            // 시한을 10초로 준다. 이것이 정직한 한계다: 백오프 간격마다 4초씩 퍼가기가 멈추고, 그 사이
            // 바구니(상한 1만)가 받는다. CLAUDE.md 실측 표에 적는다.
            for (int i = 0; i < 20; i++) archiver.offer(new ArchivableChat(channel, T0 + 120_000 + i, "{\"i\":" + i + "}"));
            awaitUntil(Duration.ofSeconds(10), () -> archiver.archivedCount() == 70);
            assertThat(archiver.archivedCount()).as("반개방에 매달린 뒤에도 퍼가기가 이어져야 한다 — 시한이 없으면 여기 못 온다").isEqualTo(70);
            assertThat(archiver.archiveBufferDroppedCount()).isZero();
            clock.set(T0 + 182_000);                                  // 10:25 창도 닫힘 → 대기 2
            awaitUntil(Duration.ofSeconds(10), () -> archiver.pendingCount() >= 2);
            assertThat(archiver.pendingCount()).isGreaterThanOrEqualTo(2);
            assertThat(archiver.uploadedCount()).as("반개방 동안 올라간 것이 없다").isEqualTo(1);
            // 실측 — 어느 층이 몇 ms 만에 끊었나(stalledAt → 첫 upload_failed). 단언이 아니라 기록이다.
            captor.events().stream().filter(e -> e.getFormattedMessage().startsWith("chat.archive.upload_failed")).findFirst()
                    .ifPresent(e -> LoggerFactory.getLogger(getClass()).info("half-open cut {} after {}ms",
                            e.getFormattedMessage(), e.getTimeStamp() - proxy.stalledAt().toEpochMilli()));

            // ③ 회복 — 스톨된 연결은 socketTimeout(3s)에 폐기되고 다음 연결부터 정상 중계.
            // 백오프의 "다음 시도 시각"은 이 시계 기준이라, 시계가 멈춘 채면 두 번째 PUT이 스톨에 걸린 뒤 재시도가
            // 영영 안 온다 — 회복을 기다리는 동안 시계를 실제 시간만큼 민다(열린 창은 없어 창 닫힘과 무관하다).
            proxy.resume();
            awaitUntil(Duration.ofSeconds(15), () -> {
                clock.advance(Duration.ofMillis(20));
                return archiver.pendingCount() == 0;
            });
            assertThat(archiver.pendingCount()).isZero();
            assertThat(archiver.uploadedCount()).isEqualTo(3);
            assertThat(listKeys("chat/" + channel + "/")).hasSize(3);   // 1023·1024·1025 — 열쇠가 clock이라 분이 다르다
            archiver.beginClose();
            archiver.awaitClosed(Duration.ofSeconds(2));
        }
    }

    /** (b) 러너 + 진짜 아카이버(실제 시계) — 반개방에 매달리는 동안에도 수신·퍼가기가 계속된다. */
    @Test
    void 반개방에_매달리는_동안에도_수신과_퍼가기는_계속된다() throws Exception {
        String channel = "outb" + UUID.randomUUID().toString().substring(0, 6);
        try (StallingTcpProxy proxy = stallingProxy();
             S3Client s3 = s3ClientVia(proxy)) {
            PendingUploads pending = new PendingUploads(60);
            ChatArchiver archiver = new ChatArchiver(new ArchiveBuffer(10_000), new MinuteBatcher("outb", Duration.ofSeconds(2)),
                    pending, new S3ArchiveUploader(s3, BUCKET),
                    new ReconnectPolicy(Duration.ofMillis(200), Duration.ofMillis(200)), System::currentTimeMillis);
            // 실제 시계라 1분 창은 이 테스트 안에서 안 닫힌다 — 업로드 시도를 만들려고 파일 하나를 미리 세운다.
            pending.enqueue(new ArchiveObject("chat/" + channel + "/2026-08-15/10/1023-outb.jsonl", new byte[]{1}, 1));
            proxy.stallResponsesAfter("PUT /");
            archiver.start();
            start(archiver);

            // 첫 틱의 PUT이 스톨에 걸릴 때까지 기다린다 — 그래야 아래 200건이 "매달리는 동안" 온 것이다.
            // 스톨 전에 흘리면 첫 틱의 drain(PUT보다 앞)이 이미 200을 채워 매달림과 무관하게 초록이 된다.
            awaitUntil(Duration.ofSeconds(5), () -> proxy.stalledAt() != null);
            assertThat(proxy.stalledAt()).as("스톨이 걸린 뒤에 흘려야 매달림 중의 수신·퍼가기를 잰 것이다").isNotNull();

            // 시도가 최대 4초 매달리는 동안 채팅 200건을 흘린다
            for (int i = 0; i < 200; i++) {
                behavior.emitChat("{\"channelId\":\"" + channel + "\",\"senderChannelId\":\"S\",\"content\":\"x" + i + "\",\"messageTime\":" + (1_754_300_000_000L + i) + "}");
            }
            awaitReceived(200);                                            // 수신 스레드는 안 막힌다
            assertThat(runner.metrics().totalReceived()).isEqualTo(200);
            awaitUntil(Duration.ofSeconds(10), () -> archiver.archivedCount() == 200);   // 시한이 끊은 뒤 다음 틱이 퍼간다
            long sinceStall = System.currentTimeMillis() - proxy.stalledAt().toEpochMilli();
            assertThat(archiver.archivedCount()).as("시한이 없으면 첫 틱이 영영 매달려 여기 못 온다").isEqualTo(200);
            assertThat(archiver.archiveBufferDroppedCount()).isZero();
            assertThat(archiver.uploadedCount()).isZero();
            // 실측 — 스톨부터 매달림이 풀려 퍼가기가 200에 닿기까지(단언 아님, 기록).
            LoggerFactory.getLogger(getClass()).info("half-open stall→archived=200 after {}ms", sinceStall);
        }
    }

    // ── 도우미 (ArchiveEndToEndTest와 같은 모양) ──

    private CollectionStatus start(ChatArchive archive) {
        CollectionStatus status = new CollectionStatus();
        runner = new CollectorRunner(
                new ChzzkProperties(true, "test-only-token", "http://localhost:" + port, Duration.ofSeconds(5),
                        Duration.ofSeconds(30), Duration.ofSeconds(60)),
                status, restClientBuilder, TestPersistence.unusedBuffer(), TestPersistence.disabledPersister(), archive, () -> { });
        runner.run(null);
        return status;
    }

    private void awaitReceived(long count) throws Exception {
        awaitUntil(Duration.ofSeconds(5), () -> runner.metrics().totalReceived() >= count);
    }
}
