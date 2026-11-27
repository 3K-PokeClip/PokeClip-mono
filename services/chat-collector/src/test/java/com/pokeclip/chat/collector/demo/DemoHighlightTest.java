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
import java.util.function.IntSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 데모 카드의 구간 · 「아직 조각이 없다」 다시 묻기 · clip에 다시 보내기. clip 문(계약 2A) 쪽 모양은 {@link ClipDemoCardClientTest}가 본다.
 */
class DemoHighlightTest {

    private static final String STREAM = "demo-stream-1";
    private static final long PRESSED = 1_800_000_000_000L;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final List<String> sentStreams = new CopyOnWriteArrayList<>();
    private final List<DemoHighlight.Card> sent = new CopyOnWriteArrayList<>();
    private final List<Instant> asked = new CopyOnWriteArrayList<>();

    /** clip이 줄 상태. 기본은 새로 만들었다(201) */
    private volatile IntSupplier answer = () -> 201;

    private final DemoHighlight.Sink sink = (streamId, card) -> {
        sentStreams.add(streamId);
        sent.add(card);
        return answer.getAsInt();
    };

    @AfterEach
    void 끈다() throws InterruptedException {
        scheduler.shutdownNow();
        scheduler.awaitTermination(1, TimeUnit.SECONDS);
    }

    /** 끝이 누른 자리다 — 그보다 뒤는 카드가 설 때 아직 녹화 전이라 clip이 창 두 끝을 못 내고 화면이 「저장」을 막는다(PR #558 리뷰) */
    @Test
    void 누른_자리_앞_15초부터_누른_자리까지_카드를_낸다() {
        highlight(at -> VideoPosition.converted(60_000, 12, 0)).publish(STREAM, PRESSED);

        기다린다(() -> sent.size() == 1);
        DemoHighlight.Card card = sent.get(0);
        assertThat(sentStreams).containsExactly(STREAM);
        assertThat(asked).as("누른 순간(급증 첫 채팅)의 영상 위치를 묻는다").containsExactly(Instant.ofEpochMilli(PRESSED));
        assertThat(card.startMs()).isEqualTo(45_000);
        assertThat(card.streamTimestampMs()).as("지점은 구간 시작 — 판별기 카드와 같다").isEqualTo(45_000);
        assertThat(card.endMs()).as("방금 변환한 자리 — 장부에 있는 자리다").isEqualTo(60_000);
        assertThat(card.messageCount()).isEqualTo(DemoChatScript.CARD_AT_CHAT);
        assertThat(card.eventId()).isEqualTo("demo-" + PRESSED);
    }

    @Test
    void 방송_시작_직후면_구간_시작을_0에서_자른다() {
        highlight(at -> VideoPosition.converted(4_000, 1, 0)).publish(STREAM, PRESSED);

        기다린다(() -> sent.size() == 1);
        assertThat(sent.get(0).startMs()).isZero();
        assertThat(sent.get(0).endMs()).isEqualTo(4_000);
    }

    /** 위치 0이면 구간이 빈다 — clip이 400으로 받지 않으니 보내지 않는다 */
    @Test
    void 위치가_0이면_보내지_않는다() {
        highlight(at -> VideoPosition.converted(0, 1, 0)).publish(STREAM, PRESSED);

        기다린다(() -> asked.size() == 1);
        LockSupport.parkNanos(Duration.ofMillis(50).toNanos());
        assertThat(sent).isEmpty();
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

    /**
     * clip이 잠깐 아프면(못 닿음 · 5xx) 같은 카드를 다시 보낸다 — 판별기는 데모 채널을 안 세므로 받쳐 줄 카드가 없다(PR #558 리뷰).
     * 같은 카드라 clip 유일 색인이 한 장으로 접는다
     */
    @Test
    void clip이_잠깐_실패하면_같은_카드를_다시_보낸다() {
        AtomicInteger sends = new AtomicInteger();
        answer = () -> switch (sends.incrementAndGet()) {
            case 1 -> -1;
            case 2 -> 503;
            default -> 201;
        };
        highlight(at -> VideoPosition.converted(60_000, 12, 0)).publish(STREAM, PRESSED);

        기다린다(() -> sent.size() == 3);
        assertThat(sent).as("같은 카드다").containsOnly(sent.get(0));
        LockSupport.parkNanos(Duration.ofMillis(50).toNanos());
        assertThat(sent).as("받은 뒤에는 더 안 보낸다").hasSize(3);
    }

    @Test
    void 계속_실패하면_정해진_횟수에서_멈춘다() {
        answer = () -> -1;
        highlight(at -> VideoPosition.converted(60_000, 12, 0)).publish(STREAM, PRESSED);

        기다린다(() -> sent.size() == DemoHighlight.SEND_ATTEMPTS);
        LockSupport.parkNanos(Duration.ofMillis(50).toNanos());
        assertThat(sent).hasSize(DemoHighlight.SEND_ATTEMPTS);
    }

    /** 4xx(거절)와 200(이미 있음)은 다시 보내도 같은 답이다 */
    @Test
    void 거절이나_이미_있음은_다시_안_보낸다() {
        for (int status : new int[]{400, 200}) {
            sent.clear();
            answer = () -> status;
            highlight(at -> VideoPosition.converted(60_000, 12, 0)).publish(STREAM, PRESSED);

            기다린다(() -> sent.size() == 1);
            LockSupport.parkNanos(Duration.ofMillis(50).toNanos());
            assertThat(sent).as("상태 %d", status).hasSize(1);
        }
    }

    /** 보낼 곳이 없는 배포면 장부도 안 묻는다 — 조각이 없으면 10초 동안 헛조회만 돈다(PR #558 리뷰) */
    @Test
    void 보낼_곳이_없으면_영상_위치도_안_묻는다() {
        new DemoHighlight((streamId, at) -> {
            asked.add(at);
            return VideoPosition.converted(60_000, 12, 0);
        }, DemoHighlight.Sink.NONE, scheduler, Duration.ofMillis(1), Duration.ZERO).publish(STREAM, PRESSED);

        LockSupport.parkNanos(Duration.ofMillis(50).toNanos());
        assertThat(asked).isEmpty();
    }

    /**
     * 70번째 채팅이 화면에 닿은 뒤에 카드를 낸다. 채팅은 중계(0.1초) · 화면 묶음(0.15초)을 거치고 카드는 바로 그려져, 기다리지 않으면
     * 카드가 70번째보다 먼저 뜬다(PR #558 리뷰)
     */
    @Test
    void 정해진_시간을_기다린_뒤에_낸다() {
        long started = System.nanoTime();
        new DemoHighlight((streamId, at) -> {
            asked.add(at);
            return VideoPosition.converted(60_000, 12, 0);
        }, sink, scheduler, Duration.ofMillis(1), Duration.ofMillis(200)).publish(STREAM, PRESSED);

        assertThat(asked).as("부른 자리에서 바로 묻지 않는다").isEmpty();
        기다린다(() -> sent.size() == 1);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isGreaterThanOrEqualTo(Duration.ofMillis(200));
        assertThat(DemoHighlight.AFTER_CHAT).as("중계 0.1초 + 화면 묶음 0.15초보다 길다").isGreaterThan(Duration.ofMillis(250));
    }

    private DemoHighlight highlight(Function<Instant, VideoPosition> position) {
        return new DemoHighlight((streamId, at) -> {
            asked.add(at);
            return position.apply(at);
        }, sink, scheduler, Duration.ofMillis(1), Duration.ZERO);
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
