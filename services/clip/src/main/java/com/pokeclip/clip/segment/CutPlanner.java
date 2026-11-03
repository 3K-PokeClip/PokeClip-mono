package com.pokeclip.clip.segment;

import com.pokeclip.clip.recipe.RecipeDocument;
import com.pokeclip.clip.segment.CutDecision.End;
import com.pokeclip.clip.segment.CutDecision.Gap;
import com.pokeclip.clip.segment.CutDecision.LedgerNeighbor;
import com.pokeclip.clip.segment.CutDecision.NotReady;
import com.pokeclip.clip.segment.CutDecision.Prime;
import com.pokeclip.clip.segment.CutDecision.PrimeSkip;
import com.pokeclip.clip.segment.CutDecision.Ready;
import com.pokeclip.clip.segment.CutDecision.Reason;
import com.pokeclip.clip.segment.CutDecision.RunHead;
import com.pokeclip.clip.segment.CutDecision.SubtitlePoint;
import com.pokeclip.clip.segment.CutDecision.SubtitleSpan;
import com.pokeclip.clip.segment.CutDecision.Unrenderable;
import com.pokeclip.ledger.timeline.LedgerRow;
import com.pokeclip.ledger.timeline.LedgerTimeline;
import com.pokeclip.ledger.timeline.Place;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 렌더 주문의 컷 판정 한 곳(POK-196 · 설계 r7 9.2 · 9.4) — 레시피 컷 {@code [in, out)}(편집기 출처 벽시각)을 회차 귀속 행 위
 * 좌표 · 자막 끝점 · 끝 소리 확보 · 디코더 준비 · 예상 길이로 옮기거나, 아직(409) · 종국 거절(422)로 가른다.
 *
 * <p><b>축은 늘 파일명 시각({@code start_wall_utc})이고 원점은 파일 머리다</b>(경계 표 5판 B6). 편집기 {@code /get} 이 파일명 시각
 * 축이라 판으로 가르지 않는다 — 판 2 의 PDT 는 {@code /get} 과 장 수준 잔차가 있고 판 NULL 의 PDT 는 래칫으로 앞선다.
 *
 * <p>판정 순서가 계약이다 — ① 범위(회차 행 없음 · 첫 행 앞 · 마지막 행 뒤) ② 틈 판정(<b>좌표로 옮기기 전</b> 원래 범위로 · cx-3)
 * ③ 좌표 ④ 영상 조각 없음 ⑤ 자막 끝점 ⑥ 올라감 ⑦ 끝 소리 확보({@link AudioCaptureRule}) · 디코더 준비 ⑧ 길이. 거절은 준비보다
 * 먼저 본다 — 확정 거절은 다시 물어도 같다. 끊긴 이웃은 모두 이어 붙인다(이음 방식 하나 — 틈 초과 거절 없음).
 *
 * <p>DB 를 읽지 않는다. 회차 행은 부르는 쪽이 주고, 장부 이웃(확보 후보 · 준비 후보)은 판정이 정한 행에 대해서만 {@link Ledger}
 * 로 묻는다 — 어느 행을 물을지가 판정 결과라서다.
 */
public final class CutPlanner {

    private static final Logger log = LoggerFactory.getLogger(CutPlanner.class);

    private CutPlanner() {
    }

    /** 판정 중에 묻는 장부 이웃. 둘 다 회차로 자르지 않는다(확보 · 준비는 회차 귀속과 무관 — 계약 6-6-8 · 설계 r7 9.4). */
    public interface Ledger {

        /** seq 다음 행(장부 PK 순). 끝 소리 확보 후보. */
        Optional<RoundRow> rowAfter(long seq);

        /**
         * 머리마다 장부 바로 앞 행({@code seq < h} 가운데 최대 — seq 는 조밀하다고 보장되지 않는다). 없는 머리는 맵에 없다.
         * 한 번에 묻는다.
         */
        Map<Long, LedgerNeighbor> rowsBefore(List<Long> heads);
    }

    /**
     * @param rows 그 방송 회차의 귀속 행, seq 오름차순(컷 범위와 양쪽 이웃을 덮어야 한다)
     * @param inMs 레시피 컷 시작(파일명 시각 epoch ms)
     * @param outMs 레시피 컷 끝(반개)
     * @param subtitles 레시피 자막(방송 절대축). 없으면 빈 목록
     * @param roundComplete 장부의 회차 완결 술어가 참이다({@code RoundRows.roundComplete}). 범위 판정(마지막 행 뒤 · 회차 행
     *        없음)의 종국 거절은 <b>이것만</b> 본다(계약 6-6-6 「녹화 밖」 · 설계 r7 9.2 1) — 되돌릴 수 없는 422 라서다
     * @param broadcastEnded clip 방송이 {@code ended} 다. 끝 소리 확보만 이것을 완결과 함께 본다(계약 6-6-8 5항). 침묵 정리는 회차
     *        완결 확인 없이 방송을 끝내므로 이 값으로 범위를 거절하면 아직 안 온 꼬리가 영구 422 가 된다
     * @param ledgerSectionStart 이 방송의 장부 구간 시작({@code ExpiredBroadcastReader} 식). null이면 앞 방송이 없다
     * @param now 지금 — 확보 후보 {@code failed} 문턱을 잰다
     */
    public record CutInput(String streamId, long recipeId, List<RoundRow> rows, long inMs, long outMs,
                           List<RecipeDocument.Segment> subtitles, boolean roundComplete, boolean broadcastEnded,
                           Instant ledgerSectionStart, Instant now) {
        public CutInput {
            rows = List.copyOf(rows);
            subtitles = List.copyOf(subtitles);
            if (outMs <= inMs) {
                throw new IllegalArgumentException("컷 끝(" + outMs + ")이 시작(" + inMs + ") 뒤가 아니다");
            }
        }
    }

    public static CutDecision plan(CutInput input, Ledger ledger) {
        List<RoundRow> rows = input.rows();
        if (rows.isEmpty()) {
            return input.roundComplete() ? reject(input, Reason.OUTSIDE_RECORDING) : new NotReady("not_yet_recorded");
        }
        List<LedgerRow> axis = axisOf(rows);
        Place a = LedgerTimeline.locate(axis, input.inMs());
        Place b = LedgerTimeline.locateEnd(axis, input.outMs());
        if (a instanceof Place.BeforeFirst) {
            return reject(input, Reason.OUTSIDE_RECORDING);
        }
        if (a instanceof Place.AfterLast || b instanceof Place.AfterLast) {
            return input.roundComplete() ? reject(input, Reason.OUTSIDE_RECORDING) : new NotReady("not_yet_recorded");
        }
        List<Gap> gaps = gapsOf(rows, a, b, input.inMs(), input.outMs());
        MediaCoord in = startOf(a);
        End out = endOf(b);
        int first = indexOf(rows, in.seq());
        int last = indexOf(rows, out.seq());
        if (first > last) {
            return reject(input, Reason.NO_FOOTAGE);
        }
        List<RoundRow> media = rows.subList(first, last + 1);
        List<SubtitleSpan> subtitles = subtitlesOf(axis, input, rows, media);
        if (!media.stream().allMatch(RoundRow::uploaded)) {
            return new NotReady("media_pending");
        }
        return finish(input, ledger, media, in, out, subtitles, gaps);
    }

    /** ⑦ 끝 소리 확보 · 디코더 준비 → ⑧ 길이 → 계획. */
    private static CutDecision finish(CutInput input, Ledger ledger, List<RoundRow> media, MediaCoord in, End out,
                                      List<SubtitleSpan> subtitles, List<Gap> gaps) {
        RoundRow tail = media.get(media.size() - 1);
        AudioCaptureRule.Outcome capture = AudioCaptureRule.decide(tail, out, () -> ledger.rowAfter(tail.seq()),
                input.roundComplete() || input.broadcastEnded(), input.now());
        if (capture instanceof AudioCaptureRule.Outcome.Wait) {
            return new NotReady("capture_pending");
        }
        if (capture instanceof AudioCaptureRule.Outcome.FailedSkip skip) {
            log.info("clip.render.capture_skipped streamId={} seq={} reason=failed ageMin={}",
                    input.streamId(), skip.row().seq(), skip.ageMinutes());
        }
        Optional<Primes> primes = primesOf(media, ledger, input.ledgerSectionStart());
        if (primes.isEmpty()) {
            return new NotReady("prime_pending");
        }
        long lengthMs = LedgerTimeline.lengthMs(input.inMs(), input.outMs(),
                gaps.stream().map(Gap::inCutMs).toList());
        Optional<RoundRow> attached = capture instanceof AudioCaptureRule.Outcome.Attach attach
                ? Optional.of(attach.row()) : Optional.empty();
        Ready ready = new Ready(in, out, media, subtitles, lengthMs, attached, primes.get().primes(),
                primes.get().skips(), gaps, primes.get().heads());
        log.info("clip.render.cut_planned streamId={} recipeId={} axis=wall gaps={} joinedMs={} lengthMs={} runHeads={}",
                input.streamId(), input.recipeId(), describeGaps(gaps), input.outMs() - input.inMs() - lengthMs,
                lengthMs, describeHeads(primes.get().heads()));
        return ready;
    }

    /** 편집기 출처 축 — 이 판정이 시각을 읽는 단 한 자리. */
    private static long axisMs(RoundRow row) {
        return row.wallMs();
    }

    private static List<LedgerRow> axisOf(List<RoundRow> rows) {
        return rows.stream()
                .map(r -> new LedgerRow(r.seq(), axisMs(r), r.durationMs(), r.continuesPrevious()))
                .toList();
    }

    /**
     * ② 원래 범위가 걸친 끊긴 이웃(J 거짓)마다 자리 · 전체 틈 · 컷 안 몫. 범위는 시작 쪽 틈의 앞 행부터 끝 쪽 틈의 뒤 행까지다 —
     * 좌표로 옮긴 뒤 범위로 재면 머리 · 꼬리 틈이 사라진다(cx-3). 이어진 이웃 사이 장부 틈(B7)은 F 가 뒤 행 머리로 내므로 틈이
     * 아니다.
     */
    private static List<Gap> gapsOf(List<RoundRow> rows, Place a, Place b, long inMs, long outMs) {
        int from = indexOf(rows, a instanceof Place.Gap g ? g.beforeSeq() : ((Place.Media) a).seq());
        int to = indexOf(rows, b instanceof Place.Gap g ? g.afterSeq() : ((Place.Media) b).seq());
        List<Gap> gaps = new ArrayList<>();
        for (int i = from + 1; i <= to; i++) {
            RoundRow p = rows.get(i - 1);
            RoundRow q = rows.get(i);
            if (q.continuesPrevious()) {
                continue;
            }
            long pEnd = axisMs(p) + p.durationMs();
            long inCut = Math.max(0, Math.min(axisMs(q), outMs) - Math.max(pEnd, inMs));
            gaps.add(new Gap(p.seq(), q.seq(), placeOf(p, q, a, b), axisMs(q) - pEnd, inCut));
        }
        return gaps;
    }

    private static Gap.Place placeOf(RoundRow p, RoundRow q, Place a, Place b) {
        if (a instanceof Place.Gap g && g.beforeSeq() == p.seq() && g.afterSeq() == q.seq()) {
            return Gap.Place.HEAD;
        }
        if (b instanceof Place.Gap g && g.beforeSeq() == p.seq() && g.afterSeq() == q.seq()) {
            return Gap.Place.TAIL;
        }
        return Gap.Place.MIDDLE;
    }

    /** ③ 시작 — 매체면 그 자리, 끊긴 틈 안이면 뒤 조각 머리. */
    private static MediaCoord startOf(Place a) {
        return switch (a) {
            case Place.Media m -> new MediaCoord(m.seq(), m.offsetMs() * 1000);
            case Place.Gap g -> new MediaCoord(g.afterSeq(), 0);
            default -> throw new IllegalStateException("범위 밖 시작: " + a);
        };
    }

    /** ③ 끝 — 매체면 그 자리, 끊긴 틈 안이면 앞 조각 마지막 프레임까지. */
    private static End endOf(Place b) {
        return switch (b) {
            case Place.Media m -> new End(m.seq(), m.offsetMs() * 1000);
            case Place.Gap g -> new End(g.beforeSeq(), null);
            default -> throw new IllegalStateException("범위 밖 끝: " + b);
        };
    }

    /**
     * ⑤ 자막 끝점 — 컷으로 자른 뒤 시작은 F · 끝은 F끝. 끊긴 틈 안 끝점은 틈 자리로 둔다(렌더가 결과 길이 0 이하인 자막만 뺀다).
     * 컷과 겹치지 않는 자막은 싣지 않는다. {@code toSegmentEnd} 는 자막에 쓰지 않는다. 매체 끝점은 계획 매체 안 seq 로
     * 낸다({@link #inPlan}).
     */
    private static List<SubtitleSpan> subtitlesOf(List<LedgerRow> axis, CutInput input, List<RoundRow> rows,
                                                  List<RoundRow> media) {
        List<SubtitleSpan> spans = new ArrayList<>();
        for (int i = 0; i < input.subtitles().size(); i++) {
            RecipeDocument.Segment segment = input.subtitles().get(i);
            long start = Math.max(segment.startAtMs(), input.inMs());
            long end = Math.min(segment.endAtMs(), input.outMs());
            if (start >= end) {
                continue;
            }
            spans.add(new SubtitleSpan(i, inPlan(pointOf(LedgerTimeline.locate(axis, start)), rows, media),
                    inPlan(pointOf(LedgerTimeline.locateEnd(axis, end)), rows, media)));
        }
        return spans;
    }

    /**
     * 이어진 틈의 같은 매체 자리를 계획 안 표기로 바꾼다. 컷이 이어진 틈 안에서 시작하면 계획은 뒤 조각 B 부터인데, 틈 안 자막의 끝
     * F끝 은 앞 조각 끝 {@code (A, dur)} 을 낸다 — 같은 매체 자리 {@code (B, 0)} 이다. 끝 쪽은 대칭으로 뒤 조각 머리
     * {@code (C, 0)} 이 앞 조각 끝 {@code (B, dur)} 이다. 계획 밖 seq 를 그대로 실으면 렌더 계획 검사(설계 r7 5.3 규칙 5 — 매체 끝점
     * seq 는 계획 매체 안)가 주문 전체를 거절한다. 같은 자리라 자막의 뜻은 그대로다(대개 결과 길이 0 이라 렌더가 버린다).
     *
     * @throws IllegalStateException 계획 밖 끝점이 이어진 이웃의 같은 자리가 아니다(컷으로 자른 자막에서는 생기지 않는다)
     */
    private static SubtitlePoint inPlan(SubtitlePoint point, List<RoundRow> rows, List<RoundRow> media) {
        if (!(point instanceof SubtitlePoint.AtMedia at)) {
            return point;
        }
        RoundRow first = media.get(0);
        RoundRow last = media.get(media.size() - 1);
        long seq = at.at().seq();
        if (seq < first.seq()) {
            RoundRow before = rows.get(indexOf(rows, first.seq()) - 1);
            if (before.seq() == seq && first.continuesPrevious() && at.at().offsetUs() == before.durationMs() * 1000L) {
                return new SubtitlePoint.AtMedia(new MediaCoord(first.seq(), 0));
            }
        } else if (seq > last.seq()) {
            RoundRow after = rows.get(indexOf(rows, last.seq()) + 1);
            if (after.seq() == seq && after.continuesPrevious() && at.at().offsetUs() == 0) {
                return new SubtitlePoint.AtMedia(new MediaCoord(last.seq(), last.durationMs() * 1000L));
            }
        } else {
            return point;
        }
        throw new IllegalStateException("자막 끝점이 계획 밖이다: " + point);
    }

    private static SubtitlePoint pointOf(Place place) {
        return switch (place) {
            case Place.Media m -> new SubtitlePoint.AtMedia(new MediaCoord(m.seq(), m.offsetMs() * 1000));
            case Place.Gap g -> new SubtitlePoint.InGap(g.beforeSeq(), g.intoGapMs());
            default -> throw new IllegalStateException("컷 안 자막 끝점이 범위 밖이다: " + place);
        };
    }

    /** 디코더 준비 판정 결과. 준비 조각이 아직이면 비어 있다(409). */
    private record Primes(List<Prime> primes, List<PrimeSkip> skips, List<RunHead> heads) {
    }

    /**
     * ⑦ 디코더 준비 — 소리 묶음 머리(결과 첫 행 · J 거짓인 행)마다 규칙 하나(설계 r7 9.4 · cx-c1 · cx-k1). 머리 h 의 장부 바로 앞
     * 행 p 가 있고 h 의 {@code is_discontinuity} 가 거짓이면 같은 녹화 흐름이라 p 를 h 묶음의 준비 조각으로 싣는다. 회차 귀속 ·
     * J · {@code followsLedgerPrevious} 는 이 판정에 쓰지 않는다 — p 가 비귀속 행이어도 같은 녹화기 바로 앞 파일이다.
     * <ul>
     *   <li>p 가 이 방송 장부 구간 시작 앞 → 앞 방송 몫이라 쓰지 않는다({@code other_broadcast} — 5-2 소비자 규약)</li>
     *   <li>p 가 {@code failed} → 기다리지 않고 준비 없이({@code failed} — 잃는 것은 머리 21ms 이하)</li>
     *   <li>p 가 {@code pending} → 아직(다른 매체 행과 같은 짧은 대기)</li>
     * </ul>
     */
    private static Optional<Primes> primesOf(List<RoundRow> media, Ledger ledger, Instant sectionStart) {
        List<RoundRow> heads = new ArrayList<>();
        for (int i = 0; i < media.size(); i++) {
            if (i == 0 || !media.get(i).continuesPrevious()) {
                heads.add(media.get(i));
            }
        }
        List<Long> asked = heads.stream().filter(h -> !h.isDiscontinuity()).map(RoundRow::seq).toList();
        Map<Long, LedgerNeighbor> previous = asked.isEmpty() ? Map.of() : ledger.rowsBefore(asked);
        List<Prime> primes = new ArrayList<>();
        List<PrimeSkip> skips = new ArrayList<>();
        List<RunHead> kinds = new ArrayList<>();
        for (RoundRow h : heads) {
            LedgerNeighbor p = h.isDiscontinuity() ? null : previous.get(h.seq());
            kinds.add(new RunHead(h.seq(), h.isDiscontinuity() ? RunHead.Kind.NEW_RECORDING
                    : p == null ? RunHead.Kind.NO_PREV_ROW : RunHead.Kind.CONTINUED));
            if (p == null) {
                continue;
            }
            if (p.seq() >= h.seq()) {
                throw new IllegalStateException("장부 바로 앞 행 " + p.seq() + " 이 머리 " + h.seq() + " 뒤다");
            }
            if (sectionStart != null && p.wallMs() < sectionStart.toEpochMilli()) {
                skips.add(new PrimeSkip(h.seq(), p.seq(), "other_broadcast"));
            } else if (RoundRow.FAILED.equals(p.uploadState())) {
                skips.add(new PrimeSkip(h.seq(), p.seq(), "failed"));
            } else if (!RoundRow.UPLOADED.equals(p.uploadState())) {
                return Optional.empty();
            } else {
                primes.add(new Prime(h.seq(), p));
            }
        }
        return Optional.of(new Primes(primes, skips, kinds));
    }

    private static CutDecision reject(CutInput input, Reason reason) {
        log.info("clip.render.cut_rejected streamId={} recipeId={} reason={}",
                input.streamId(), input.recipeId(), reason.wire());
        return new Unrenderable(reason);
    }

    private static int indexOf(List<RoundRow> rows, long seq) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).seq() == seq) {
                return i;
            }
        }
        throw new IllegalStateException("F 가 낸 seq 가 회차 행에 없다: " + seq);
    }

    private static String describeGaps(List<Gap> gaps) {
        return gaps.isEmpty() ? "-" : gaps.stream()
                .map(g -> g.place().name().toLowerCase(Locale.ROOT) + ":" + g.beforeSeq() + "-" + g.afterSeq() + ":"
                        + g.gapMs()
                        + ":" + g.inCutMs())
                .collect(Collectors.joining(","));
    }

    private static String describeHeads(List<RunHead> heads) {
        return heads.stream().map(h -> h.seq() + ":" + h.kind().wire()).collect(Collectors.joining(","));
    }
}
