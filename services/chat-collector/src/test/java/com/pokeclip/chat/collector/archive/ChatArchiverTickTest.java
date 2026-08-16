package com.pokeclip.chat.collector.archive;

import com.pokeclip.chat.collector.reconnect.ReconnectPolicy;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 스케줄러를 안 띄우고 {@code tick()}·{@code safeTick()}을 직접 부른다 — 시계도 손으로 돌린다.
 * 예외는 {@code start가_거는_것은_safeTick이라_Error가_나도_다음_틱이_또_돈다} 하나다:
 * start()가 <b>무엇을</b> 예약하는지는 스케줄러 없이 잴 수 없다. 거기서 그 전제를 지운다면 배선 회귀가
 * 아무 검사에도 안 잡힌다.
 */
class ChatArchiverTickTest {

    private static final long T0 = Instant.parse("2026-08-15T10:23:00Z").toEpochMilli();
    /** 읽을 때마다 Error를 던지는 시계 — 틱의 첫 줄이 그것을 읽는다(생성자는 시계를 안 읽는다). */
    private static final LongSupplier BROKEN_CLOCK = () -> { throw new NoClassDefFoundError("fake"); };
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

    /**
     * 업로더가 던진 것이 {@code RuntimeException}이든 {@code Error}(SDK 초기화 실패 = NoClassDefFoundError)든
     * <b>실패 한 건</b>이다 — 백오프가 걸리고 WARN은 첫 건만, 틱 래퍼까지 안 샌다. 두 타입을 <b>같은 본문</b>으로
     * 재는 이유: catch를 한쪽으로 좁히면 다른 쪽이 백오프 없이 매 틱 같은 파일을 두드리고 tick_failed를 초당
     * 한 줄 찍는데, 검사가 하나뿐이면 그 수정이 초록으로 지나간다.
     */
    @Test
    void 업로더가_RuntimeException을_던지면_실패_한_건으로_백오프가_걸린다() {
        AtomicInteger attempts = new AtomicInteger();
        업로더가_던진_것은_실패_한_건이다(
                o -> { attempts.incrementAndGet(); throw new IllegalStateException("bug"); }, attempts, "IllegalStateException");
    }

    @Test
    void 업로더가_Error를_던져도_실패_한_건으로_백오프가_걸린다() {
        AtomicInteger attempts = new AtomicInteger();
        업로더가_던진_것은_실패_한_건이다(
                o -> { attempts.incrementAndGet(); throw new NoClassDefFoundError("fake sdk init"); }, attempts, "NoClassDefFoundError");
    }

    private void 업로더가_던진_것은_실패_한_건이다(ArchiveUploader bomb, AtomicInteger attempts, String causeType) {
        ChatArchiver a = new ChatArchiver(buffer, new MinuteBatcher("r1", Duration.ofSeconds(2)), pending, bomb,
                new ReconnectPolicy(Duration.ofSeconds(1), Duration.ofSeconds(60)), clock);
        buffer.offer(chat(T0 + 1));
        clock.set(T0 + 62_000);
        try (LogCaptor captor = new LogCaptor()) {
            assertThatCode(a::tick).doesNotThrowAnyException();         // 창 닫힘 → 시도 1 → 실패로 취급
            clock.advance(Duration.ofMillis(500));                       // 백오프 1초 안
            a.tick();
            assertThat(attempts.get()).as("백오프가 안 걸리면 매 틱 두드린다").isEqualTo(1);
            assertThat(a.pendingCount()).as("실패한 파일은 대기 줄에 남는다").isEqualTo(1);
            assertThat(captor.messages().stream()
                    .filter(m -> m.startsWith("chat.archive.upload_failed causeType=" + causeType)).count()).isEqualTo(1);
            assertThat(captor.messages()).noneMatch(m -> m.startsWith("chat.archive.tick_failed"));
        }
    }

    /**
     * 틱 래퍼({@code safeTick})는 업로더 밖에서 난 {@code Error}도 삼킨다 — 안 삼키면 scheduleAtFixedRate가 예약을
     * 접어 아카이브가 영영 끊긴다. 주입 자리는 시계뿐이다(바구니·묶음기·줄·정책이 전부 final) — 틱 첫 줄이 그것을
     * 읽는다. 여기서는 그 한 줄만 재므로 스케줄러를 안 띄운다. 배선은 아래 검사가 잰다.
     */
    @Test
    void 틱_안에서_Error가_나도_safeTick은_삼키고_tick_failed_한_줄을_남긴다() {
        ChatArchiver a = new ChatArchiver(buffer, new MinuteBatcher("r1", Duration.ofSeconds(2)), pending, uploader,
                new ReconnectPolicy(Duration.ofSeconds(1), Duration.ofSeconds(60)), BROKEN_CLOCK);
        try (LogCaptor captor = new LogCaptor()) {
            assertThatCode(a::safeTick).as("Error가 새면 스케줄러가 조용히 멈춘다").doesNotThrowAnyException();
            assertThat(captor.messages()).containsOnlyOnce("chat.archive.tick_failed causeType=NoClassDefFoundError");
        }
    }

    /**
     * {@code start()}가 예약하는 것은 {@code tick}이 아니라 {@code safeTick}이다. <b>이 배선이 풀려도 위 검사들은 전부
     * 초록이다</b> — 그것들은 safeTick을 직접 부르기 때문이다. 그래서 여기서만 실제 스케줄러를 띄우고 틱이
     * <b>두 번</b> 도는 것을 본다: 한 줄만 보면 "첫 Error에 예약이 접혔다"와 구별되지 않는다.
     */
    @Test
    void start가_거는_것은_safeTick이라_Error가_나도_다음_틱이_또_돈다() throws Exception {
        ChatArchiver a = new ChatArchiver(buffer, new MinuteBatcher("r1", Duration.ofSeconds(2)), pending, uploader,
                new ReconnectPolicy(Duration.ofSeconds(1), Duration.ofSeconds(60)), BROKEN_CLOCK);
        try (LogCaptor captor = new LogCaptor()) {
            a.start();
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (tickFailures(captor) < 2 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(tickFailures(captor)).as("첫 Error에 예약이 접히면 한 줄에서 멈춘다").isGreaterThanOrEqualTo(2);
        } finally {
            a.beginClose();
        }
    }

    private static long tickFailures(LogCaptor captor) {
        return captor.messages().stream().filter(m -> m.startsWith("chat.archive.tick_failed")).count();
    }

    /** 실패 WARN은 연속 실패의 첫 건에만, 회복 INFO 뒤 다시 실패하면 또 한 번 — 도배는 막되 두 번째 장애는 보인다. */
    @Test
    void 실패_경고는_연속_실패의_첫_건에만_남고_회복_뒤_다시_실패하면_또_남는다() {
        try (LogCaptor captor = new LogCaptor()) {
            uploader.failing = true;
            buffer.offer(chat(T0 + 1));
            clock.set(T0 + 62_000);
            archiver.tick();                               // 실패 1
            clock.advance(Duration.ofSeconds(61));
            archiver.tick();                               // 실패 2 (같은 장애)
            assertThat(captor.messages().stream().filter(m -> m.startsWith("chat.archive.upload_failed")).count()).isEqualTo(1);
            uploader.failing = false;
            clock.advance(Duration.ofSeconds(61));
            archiver.tick();                               // 회복
            assertThat(captor.messages()).anyMatch(m -> m.startsWith("chat.archive.upload_recovered afterFailures=2"));
            uploader.failing = true;
            buffer.offer(chat(T0 + 60_000 + 1));
            clock.advance(Duration.ofSeconds(1));          // 10:24 창도 유예를 넘겼다 → 닫힘 → 시도
            archiver.tick();                               // 새 장애의 첫 실패
            assertThat(captor.messages().stream().filter(m -> m.startsWith("chat.archive.upload_failed")).count()).isEqualTo(2);
        }
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
