package com.pokeclip.clip.broadcast.reaper;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.broadcast.BroadcastStatus;
import com.pokeclip.clip.broadcast.intake.EndedListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

/**
 * 종료 편지를 놓친 방송을 치운다(POK-244). <b>마지막 살아있는 신호 뒤로 유예만큼 조용하면</b>
 * {@code live}를 {@code ended}로 굳힌다.
 *
 * <p><b>왜 있나.</b> 종료 편지를 못 받은 방송은 영원히 {@code live}로 남고 치우는 장치가 없었다
 * (POK-218이 찾았다). 라이브 목록에 죽은 방송이 쌓이고, 수집기는 그 목록을 보고 계정당 셋뿐인
 * 치지직 세션 자리를 계속 붙잡는다. 편지 발행(계약9)은 1번 몫이고 생겨도 유실될 수 있다 —
 * 안전망은 우리 표에 있어야 한다.
 *
 * <p><b>살아있는 신호는 1번 조각 장부({@code stream_segments})의 마지막 조각 시각이다.</b>
 * 조각이 들어오는 한 영상이 있고, 영상이 있으면 클립을 만들 수 있으니 「살아있다」의 뜻과 같다.
 * 조각이 하나도 없으면 방송 시작 시각으로 잰다. 둘 중 <b>늦은 쪽</b>을 쓴다 — 「살아있는 방송은
 * 절대 안 닫힌다」가 「죽은 방송을 빨리 치운다」보다 우선이다. 수집기의 채팅·방송 정보 시각은
 * 안 본다: 그 표는 수집기가 <b>이 서버의 live 목록을 보고</b> 채우므로 근거로 쓰면 순환이고,
 * 채팅은 송출이 끊긴 뒤에도 얼마간 이어진다.
 *
 * <p><b>기존 규칙을 하나도 안 건드린다.</b>
 * <ul>
 *   <li>{@code last_sequence}를 <b>올리지 않는다</b> — 닫은 뒤 진짜 {@code ended}(더 높은 순서)가
 *       늦게 오면 {@code Broadcast.applyEnded}가 정상 통과해 종료 시각을 편지 값으로 정정하고,
 *       더 낮은 순서의 {@code started}는 그대로 낡은 편지로 걸러진다(POK-82).</li>
 *   <li>{@code broadcast_events}에 <b>아무것도 넣지 않는다</b> — 그 표의 {@code event_id} UNIQUE가
 *       멱등의 유일한 축이라 합성 편지를 끼우면 뒤에 오는 실제 편지가 DUPLICATE로 삼켜질 수 있다.
 *       이 장치의 멱등은 「행 락을 잡고 아직 {@code live}인가」로 충분하다.</li>
 *   <li>화면 통보는 편지 경로와 같은 훅({@link EndedListener})을 <b>커밋 뒤에</b> 부른다 —
 *       종료 원인이 둘이어도 화면이 받는 것은 하나다.</li>
 * </ul>
 *
 * <p><b>{@code ended_at}은 마지막 신호 시각이다</b>(지금 시각이 아니다). 방송은 그 근처에서 끝났고,
 * VOD 보관 기한도 거기서 센다. 편지가 뒤늦게 오면 그 값으로 덮인다.
 *
 * <p><b>{@code @Component}가 아니다.</b> {@code Duration}·{@code Supplier<Instant>}는 스프링이 못
 * 만든다 — {@link ReaperConfiguration}이 조립한다(수집기 {@code EndedStreamSweeper}와 같은 모양).
 */
public class StaleBroadcastReaper {

    private static final Logger log = LoggerFactory.getLogger(StaleBroadcastReaper.class);

    /**
     * live 줄마다 장부의 마지막 조각 한 행. {@code MAX(start_wall_utc)}가 아니라 <b>{@code seq} 최대 행</b>이다 —
     * 장부의 PK가 {@code (stream_id, seq)}라 뒤에서 한 줄만 읽고 끝나고, 방송 하나에 조각이 수만 개라도
     * 회차 비용이 방송 수에 비례한다. {@code seq}는 단조 증가라 최신 조각과 같다.
     *
     * <p>{@code status}는 리터럴이다({@code BroadcastRepository.findLive}와 같은 이유).
     * 🔴 <b>장부의 열쇠는 {@code broadcasts.stream_id}다</b> — 세그먼트 조회({@code StreamSegmentReader})와
     * 같은 축(물리 축)이고, POK-233이 회차 좌표로 갈아탈 때 둘을 같이 옮겨야 한다.
     */
    static final String LIVE_WITH_LAST_SEGMENT = """
            SELECT b.stream_id, b.started_at, b.created_at, s.start_wall_utc AS last_segment_at
              FROM broadcasts b
              LEFT JOIN LATERAL (SELECT start_wall_utc FROM stream_segments s
                                  WHERE s.stream_id = b.stream_id
                                  ORDER BY seq DESC LIMIT 1) s ON true
             WHERE b.status = 'live'""";

    /** 락을 잡은 뒤 다시 읽는 한 줄 — 후보를 고른 뒤 락을 잡기 전에 들어온 조각을 본다. */
    static final String LAST_SEGMENT_OF = """
            SELECT start_wall_utc FROM stream_segments WHERE stream_id = ? ORDER BY seq DESC LIMIT 1""";

    private final JdbcTemplate jdbc;
    private final BroadcastRepository broadcasts;
    private final TransactionTemplate tx;
    private final EndedListener endedListener;
    private final Duration grace;
    private final Supplier<Instant> clock;

    /** @param endedListener 화면 통보 훅. null이면 통보 없이 표만 굳힌다(편지 러너와 같은 규약) */
    public StaleBroadcastReaper(JdbcTemplate jdbc, BroadcastRepository broadcasts, TransactionTemplate tx,
                                EndedListener endedListener, Duration grace, Supplier<Instant> clock) {
        this.jdbc = jdbc;
        this.broadcasts = broadcasts;
        this.tx = tx;
        this.endedListener = endedListener;
        this.grace = grace;
        this.clock = clock;
    }

    /**
     * <b>Throwable까지 잡는다.</b> {@code @Scheduled}는 한 번 던지면 그 뒤 주기가 영영 안 돈다 —
     * 안전망이 조용히 죽는 것이 이 장치의 최악이다.
     */
    @Scheduled(fixedDelayString = "${pokeclip.broadcast.reaper.interval}",
            initialDelayString = "${pokeclip.broadcast.reaper.interval}")
    public void tick() {
        try {
            reapOnce();
        } catch (Throwable t) {
            log.warn("broadcast.reaper.failed causeType={}", t.getClass().getSimpleName());
        }
    }

    /** 한 회차. 닫은 방송 수를 준다(시험용 손잡이). */
    int reapOnce() {
        Instant now = clock.get();
        Instant deadline = now.minus(grace);
        List<Candidate> stale = jdbc.query(LIVE_WITH_LAST_SEGMENT, (rs, i) -> {
            Timestamp segment = rs.getTimestamp("last_segment_at");
            Timestamp started = rs.getTimestamp("started_at");
            Timestamp created = rs.getTimestamp("created_at");
            // created_at은 「우리가 처음 안 시각」이지 살아있는 신호가 아니다 — 시작·조각이 둘 다
            // 없을 때(V201상 live 줄에는 없는 일이다)만 NPE 대신 쓰는 바닥이다.
            Instant signal = latest(segment, started);
            return new Candidate(rs.getString("stream_id"),
                    signal != null ? signal : created.toInstant(), segment != null);
        }).stream().filter(c -> !c.lastSignalAt().isAfter(deadline)).toList();

        int ended = 0;
        for (Candidate candidate : stale) {
            // 방송마다 트랜잭션을 따로 연다 — 하나가 실패해도 나머지는 닫힌다.
            Candidate closed;
            try {
                closed = tx.execute(status -> endIfStillStale(candidate.streamId(), deadline));
            } catch (RuntimeException e) {
                log.warn("broadcast.reaper.end_failed streamId={} causeType={}",
                        candidate.streamId(), e.getClass().getSimpleName());
                continue;
            }
            if (closed == null) {
                continue;   // 후보를 고른 뒤 편지가 먼저 닫았거나 새 조각이 들어왔다 — 그쪽이 정본이다
            }
            ended++;
            log.info("broadcast.reaper.ended streamId={} lastSignalAt={} basis={} silence={}",
                    closed.streamId(), closed.lastSignalAt(),
                    closed.fromSegment() ? "SEGMENT" : "BROADCAST_START",
                    Duration.between(closed.lastSignalAt(), now));
            notifyEnded(closed.streamId());
        }
        if (ended > 0) {
            log.info("broadcast.reaper.swept live={} ended={}", stale.size(), ended);
        }
        return ended;
    }

    /**
     * 🔴 <b>락을 잡은 뒤 신호를 다시 읽는다</b>(봇 리뷰 1판 codex P1). 후보 목록은 락 밖에서 만든 스냅샷이라,
     * 그 뒤 락을 잡기 전에 새 조각이 들어오면 스냅샷은 낡았는데 줄은 아직 {@code live}다 — 스냅샷대로 닫으면
     * <b>살아있는 방송을 닫는다.</b> 앞 후보의 락 대기가 길수록 그 창이 넓다. 다시 읽은 신호가 유예 안이면 안 닫고,
     * 닫을 때의 {@code ended_at}도 다시 읽은 값이다.
     *
     * @return 닫았으면 닫은 근거(다시 읽은 신호), 아니면 null
     */
    private Candidate endIfStillStale(String streamId, Instant deadline) {
        Broadcast broadcast = broadcasts.findByStreamIdForUpdate(streamId).orElse(null);
        if (broadcast == null || broadcast.getStatus() != BroadcastStatus.LIVE) {
            return null;
        }
        Instant lastSegment = jdbc.query(LAST_SEGMENT_OF, rs -> rs.next() ? rs.getTimestamp(1).toInstant() : null, streamId);
        Instant signal = latest(lastSegment, broadcast.getStartedAt());
        if (signal == null) {
            signal = broadcast.getCreatedAt();
        }
        if (signal.isAfter(deadline)) {
            log.info("broadcast.reaper.revived streamId={} lastSignalAt={}", streamId, signal);
            return null;
        }
        broadcast.endBySilence(signal);
        return new Candidate(streamId, signal, lastSegment != null);
    }

    /** 통보 실패가 다음 방송의 굳히기를 막으면 안 된다 — 편지 러너의 {@code notifyEnded}와 같은 폭. */
    private void notifyEnded(String streamId) {
        if (endedListener == null) {
            return;
        }
        try {
            endedListener.broadcastEnded(streamId);
        } catch (RuntimeException e) {
            log.warn("broadcast.reaper.ended_listener_failed streamId={} causeType={}",
                    streamId, e.getClass().getSimpleName());
        }
    }

    /** 둘 중 늦은 쪽. 둘 다 없으면 null. */
    private static Instant latest(Timestamp... candidates) {
        Instant best = null;
        for (Timestamp t : candidates) {
            if (t != null && (best == null || t.toInstant().isAfter(best))) {
                best = t.toInstant();
            }
        }
        return best;
    }

    private static Instant latest(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return b == null || a.isAfter(b) ? a : b;
    }

    private record Candidate(String streamId, Instant lastSignalAt, boolean fromSegment) {
    }
}
