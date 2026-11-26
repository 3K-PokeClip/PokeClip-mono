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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 임시 채팅이 <b>평소 채팅과 같은 자리</b>(저장 바구니 · 중계)로 들어가는지 잰다. 그 뒤(저장 · 판별 · 화면)는
 * 실제 채팅과 같은 길이라 여기서 다시 안 잰다.
 */
class DemoChatBurstTest {

    private static final String STREAM = "demo-stream-1";

    /** 잠금(LOCK_TICKS 180틱)을 실제로 기다리는 시험의 틱. 180틱이 0.72초다 */
    private static final Duration SHORT_TICK = Duration.ofMillis(4);

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
        DemoChatBurst slow = new DemoChatBurst(buffer, relay, scheduler, clock::get, SHORT_TICK);

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
        DemoChatBurst slow = new DemoChatBurst(buffer, relay, scheduler, clock::get, SHORT_TICK);
        int burst = DemoChatScript.BURST_TICKS * DemoChatScript.PER_TICK;

        assertThat(slow.start(STREAM)).isTrue();
        기다린다(() -> relayed.size() == burst);
        long burstDone = System.nanoTime();
        // 틱 열 개를 더 흘린다 — 잠금이 없으면 마지막 급증 틱 다음 틱에 풀리므로, 바로 누르면 잠금 없이도 무시될 수 있다
        Thread.sleep(SHORT_TICK.multipliedBy(10).toMillis());

        assertThat(slow.start(STREAM)).as("급증은 끝났지만 아직 잠금 안이다").isFalse();
        assertThat(relayed).as("무시한 누름은 아무것도 더 안 흘린다").hasSize(burst);
        assertThat(buffer.size()).isEqualTo(DemoChatScript.CALM_WINDOWS + burst);

        기다린다(() -> slow.start(STREAM));
        // 아래 경계는 넉넉히 반만 본다 — 급증이 끝난 것을 시험 스레드가 늦게 볼수록 잰 시간이 짧아진다
        assertThat(Duration.ofNanos(System.nanoTime() - burstDone))
                .as("마지막 급증부터 잠금 시간이 지나야 풀린다")
                .isGreaterThanOrEqualTo(DemoChatBurst.cooldown(SHORT_TICK).dividedBy(2));
    }

    /**
     * 실행기가 밀렸다가 밀린 틱을 몰아 돌아도 잠금은 <b>마지막 급증부터</b> 잰다. 누른 시각부터 틱 수로 세면 몰아 돈
     * 빈 틱이 잠금을 같이 써 버려, 늦게 흘린 급증 바로 뒤에 또 누를 수 있다 — 두 급증이 판별기 묶기 간격(20초) 안에
     * 붙어 한 사건이 되고 카드가 밀린다
     */
    @Test
    void 실행기가_밀렸다_몰아_돌아도_잠금은_마지막_급증부터_잰다() throws InterruptedException {
        AtomicBoolean stalled = new AtomicBoolean();
        RelaySink stalling = (streamId, payload) -> {
            // 첫 급증 틱에서 실행기를 누른 뒤 잠금 전체(LOCK_TICKS)보다 오래 붙잡는다
            if (stalled.compareAndSet(false, true)) {
                LockSupport.parkNanos(SHORT_TICK.multipliedBy(DemoChatScript.LOCK_TICKS + 50).toNanos());
            }
            relay.offer(streamId, payload);
        };
        DemoChatBurst slow = new DemoChatBurst(buffer, stalling, scheduler, clock::get, SHORT_TICK);
        int burst = DemoChatScript.BURST_TICKS * DemoChatScript.PER_TICK;

        assertThat(slow.start(STREAM)).isTrue();
        기다린다(() -> relayed.size() == burst);
        Thread.sleep(SHORT_TICK.multipliedBy(10).toMillis());

        assertThat(slow.start(STREAM)).as("밀린 급증을 막 흘렸다 — 누른 지 LOCK_TICKS가 지났어도 아직 잠금 안이다").isFalse();
        기다린다(() -> slow.start(STREAM));
    }

    /**
     * 실제 틱(250ms)으로 잠금이 카드가 나오는 시각보다 길다. 판별기 묶기 간격 20초 안에 또 누르면 두 급증이 한 사건으로
     * 묶여 카드가 그만큼 밀린다. 카드는 누른 뒤 약 37.5초에 나온다(2026-11-25 운영 실측)
     */
    @Test
    void 평소에는_누른_뒤_카드가_나올_때까지_잠근다() {
        Duration lastBurstTick = DemoChatBurst.TICK.multipliedBy(DemoChatScript.BURST_TICKS - 1);
        Duration unlockAfterPress = lastBurstTick.plus(DemoChatBurst.cooldown(DemoChatBurst.TICK));

        assertThat(unlockAfterPress).isEqualTo(DemoChatBurst.TICK.multipliedBy(DemoChatScript.LOCK_TICKS));
        assertThat(unlockAfterPress).as("카드(약 37.5초)가 나오기 전에 풀리면 또 누른 급증이 카드를 민다")
                .isGreaterThan(Duration.ofMillis(37_500));
    }

    /** 바쁜 대기로 코어를 태우지 않게 1ms씩 쉰다 */
    private static void 기다린다(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("5초 안에 안 됐다").isLessThan(deadline);
            LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
        }
    }
}
