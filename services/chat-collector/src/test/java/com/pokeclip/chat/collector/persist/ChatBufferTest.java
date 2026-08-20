package com.pokeclip.chat.collector.persist;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatBufferTest {

    private static PersistableChat chat(int i) {
        return new PersistableChat(null, "ch", "s-" + i, "m" + i, 1000L + i, 2000L + i);
    }

    @Test
    void 넣은_것을_순서대로_꺼낸다() {
        ChatBuffer buffer = new ChatBuffer(10);
        buffer.offer(chat(1));
        buffer.offer(chat(2));
        assertThat(buffer.drain(10)).containsExactly(chat(1), chat(2));
        assertThat(buffer.size()).isZero();
    }

    @Test
    void 가득_차면_가장_오래된_것을_버리고_센다() {
        ChatBuffer buffer = new ChatBuffer(2);
        buffer.offer(chat(1));
        buffer.offer(chat(2));
        buffer.offer(chat(3));                    // 1이 밀려난다
        assertThat(buffer.drain(10)).containsExactly(chat(2), chat(3));
        assertThat(buffer.droppedCount()).isEqualTo(1);
    }

    @Test
    void drain은_max까지만_꺼낸다() {
        ChatBuffer buffer = new ChatBuffer(10);
        buffer.offer(chat(1));
        buffer.offer(chat(2));
        buffer.offer(chat(3));
        assertThat(buffer.drain(2)).hasSize(2);
        assertThat(buffer.size()).isEqualTo(1);
    }

    /**
     * PR #52 P2 ④. 실패 배치를 offer로 되돌리면 큐 꼬리로 가서 — 시각 순서가
     * 뒤집히고 — 상한 초과 head-drop이 실패분 대신 <b>더 새로운 채팅</b>을 버린다.
     * 앞으로 되돌려야 "가장 오래된 것부터 버린다"가 계속 참이다.
     */
    @Test
    void 실패_배치를_앞에_되돌리면_원래_순서가_유지된다() {
        ChatBuffer buffer = new ChatBuffer(10);
        buffer.offer(chat(1));
        buffer.offer(chat(2));
        buffer.offer(chat(3));
        var taken = buffer.drain(2);        // [1, 2]를 꺼내 저장 시도가 실패했다 치자
        buffer.offer(chat(4));              // 그 사이 새 채팅이 왔다

        buffer.restoreFront(taken);

        assertThat(buffer.drain(10)).containsExactly(chat(1), chat(2), chat(3), chat(4));
        assertThat(buffer.size()).isZero();
    }

    /**
     * code-review E. 되돌리는 사이 새 채팅이 상한까지 찼으면 되돌린 만큼 초과다 —
     * 초과분은 head(방금 되돌린, 가장 오래된 것)부터 버리고 센다. 안 지키면
     * 상한이 순간적으로 뚫려 DEFAULT_CAPACITY가 말하는 메모리 상한이 거짓이 된다.
     */
    @Test
    void 되돌려서_상한을_넘으면_초과분을_오래된_쪽부터_버리고_센다() {
        ChatBuffer buffer = new ChatBuffer(3);
        buffer.offer(chat(1));
        buffer.offer(chat(2));
        buffer.offer(chat(3));
        var taken = buffer.drain(2);        // [1,2] 저장 시도 중
        buffer.offer(chat(4));
        buffer.offer(chat(5));              // 그 사이 상한(3)까지 다시 찼다

        buffer.restoreFront(taken);         // 5개 — 2개 초과

        assertThat(buffer.size()).isEqualTo(3);
        assertThat(buffer.droppedCount()).isEqualTo(2);
        assertThat(buffer.drain(10)).containsExactly(chat(3), chat(4), chat(5));
    }

    /**
     * PR #53 P2 ③. offer의 초과 처리(size 판정 → poll → dropped++)와 restoreFront의
     * 초과 처리가 겹치면 <b>같은 초과분을 두 스레드가 각각 센다</b> — 한쪽이 판정한
     * 뒤 poll하기 전에 다른 쪽이 그 초과까지 걷어내면, 앞쪽은 이미 상한 안인데도
     * 하나를 더 버린다(불필요 드롭 1 + dropped 오차 1). 창이 몇 인스트럭션이라
     * 한 번의 겹침을 결정적으로 고정할 손잡이가 없다 — 대신 겹침을 많이 만든다:
     * 수정 전 실측 300라운드 중 21라운드에서 과잉 드롭(최종 size < capacity).
     *
     * <p>판정 근거: 꺼낸 것을 전부 되돌리므로 버린 것 말고는 사라지는 것이 없고,
     * 넣은 수가 상한 이상이면 <b>최종 size는 정확히 capacity</b>여야 한다.
     * 그보다 작으면 필요 없는 드롭이 있었던 것이다. dropped + size = 넣은 수는
     * 경합 유무와 무관하게 성립하므로(버린 것은 실제로 빠진다) 그것만 보면 못 잡는다.
     */
    @Test
    void offer와_restoreFront가_겹쳐도_필요_없는_드롭을_하지_않는다() throws Exception {
        int capacity = 4;
        int offers = 20_000;
        for (int round = 0; round < 300; round++) {
            ChatBuffer buffer = new ChatBuffer(capacity);
            java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
            Thread producer = new Thread(() -> {
                for (int i = 0; i < offers; i++) {
                    buffer.offer(chat(i));
                }
                done.set(true);
            }, "offer");
            Thread restorer = new Thread(() -> {
                while (!done.get()) {
                    var taken = buffer.drain(2);   // 저장 시도가 실패했다 치고 곧장 되돌린다
                    if (!taken.isEmpty()) buffer.restoreFront(taken);
                }
            }, "restore");
            producer.start();
            restorer.start();
            producer.join();
            restorer.join();

            assertThat(buffer.droppedCount() + buffer.size())
                    .as("라운드 %d — 버린 것 말고 사라진 것이 있다", round)
                    .isEqualTo(offers);
            assertThat(buffer.size())
                    .as("라운드 %d — 최종 size가 상한보다 작으면 필요 없는 드롭이 있었다 (dropped=%d)",
                            round, buffer.droppedCount())
                    .isEqualTo(capacity);
        }
    }

    /**
     * PR #56 P1. offer의 큐 add와 size 증가, drain의 poll과 size 감소가 서로 다른
     * 순간에 일어나면 두 스레드가 겹칠 때 size가 큐의 실제 크기와 어긋난다 —
     * 상한 판정과 dropped가 그 size를 보므로, 어긋난 순간에 상한이 뚫리거나 필요
     * 없는 드롭이 난다. 큐 조작과 size 갱신이 한 락 안이면 size는 언제 읽어도
     * 큐 크기와 같고 [0, capacity] 밖으로 나가지 않는다.
     *
     * <p>세 관측을 같이 잰다: ⓐ 저장 스레드가 읽는 size가 [0, capacity] 밖인 순간이
     * 있었는가 ⓑ 끝난 뒤 size()가 실제로 꺼낼 수 있는 개수와 같은가 ⓒ 꺼낸 것 +
     * 남은 것 + 버린 것 = 넣은 것. 창이 몇 인스트럭션이라 겹침을 많이 만든다.
     * 수정 전 실측(2026-08-15): 300라운드 중 227라운드에서 음수(합 700,581회),
     * 173라운드에서 상한 초과(합 562회). ⓑ·ⓒ는 수정 전에도 300/300 정합이었다 —
     * 어긋남은 최종값이 아니라 <b>상한 판정이 읽는 순간</b>에 있다. 수정 후 0.
     */
    @Test
    void offer와_drain이_겹쳐도_size가_큐의_실제_크기와_어긋나지_않는다() throws Exception {
        int capacity = 4;
        int offers = 20_000;
        for (int round = 0; round < 300; round++) {
            ChatBuffer buffer = new ChatBuffer(capacity);
            java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
            java.util.concurrent.atomic.AtomicInteger drained = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger negativeSeen = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicInteger overCapacitySeen = new java.util.concurrent.atomic.AtomicInteger();
            Thread producer = new Thread(() -> {
                for (int i = 0; i < offers; i++) {
                    buffer.offer(chat(i));
                }
                done.set(true);
            }, "offer");
            Thread drainer = new Thread(() -> {
                while (!done.get()) {
                    drained.addAndGet(buffer.drain(2).size());
                    int seen = buffer.size();
                    if (seen < 0) {
                        negativeSeen.incrementAndGet();
                    } else if (seen > capacity) {
                        overCapacitySeen.incrementAndGet();
                    }
                }
            }, "drain");
            producer.start();
            drainer.start();
            producer.join();
            drainer.join();

            int reported = buffer.size();
            int actual = buffer.drain(Integer.MAX_VALUE).size();
            assertThat(negativeSeen.get())
                    .as("라운드 %d — size가 음수로 보인 순간이 있었다 (add와 증가 사이에 drain이 끼었다)", round)
                    .isZero();
            assertThat(overCapacitySeen.get())
                    .as("라운드 %d — size가 상한 %d를 넘어 보인 순간이 있었다", round, capacity)
                    .isZero();
            assertThat(reported)
                    .as("라운드 %d — size()가 실제 큐 크기와 다르다", round)
                    .isEqualTo(actual);
            assertThat(drained.get() + actual + buffer.droppedCount())
                    .as("라운드 %d — 꺼낸 것·남은 것·버린 것 말고 사라진 것이 있다", round)
                    .isEqualTo(offers);
        }
    }

    @Test
    void 되돌린_뒤에도_상한_초과는_가장_오래된_것부터_버린다() {
        ChatBuffer buffer = new ChatBuffer(3);
        buffer.offer(chat(1));
        buffer.offer(chat(2));
        buffer.offer(chat(3));
        buffer.restoreFront(buffer.drain(2));   // [1,2,3] 그대로 — 상한에 딱 찼다

        buffer.offer(chat(4));                  // 초과 — 가장 오래된 1이 버려져야 한다

        assertThat(buffer.drain(10)).containsExactly(chat(2), chat(3), chat(4));
        assertThat(buffer.droppedCount()).isEqualTo(1);
    }
}
