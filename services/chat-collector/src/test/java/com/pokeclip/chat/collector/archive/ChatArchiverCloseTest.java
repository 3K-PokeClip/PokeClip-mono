package com.pokeclip.chat.collector.archive;

import com.pokeclip.chat.collector.reconnect.ReconnectPolicy;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/** 여기서는 {@code start()}로 실제 스케줄러를 띄운다 — close가 스케줄러 스레드에서 도는지까지 본다. */
class ChatArchiverCloseTest {

    private static final long T0 = Instant.parse("2026-08-15T10:23:00Z").toEpochMilli();

    @Test
    void 닫으면_열린_창이_닫혀_올라가고_카운터에_잡힌다() throws Exception {
        FakeUploader uploader = new FakeUploader();
        ChatArchiver archiver = newArchiver(uploader, new MutableClock(T0));
        archiver.start();
        archiver.offer(chat(T0 + 100));
        archiver.offer(chat(T0 + 200));
        Thread.sleep(1_200);                                       // 한 틱 지나 창이 열려 있다
        assertThat(uploader.uploaded).isEmpty();

        archiver.beginClose();
        archiver.awaitClosed(Duration.ofSeconds(5));

        assertThat(uploader.uploaded).hasSize(1);
        assertThat(uploader.uploaded.get(0).messageCount()).isEqualTo(2);
        assertThat(archiver.uploadedCount()).isEqualTo(1);
        assertThat(archiver.pendingCount()).isZero();
    }

    @Test
    void 닫을_때_바구니에_남은_것도_창에_넣고_올린다() throws Exception {
        // 틱이 아직 안 돈 채팅(offer 직후 close)도 잃지 않는다.
        FakeUploader uploader = new FakeUploader();
        ChatArchiver archiver = newArchiver(uploader, new MutableClock(T0));
        archiver.start();
        archiver.offer(chat(T0 + 1));
        archiver.beginClose();
        archiver.awaitClosed(Duration.ofSeconds(5));
        assertThat(uploader.uploaded).hasSize(1);
        assertThat(archiver.archivedCount()).isEqualTo(1);
    }

    @Test
    void 창고가_죽어_있으면_한_번만_시도하고_못_올린_파일을_버리고_센다() throws Exception {
        FakeUploader uploader = new FakeUploader();
        uploader.failing = true;
        ChatArchiver archiver = newArchiver(uploader, new MutableClock(T0));
        archiver.start();
        archiver.offer(chat(T0 + 1));
        int before = uploader.attempts.get();
        archiver.beginClose();
        archiver.awaitClosed(Duration.ofSeconds(5));
        assertThat(uploader.attempts.get() - before).as("종료에서 재시도 루프를 돌지 않는다").isEqualTo(1);
        assertThat(archiver.droppedObjectsCount()).isEqualTo(1);
        assertThat(archiver.droppedMessagesCount()).isEqualTo(1);
        assertThat(archiver.pendingCount()).isZero();
    }

    @Test
    void 백오프_중이어도_종료의_마지막_시도는_나간다() throws Exception {
        // 운영 중 실패로 백오프 60초가 걸려 있어도, 종료는 그것을 기다리지 않고 1회 시도한다.
        FakeUploader uploader = new FakeUploader();
        MutableClock clock = new MutableClock(T0);
        ChatArchiver archiver = newArchiver(uploader, clock);
        uploader.failing = true;
        archiver.offer(chat(T0 + 1));
        clock.set(T0 + 62_000);
        archiver.tick();                                           // 실패 → 백오프
        uploader.failing = false;                                  // 창고 회복
        archiver.beginClose();
        archiver.awaitClosed(Duration.ofSeconds(5));
        assertThat(uploader.uploaded).hasSize(1);
    }

    @Test
    void 두_번_닫아도_두_번째는_첫_번째의_완료를_기다리고_돌아온다() throws Exception {
        FakeUploader uploader = new FakeUploader();
        uploader.delay = Duration.ofMillis(300);
        ChatArchiver archiver = newArchiver(uploader, new MutableClock(T0));
        archiver.start();
        archiver.offer(chat(T0 + 1));
        archiver.beginClose();
        Thread second = new Thread(() -> {
            archiver.beginClose();
            archiver.awaitClosed(Duration.ofSeconds(5));
        });
        second.start();
        archiver.awaitClosed(Duration.ofSeconds(5));
        second.join(2_000);
        assertThat(second.isAlive()).isFalse();
        assertThat(uploader.uploaded).hasSize(1);
    }

    @Test
    void 시한_안에_못_끝나면_돌아오되_경고를_남긴다() throws Exception {
        FakeUploader uploader = new FakeUploader();
        uploader.delay = Duration.ofSeconds(3);
        ChatArchiver archiver = newArchiver(uploader, new MutableClock(T0));
        archiver.start();
        archiver.offer(chat(T0 + 1));
        try (LogCaptor captor = new LogCaptor()) {
            long t = System.nanoTime();
            archiver.beginClose();
            archiver.awaitClosed(Duration.ofMillis(500));
            assertThat(Duration.ofNanos(System.nanoTime() - t)).isLessThan(Duration.ofSeconds(2));
            assertThat(captor.messages()).anyMatch(m -> m.startsWith("chat.archive.close_timeout"));
        }
    }

    /**
     * 종료 flush 안에서 업로더 <b>밖</b>에서 난 {@code Error}도 잡아 로그와 폐기 카운터를 남긴다 — 안 잡으면 latch만 풀리고
     * 로그 0줄·대기 줄은 pending에 남은 채 반쪽 종료다(/code-review 2라운드 F1). 업로더의 Error는 uploadOne이 실패로
     * 처리하므로(F2) 주입 자리는 시계다(바구니·묶음기·줄·정책이 전부 final): 대기 줄에 파일이 선 뒤의 첫 시계 읽기 —
     * 실패한 업로드 뒤 백오프 시각 계산 — 에서 Error가 난다.
     */
    @Test
    void 종료_flush에서_Error가_나면_close_flush_failed를_남기고_남은_파일을_버리고_센다() throws Exception {
        FakeUploader uploader = new FakeUploader();
        uploader.failing = true;
        PendingUploads pending = new PendingUploads(10);
        LongSupplier clockThatBreaksOncePending = () -> {
            if (pending.size() > 0) throw new NoClassDefFoundError("fake");
            return T0;
        };
        ChatArchiver archiver = new ChatArchiver(new ArchiveBuffer(100), new MinuteBatcher("r1", Duration.ofSeconds(2)),
                pending, uploader, new ReconnectPolicy(Duration.ofSeconds(1), Duration.ofSeconds(60)), clockThatBreaksOncePending);
        archiver.start();
        archiver.offer(chat(T0 + 1));
        try (LogCaptor captor = new LogCaptor()) {
            archiver.beginClose();
            archiver.awaitClosed(Duration.ofSeconds(5));
            assertThat(captor.messages()).as("Error가 새면 로그 0줄이다").containsOnlyOnce(
                    "chat.archive.close_flush_failed causeType=NoClassDefFoundError pending=1 bufferSize=0");
            assertThat(captor.messages()).noneMatch(m -> m.startsWith("chat.archive.close_timeout"));
        }
        assertThat(archiver.droppedObjectsCount()).as("못 올린 파일은 pending이 아니라 dropped에 실려야 등식이 닫힌다").isEqualTo(1);
        assertThat(archiver.pendingCount()).isZero();
    }

    /**
     * 같은 Error라도 <b>대기 줄이 비어 있으면</b> 폐기 로그를 안 남긴다 — 아무것도 안 버렸는데 {@code close_dropped
     * objects=0}이 남으면 그 이름으로 거는 알림이 헛운다(/code-review 3라운드 G1). 못 센 것(바구니·열린 창)은
     * {@code bufferSize}가 단서다.
     */
    @Test
    void 대기_줄이_빈_채_Error가_나면_폐기_로그는_안_남고_바구니_크기가_단서로_남는다() throws Exception {
        ChatArchiver archiver = new ChatArchiver(new ArchiveBuffer(100), new MinuteBatcher("r1", Duration.ofSeconds(2)),
                new PendingUploads(10), new FakeUploader(),
                new ReconnectPolicy(Duration.ofSeconds(1), Duration.ofSeconds(60)),
                () -> { throw new NoClassDefFoundError("fake"); });   // 종료 flush 첫 줄에서 바로 Error
        archiver.offer(chat(T0 + 1));                                  // 바구니에만 있다(틱을 안 돌렸다)
        try (LogCaptor captor = new LogCaptor()) {
            archiver.beginClose();
            archiver.awaitClosed(Duration.ofSeconds(5));
            assertThat(captor.messages()).containsOnlyOnce(
                    "chat.archive.close_flush_failed causeType=NoClassDefFoundError pending=0 bufferSize=1");
            assertThat(captor.messages()).as("버린 것이 없으면 폐기 로그도 없다").noneMatch(m -> m.startsWith("chat.archive.close_dropped"));
        }
        assertThat(archiver.droppedObjectsCount()).isZero();
    }

    /** 러너는 closeSinks를 두 번 지나 둘째가 awaitClosed(0)을 부른다 — 시한 초과 WARN은 프로세스 생애 한 번이면 된다(F4). */
    @Test
    void 시한_초과_경고는_awaitClosed를_거듭_불러도_한_번만_남는다() throws Exception {
        FakeUploader uploader = new FakeUploader();
        uploader.delay = Duration.ofSeconds(3);
        ChatArchiver archiver = newArchiver(uploader, new MutableClock(T0));
        archiver.start();
        archiver.offer(chat(T0 + 1));
        try (LogCaptor captor = new LogCaptor()) {
            archiver.beginClose();
            archiver.awaitClosed(Duration.ZERO);
            archiver.awaitClosed(Duration.ZERO);
            assertThat(captor.messages().stream().filter(m -> m.startsWith("chat.archive.close_timeout")).count()).isEqualTo(1);
        }
    }

    private ChatArchiver newArchiver(FakeUploader uploader, MutableClock clock) {
        return new ChatArchiver(new ArchiveBuffer(100), new MinuteBatcher("r1", Duration.ofSeconds(2)),
                new PendingUploads(10), uploader, new ReconnectPolicy(Duration.ofSeconds(1), Duration.ofSeconds(60)), clock);
    }

    private static ArchivableChat chat(long receivedAt) {
        return new ArchivableChat("CH", receivedAt, "{}");
    }
}
