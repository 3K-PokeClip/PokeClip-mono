package com.pokeclip.chat.collector.relay;

import com.pokeclip.chat.collector.ChzzkProperties;
import com.pokeclip.chat.collector.CollectionStatus;
import com.pokeclip.chat.collector.CollectorRunner;
import com.pokeclip.chat.collector.archive.ChatArchive;
import com.pokeclip.chat.collector.persist.ChatBuffer;
import com.pokeclip.chat.collector.persist.ChatPersister;
import com.pokeclip.chat.collector.persist.DonationBuffer;
import com.pokeclip.chat.collector.persist.DonationPersister;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 러너 종료가 중계를 <b>공유 기한</b>(마지막 flush 대기 5초) 안에서 닫는다(계획 검증 F1). 종료 예산에 새 항이 없다 —
 * {@code ShutdownBudgetTest}는 바뀌지 않고 여유 1초 그대로다. 여기서는 그 약속을 <b>시간으로</b> 잰다: clip이
 * 시한 없이 매달려도 {@code stop()}이 기한 + 1초 안에 돌아오고, 돌아온 뒤 중계 셈이 확정돼 있다.
 */
class RelayRunnerCloseTest {

    private RelayRegistryPathTest.BlockingClient blocking;
    private ChatRelayer relayer;

    @AfterEach
    void release() {
        if (blocking != null) blocking.release();
    }

    @Test
    void 중계_닫기는_sinks_기한을_나눠_쓰고_돌아올_때_셈이_확정이다() throws Exception {
        blocking = new RelayRegistryPathTest.BlockingClient();
        RelayProperties props = new RelayProperties(true, 10_000, Duration.ofMillis(100), "http://127.0.0.1:9");
        RelayBuffer buffer = new RelayBuffer(props.bufferCapacity());
        relayer = new ChatRelayer(buffer, blocking, props);
        relayer.start();
        buffer.offer("s-close", chat("매달린다"));
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (blocking.entered() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(blocking.entered()).as("중계 스레드가 매달린 뒤에 닫아야 기한을 잰다").isEqualTo(1);
        buffer.offer("s-close", chat("바구니에남음"));

        CollectorRunner runner = runner(relayer);
        long started = System.nanoTime();
        runner.stop();
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(took)
                .as("중계를 공유 기한 밖에서 따로 기다리면 매달린 clip이 종료 유예를 먹는다")
                .isLessThan(Duration.ofSeconds(6));
        assertThat(relayer.relayDropped()).as("돌아온 순간 매달린 1 + 남은 1이 버린 수로 확정").isEqualTo(2);
        assertThat(relayer.relayOffered())
                .isEqualTo(relayer.relayed() + relayer.relayDropped() + relayer.bufferDropped() + buffer.size());
    }

    @Test
    void 러너_종료가_중계를_닫아_새로_안_받고_담긴_것은_보낸다() throws Exception {
        RelayRegistryPathTest.RecordingClient recording = new RelayRegistryPathTest.RecordingClient();
        RelayProperties props = new RelayProperties(true, 10_000, Duration.ofMillis(100), "http://127.0.0.1:9");
        RelayBuffer buffer = new RelayBuffer(props.bufferCapacity());
        relayer = new ChatRelayer(buffer, recording, props);
        buffer.offer("s-drain", chat("닫기전"));   // 시작 전에 담아 둔다 — 러너 닫기가 기다려야 간다

        relayer.start();
        runner(relayer).stop();
        buffer.offer("s-drain", chat("닫은뒤"));

        assertThat(recording.events()).extracting(e -> ((RelayPayload.Chat) e.payload()).text())
                .as("러너가 중계를 안 닫거나 안 기다리면 판정 전에 못 보냈을 수 있다").containsExactly("닫기전");
        assertThat(relayer.isRunning()).as("러너가 닫았으면 스레드가 끝나 있다").isFalse();
        assertThat(buffer.offeredCount()).as("닫힌 뒤에는 번호도 안 받는다").isEqualTo(1);
    }

    private static CollectorRunner runner(RelayLifecycle relay) {
        ChatBuffer chatBuffer = new ChatBuffer(100);
        return new CollectorRunner(
                new ChzzkProperties(false, "unused", "http://127.0.0.1:9", Duration.ofSeconds(1),
                        Duration.ofMillis(50), Duration.ofSeconds(1), Duration.ofMillis(60)),
                new CollectionStatus(), RestClient.builder(), chatBuffer,
                new ChatPersister(new JdbcTemplate(), chatBuffer), ChatArchive.NONE,
                new DonationPersister(new JdbcTemplate(), new DonationBuffer()), null, () -> { }, relay);
    }

    private static RelayPayload.Chat chat(String text) {
        return new RelayPayload.Chat(Instant.EPOCH, "n", "s", null, text);
    }
}
