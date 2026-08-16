package com.pokeclip.chat.collector.archive;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ArchiveKeyTest {

    @Test
    void 키는_chat_채널_날짜_시_분_runId_jsonl이고_UTC다() {
        // 2026-08-15T10:23:45.123Z = 1786789425123 ms
        long received = Instant.parse("2026-08-15T10:23:45.123Z").toEpochMilli();
        long windowStart = ArchiveKey.windowStartOf(received);

        assertThat(windowStart).isEqualTo(Instant.parse("2026-08-15T10:23:00Z").toEpochMilli());
        assertThat(ArchiveKey.of("abc123", windowStart, "k7x2m9pq", 1))
                .isEqualTo("chat/abc123/2026-08-15/10/1023-k7x2m9pq.jsonl");
    }

    @Test
    void 분_경계_직전과_직후는_다른_창이다() {
        long t1 = Instant.parse("2026-08-15T10:23:59.999Z").toEpochMilli();
        long t2 = Instant.parse("2026-08-15T10:24:00.000Z").toEpochMilli();
        assertThat(ArchiveKey.windowStartOf(t1)).isNotEqualTo(ArchiveKey.windowStartOf(t2));
        assertThat(ArchiveKey.windowStartOf(t2)).isEqualTo(t2);
        // t1의 창 시작이 10:23:00임을 직접 잰다 — 위 둘만으로는 windowStartOf가 항등이어도 초록이다(리뷰 1회차 사소 4).
        assertThat(ArchiveKey.windowStartOf(t1)).isEqualTo(Instant.parse("2026-08-15T10:23:00Z").toEpochMilli());
    }

    @Test
    void 채널ID의_경로_문자는_밑줄로_바뀐다() {
        long w = Instant.parse("2026-08-15T00:05:00Z").toEpochMilli();
        assertThat(ArchiveKey.of("a/b..c d", w, "r1", 1))
                .isEqualTo("chat/a_b__c_d/2026-08-15/00/0005-r1.jsonl");
    }

    @Test
    void 같은_창이_다시_열리면_순번_접미가_붙고_첫_번째는_접미가_없다() {
        long w = Instant.parse("2026-08-15T10:23:00Z").toEpochMilli();
        assertThat(ArchiveKey.of("c", w, "r1", 1)).isEqualTo("chat/c/2026-08-15/10/1023-r1.jsonl");
        assertThat(ArchiveKey.of("c", w, "r1", 2)).isEqualTo("chat/c/2026-08-15/10/1023-r1-2.jsonl");
        assertThat(ArchiveKey.of("c", w, "r1", 3)).isEqualTo("chat/c/2026-08-15/10/1023-r1-3.jsonl");
    }

    @Test
    void 자정_직후_시와_분이_두_자리로_찍힌다() {
        long w = Instant.parse("2026-01-02T00:00:00Z").toEpochMilli();
        assertThat(ArchiveKey.of("c", w, "r1", 1)).isEqualTo("chat/c/2026-01-02/00/0000-r1.jsonl");
    }
}
