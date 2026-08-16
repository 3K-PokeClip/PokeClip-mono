package com.pokeclip.chat.collector.archive;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MinuteBatcherTest {

    private static final long T0 = Instant.parse("2026-08-15T10:23:00Z").toEpochMilli();
    private final MinuteBatcher batcher = new MinuteBatcher("r1", Duration.ofSeconds(2));

    @Test
    void 같은_분의_채팅은_한_창에_쌓이고_아직_안_닫힌다() {
        List<ArchiveObject> closed = batcher.accept(List.of(chat("CH", T0 + 100), chat("CH", T0 + 59_000)), T0 + 59_500);
        assertThat(closed).isEmpty();
        assertThat(batcher.openWindows()).isEqualTo(1);
    }

    @Test
    void 다음_분의_채팅이_오면_이전_창이_즉시_닫힌다() {
        batcher.accept(List.of(chat("CH", T0 + 100), chat("CH", T0 + 200)), T0 + 500);
        List<ArchiveObject> closed = batcher.accept(List.of(chat("CH", T0 + 60_000)), T0 + 60_100);

        assertThat(closed).hasSize(1);
        ArchiveObject obj = closed.get(0);
        assertThat(obj.key()).isEqualTo("chat/CH/2026-08-15/10/1023-r1.jsonl");
        assertThat(obj.messageCount()).isEqualTo(2);
        assertThat(new String(obj.bytes(), StandardCharsets.UTF_8).split("\n")).hasSize(2);
        assertThat(batcher.openWindows()).isEqualTo(1);   // 10:24 창이 열려 있다
    }

    @Test
    void 채팅이_안_와도_틱_시각이_창_끝_유예를_넘으면_닫힌다() {
        batcher.accept(List.of(chat("CH", T0 + 100)), T0 + 500);
        assertThat(batcher.accept(List.of(), T0 + 61_999)).isEmpty();          // 유예 2초 안
        List<ArchiveObject> closed = batcher.accept(List.of(), T0 + 62_000);   // 창 끝 + 2s
        assertThat(closed).hasSize(1);
        assertThat(batcher.openWindows()).isZero();
    }

    @Test
    void 빈_창은_파일을_만들지_않는다() {
        assertThat(batcher.accept(List.of(), T0 + 200_000)).isEmpty();
        assertThat(batcher.closeAll()).isEmpty();
    }

    @Test
    void 채널이_다르면_창도_다르다() {
        batcher.accept(List.of(chat("A", T0 + 1), chat("B", T0 + 2)), T0 + 10);
        List<ArchiveObject> closed = batcher.closeAll();
        assertThat(closed).extracting(ArchiveObject::key)
                .containsExactlyInAnyOrder("chat/A/2026-08-15/10/1023-r1.jsonl", "chat/B/2026-08-15/10/1023-r1.jsonl");
    }

    @Test
    void 인코드가_깨지는_한_건은_세고_버리되_창은_산다() {
        // raw가 null이면 인코더가 예외를 던진다 — 그 한 건 때문에 창(=다른 채팅)이 죽으면 안 된다.
        ArchivableChat broken = new ArchivableChat("CH", T0 + 1, null);
        batcher.accept(List.of(chat("CH", T0), broken, chat("CH", T0 + 2)), T0 + 10);
        List<ArchiveObject> closed = batcher.closeAll();
        assertThat(closed).hasSize(1);
        assertThat(closed.get(0).messageCount()).isEqualTo(2);
        assertThat(batcher.encodeFailures()).isEqualTo(1);
    }

    @Test
    void 창이_닫힌_뒤_같은_분의_채팅이_뒤늦게_오면_새_창은_순번_접미_키를_받고_reopened가_오른다() {
        // 유예로 10:23 창이 닫힌 뒤(now = 10:24:02) 10:23:59.9 채팅이 뒤늦게 온다(바구니 밀림·시계 역행).
        batcher.accept(List.of(chat("CH", T0 + 100)), T0 + 500);
        List<ArchiveObject> first = batcher.accept(List.of(), T0 + 62_000);
        assertThat(first).extracting(ArchiveObject::key).containsExactly("chat/CH/2026-08-15/10/1023-r1.jsonl");
        // 뒤늦은 채팅으로 다시 열린 창은 틱 시각이 이미 유예를 넘어 같은 accept 안에서 닫힌다 — 그래서 반환값에서 받는다.
        List<ArchiveObject> second = batcher.accept(List.of(chat("CH", T0 + 59_900)), T0 + 62_500);
        assertThat(second).extracting(ArchiveObject::key).containsExactly("chat/CH/2026-08-15/10/1023-r1-2.jsonl");
        assertThat(batcher.reopenedCount()).isEqualTo(1);
        // 세 번째 재열림은 -3
        List<ArchiveObject> third = batcher.accept(List.of(chat("CH", T0 + 59_950)), T0 + 63_000);
        assertThat(third).extracting(ArchiveObject::key).containsExactly("chat/CH/2026-08-15/10/1023-r1-3.jsonl");
        assertThat(batcher.closeAll()).isEmpty();
    }

    @Test
    void 채널_첫_건이_인코드에_실패하면_창을_열지_않아_빈_파일이_안_생긴다() {
        batcher.accept(List.of(new ArchivableChat("CH", T0 + 1, null)), T0 + 10);
        assertThat(batcher.openWindows()).isZero();
        assertThat(batcher.closeAll()).isEmpty();
        assertThat(batcher.encodeFailures()).isEqualTo(1);
    }

    @Test
    void closeAll은_열린_창을_전부_닫고_비운다() {
        batcher.accept(List.of(chat("CH", T0 + 1)), T0 + 10);
        assertThat(batcher.closeAll()).hasSize(1);
        assertThat(batcher.openWindows()).isZero();
        assertThat(batcher.closeAll()).isEmpty();
    }

    private static ArchivableChat chat(String ch, long receivedAt) {
        return new ArchivableChat(ch, receivedAt, "{\"t\":" + receivedAt + "}");
    }
}
