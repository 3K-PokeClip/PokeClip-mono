package com.pokeclip.chat.collector;

import com.pokeclip.chat.collector.archive.ArchivableChat;
import com.pokeclip.chat.collector.archive.ArchiveCounters;
import com.pokeclip.chat.collector.archive.ChatArchive;
import com.pokeclip.chat.collector.fake.FakeChzzkBehavior;
import com.pokeclip.chat.collector.fake.FakeChzzkTest;
import com.pokeclip.chat.collector.persist.ChatBuffer;
import com.pokeclip.chat.collector.persist.ChatPersister;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import com.pokeclip.chat.collector.support.TestPersistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 가짜 치지직 + 가짜 {@link ChatArchive} — S3 없이 <b>러너 배선만</b> 잰다.
 * 수신이 아카이브에도 들어가는가, 종료가 저장기와 <b>나란히</b> 닫는가, 영구 정지 경로도 닫는가.
 */
@FakeChzzkTest
class CollectorArchiveWiringTest extends IntegrationTestSupport {

    /**
     * offer된 것을 기억하고 close 순서를 <b>순번</b>으로 기록하는 가짜. stop()은 closeSinks()를 두 번
     * 지나므로(stop 본문 · logVerdictOnce) <b>첫 호출만</b> 기록한다(compareAndSet) — 안 그러면 두 번째가
     * 덮어 순서 단언이 깨진다(plan-critic 중대-4). nanoTime 대신 순번인 이유는 같은 값이 나올 수 있어서다.
     */
    static final class RecordingArchive implements ChatArchive {
        static final AtomicInteger SEQ = new AtomicInteger();
        final List<ArchivableChat> offered = new CopyOnWriteArrayList<>();
        final AtomicInteger beginCloseSeq = new AtomicInteger();
        final AtomicInteger awaitClosedSeq = new AtomicInteger();
        /** awaitClosed가 받은 예산을 호출 순서대로 — 둘째 closeSinks가 새 예산을 안 받는지 잰다. */
        final List<Duration> awaitBudgets = new CopyOnWriteArrayList<>();
        /** 첫 awaitClosed만 이만큼 매달린다 — "아카이브가 시한까지 안 돌아오는" 상태의 축소판. */
        volatile Duration firstAwaitBlocks = Duration.ZERO;

        @Override public void offer(ArchivableChat chat) { offered.add(chat); }
        @Override public void beginClose() { beginCloseSeq.compareAndSet(0, SEQ.incrementAndGet()); }
        @Override public void awaitClosed(Duration budget) {
            awaitBudgets.add(budget);
            if (awaitClosedSeq.compareAndSet(0, SEQ.incrementAndGet()) && !firstAwaitBlocks.isZero()) {
                try {
                    Thread.sleep(firstAwaitBlocks.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        @Override public ArchiveCounters counters() { return ArchiveCounters.NONE; }
    }

    @LocalServerPort int port;
    @Autowired FakeChzzkBehavior behavior;
    @Autowired RestClient.Builder restClientBuilder;

    private CollectorRunner runner;

    @AfterEach
    void tearDown() {
        if (runner != null) runner.stop();
        behavior.reset();
    }

    @Test
    void 수신한_채팅은_DB_바구니와_아카이브에_같이_들어가고_raw는_안쪽_JSON_그대로다() throws Exception {
        RecordingArchive archive = new RecordingArchive();
        start(archive);
        String inner = "{\"channelId\":\"CH1\",\"senderChannelId\":\"S1\",\"content\":\"ㅋㅋ\",\"messageTime\":1754300000000,\"profile\":{\"nickname\":\"닉\"}}";
        behavior.emitChat(inner);
        awaitUntil(Duration.ofSeconds(5), () -> archive.offered.size() == 1);
        ArchivableChat chat = archive.offered.get(0);
        assertThat(chat.raw()).isEqualTo(inner);
        assertThat(chat.channelId()).isEqualTo("CH1");
        assertThat(chat.receivedAtMillis()).isBetween(System.currentTimeMillis() - 5_000, System.currentTimeMillis());
        assertThat(runner.metrics().totalReceived()).isEqualTo(1);
    }

    /**
     * <b>persister.close()가 던져도 아카이브 대기는 반드시 지난다.</b> closeSinks가 그 호출을 try/finally로
     * 안 감싸면 마지막 flush를 아무도 안 기다린 채 프로세스가 내려가고, 잃은 파일의 단서(close_timeout)조차
     * 안 남는다 — 조용한 유실이다(/code-review 4라운드). 지금 {@code ChatPersister.close}는 모든 갈래를 잡게
     * 짜여 있지만 그 계약은 컴파일러가 강제하지 않는다.
     *
     * <p><b>예외는 삼키지 않는다</b> — 삼키면 "종료가 실패했다"는 사실이 사라진다. 그래서 이 검사가 드러내는
     * 대로 <b>그 stop()은 거기서 끊긴다</b>: 세션 반납·소켓 닫기·판정 줄이 안 나간다. 이것은 이 카드가 만든
     * 성질이 아니다 — 이 자리는 원래 {@code persister.close()}를 직접 부르던 곳이고, 던지면 그때도 똑같이
     * 끊겼다. {@code persist/*}는 이 카드가 미변경으로 정한 범위라 여기서 안 고치고 알려진 한계로 남긴다.
     * 두 번째 stop()이 정상으로 끝나는 것으로 뒷정리까지 확인한다.
     */
    @Test
    void persister_close가_던져도_아카이브_대기는_지나고_예외는_전파된다() throws Exception {
        AtomicBoolean thrownOnce = new AtomicBoolean();
        ChatPersister throwingOnce = new ChatPersister(new JdbcTemplate(), new ChatBuffer(10)) {
            @Override public void close() {
                if (thrownOnce.compareAndSet(false, true)) {
                    throw new IllegalStateException("boom");
                }
            }
        };
        RecordingArchive archive = new RecordingArchive();
        CollectionStatus status = new CollectionStatus();
        runner = new CollectorRunner(
                new ChzzkProperties(true, "test-only-token", "http://localhost:" + port, Duration.ofSeconds(5),
                        Duration.ofSeconds(30), Duration.ofSeconds(60)),
                status, restClientBuilder, TestPersistence.unusedBuffer(), throwingOnce, archive, () -> { });
        runner.run(null);
        awaitState(status, CollectionStatus.State.COLLECTING);

        assertThatThrownBy(runner::stop).isInstanceOf(IllegalStateException.class);
        assertThat(archive.beginCloseSeq.get()).as("flush 제출은 persister 앞이라 던지기 전에 끝난다").isPositive();
        assertThat(archive.awaitClosedSeq.get()).as("try/finally가 없으면 이 줄을 통째로 건너뛴다").isPositive();
        // 끊긴 종료를 마저 끝낸다 — 두 번째는 안 던지므로 반납·소켓 닫기가 여기서 돈다(tearDown이 그것을 요구한다).
        runner.stop();
    }

    @Test
    void 나란히_닫기_순서_beginClose_다음_persister_close_다음_awaitClosed() throws Exception {
        // "나란히"의 구조적 증거는 순서다 — 아카이브의 마지막 flush 제출(beginClose)이 persister.close()의
        // 5초 대기보다 <b>앞</b>에 있어야 그 5초 동안 저쪽도 돈다. ChatPersister는 final이 아니라
        // close()를 덮어 순번을 찍는다(TestPersistence.disabledPersister와 같은 더미 datasource).
        AtomicInteger persisterCloseSeq = new AtomicInteger();
        ChatPersister recording = new ChatPersister(new JdbcTemplate(), new ChatBuffer(10)) {
            @Override public void close() { persisterCloseSeq.compareAndSet(0, RecordingArchive.SEQ.incrementAndGet()); }
        };
        RecordingArchive archive = new RecordingArchive();
        CollectionStatus status = new CollectionStatus();
        runner = new CollectorRunner(
                new ChzzkProperties(true, "test-only-token", "http://localhost:" + port, Duration.ofSeconds(5),
                        Duration.ofSeconds(30), Duration.ofSeconds(60)),
                status, restClientBuilder, TestPersistence.unusedBuffer(), recording, archive, () -> { });
        runner.run(null);
        awaitState(status, CollectionStatus.State.COLLECTING);
        runner.stop();
        assertThat(archive.beginCloseSeq.get()).isPositive();
        assertThat(persisterCloseSeq.get()).as("persister.close()가 불렸다").isPositive();
        assertThat(archive.beginCloseSeq.get()).as("아카이브 flush 제출이 persister 대기보다 먼저").isLessThan(persisterCloseSeq.get());
        assertThat(archive.awaitClosedSeq.get()).as("아카이브 대기는 persister 뒤").isGreaterThan(persisterCloseSeq.get());
    }

    /**
     * stop()은 closeSinks를 두 번 지난다(stop 본문 · 판정 직전). 첫 awaitClosed가 시한까지 매달렸으면 둘째가 <b>또</b>
     * 5초를 받으면 안 된다 — 예산은 첫 closeSinks가 정한 시한(deadline) 하나다. 첫 대기를 300ms 매달리게 하고 둘째가
     * 받은 예산이 그만큼 줄었는지 본다(가짜 10.03초 실측이 이 자리였다 — /code-review 1라운드 K06).
     */
    @Test
    void 둘째_closeSinks는_첫째가_정한_시한의_남은_만큼만_기다린다() throws Exception {
        RecordingArchive archive = new RecordingArchive();
        archive.firstAwaitBlocks = Duration.ofMillis(300);
        CollectionStatus status = start(archive);
        awaitState(status, CollectionStatus.State.COLLECTING);
        runner.stop();
        assertThat(archive.awaitBudgets).as("stop()이 closeSinks를 두 번 지난다").hasSizeGreaterThanOrEqualTo(2);
        Duration first = archive.awaitBudgets.get(0);
        Duration second = archive.awaitBudgets.get(1);
        assertThat(first).isGreaterThan(Duration.ofSeconds(4));
        assertThat(second).as("둘째는 첫째가 쓴 300ms만큼 줄어야 한다 — 새 5초를 받으면 안 된다")
                .isLessThanOrEqualTo(first.minusMillis(250));
    }

    @Test
    void 영구_정지_경로에서도_아카이브가_닫힌다() throws Exception {
        RecordingArchive archive = new RecordingArchive();
        CollectionStatus status = start(archive);
        behavior.emitSystem("{\"type\":\"revoked\",\"data\":{}}");
        awaitState(status, CollectionStatus.State.STOPPED);
        awaitUntil(Duration.ofSeconds(5), () -> archive.awaitClosedSeq.get() > 0);
        assertThat(archive.beginCloseSeq.get()).isPositive();
        // awaitUntil은 시한이 차면 조용히 돌아온다 — 이 단언이 없으면 영구 정지 경로가 awaitClosed를 빼먹어도
        // 5초 느려질 뿐 초록이다(reviewer 3회차 M2 변이 재현). "닫힌다"의 뒷절반(대기)까지 여기서 잰다.
        assertThat(archive.awaitClosedSeq.get()).as("영구 정지 경로도 awaitClosed까지 간다").isPositive();
    }

    // ── 도우미 (ChatLogLeakTest와 같은 모양) ──

    /** {@code run()}으로 띄운다 — {@code start()}는 수립 실패를 밖으로 던진다. */
    private CollectionStatus start(ChatArchive archive) {
        CollectionStatus status = new CollectionStatus();
        runner = new CollectorRunner(
                new ChzzkProperties(true, "test-only-token", "http://localhost:" + port, Duration.ofSeconds(5),
                        Duration.ofSeconds(30), Duration.ofSeconds(60)),
                status, restClientBuilder, TestPersistence.unusedBuffer(), TestPersistence.disabledPersister(), archive, () -> { });
        runner.run(null);
        return status;
    }

    private static void awaitState(CollectionStatus status, CollectionStatus.State state) throws Exception {
        awaitUntil(Duration.ofSeconds(5), () -> status.state() == state);
    }
}
