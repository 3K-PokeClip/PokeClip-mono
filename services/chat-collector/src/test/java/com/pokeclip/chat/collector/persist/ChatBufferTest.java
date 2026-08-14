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
}
