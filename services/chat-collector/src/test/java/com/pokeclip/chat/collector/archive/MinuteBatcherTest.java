package com.pokeclip.chat.collector.archive;

import ch.qos.logback.classic.Level;
import com.pokeclip.chat.collector.Platform;
import com.pokeclip.web.support.LogCaptor;
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
        assertThat(obj.key()).isEqualTo("chat/CHZZK/CH/2026-08-15/10/1023-r1.jsonl");
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
                .containsExactlyInAnyOrder("chat/CHZZK/A/2026-08-15/10/1023-r1.jsonl", "chat/CHZZK/B/2026-08-15/10/1023-r1.jsonl");
    }

    @Test
    void 인코드가_깨지는_한_건은_세고_버리되_창은_산다() {
        // raw가 null이면 인코더가 예외를 던진다 — 그 한 건 때문에 창(=다른 채팅)이 죽으면 안 된다.
        ArchivableChat broken = new ArchivableChat(Platform.CHZZK, "CH", T0 + 1, null);
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
        assertThat(first).extracting(ArchiveObject::key).containsExactly("chat/CHZZK/CH/2026-08-15/10/1023-r1.jsonl");
        // 뒤늦은 채팅으로 다시 열린 창은 틱 시각이 이미 유예를 넘어 같은 accept 안에서 닫힌다 — 그래서 반환값에서 받는다.
        List<ArchiveObject> second = batcher.accept(List.of(chat("CH", T0 + 59_900)), T0 + 62_500);
        assertThat(second).extracting(ArchiveObject::key).containsExactly("chat/CHZZK/CH/2026-08-15/10/1023-r1-2.jsonl");
        assertThat(batcher.reopenedCount()).isEqualTo(1);
        // 세 번째 재열림은 -3
        List<ArchiveObject> third = batcher.accept(List.of(chat("CH", T0 + 59_950)), T0 + 63_000);
        assertThat(third).extracting(ArchiveObject::key).containsExactly("chat/CHZZK/CH/2026-08-15/10/1023-r1-3.jsonl");
        assertThat(batcher.closeAll()).isEmpty();
    }

    /**
     * 재열림은 카운터 여섯에 없다(PRD) — 운영이 알 길은 이 로그뿐이다. 첫 번째만 찍는다(시계 요동 때 채팅마다 재열림이
     * 날 수 있어 encode_failed와 같은 도배 방지). seq는 안 싣는다 — 첫 재열림의 seq는 언제나 2라 정보가 0이다(F16).
     */
    @Test
    void 첫_재열림은_INFO_한_줄을_남기고_두_번째부터는_안_남긴다() {
        try (LogCaptor captor = new LogCaptor()) {
            batcher.accept(List.of(chat("CH", T0 + 100)), T0 + 500);
            batcher.accept(List.of(), T0 + 62_000);                                  // 유예로 닫힘
            batcher.accept(List.of(chat("CH", T0 + 59_900)), T0 + 62_500);          // 재열림 1 (seq 2)
            batcher.accept(List.of(chat("CH", T0 + 59_950)), T0 + 63_000);          // 재열림 2 (seq 3)
            List<String> lines = captor.messages().stream().filter(m -> m.startsWith("chat.archive.window_reopened")).toList();
            assertThat(lines).containsExactly("chat.archive.window_reopened");
            assertThat(captor.levelOf("chat.archive.window_reopened")).isEqualTo(Level.INFO);
            assertThat(batcher.reopenedCount()).isEqualTo(2);
        }
    }

    /**
     * 순번 기억은 2시간이 지나면 잊는다 — 안 잊으면 맵이 방송 내내 자라고, 잊는 자리가 창 닫기에 있으니 닫기가 그 일을
     * 하는지 본다: 2시간 뒤 창을 하나 닫으면 옛 열쇠가 사라져, 그 분이 다시 열려도 접미가 없다.
     */
    @Test
    void 두_시간이_지난_열쇠는_창을_닫을_때_잊혀_그_분이_다시_열려도_접미가_없다() {
        batcher.accept(List.of(chat("CH", T0 + 100)), T0 + 500);
        batcher.accept(List.of(), T0 + 62_000);                                      // 10:23 닫힘 → 열쇠 기억
        long later = T0 + MinuteBatcher.CLOSED_MEMORY_MS + 120_000;                  // 2시간 2분 뒤의 창
        batcher.accept(List.of(chat("CH", later + 100)), later + 500);
        batcher.accept(List.of(), later + 62_000);                                   // 그 창을 닫으며 옛 열쇠를 잊는다
        List<ArchiveObject> reopened = batcher.accept(List.of(chat("CH", T0 + 59_900)), later + 62_500);
        assertThat(reopened).extracting(ArchiveObject::key).containsExactly("chat/CHZZK/CH/2026-08-15/10/1023-r1.jsonl");
        assertThat(batcher.reopenedCount()).isZero();
    }

    @Test
    void 채널_첫_건이_인코드에_실패하면_창을_열지_않아_빈_파일이_안_생긴다() {
        batcher.accept(List.of(new ArchivableChat(Platform.CHZZK, "CH", T0 + 1, null)), T0 + 10);
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

    /**
     * 같은 분·같은 아이디라도 플랫폼이 다르면 창도 파일도 따로다. 창 열쇠가 채널만 보면 두 플랫폼의 채팅이 한 파일에 섞이고, 그
     * 파일은 한쪽 폴더에만 가서 다른 쪽 탈퇴 정리가 남의 채팅까지 지운다.
     */
    @Test
    void 같은_분_같은_아이디라도_플랫폼이_다르면_다른_파일이다() {
        batcher.accept(List.of(chat(Platform.CHZZK, "same", T0 + 100), chat(Platform.SOOP, "same", T0 + 200),
                chat(Platform.SOOP, "same", T0 + 300)), T0 + 500);
        assertThat(batcher.openWindows()).isEqualTo(2);

        List<ArchiveObject> closed = batcher.closeAll();

        assertThat(closed).extracting(ArchiveObject::key, ArchiveObject::messageCount).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("chat/CHZZK/same/2026-08-15/10/1023-r1.jsonl", 1),
                org.assertj.core.groups.Tuple.tuple("chat/SOOP/same/2026-08-15/10/1023-r1.jsonl", 2));
    }

    /**
     * 재열림 순번도 플랫폼마다 센다. 순번 맵이 채널만 보면 치지직 창이 닫힌 뒤 처음 열리는 SOOP 창이 「다시 열림」으로 세어져
     * {@code -2}가 붙는다(파일은 안 섞이지만 거짓 재열림이 로그와 접미에 남는다, 계획 검증 F2-7).
     */
    @Test
    void 재열림_순번은_플랫폼마다_따로_센다() {
        batcher.accept(List.of(chat(Platform.CHZZK, "same", T0 + 100)), T0 + 500);
        assertThat(batcher.accept(List.of(), T0 + 62_000)).extracting(ArchiveObject::key)
                .containsExactly("chat/CHZZK/same/2026-08-15/10/1023-r1.jsonl");

        List<ArchiveObject> soop = batcher.accept(List.of(chat(Platform.SOOP, "same", T0 + 59_900)), T0 + 62_500);
        assertThat(soop).extracting(ArchiveObject::key)
                .as("SOOP의 그 분은 처음 열린 창이다")
                .containsExactly("chat/SOOP/same/2026-08-15/10/1023-r1.jsonl");
        assertThat(batcher.reopenedCount()).isZero();

        List<ArchiveObject> chzzk = batcher.accept(List.of(chat(Platform.CHZZK, "same", T0 + 59_950)), T0 + 63_000);
        assertThat(chzzk).extracting(ArchiveObject::key)
                .containsExactly("chat/CHZZK/same/2026-08-15/10/1023-r1-2.jsonl");
        assertThat(batcher.reopenedCount()).isEqualTo(1);
    }

    private static ArchivableChat chat(String ch, long receivedAt) {
        return chat(Platform.CHZZK, ch, receivedAt);
    }

    private static ArchivableChat chat(Platform platform, String ch, long receivedAt) {
        return new ArchivableChat(platform, ch, receivedAt, "{\"t\":" + receivedAt + "}");
    }
}
