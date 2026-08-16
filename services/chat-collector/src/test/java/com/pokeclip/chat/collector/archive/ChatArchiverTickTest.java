package com.pokeclip.chat.collector.archive;

import com.pokeclip.chat.collector.reconnect.ReconnectPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** 스케줄러를 안 띄우고 {@code tick()}을 직접 부른다 — 시계도 손으로 돌린다. */
class ChatArchiverTickTest {

    private static final long T0 = Instant.parse("2026-08-15T10:23:00Z").toEpochMilli();
    private final ArchiveBuffer buffer = new ArchiveBuffer(100);
    private final PendingUploads pending = new PendingUploads(5);
    private final FakeUploader uploader = new FakeUploader();
    private final MutableClock clock = new MutableClock(T0);
    private final ChatArchiver archiver = new ChatArchiver(buffer,
            new MinuteBatcher("r1", Duration.ofSeconds(2)), pending, uploader,
            new ReconnectPolicy(Duration.ofSeconds(1), Duration.ofSeconds(60)), clock);

    @Test
    void 틱은_바구니를_비워_창에_쌓고_창이_닫히면_대기_줄을_거쳐_올린다() {
        buffer.offer(chat(T0 + 100));
        buffer.offer(chat(T0 + 200));
        archiver.tick();                                   // 창 열림, 아직 안 닫힘
        assertThat(archiver.archivedCount()).isEqualTo(2);
        assertThat(uploader.uploaded).isEmpty();

        clock.set(T0 + 62_000);                            // 창 끝 + 유예
        archiver.tick();
        assertThat(uploader.uploaded).hasSize(1);
        assertThat(uploader.uploaded.get(0).messageCount()).isEqualTo(2);
        assertThat(archiver.uploadedCount()).isEqualTo(1);
        assertThat(archiver.pendingCount()).isZero();
    }

    @Test
    void 업로드가_실패하면_대기_줄에_남고_백오프_시각_전에는_다시_두드리지_않는다() {
        uploader.failing = true;
        buffer.offer(chat(T0 + 100));
        clock.set(T0 + 62_000);
        archiver.tick();                                   // 창 닫힘 → 업로드 시도 1 → 실패
        assertThat(uploader.attempts.get()).isEqualTo(1);
        assertThat(archiver.pendingCount()).isEqualTo(1);

        clock.advance(Duration.ofMillis(500));             // 백오프 1초 안
        archiver.tick();
        assertThat(uploader.attempts.get()).as("백오프 중에는 안 두드린다").isEqualTo(1);

        clock.advance(Duration.ofMillis(600));             // 1.1초 지남
        archiver.tick();
        assertThat(uploader.attempts.get()).isEqualTo(2);  // 두 번째 시도, 다음 백오프 2초
    }

    @Test
    void 백오프_중에도_바구니_퍼가기와_창_닫기는_계속된다() {
        uploader.failing = true;
        buffer.offer(chat(T0 + 100));
        clock.set(T0 + 62_000);
        archiver.tick();                                   // 첫 실패 → 백오프
        // 백오프 대기 중(다음 시도 전)에 다음 분 채팅이 온다
        for (int i = 0; i < 50; i++) {
            buffer.offer(chat(T0 + 60_000 + i));
        }
        clock.advance(Duration.ofMillis(200));
        archiver.tick();
        assertThat(buffer.size()).as("백오프가 퍼가기를 막으면 바구니가 찬다").isZero();
        assertThat(archiver.archivedCount()).isEqualTo(51);
        clock.set(T0 + 122_000);                           // 10:24 창도 유예 지남 (백오프 상한 60s < 62s라 시도도 나가지만 실패)
        archiver.tick();
        assertThat(archiver.pendingCount()).as("닫힌 창은 대기 줄에 선다").isEqualTo(2);
    }

    @Test
    void 회복하면_밀린_파일이_순서대로_전부_올라가고_카운터가_맞는다() {
        uploader.failing = true;
        for (int m = 0; m < 3; m++) {                      // 3분치 → 파일 3개
            buffer.offer(chat(T0 + m * 60_000L + 1));
            clock.set(T0 + (m + 1) * 60_000L + 2_000);
            archiver.tick();
        }
        assertThat(archiver.pendingCount()).isEqualTo(3);
        uploader.failing = false;
        clock.advance(Duration.ofSeconds(61));             // 백오프 상한을 넘겨 시도가 나가게
        archiver.tick();
        assertThat(uploader.uploaded).extracting(ArchiveObject::key).containsExactly(
                "chat/CH/2026-08-15/10/1023-r1.jsonl", "chat/CH/2026-08-15/10/1024-r1.jsonl", "chat/CH/2026-08-15/10/1025-r1.jsonl");
        assertThat(archiver.uploadedCount()).isEqualTo(3);
        assertThat(archiver.pendingCount()).isZero();
    }

    @Test
    void 대기_줄_상한을_넘으면_오래된_파일부터_버리고_센다() {
        uploader.failing = true;
        for (int m = 0; m < 7; m++) {                      // 상한 5 → 2개 버림
            buffer.offer(chat(T0 + m * 60_000L + 1));
            clock.set(T0 + (m + 1) * 60_000L + 2_000);
            archiver.tick();
        }
        assertThat(archiver.pendingCount()).isEqualTo(5);
        assertThat(archiver.droppedObjectsCount()).isEqualTo(2);
        assertThat(archiver.droppedMessagesCount()).isEqualTo(2);
    }

    @Test
    void 한_틱의_업로드는_예산_안에서만_돌고_나머지는_다음_틱으로() {
        // 파일 5개가 밀려 있고 업로드가 각 300ms 걸리면 예산 500ms에 1~2개만 올라간다.
        uploader.failing = true;
        for (int m = 0; m < 5; m++) {
            buffer.offer(chat(T0 + m * 60_000L + 1));
            clock.set(T0 + (m + 1) * 60_000L + 2_000);
            archiver.tick();
        }
        uploader.failing = false;
        uploader.delay = Duration.ofMillis(300);
        clock.advance(Duration.ofSeconds(61));
        archiver.tick();
        assertThat(uploader.uploaded.size()).isBetween(1, 2);
        assertThat(archiver.pendingCount()).isBetween(3L, 4L);
    }

    @Test
    void 업로더가_예외_아닌_RuntimeException을_던져도_틱은_다음에_계속된다() {
        ArchiveUploader bomb = o -> { throw new IllegalStateException("bug"); };
        ChatArchiver a = new ChatArchiver(buffer, new MinuteBatcher("r1", Duration.ofSeconds(2)), pending, bomb,
                new ReconnectPolicy(Duration.ofSeconds(1), Duration.ofSeconds(60)), clock);
        buffer.offer(chat(T0 + 1));
        clock.set(T0 + 62_000);
        assertThatCode(a::tick).doesNotThrowAnyException();
        assertThat(a.pendingCount()).isEqualTo(1);        // 실패로 취급, 대기 줄에 남는다
    }

    @Test
    void 바구니_상한_초과는_archiveBufferDropped로_보인다() {
        ArchiveBuffer small = new ArchiveBuffer(3);
        ChatArchiver a = new ChatArchiver(small, new MinuteBatcher("r1", Duration.ofSeconds(2)), pending, uploader,
                new ReconnectPolicy(Duration.ofSeconds(1), Duration.ofSeconds(60)), clock);
        for (int i = 0; i < 5; i++) {
            small.offer(chat(T0 + i));
        }
        a.tick();
        assertThat(a.archivedCount()).isEqualTo(3);
        assertThat(a.archiveBufferDroppedCount()).isEqualTo(2);
    }

    private static ArchivableChat chat(long receivedAt) {
        return new ArchivableChat("CH", receivedAt, "{\"t\":" + receivedAt + "}");
    }
}
