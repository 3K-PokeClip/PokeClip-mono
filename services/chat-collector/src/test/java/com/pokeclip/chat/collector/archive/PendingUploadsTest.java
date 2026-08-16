package com.pokeclip.chat.collector.archive;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PendingUploadsTest {

    @Test
    void 먼저_들어온_것이_먼저_나간다() {
        PendingUploads pending = new PendingUploads(10);
        pending.enqueue(obj("k1", 1));
        pending.enqueue(obj("k2", 2));
        assertThat(pending.peek().key()).isEqualTo("k1");
        pending.removeHead();
        assertThat(pending.peek().key()).isEqualTo("k2");
        pending.removeHead();
        assertThat(pending.peek()).isNull();
        assertThat(pending.size()).isZero();
    }

    @Test
    void 상한을_넘으면_가장_오래된_파일부터_버리고_파일수와_채팅수를_센다() {
        PendingUploads pending = new PendingUploads(2);
        pending.enqueue(obj("k1", 10));
        pending.enqueue(obj("k2", 20));
        pending.enqueue(obj("k3", 30));
        assertThat(pending.size()).isEqualTo(2);
        assertThat(pending.peek().key()).isEqualTo("k2");
        assertThat(pending.droppedObjects()).isEqualTo(1);
        assertThat(pending.droppedMessages()).isEqualTo(10);
    }

    private static ArchiveObject obj(String key, int count) {
        return new ArchiveObject(key, new byte[]{1}, count, "CH", 0L);
    }
}
