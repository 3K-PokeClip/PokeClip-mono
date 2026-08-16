package com.pokeclip.chat.collector.archive;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PendingUploadsTest {

    @Test
    void 먼저_들어온_것이_먼저_나가고_올린_수를_줄이_센다() {
        PendingUploads pending = new PendingUploads(10);
        pending.enqueue(obj("k1", 1));
        pending.enqueue(obj("k2", 2));
        assertThat(pending.peek().key()).isEqualTo("k1");
        pending.markHeadUploaded();
        assertThat(pending.peek().key()).isEqualTo("k2");
        assertThat(pending.uploaded()).isEqualTo(1);
        pending.markHeadUploaded();
        assertThat(pending.peek()).isNull();
        assertThat(pending.size()).isZero();
        // 판정 줄 등식 uploaded + pending + droppedObjects = 닫힌 창 수 — 뺀 것과 센 것이 한 락 안이라 어긋날 틈이 없다.
        assertThat(pending.uploaded()).isEqualTo(2);
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

    @Test
    void 전부_버리면_줄이_비고_파일수와_채팅수가_드롭_카운터에_더해진다() {
        PendingUploads pending = new PendingUploads(2);
        pending.enqueue(obj("k1", 10));
        pending.enqueue(obj("k2", 20));
        pending.enqueue(obj("k3", 30));                       // k1은 상한 초과로 이미 버려짐(1, 10)
        PendingUploads.Dropped dropped = pending.dropAll();
        assertThat(dropped.objects()).isEqualTo(2);
        assertThat(dropped.messages()).isEqualTo(50);
        assertThat(pending.size()).isZero();
        assertThat(pending.peek()).isNull();
        assertThat(pending.droppedObjects()).isEqualTo(3);     // 상한 초과 1 + 종료 폐기 2
        assertThat(pending.droppedMessages()).isEqualTo(60);
        assertThat(pending.dropAll()).isEqualTo(new PendingUploads.Dropped(0, 0));   // 빈 줄은 0
    }

    private static ArchiveObject obj(String key, int count) {
        return new ArchiveObject(key, new byte[]{1}, count);
    }
}
