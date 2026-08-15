package com.pokeclip.chat.collector.archive;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ArchiveBufferTest {

    @Test
    void 넣은_순서대로_꺼낸다() {
        ArchiveBuffer buffer = new ArchiveBuffer(10);
        buffer.offer(chat(1));
        buffer.offer(chat(2));
        buffer.offer(chat(3));
        assertThat(buffer.drain(10)).extracting(ArchivableChat::receivedAtMillis).containsExactly(1L, 2L, 3L);
        assertThat(buffer.size()).isZero();
    }

    @Test
    void 상한을_넘으면_가장_오래된_것부터_버리고_센다() {
        ArchiveBuffer buffer = new ArchiveBuffer(3);
        for (int i = 1; i <= 5; i++) {
            buffer.offer(chat(i));
        }
        assertThat(buffer.droppedCount()).isEqualTo(2);
        assertThat(buffer.drain(10)).extracting(ArchivableChat::receivedAtMillis).containsExactly(3L, 4L, 5L);
    }

    @Test
    void drain_상한만큼만_꺼내고_나머지는_남는다() {
        ArchiveBuffer buffer = new ArchiveBuffer(10);
        for (int i = 1; i <= 5; i++) {
            buffer.offer(chat(i));
        }
        assertThat(buffer.drain(2)).hasSize(2);
        assertThat(buffer.size()).isEqualTo(3);
    }

    private static ArchivableChat chat(long receivedAt) {
        return new ArchivableChat("CH", receivedAt, "{}");
    }
}
