package com.pokeclip.chat.collector.persist;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChatBufferTest {

    private static PersistableChat chat(int i) {
        return new PersistableChat("ch", "s-" + i, "m" + i, 1000L + i, 2000L + i);
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
