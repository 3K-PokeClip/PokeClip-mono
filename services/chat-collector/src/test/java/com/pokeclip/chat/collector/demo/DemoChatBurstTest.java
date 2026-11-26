package com.pokeclip.chat.collector.demo;

import com.pokeclip.chat.collector.Platform;
import com.pokeclip.chat.collector.persist.ChatBuffer;
import com.pokeclip.chat.collector.persist.PersistableChat;
import com.pokeclip.chat.collector.relay.RelayPayload;
import com.pokeclip.chat.collector.relay.RelaySink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 임시 채팅이 <b>평소 채팅과 같은 자리</b>(저장 바구니 · 중계)로 들어가는지 잰다. 그 뒤(저장 · 판별 · 화면)는
 * 실제 채팅과 같은 길이라 여기서 다시 안 잰다.
 */
class DemoChatBurstTest {

    private static final String STREAM = "demo-stream-1";

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private final ChatBuffer buffer = new ChatBuffer();

    private final List<RelayPayload.Chat> relayed = new CopyOnWriteArrayList<>();

    /** 다른 방송 것은 안 담는다. 여기서 단언하면 실행기 스레드에서 던져 그 틱이 조용히 멈춘다 */
    private final RelaySink relay = (streamId, payload) -> {
        if (streamId.equals(STREAM)) {
            relayed.add((RelayPayload.Chat) payload);
        }
    };

    private final AtomicLong clock = new AtomicLong(1_800_000_000_000L);

    private final DemoChatBurst burst = new DemoChatBurst(buffer, relay, scheduler, clock::get, Duration.ofMillis(1));

    @AfterEach
    void 끈다() throws InterruptedException {
        scheduler.shutdownNow();
        scheduler.awaitTermination(1, TimeUnit.SECONDS);
    }

    @Test
    void 평소_채팅은_저장만_하고_급증은_저장과_중계_둘_다_한다() {
        assertThat(burst.start(STREAM)).isTrue();

        int total = DemoChatScript.CALM_WINDOWS + DemoChatScript.BURST_TICKS * DemoChatScript.PER_TICK;
        기다린다(() -> buffer.size() == total);
        List<PersistableChat> saved = buffer.drain(total);

        assertThat(relayed).as("지난 시각 채팅을 채팅창에 한꺼번에 쏟지 않는다")
                .hasSize(DemoChatScript.BURST_TICKS * DemoChatScript.PER_TICK);
        assertThat(saved).allSatisfy(chat -> {
            assertThat(chat.streamId()).isEqualTo(STREAM);
            assertThat(chat.channelId()).as("실제 채널과 섞이지 않는 데모 채널").startsWith("demo-");
            assertThat(chat.receivedAtMillis()).as("판별기는 받은 시각으로 방송이 살아 있는지 본다")
                    .isEqualTo(clock.get());
        });
        assertThat(saved).extracting(PersistableChat::platform).contains(Platform.CHZZK, Platform.SOOP);
        assertThat(relayed).allSatisfy(chat -> assertThat(chat.time().toEpochMilli()).isEqualTo(clock.get()));
    }

    @Test
    void 흐르는_동안_또_누르면_무시하고_끝나면_다시_된다() {
        DemoChatBurst slow = new DemoChatBurst(buffer, relay, scheduler, clock::get, Duration.ofMillis(20));

        assertThat(slow.start(STREAM)).isTrue();
        assertThat(slow.start(STREAM)).as("두 번 눌러 급증이 겹치지 않게").isFalse();
        assertThat(slow.start("other-stream")).as("다른 방송은 따로다").isTrue();

        기다린다(() -> slow.start(STREAM));
    }

    /**
     * 다 흘린 뒤에도 카드가 나올 때까지는 또 눌러도 무시한다. 이어 붙은 급증을 판별기가 한 사건으로 묶어 카드가 밀린다
     * (2026-11-26 운영: 23초 뒤 두 번째 키 → 카드가 첫 키 63초 뒤). 무시한 누름은 아무것도 더 넣지 않는다
     */
    @Test
    void 다_흘린_뒤에도_카드가_나올_때까지는_또_눌러도_무시한다() throws InterruptedException {
        DemoChatBurst slow = new DemoChatBurst(buffer, relay, scheduler, clock::get, Duration.ofMillis(20));
        int burst = DemoChatScript.BURST_TICKS * DemoChatScript.PER_TICK;

        assertThat(slow.start(STREAM)).isTrue();
        기다린다(() -> relayed.size() == burst);
        // 틱 열 개를 더 흘린다 — 잠금이 없으면 마지막 급증 틱 다음 틱에 풀리므로, 바로 누르면 잠금 없이도 무시될 수 있다
        Thread.sleep(200);

        assertThat(slow.start(STREAM)).as("급증은 끝났지만 아직 잠금 틱 안이다").isFalse();
        assertThat(relayed).as("무시한 누름은 아무것도 더 안 흘린다").hasSize(burst);
        assertThat(buffer.size()).isEqualTo(DemoChatScript.CALM_WINDOWS + burst);

        기다린다(() -> slow.start(STREAM));
    }

    private static void 기다린다(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("5초 안에 안 됐다").isLessThan(deadline);
            Thread.onSpinWait();
        }
    }
}
