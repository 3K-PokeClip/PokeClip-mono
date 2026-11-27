package com.pokeclip.chat.collector.demo;

import com.pokeclip.chat.collector.Platform;
import com.pokeclip.chat.collector.persist.ChatBuffer;
import com.pokeclip.chat.collector.persist.PersistableChat;
import com.pokeclip.chat.collector.relay.RelayPayload;
import com.pokeclip.chat.collector.relay.RelaySink;
import com.pokeclip.chat.collector.sync.VideoPosition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 임시 채팅이 <b>평소 채팅과 같은 자리</b>(저장 바구니 · 중계)로 들어가는지, 카드가 <b>정확히 {@value DemoChatScript#CARD_AT_CHAT}번째
 * 채팅에서</b> 나가는지 잰다. 저장 뒤(반응 그래프 · 채팅창)는 실제 채팅과 같은 길이라 여기서 다시 안 잰다.
 */
class DemoChatBurstTest {

    private static final String STREAM = "demo-stream-1";

    private static final int BURST = DemoChatScript.BURST_TICKS * DemoChatScript.PER_TICK;

    /** 흐르는 동안을 실제로 기다리는 시험의 틱. 40틱이 0.16초다 */
    private static final Duration SHORT_TICK = Duration.ofMillis(4);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    /**
     * 카드 실행기. 넘겨받은 일을 기다림 없이 <b>부른 스레드에서 바로</b> 돌린다 — 그래야 카드를 내는 순간 몇 건이 흘렀는지 잴 수 있다.
     * 운영은 따로 도는 실행기이고, 70번째 채팅이 화면에 닿게 {@link DemoHighlight#AFTER_CHAT}만큼 기다린 뒤 돈다({@link DemoHighlightTest})
     */
    private final ScheduledExecutorService inline = new ScheduledThreadPoolExecutor(1) {
        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            command.run();
            return null;
        }
    };

    private final ChatBuffer buffer = new ChatBuffer();

    private final List<RelayPayload.Chat> relayed = new CopyOnWriteArrayList<>();

    /** 다른 방송 것은 안 담는다. 여기서 단언하면 실행기 스레드에서 던져 그 틱이 조용히 멈춘다 */
    private final RelaySink relay = (streamId, payload) -> {
        if (streamId.equals(STREAM)) {
            relayed.add((RelayPayload.Chat) payload);
        }
    };

    private final AtomicLong clock = new AtomicLong(1_800_000_000_000L);

    /** 카드를 낼 때(영상 위치를 물을 때) 이 방송 채팅이 몇 건 흘렀었나 */
    private final List<Integer> relayedWhenCard = new CopyOnWriteArrayList<>();
    private final List<DemoHighlight.Card> cards = new CopyOnWriteArrayList<>();

    private final DemoHighlight highlight = new DemoHighlight((streamId, at) -> {
        if (streamId.equals(STREAM)) {
            relayedWhenCard.add(relayed.size());
        }
        return VideoPosition.converted(60_000, 12, 0);
    }, (streamId, card) -> {
        if (streamId.equals(STREAM)) {
            cards.add(card);
        }
        return 201;
    }, inline, Duration.ofMillis(1), Duration.ZERO);

    private final DemoChatBurst burst = new DemoChatBurst(buffer, relay, highlight, scheduler, clock::get,
            Duration.ofMillis(1));

    @AfterEach
    void 끈다() throws InterruptedException {
        scheduler.shutdownNow();
        inline.shutdownNow();
        scheduler.awaitTermination(1, TimeUnit.SECONDS);
    }

    @Test
    void 급증은_저장과_중계_둘_다_한다() {
        assertThat(burst.start(STREAM)).isTrue();

        기다린다(() -> buffer.size() == BURST);
        List<PersistableChat> saved = buffer.drain(BURST);

        assertThat(relayed).hasSize(BURST);
        assertThat(saved).allSatisfy(chat -> {
            assertThat(chat.streamId()).isEqualTo(STREAM);
            assertThat(chat.channelId()).as("실제 채널과 섞이지 않는 데모 채널 — 판별기가 이 접두어로 뺀다").startsWith("demo-");
            assertThat(chat.receivedAtMillis()).isEqualTo(clock.get());
        });
        // 두 플랫폼을 다 요구하지 않는다 — 닉네임별 무작위라 한쪽으로 쏠릴 수 있다(두 플랫폼은 씨앗 고정 DemoChatScriptTest가 본다)
        DemoChatScript.Audience audience = DemoChatScript.audienceOf(STREAM);
        assertThat(saved).as("플랫폼은 이 방송의 닉네임별 배정을 따른다").allSatisfy(chat -> assertThat(chat.platform())
                .isEqualTo(audience.of(Integer.parseInt(chat.senderChannelId().substring("demo-viewer-".length())))));
        assertThat(relayed).allSatisfy(chat -> assertThat(chat.time().toEpochMilli()).isEqualTo(clock.get()));
    }

    /** 120건 가운데 정확히 70번째에서 한 번이다(사용자 결정, 2026-11-27) */
    @Test
    void 칠십번째_채팅을_넣는_순간_카드를_한_번_낸다() {
        long pressedAt = clock.get();
        assertThat(burst.start(STREAM)).isTrue();

        기다린다(() -> relayed.size() == BURST);
        assertThat(relayedWhenCard).as("70번째 채팅을 채팅창(중계)에 넣은 바로 다음, 71번째 전에 카드를 맡긴다")
                .containsExactly(DemoChatScript.CARD_AT_CHAT);
        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).eventId()).as("누른 시각으로 카드를 가른다").isEqualTo("demo-" + pressedAt);
        assertThat(cards.get(0).messageCount()).isEqualTo(DemoChatScript.CARD_AT_CHAT);
    }

    @Test
    void 흐르는_동안_또_누르면_무시하고_끝나면_다시_된다() {
        DemoChatBurst slow = new DemoChatBurst(buffer, relay, highlight, scheduler, clock::get, SHORT_TICK);

        assertThat(slow.start(STREAM)).isTrue();
        assertThat(slow.start(STREAM)).as("두 번 눌러 급증이 겹치지 않게").isFalse();
        assertThat(slow.start("other-stream")).as("다른 방송은 따로다").isTrue();

        기다린다(() -> slow.start(STREAM));
    }

    /** 다 흘린 뒤 다시 누르면 카드도 다시 선다. 카드가 바로 서므로 판별기 시절의 45초 잠금은 없앴다(2026-11-27) */
    @Test
    void 다_흘린_뒤_다시_누르면_카드가_또_선다() {
        DemoChatBurst slow = new DemoChatBurst(buffer, relay, highlight, scheduler, clock::get, SHORT_TICK);

        assertThat(slow.start(STREAM)).isTrue();
        기다린다(() -> relayed.size() == BURST);
        clock.addAndGet(30_000);
        기다린다(() -> slow.start(STREAM));
        기다린다(() -> relayed.size() == BURST * 2);

        assertThat(cards).extracting(DemoHighlight.Card::eventId).doesNotHaveDuplicates().hasSize(2);
        assertThat(relayedWhenCard).containsExactly(DemoChatScript.CARD_AT_CHAT, BURST + DemoChatScript.CARD_AT_CHAT);
    }

    /**
     * 같은 방송에서 다시 눌러도 같은 사람은 같은 플랫폼이다. 채팅창은 최근 300줄을 남겨 앞 급증(120줄)과
     * 뒤 급증이 같이 보이므로, 누를 때마다 새로 뽑으면 같은 닉네임이 치지직 · SOOP 아이콘으로 섞여 지어낸 티가 난다
     */
    @Test
    void 같은_방송에서_다시_눌러도_같은_사람은_같은_플랫폼이다() {
        DemoChatBurst slow = new DemoChatBurst(buffer, relay, highlight, scheduler, clock::get, SHORT_TICK);

        assertThat(slow.start(STREAM)).isTrue();
        기다린다(() -> relayed.size() == BURST);
        기다린다(() -> slow.start(STREAM));
        기다린다(() -> relayed.size() == BURST * 2);

        Map<String, Set<Platform>> byNickname = new HashMap<>();
        relayed.forEach(chat -> byNickname.computeIfAbsent(chat.nickname(), k -> new HashSet<>()).add(chat.platform()));
        assertThat(byNickname.values()).as("두 번 누른 사이에 같은 닉네임이 플랫폼을 바꾸지 않는다")
                .allSatisfy(platforms -> assertThat(platforms).hasSize(1));
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
