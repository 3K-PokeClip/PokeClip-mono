package com.pokeclip.chat.collector.observe;

import com.pokeclip.chat.collector.archive.ArchiveCounters;
import com.pokeclip.chat.collector.chzzk.ChatMessage;
import com.pokeclip.chat.collector.support.TestPersistence;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SummaryLoggerTest {

    /** PRD 「성공 기준」이 요약에 남기라고 정한 항목 전부. */
    private static final String[] REQUIRED = {
            "received=", "maxReceiveGap=", "lastReceivedAt=",
            "lastPingAt=", "maxPingGap=", "lastPongAt=", "maxPongGap=",
            "orderViolations=", "delayMin=", "delayMedian=", "delayMax=",
            "system=", "decodeFailures=",
            // 하트비트가 조용히 죽는 두 경로. 세기만 하고 안 실으면 아무도 못 본다 —
            // 콜백 안에 health를 DOWN으로 돌리는 일이 있으면 수집이 죽었는데
            // health는 UP이고 요약에도 표시가 없는 상태가 된다.
            "sendFailures=", "callbackFailures=",
            // 싱크가 던져 삼킨 횟수. 수신은 사는데 처리가 통째로 죽은 상태가
            // 여기 안 실리면 아무 데도 안 남는다.
            "sinkFailures="
    };

    @Test
    void 요약_한_줄에_판정에_필요한_항목이_전부_있다() {
        CollectionMetrics metrics = new CollectionMetrics();
        metrics.recordMessage(new ChatMessage("CH1", "S1", "ㅋㅋ", 1_000L, "{}"), 1_100L);

        String line = SummaryLogger.render("s1", metrics.snapshot(), Heartbeat.idleForTest(),
                0L, 0L, 0L, 0L, 0L, ArchiveCounters.NONE);

        assertThat(line).startsWith("chat.summary ");
        for (String key : REQUIRED) {
            assertThat(line).as("요약에 " + key + "가 없으면 그 항목을 아무도 판정 못 한다")
                    .contains(key);
        }
    }

    /**
     * 위 테스트는 키만 박아 둔 상수 문자열도 통과시킨다. 값이 실제 관측에서
     * 온다는 것을 따로 못박는다.
     */
    @Test
    void 요약이_박아둔_문자열이_아니라_실제_값을_싣는다() {
        CollectionMetrics metrics = new CollectionMetrics();
        metrics.recordMessage(new ChatMessage("CH1", "S1", "ㅋㅋ", 1_000L, "{}"), 1_100L);
        metrics.recordMessage(new ChatMessage("CH1", "S2", "ㅎㅎ", 3_000L, "{}"), 3_100L);
        metrics.recordDecodeFailure();
        metrics.recordSystemEvent("connected");

        String line = SummaryLogger.render("s1", metrics.snapshot(), Heartbeat.idleForTest(),
                7L, 0L, 0L, 0L, 0L, ArchiveCounters.NONE);

        assertThat(line).contains("received=2")
                .contains("decodeFailures=1")
                .contains("connected=1")
                // 0을 박아 두면 다른 테스트가 전부 0을 넘기므로 안 걸린다.
                .contains("sinkFailures=7");
    }

    /** 개별 메시지 로그가 0줄이어도 요약이 본문을 흘리면 완료 조건 2번이 깨진다. */
    @Test
    void 요약에_본문과_작성자_식별자가_없다() {
        CollectionMetrics metrics = new CollectionMetrics();
        metrics.recordMessage(new ChatMessage("CHANNEL-NEEDLE", "SENDER-NEEDLE", "CONTENT-NEEDLE", 1_000L, "{\"content\":\"CONTENT-NEEDLE\"}"), 1_100L);

        String line = SummaryLogger.render("s1", metrics.snapshot(), Heartbeat.idleForTest(),
                0L, 0L, 0L, 0L, 0L, ArchiveCounters.NONE);

        // 양성 대조가 먼저다. 수신 0건이면 바늘이 요약을 지나간 적이 없어
        // doesNotContain 둘이 자동으로 참이 된다 — 아무것도 검사하지 않은 초록불이다.
        assertThat(line).as("바늘이 요약을 지나가지 않았다면 아래 두 줄은 검사가 아니다")
                .contains("received=1");

        // channelId는 개별 메시지 필드는 아니지만 요약에 실을 이유도 없다 —
        // persisted/conflicts/dropped 숫자만 싣는다는 정책을 여기서 못박는다.
        assertThat(line).doesNotContain("CONTENT-NEEDLE").doesNotContain("SENDER-NEEDLE")
                .doesNotContain("CHANNEL-NEEDLE");
    }

    /** 적재가 생겼는데 요약에 안 실리면 "저장이 도는지"를 아무도 못 본다. */
    @Test
    void 요약에_persisted_conflicts_poisoned_dropped가_실린다() {
        CollectionMetrics metrics = new CollectionMetrics();
        metrics.recordMessage(new ChatMessage("CH1", "S1", "ㅋㅋ", 1_000L, "{}"), 1_100L);

        String line = SummaryLogger.render("s1", metrics.snapshot(), Heartbeat.idleForTest(),
                0L, 5L, 2L, 3L, 1L, ArchiveCounters.NONE);

        // 키만 박아 둔 상수 문자열이 통과하지 못하게 값까지 본다.
        assertThat(line).contains("persisted=5")
                .contains("conflicts=2")
                .contains("poisoned=3")
                .contains("dropped=1");
    }

    /** 아카이브 관측 여섯. 요약·판정 줄 둘 다에 실린다(태스크 9). */
    private static final List<String> REQUIRED_ARCHIVE = List.of(
            "archived=", "archiveBufferDropped=", "uploaded=", "pending=", "droppedObjects=", "droppedMessages=");

    @Test
    void 요약_줄에_아카이브_카운터_여섯이_값과_함께_실린다() {
        CollectionMetrics metrics = new CollectionMetrics();
        ArchiveCounters archive = counters(7, 1, 3, 2, 1, 4, "r1");
        String line = SummaryLogger.render("s1", metrics.snapshot(), Heartbeat.idleForTest(), 0L, 0L, 0L, 0L, 0L, archive);
        assertThat(line).contains("archived=7").contains("archiveBufferDropped=1").contains("uploaded=3")
                .contains("pending=2").contains("droppedObjects=1").contains("droppedMessages=4");
    }

    @Test
    void 판정_줄에는_archiveRunId까지_실린다() {
        // stopReason null = 정상 종료(SHUTDOWN) — StopReason에는 SHUTDOWN 상수가 없다.
        String line = SummaryLogger.renderVerdict(1L, 0L, new CollectionMetrics().verdict(), null,
                0L, 0L, 0L, 0L, 0L, counters(0, 0, 0, 0, 0, 0, "k7x2m9pq"));
        for (String key : REQUIRED_ARCHIVE) assertThat(line).contains(key);
        assertThat(line).contains("archiveRunId=k7x2m9pq");
    }

    /** 값만 돌려주는 카운터 묶음. */
    static ArchiveCounters counters(long archived, long bufDropped, long uploaded, long pending, long dObj, long dMsg, String runId) {
        return new ArchiveCounters() {
            public long archivedCount() { return archived; }
            public long archiveBufferDroppedCount() { return bufDropped; }
            public long uploadedCount() { return uploaded; }
            public long pendingCount() { return pending; }
            public long droppedObjectsCount() { return dObj; }
            public long droppedMessagesCount() { return dMsg; }
            public String runId() { return runId; }
        };
    }

    /**
     * <b>줄이 어느 방송의 것인지 말하는가.</b> 스트리머가 여럿이면 30초마다 이 줄이
     * 세션 수만큼 나가는데, 식별자가 없으면 <b>서로 구분되지 않는 줄 N개</b>가 된다 —
     * 「일부만 안 걷힌다」를 로그로 가르는 열쇠가 여기 하나뿐이다.
     */
    // 문항 2: contains("stream=")이면 포맷에 박아 둔 문자열도 통과한다.
    //         <b>인자로 준 값</b>이 그대로 나오는지를 본다.
    @Test
    void 요약_줄이_어느_방송의_것인지_말한다() {
        CollectionMetrics metrics = new CollectionMetrics();

        String line = SummaryLogger.render("s-42", metrics.snapshot(), Heartbeat.idleForTest(),
                0L, 0L, 0L, 0L, 0L, ArchiveCounters.NONE);

        assertThat(line).startsWith("chat.summary stream=s-42 ");
    }

    /**
     * <b>같은 스트리머의 새 방송이 오면 번호만 갈아끼운다</b>(세션도 소켓도 그대로).
     * 요약이 시작 시점의 번호를 붙들면 그 뒤 줄이 전부 <b>끝난 방송</b>을 가리킨다 —
     * 그 줄을 보고 앞 방송이 아직 걷히고 있다고 읽는다.
     */
    // 문항 2: 첫 단언(s1)만 있으면 값을 붙들어 둔 구현도 통과한다. 갈아끼운 뒤를 같이 본다.
    // 문항 5: start()가 stream.get()을 한 번만 부르게 되돌리면 둘째 대기가 시한을 다 쓰고 빨간불이다.
    @Test
    void 방송_번호를_갈아끼우면_다음_요약부터_새_번호가_나간다() throws Exception {
        CollectionMetrics metrics = new CollectionMetrics();
        AtomicReference<String> stream = new AtomicReference<>("s1");

        try (LogCaptor captor = new LogCaptor();
             SummaryLogger ignored = SummaryLogger.start(stream::get, metrics, Heartbeat.idleForTest(),
                     Duration.ofMillis(100), () -> 0L, TestPersistence.disabledPersister(),
                     () -> 0L, ArchiveCounters.NONE)) {
            awaitLine(captor, "chat.summary stream=s1 ");
            assertThat(captor.messages()).anyMatch(m -> m.startsWith("chat.summary stream=s1 "));

            stream.set("s2");

            awaitLine(captor, "chat.summary stream=s2 ");
            assertThat(captor.messages())
                    .as("시작 시점의 번호를 붙들면 끝난 방송 번호를 단 줄이 영영 나간다")
                    .anyMatch(m -> m.startsWith("chat.summary stream=s2 "));
        }
    }

    /**
     * <b>편지 경로에서 「몇을 걷었나」를 말하는 항.</b> 판정 줄의 {@code session=}은 러너
     * 자신의 세션 번호라 그 경로에서 늘 0이다 — 이 항이 없으면 판정 줄만 보고
     * 「이 프로세스는 아무것도 안 걷었다」로 읽힌다.
     */
    // 문항 2: contains("registrySessions=")이면 박아 둔 문자열도 통과한다. 값을 본다.
    // 문항 4: registrySessions=3만 보면 <b>session=을 그 값으로 덮은</b> 구현도 통과한다 —
    //         두 항이 서로 다른 값을 든다는 것을 한 문자열로 못박는다.
    @Test
    void 판정_줄이_편지로_연_세션_수를_싣는다() {
        String line = SummaryLogger.renderVerdict(0L, 3L, new CollectionMetrics().verdict(), null,
                0L, 0L, 0L, 0L, 0L, ArchiveCounters.NONE);

        assertThat(line).startsWith("chat.session.verdict session=0 registrySessions=3 ");
    }

    private static void awaitLine(LogCaptor captor, String prefix) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (captor.messages().stream().noneMatch(m -> m.startsWith(prefix))
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    /** 요약은 ping 스케줄러가 아니라 자기 스레드에서 나가야 한다. */
    @Test
    void 요약은_ping_스레드에서_찍히지_않는다() throws Exception {
        CollectionMetrics metrics = new CollectionMetrics();

        try (LogCaptor captor = new LogCaptor();
             SummaryLogger logger = SummaryLogger.start(() -> "s1", metrics, Heartbeat.idleForTest(),
                     Duration.ofMillis(100), () -> 0L, TestPersistence.disabledPersister(), () -> 0L, ArchiveCounters.NONE)) {
            Thread.sleep(400);

            assertThat(logger.emitterThreadNames())
                    .as("ping 스케줄러에 요약을 얹는 순간 8/1이 재현된다")
                    .containsExactly("chzzk-summary");
            assertThat(captor.messages()).anyMatch(m -> m.startsWith("chat.summary "));
        }
    }
}
