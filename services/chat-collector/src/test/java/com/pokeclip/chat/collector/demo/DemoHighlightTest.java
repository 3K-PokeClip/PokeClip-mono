package com.pokeclip.chat.collector.demo;

import com.pokeclip.chat.collector.sync.VideoPosition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 데모 카드의 구간과 「아직 조각이 없다」를 다시 묻는 갈래. clip 문(계약 2A) 쪽 모양은 {@link ClipDemoCardClientTest}가 본다.
 */
class DemoHighlightTest {

    private static final String STREAM = "demo-stream-1";
    private static final long PRESSED = 1_800_000_000_000L;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final List<String> sentStreams = new CopyOnWriteArrayList<>();
    private final List<DemoHighlight.Card> sent = new CopyOnWriteArrayList<>();
    private final List<Instant> asked = new CopyOnWriteArrayList<>();

    private final DemoHighlight.Sink sink = (streamId, card) -> {
        sentStreams.add(streamId);
        sent.add(card);
        return 201;
    };

    @AfterEach
    void 끈다() throws InterruptedException {
        scheduler.shutdownNow();
        scheduler.awaitTermination(1, TimeUnit.SECONDS);
    }

    @Test
    void 누른_자리_앞_15초부터_급증_끝_뒤_5초까지_카드를_낸다() {
        highlight(at -> VideoPosition.converted(60_000, 12, 0)).publish(STREAM, PRESSED);

        기다린다(() -> sent.size() == 1);
        DemoHighlight.Card card = sent.get(0);
        assertThat(sentStreams).containsExactly(STREAM);
        assertThat(asked).as("누른 순간(급증 첫 채팅)의 영상 위치를 묻는다").containsExactly(Instant.ofEpochMilli(PRESSED));
        assertThat(card.startMs()).isEqualTo(45_000);
        assertThat(card.streamTimestampMs()).as("지점은 구간 시작 — 판별기 카드와 같다").isEqualTo(45_000);
        assertThat(card.endMs()).as("급증 10초 + 뒤 5초").isEqualTo(75_000);
        assertThat(card.messageCount()).isEqualTo(DemoChatScript.CARD_AT_CHAT);
        assertThat(card.eventId()).isEqualTo("demo-" + PRESSED);
    }

    @Test
    void 방송_시작_직후면_구간_시작을_0에서_자른다() {
        highlight(at -> VideoPosition.converted(4_000, 1, 0)).publish(STREAM, PRESSED);

        기다린다(() -> sent.size() == 1);
        assertThat(sent.get(0).startMs()).isZero();
        assertThat(sent.get(0).endMs()).isEqualTo(19_000);
    }

    /** 조각이 아직 장부에 안 들어온 것은 다시 물으면 풀린다 — 포기하면 카드가 영영 안 선다 */
    @Test
    void 조각이_아직이면_다시_묻고_들어오면_낸다() {
        AtomicInteger calls = new AtomicInteger();
        highlight(at -> calls.incrementAndGet() < 3 ? VideoPosition.notYetIndexed(0)
                : VideoPosition.converted(60_000, 12, 0)).publish(STREAM, PRESSED);

        기다린다(() -> sent.size() == 1);
        assertThat(calls).hasValue(3);
    }

    @Test
    void 끝내_조각이_안_오면_정해진_만큼만_묻고_포기한다() {
        highlight(at -> VideoPosition.notYetIndexed(0)).publish(STREAM, PRESSED);

        기다린다(() -> asked.size() == DemoHighlight.ATTEMPTS);
        LockSupport.parkNanos(Duration.ofMillis(50).toNanos());
        assertThat(asked).hasSize(DemoHighlight.ATTEMPTS);
        assertThat(sent).isEmpty();
    }

    /** 방송 시작 직후 보정값만큼은 영상이 「영영 없다」 — 다시 물어도 같아 묻지 않는다 */
    @Test
    void 영상이_없으면_다시_안_묻고_카드도_안_낸다() {
        highlight(at -> VideoPosition.noFootage(0)).publish(STREAM, PRESSED);

        기다린다(() -> asked.size() == 1);
        LockSupport.parkNanos(Duration.ofMillis(50).toNanos());
        assertThat(asked).hasSize(1);
        assertThat(sent).isEmpty();
    }

    @Test
    void 변환이_던져도_실행기가_살아_다음_카드를_낸다() {
        AtomicInteger calls = new AtomicInteger();
        DemoHighlight highlight = highlight(at -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("장부 조회 실패");
            }
            return VideoPosition.converted(60_000, 12, 0);
        });

        highlight.publish(STREAM, PRESSED);
        highlight.publish(STREAM, PRESSED + 30_000);

        기다린다(() -> sent.size() == 1);
        assertThat(sent.get(0).eventId()).isEqualTo("demo-" + (PRESSED + 30_000));
    }

    private DemoHighlight highlight(Function<Instant, VideoPosition> answer) {
        return new DemoHighlight((streamId, at) -> {
            asked.add(at);
            return answer.apply(at);
        }, sink, scheduler, Duration.ofMillis(1));
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
