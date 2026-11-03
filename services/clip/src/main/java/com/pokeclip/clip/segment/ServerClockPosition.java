package com.pokeclip.clip.segment;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.segment.RoundRows.Round;
import com.pokeclip.clip.segment.RoundRows.Row;
import com.pokeclip.ledger.timeline.LedgerRow;
import com.pokeclip.ledger.timeline.LedgerTimeline;
import com.pokeclip.ledger.timeline.Place;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * 마크가 저장할 카드 위치({@code start_pts} 재생 위치 축 ms)를 회차 귀속 행에서 <b>행별로</b> 고르는 한 곳(POK-196 · 설계 r7
 * 8.2). 라이브 웹 마크 · OBS 마크(서버 시계 T)와 지난 방송 웹 마크(재생 위치 P)가 같이 쓴다.
 *
 * <p><b>왜 「T − 기준점」 이 아닌가.</b> 출시 이미지가 쓴 판 NULL 행은 회차 안 {@code PDT − start_pts} 가 상수가 아니다 —
 * 위치는 앞 위치 + 앞 길이로 쌓이고(비귀속 행 길이도 더해진다 · 1.5초 넘는 틈에서만 drift 를 더한다), PDT 는 앞 PDT + 길이와
 * 파일명 시각 가운데 큰 쪽이라 래칫으로 앞서거나 파일명 시각을 따라 뛴다({@code indexer.go:820-864} ·
 * {@code store.go:461-509}). 그래서 회차 상수 하나를 빼면 다른 조각이 나온다. 행마다 그 행의 축 시작에서 거리를 재
 * 그 행의 {@code start_pts} 에 더하면 축이 서로 맞는다 — 판 NULL 의 거리는 파일 머리 기준이고 그 행의 {@code start_pts} 도
 * 파일 머리 축이며, 판 2 는 둘 다 영상 시작 축이다.
 *
 * <p>행 고르기는 공통 모듈 F({@link LedgerTimeline#locate} — 시작 ≤ t 인 행 가운데 seq 최대 · 겹침은 나중 조각)를 쓴다. 회차
 * 행 열은 {@link RoundRows}가 준다(비귀속 행 · 앞 방송 꼬리 · 하한 거부 행이 없다).
 */
@Component
public class ServerClockPosition {

    private static final Logger log = LoggerFactory.getLogger(ServerClockPosition.class);

    /** 마크가 들어온 문. 신호 {@code clip.mark.positioned source=} 값이다. */
    public enum Source {
        OBS("obs"), WEB_LIVE("web_live"), WEB_PAST("web_past");

        private final String value;

        Source(String value) {
            this.value = value;
        }
    }

    /**
     * 고른 위치. {@code axis}는 고른 행을 잰 축({@code wall} · {@code pdt}). {@code extended}는 마지막 귀속 행 끝을 넘어
     * 라이브 머리로 늘렸다, {@code folded}는 행 밖이라 가장자리로 접었다.
     */
    public record Position(long cardMs, long seq, String axis, boolean extended, boolean folded) {
    }

    private final RoundRows rounds;

    ServerClockPosition(RoundRows rounds) {
        this.rounds = rounds;
    }

    /**
     * 서버 시계 T(라이브 · OBS 마크). 회차 귀속 행(옛 방송은 시간창 행)이 하나도 없으면 비어 있다 — 부르는 쪽이 503
     * {@code timeline_not_ready}로 바꾼다.
     */
    public Optional<Position> ofServerClock(Broadcast broadcast, Instant t, Source source) {
        return position(broadcast, source, (round, rows) -> fromServerClock(rows, t.toEpochMilli()));
    }

    /**
     * 지난 방송 재생 위치 P(목록 PDT 축 절대 시각 — 웹이 보낸 {@code positionMs} + 기준점). 기준점이 같으니 웹의
     * 「목록 PDT − 기준점」 을 정확히 되살린다.
     */
    public Optional<Position> ofPlayback(Broadcast broadcast, Instant p) {
        return position(broadcast, Source.WEB_PAST, (round, rows) -> fromPlayback(round, rows, p.toEpochMilli()));
    }

    private Optional<Position> position(Broadcast broadcast, Source source,
                                        BiFunction<Round, List<Row>, Optional<Position>> pick) {
        Optional<Round> round = rounds.roundOf(broadcast);
        if (round.isEmpty()) {
            return Optional.empty();
        }
        Optional<Position> position = pick.apply(round.get(), rounds.rowsOf(round.get()));
        // streamId 는 방송 번호(API 경로에 드러나는 값)다 — 계약4 의 SRT streamid(접속 비밀)가 아니다.
        position.ifPresent(p -> log.info(
                "clip.mark.positioned streamId={} source={} axis={} seq={} rounds={} extended={} folded={}",
                broadcast.getStreamId(), source.value, p.axis(), p.seq(),
                round.get() instanceof RoundRows.Members ? "members" : "time_window", p.extended(), p.folded()));
        return position;
    }

    /**
     * 6-6-9 섞인 floor. 판 NULL 행 가운데 {@code start_wall_utc ≤ T} 인 seq 최대 행, 판 2 행 가운데
     * {@code playback_pdt ≤ T} 인 seq 최대 행, 둘 다 있으면 seq 큰 쪽(k). 위치 = {@code start_pts(k) + (T − k 의 축 시작)}.
     * 거리가 k 길이를 넘으면 k 가 마지막 귀속 행일 때(라이브 머리 — 누른 순간의 조각은 아직 장부에 없다) 그대로 늘리고, 다음
     * 귀속 행이 있으면 채팅 옛 행 식처럼 k 끝으로 접는다(그 자리는 카드 장면에서 뒤 행 첫 화면이 된다). T 가 첫 귀속 행 앞이면
     * 첫 귀속 행 시작으로 접는다 — 스트림 전체 위치 0 으로 두면 회차 floor 가 없어진다.
     *
     * @param rows 회차 행, seq 오름차순
     */
    static Optional<Position> fromServerClock(List<Row> rows, long t) {
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Optional<Row> wall = floorOf(rows, r -> r.timingVersion() == null, Row::wallMs, t);
        Optional<Row> pdt = floorOf(rows, r -> r.v2() && r.pdtMs() != null, Row::pdtMs, t);
        Optional<Row> chosen = wall.isEmpty() ? pdt
                : pdt.isEmpty() ? wall
                : Optional.of(wall.get().seq() > pdt.get().seq() ? wall.get() : pdt.get());
        if (chosen.isEmpty()) {
            Row first = rows.get(0);
            return Optional.of(new Position(first.startPtsMs(), first.seq(), axisOf(first), false, true));
        }
        Row k = chosen.get();
        long into = t - (k.v2() ? k.pdtMs() : k.wallMs());
        if (into < k.durationMs()) {
            return Optional.of(new Position(k.startPtsMs() + into, k.seq(), axisOf(k), false, false));
        }
        if (k.seq() == rows.get(rows.size() - 1).seq()) {
            return Optional.of(new Position(k.startPtsMs() + into, k.seq(), axisOf(k), true, false));
        }
        return Optional.of(new Position(k.startPtsMs() + k.durationMs(), k.seq(), axisOf(k), false, true));
    }

    /**
     * 지난 방송: 회차 행 가운데 목록 시각 ≤ P 인 seq 최대 행 k → {@code start_pts(k) + (P − 목록 시각(k))}. 가장자리 셋 — 거리가
     * k 길이를 넘고 다음 행 k′ 가 있으면 {@code start_pts(k′)}(뒤 조각 첫 화면 · 카드 장면 함수의 틈 처분과 같은 자리), 마지막 행
     * 끝 뒤면 {@code start_pts(k) + dur − 1}(마지막 화면 쪽 매체 자리), 첫 행 앞이면 {@code start_pts(첫 행)}.
     *
     * <p>목록 시각은 {@code playback_pdt} 다. <b>회차 귀속 행은 PDT 가 빈 행을 floor 에 넣지 않는다</b> — 귀속 행에는 늘 있으나 DB
     * 제약은 아니라서, 빈 행을 벽시각으로 끼우면 두 축이 한 열에 섞여 seq 가 큰 그 행이 앞 행의 자리를 가로챈다. 회차 귀속 행에
     * PDT 가 하나도 없으면 위치가 없다(부르는 쪽이 503). <b>머리 세션 행이 없는 옛 방송(시간창)만</b> 빈 행(세션 장부 전의 옛 행)을
     * 기준점과 같은 식으로 벽시각으로 본다 — 웹이 뺀 기준점이 {@code COALESCE(playback_pdt, start_wall_utc) − start_pts} 라 같은
     * 축으로 되살려야 왕복이 정확하다.
     */
    static Optional<Position> fromPlayback(Round round, List<Row> rows, long p) {
        List<Row> listed = round instanceof RoundRows.Members
                ? rows.stream().filter(r -> r.pdtMs() != null).toList()
                : rows;
        if (listed.isEmpty()) {
            return Optional.empty();
        }
        Place place = LedgerTimeline.locate(ledger(listed, ServerClockPosition::listedMs), p);
        return Optional.of(switch (place) {
            case Place.Media m -> {
                Row k = rowOf(listed, m.seq());
                yield new Position(k.startPtsMs() + m.offsetMs(), k.seq(), "pdt", false, false);
            }
            case Place.Gap g -> {
                Row next = rowOf(listed, g.afterSeq());
                yield new Position(next.startPtsMs(), next.seq(), "pdt", false, true);
            }
            case Place.AfterLast a -> {
                Row last = listed.get(listed.size() - 1);
                yield new Position(last.startPtsMs() + Math.max(0, last.durationMs() - 1), last.seq(), "pdt", false,
                        true);
            }
            case Place.BeforeFirst b -> new Position(listed.get(0).startPtsMs(), listed.get(0).seq(), "pdt", false,
                    true);
        });
    }

    /**
     * 그 판의 행만 그 축으로 세워 F 로 floor 행 k 를 고른다. 이어짐 칸은 거짓으로 둔다 — 여기서는 k 만 쓰고(F 가 k 끝 뒤를
     * 공백으로 내면 그 앞 행이 k 다), 접기는 이어짐이 아니라 다음 귀속 행 유무로 정한다.
     */
    private static Optional<Row> floorOf(List<Row> rows, Predicate<Row> on, ToLongFunction<Row> axis, long t) {
        List<Row> candidates = rows.stream().filter(on).toList();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        Place place = LedgerTimeline.locate(ledger(candidates, axis), t);
        return switch (place) {
            case Place.Media m -> Optional.of(rowOf(candidates, m.seq()));
            case Place.Gap g -> Optional.of(rowOf(candidates, g.beforeSeq()));
            case Place.AfterLast a -> Optional.of(candidates.get(candidates.size() - 1));
            case Place.BeforeFirst b -> Optional.empty();
        };
    }

    private static List<LedgerRow> ledger(List<Row> rows, ToLongFunction<Row> axis) {
        return rows.stream().map(r -> new LedgerRow(r.seq(), axis.applyAsLong(r), r.durationMs(), false)).toList();
    }

    private static long listedMs(Row row) {
        return row.pdtMs() != null ? row.pdtMs() : row.wallMs();
    }

    private static String axisOf(Row row) {
        return row.v2() ? "pdt" : "wall";
    }

    private static Row rowOf(List<Row> rows, long seq) {
        for (Row row : rows) {
            if (row.seq() == seq) {
                return row;
            }
        }
        throw new IllegalStateException("F 가 낸 seq 가 행 열에 없다: " + seq);
    }
}
