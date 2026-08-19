package com.pokeclip.chat.collector.observe;

import com.pokeclip.chat.collector.archive.ArchiveCounters;
import com.pokeclip.chat.collector.persist.PersistCounters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 30초마다 요약 한 줄. <b>자기 전용 스케줄러를 갖는다.</b>
 *
 * <p>이것을 ping 스케줄러에 얹고 싶어지는 순간이 2026-08-01 사고를 재현하는
 * 순간이다 — 그때 ping이 다른 일과 스레드를 공유하다 74초간 못 나갔다.
 * 요약은 로그 I/O라 언제든 느려질 수 있고, 느려지면 ping이 뒤에 줄 선다.
 *
 * <p>개별 메시지 로그는 0줄이다. 여기 나가는 것도 세는 값뿐이고
 * 본문·작성자 식별자·닉네임은 어느 필드에도 없다.
 */
public final class SummaryLogger implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SummaryLogger.class);

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "chzzk-summary");   // ping 스레드와 다른 이름
                t.setDaemon(true);
                return t;
            });

    private final Set<String> emitterThreadNames = new ConcurrentSkipListSet<>();

    private SummaryLogger() { }

    /**
     * @param stream       어느 방송의 줄인가. <b>값이 아니라 공급자로 받는다</b> — 같은
     *                     스트리머의 새 방송이 오면 소켓은 그대로 두고 번호만 갈아끼므로
     *                     (retarget), 붙들어 두면 그 뒤 줄이 전부 끝난 방송 번호를 단다
     * @param sinkFailures 삼킨 싱크 예외의 수를 읽어 오는 곳. ChatSession이 들고
     *                     있는 값이라 공급자로 받는다 — 세션은 재수립마다 바뀐다
     * @param counters     적재 카운터 묶음(persisted·conflicts·poisoned) —
     *                     공급자 나열이면 자리바꿈 실수를 타입이 못 잡는다
     * @param dropped      버퍼 상한 초과로 버린 수. 소유가 ChatBuffer라 따로 받는다
     * @param archive      아카이브 카운터 여섯 + runId 묶음. 아카이브가 꺼져 있으면 {@link ArchiveCounters#NONE}
     */
    public static SummaryLogger start(Supplier<String> stream, CollectionMetrics metrics,
                                      Heartbeat heartbeat, Duration period, LongSupplier sinkFailures,
                                      PersistCounters counters, LongSupplier dropped,
                                      ArchiveCounters archive) {
        SummaryLogger logger = new SummaryLogger();
        long periodMillis = period.toMillis();
        logger.scheduler.scheduleAtFixedRate(() -> {
            logger.emitterThreadNames.add(Thread.currentThread().getName());
            try {
                log.info("{}", render(stream.get(), metrics.snapshot(), heartbeat, sinkFailures.getAsLong(),
                        counters.persistedCount(), counters.conflictedCount(),
                        counters.poisonedCount(), dropped.getAsLong(), archive));
            } catch (RuntimeException e) {
                // 요약이 터져도 스케줄러는 계속 돈다. 여기서 예외가 밖으로 나가면
                // scheduleAtFixedRate가 조용히 멈춰 요약이 영영 안 나가고,
                // 그러면 수집이 멀쩡한지 아무도 못 본다.
                log.warn("chat.summary.render_failed causeType={}", e.getClass().getSimpleName());
            }
        }, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
        return logger;
    }

    /** 순수 함수라 스케줄러 없이도 검사할 수 있다. */
    public static String render(String stream, CollectionMetrics.Snapshot s, Heartbeat heartbeat,
                                long sinkFailures, long persisted, long conflicted, long poisoned,
                                long dropped, ArchiveCounters archive) {
        return "chat.summary"
                // <b>첫 항이다.</b> 스트리머가 여럿이면 30초마다 이 줄이 세션 수만큼 나가는데,
                // 이것이 없으면 <b>서로 구분되지 않는 줄 N개</b>가 된다 — 「일부만 안 걷힌다」를
                // 로그로 가르는 유일한 열쇠다. 옛 경로는 방송 번호를 몰라 none이다.
                + " stream=" + stream
                + " received=" + s.received()
                + " maxReceiveGap=" + duration(s.maxReceiveGap())
                + " lastReceivedAt=" + instant(s.lastReceivedAt())
                + " lastPingAt=" + instant(heartbeat.lastPingAt())
                + " maxPingGap=" + duration(heartbeat.maxPingGap())
                + " lastPongAt=" + instant(heartbeat.lastPongAt())
                + " maxPongGap=" + duration(heartbeat.maxPongGap())
                + " orderViolations=" + s.orderViolations()
                + " delayMin=" + duration(s.delayMin())
                + " delayMedian=" + duration(s.delayMedian())
                + " delayMax=" + duration(s.delayMax())
                + " system=" + s.systemEvents()
                + " decodeFailures=" + s.decodeFailures()
                + " sendFailures=" + heartbeat.sendFailureCount()
                + " callbackFailures=" + heartbeat.callbackFailureCount()
                + " sinkFailures=" + sinkFailures
                // 적재 관측 넷. received = persisted + conflicts + poisoned + dropped가
                // 어긋나면 이 줄이 첫 단서다 — 숫자만 싣는다. 본문·식별자는 없다.
                + " persisted=" + persisted
                + " conflicts=" + conflicted
                + " poisoned=" + poisoned
                + " dropped=" + dropped
                // 아카이브 관측 여섯. 등식 둘로 검산한다 —
                // received = archived + archiveBufferDropped (채팅 단위) ·
                // uploaded + pending + droppedObjects = 닫힌 창 수 (파일 단위).
                // 숫자만이다 — S3 키·raw는 어느 레벨에도 안 싣는다.
                //
                // 단서 둘을 같이 읽어야 한다. ① 아카이브가 꺼져 있으면(S3_BUCKET 빈 값 = 기본) 여섯 항이
                // 계속 0이라 첫째 등식이 안 맞는다 — 유실이 아니라 꺼짐이고, 시작 로그 chat.archive.disabled와
                // 판정 줄 archiveRunId=none이 그것을 말한다. ② 여기서 여섯 항을 따로 읽으므로 그 사이에
                // 업로드가 끝나면 uploaded는 옛 값·pending은 새 값이라 둘째 등식이 순간 1 모자라 보인다
                // (다음 줄에서 회복). 게터마다 락을 잡아도 연속 호출은 원자적이 아니다 — 정본은 판정 줄이고,
                // 그쪽은 아카이버가 닫힌 뒤라 움직이는 값이 없다.
                + " archived=" + archive.archivedCount()
                + " archiveBufferDropped=" + archive.archiveBufferDroppedCount()
                + " uploaded=" + archive.uploadedCount()
                + " pending=" + archive.pendingCount()
                + " droppedObjects=" + archive.droppedObjectsCount()
                + " droppedMessages=" + archive.droppedMessagesCount();
    }

    /**
     * 수집이 끝났을 때 한 줄. <b>30초 요약은 창 값이라 이 줄이 없으면 판정하려고
     * 20줄을 뒤져야 한다.</b>
     *
     * <p><b>값 항목이 전부 프로세스 생애 누계다. 아래가 전 항목이다</b> —
     * 빠진 항이 하나라도 있으면 "이 줄은 어느 경계인가"가 다시 열린다.
     *
     * <ul>
     *   <li><b>프로세스 누계</b>(지표를 세션마다 갈아 끼우지 않는다) — {@code received}·
     *       {@code lastReceivedAt}·{@code maxReceiveGap}·{@code orderViolations}·
     *       {@code delayMin}·{@code delayMedian}·{@code delayMax}·{@code delaySamples}·
     *       {@code system}·{@code decodeFailures}·{@code collectedFor}·
     *       {@code maxPingGap}·{@code maxPongGap}·{@code sendFailures}·
     *       {@code callbackFailures}·{@code sinkFailures}·
     *       {@code persisted}·{@code conflicts}·{@code poisoned}·{@code dropped}·
     *       {@code archived}·{@code archiveBufferDropped}·{@code uploaded}·{@code pending}·
     *       {@code droppedObjects}·{@code droppedMessages}·
     *       {@code reconnects}·{@code outage}
     *   <li>{@code archiveRunId}는 누계가 아니라 이 프로세스의 표식이다 — S3 키의
     *       {@code -{runId}.jsonl}과 같은 값이라 이걸로 이 프로세스가 올린 파일을 찾는다
     *   <li>{@code lastOutageFrom}·{@code lastOutageTo}는 누계가 아니라
     *       <b>마지막 절단 하나</b>의 시각이다. 누계로 읽으면 "이 시각부터 내내
     *       끊겨 있었다"가 된다. 한 번도 안 끊겼으면 둘 다 {@code none}이고,
     *       <b>{@code lastOutageFrom}만 있고 {@code lastOutageTo}가 {@code none}이면
     *       끊긴 채로 끝난 것이다</b> — 그때 {@code outage}는 판정 시각까지의
     *       하한이지 유실의 전부가 아니다
     *   <li>{@code session}은 경계가 아니라 몇 번째 세션의 판정인가다. <b>러너 자신의
     *       세션 번호라 편지 경로에서는 0이다</b> — 러너가 세션을 하나도 안 열기 때문이고,
     *       그 경로에서 「몇을 걷었나」를 말하는 것은 바로 아래 {@code registrySessions}다
     *   <li>{@code registrySessions}는 편지로 <b>실제로 세운</b> 세션의 누계다(닫힌 것도 센다).
     *       번호 갈아끼움은 안 센다 — 세션도 소켓도 그대로라 새로 선 것이 아니다
     *   <li>{@code reason}은 경계가 아니라 그 판정의 사유다
     * </ul>
     *
     * <p><b>{@code maxReceiveGap}은 누계지만 절단 구간을 빼고 잰다.</b> 안 빼면
     * 끊겨 있던 시간이 통째로 하나의 수신 공백이 되어 "한산했을 뿐"과 "끊겨
     * 있었다"가 같은 숫자로 보인다 — 한산한 것은 정상이고(방송을 꺼도 세션은
     * 안 끊긴다) 끊긴 것은 유실이다. 빼낸 시간은 {@code outage}가 든다.
     *
     * <p><b>{@code sinkFailures}도 {@code CollectionMetrics}가 걷어 올린다.</b>
     * 세는 주체가 {@code ChatSession}이라 세션과 함께 사라지는데, 여기서 그 세션
     * 값을 직접 읽으면 이 한 항만 경계가 다르다. 그러면 판정이 프로세스 종료
     * 1회로 옮겨지는 순간 앞 세션이 삼킨 프레임 수가 어디에도 안 남는다 —
     * {@code maxPingGap}과 같은 결함이다.
     *
     * <p>하트비트 값을 {@code Heartbeat}에서 직접 읽지 않는다 — 그 객체는 소켓마다
     * 새로 만들어져 <b>마지막 세션 값만</b> 든다. 세션이 끝날 때 걷은 것을 여기서 쓴다.
     *
     * <p><b>"방송 전체"가 아니다.</b> 세션은 방송이 아니라 계정에 붙어 방송을 꺼도
     * 안 끊기고(361초 실측), 방송 경계를 알려면 POK-82가 필요하다.
     *
     * <p>수립조차 못 했을 때도 나와야 하므로 static이다 — 그때는 SummaryLogger
     * 인스턴스가 아예 없다.
     *
     * <p>요약과 같은 규칙이다. 본문·작성자 식별자·닉네임·토큰은 어느 필드에도 없다.
     */
    public static void logFinalVerdict(long session, long registrySessions,
                                       CollectionMetrics.Verdict verdict,
                                       Object stopReason, long otherSessionsReceived,
                                       PersistCounters counters, long dropped,
                                       ArchiveCounters archive) {
        log.info("{}", renderVerdict(session, registrySessions, verdict, stopReason, otherSessionsReceived,
                counters.persistedCount(), counters.conflictedCount(), counters.poisonedCount(),
                dropped, archive));
    }

    /**
     * 순수 함수라 로그 없이도 검사할 수 있다.
     *
     * @param otherSessionsReceived {@code verdict} 밖의 세션들이 받은 채팅 수 — 스트리머
     *                              여럿을 동시에 걷을 때 {@code SessionRegistry}의 몫이다.
     *                              <b>등식의 좌변이라 반드시 합쳐야 한다</b>: 우변 넷
     *                              (persisted·conflicts·poisoned·dropped)은 공유 부품의
     *                              프로세스 누계인데 좌변만 세션별이라, 안 합치면 등식이
     *                              깨지는 것이 아니라 <b>「받은 게 없다」로 읽힌다.</b>
     *                              <b>더할 수 있는 것만 여기로 온다</b> — 최댓값(수신 공백·
     *                              ping/pong 간격)과 「마지막 하나」(절단 시각·지연 중앙값)는
     *                              합치면 조용히 틀린 숫자가 되므로 안 싣는다
     */
    public static String renderVerdict(long session, long registrySessions,
                                       CollectionMetrics.Verdict v,
                                       Object stopReason, long otherSessionsReceived,
                                       long persisted, long conflicted, long poisoned, long dropped,
                                       ArchiveCounters archive) {
        return "chat.session.verdict"
                // 첫 항이다. 줄을 세션 단위로 고르는 사람도 도구도 여기서 갈린다.
                // <b>편지 경로에서는 이 러너가 연 세션이 없어 0이다</b> — 세션 번호는
                // 세션별 줄(chat.session.ended)이 든다.
                + " session=" + session
                // <b>편지 경로에서 이 프로세스가 몇을 걷었는지는 여기 하나에만 있다.</b>
                // 위 session=은 러너 자신의 것이라 그 경로에서 늘 0이고, 아래 received는
                // 세션 수가 아니라 채팅 수다 — 이 항이 없으면 "세션 0개"로 읽힌다.
                // <b>더할 수 있는 값이라</b> 실을 수 있다(최댓값·「마지막 하나」는 못 싣는다).
                + " registrySessions=" + registrySessions
                + " received=" + (v.totalReceived() + otherSessionsReceived)
                + " collectedFor=" + duration(v.totalCollectedFor())
                + " lastReceivedAt=" + instant(v.lastReceivedAt())
                + " maxReceiveGap=" + duration(v.maxReceiveGap())
                + " maxPingGap=" + duration(v.maxPingGap())
                + " maxPongGap=" + duration(v.maxPongGap())
                + " orderViolations=" + v.orderViolations()
                + " delayMin=" + duration(v.delayMin())
                + " delayMedian=" + duration(v.delayMedian())
                + " delayMax=" + duration(v.delayMax())
                + " delaySamples=" + v.delaySamples()
                + " system=" + v.systemEvents()
                + " decodeFailures=" + v.decodeFailures()
                + " sendFailures=" + v.sendFailures()
                + " callbackFailures=" + v.callbackFailures()
                + " sinkFailures=" + v.sinkFailures()
                // 적재 관측 넷(프로세스 누계). 러너가 판정 직전에 퍼시스터를 닫아
                // (stop()·영구 정지 둘 다) 마지막 flush분까지 여기 실린다 — 수동 검증
                // 등식 received = persisted + conflicts + poisoned + dropped는 이 줄로
                // 검산한다. 잔여 한계: DB 장애 중 종료면 shutdown_left 잔량은 어느
                // 항에도 안 실려 등식이 그만큼 안 닫힌다 — 그 건수는
                // chat.persist.shutdown_left 로그가 든다. "항상 닫힌다"고 읽지 마라.
                + " persisted=" + persisted
                + " conflicts=" + conflicted
                + " poisoned=" + poisoned
                + " dropped=" + dropped
                // 아카이브 관측 여섯(프로세스 누계). 러너가 판정 직전에 아카이버 닫기를 시작해
                // (beginClose) 시한 5초를 기다리므로 <b>대개</b> 마지막 창의 업로드까지 여기 실린다.
                // 등식 둘 — received = archived + archiveBufferDropped ·
                // uploaded + pending + droppedObjects = 닫힌 창 수 — 로 검산한다.
                // 잔여 한계: 마지막 flush가 5초 안에 못 끝나면(chat.archive.close_timeout이 단서)
                // 이 줄은 flush 도중 값이다 — 그 뒤 올라간 파일은 uploaded에 안 실리고 pending에
                // 남아 있다. "항상 닫힌 뒤"라고 읽지 마라(persisted 쪽과 같은 단서).
                + " archived=" + archive.archivedCount()
                + " archiveBufferDropped=" + archive.archiveBufferDroppedCount()
                + " uploaded=" + archive.uploadedCount()
                + " pending=" + archive.pendingCount()
                + " droppedObjects=" + archive.droppedObjectsCount()
                + " droppedMessages=" + archive.droppedMessagesCount()
                // 이 runId로 S3에서 이 프로세스의 파일을 찾는다 — 키의 "-{runId}.jsonl" 부분이다.
                // 재시작이 잦으면 같은 분에 파일이 여럿인데, 어느 것이 이 프로세스 것인지는 이 값뿐이다.
                + " archiveRunId=" + archive.runId()
                // 끊겼다 붙은 횟수와 그동안 놓친 시간. 위 maxReceiveGap이 절단을
                // 빼고 재므로, 이 둘이 없으면 유실 구간이 어느 항에도 안 남는다.
                + " reconnects=" + v.reconnects()
                + " outage=" + duration(v.totalOutage())
                // PRD 완료 조건: "끊긴 시각·복구 시각 두 값". 누적 시간만으로는
                // "언제 놓쳤나"를 못 찾는다 — 영상과 대조하려면 시각이 필요하다.
                + " lastOutageFrom=" + instant(v.lastOutageFrom())
                + " lastOutageTo=" + instant(v.lastOutageTo())
                // 왜 끝났는지가 없으면 "정상 종료"와 "조용히 끊겼다"가 같은 줄이 된다.
                + " reason=" + (stopReason == null ? "SHUTDOWN" : stopReason);
    }

    public Set<String> emitterThreadNames() { return Set.copyOf(emitterThreadNames); }

    private static String duration(Duration d) {
        long millis = d.toMillis();
        return millis < 1_000 ? millis + "ms" : (millis / 100) / 10.0 + "s";
    }

    /** 한 건도 못 받았으면 시각이 없다. 0을 찍으면 1970년으로 읽힌다. */
    private static String instant(Instant at) {
        return at == null ? "none" : at.toString();
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
