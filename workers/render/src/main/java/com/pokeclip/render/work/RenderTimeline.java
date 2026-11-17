package com.pokeclip.render.work;

import com.pokeclip.render.job.ErrorCode;
import com.pokeclip.render.job.OffsetOrigin;
import com.pokeclip.render.job.RenderFailure;
import com.pokeclip.render.job.RenderPlan;
import com.pokeclip.render.job.SourceRole;
import com.pokeclip.render.job.SourceSegment;
import com.pokeclip.render.job.SubtitlePoint;
import com.pokeclip.render.job.SubtitleSpan;
import com.pokeclip.render.media.SegmentTiming;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 렌더 계획 + 조각 판독 → 영상 자를 틱 · 영상 이동량 · 소리 묶음 · 배치표 · 결과 끝(PDT 설계 r7 7.3 · 7.4 · 7.6). 그래프
 * (A′ 조각 단계 · 이음 목록 · 최종 단계)와 배치표 · 자막이 모두 이 한 곳의 값을 쓴다(설계 7.6). 명령 문자열은 만들지 않는다.
 *
 * <p>시각 계산은 모두 정수다. 영상은 원래 틱 그대로이고, 틱 · µs · 표본이 섞이는 자리는 「µs × ts」 단위(1초 = 10⁶·ts)로
 * 옮겨 나눗셈 없이 비교한다. 경계 비교는 B2 의 반 µs 여유 꼴({@code 2·10⁶·PTS ≤ 2·10⁶·D0 + (2x′ ± 1)·ts})이다.
 *
 * <p>입력 약속: 결과에 드는 조각({@code segment})은 모두 {@code timings} 에 있다. 준비 조각({@code audio_prime})은 부르는
 * 쪽이 받고 판독을 맞춰 본 것(설계 7.4 소리 묶음 7 — 받지 못함 {@code missing} · 판독 어긋남 {@code mismatch} 는 부르는 쪽이
 * 남기고 빼서 넘긴다)만 넣는다. 확보 조각({@code audio_capture})은 결과가 쓸 때만 판독이 든다 — 쓸지는 결과 끝 소유를 마친
 * {@link Draft} 가 판독 없이 정하므로, 부르는 쪽은 {@link #draft} 로 그 결정을 받은 뒤 쓰일 조각만 받아 판독한다(설계 7.4 소리 묶음
 * 8 · 1). 트랙 번호 N 은 {@link SegmentTiming#audio()} 의 N번째다.
 */
public final class RenderTimeline {

    /** 결과 길이 상한(설계 7.4 결과 끝 소유 2). */
    static final long CAP_US = 180_000_000;
    /** {@code aresample} 출력 표본율(설계 7.4 소리 묶음 4). */
    static final long OUTPUT_RATE = 48_000;
    static final long MICROS = 1_000_000;
    private static final long TWO_MICROS = 2 * MICROS;

    /**
     * 영상 프레임이 남는 조각 하나(원래 틱). A′ 조각 단계 {@code trim=start_pts=<startPts>:end_pts=<endPts>} 이고
     * {@code endPts} = 남는 마지막 PTS + 1(끝 포함). 결과 축 프레임 자리 = PTS + {@code deltaTicks}.
     */
    public record VideoCut(long seq, long startPts, long endPts, long deltaTicks) {

        /** 결과 축 첫 프레임 틱 R_j = a_j + Δ_j(설계 13.5 대응표). 첫 조각은 0 이다. */
        public long resultTicks() {
            return startPts + deltaTicks;
        }
    }

    /**
     * 배치표 한 줄(설계 7.6). {@code zeroAtUs} = 그 조각 x′ = 0 의 결과 자리 · {@code [fromUs, toUs]} = 결과에 든 그 조각
     * 몫(영상 · 소리 합집합)을 x′ 로 · {@code headLeadUs} = m.
     */
    public record Placement(long seq, long zeroAtUs, long fromUs, long toUs, long headLeadUs) {
    }

    /** 결과 µs 로 옮긴 자막 하나. 길이 0 이하는 빠진다(계약6 100행). */
    public record PlacedSubtitle(int index, long startUs, long endUs) {
    }

    /** 관측 신호 한 줄(설계 15절 이름 · 칸). 부르는 쪽이 {@code jobId} 를 붙여 남긴다. */
    public record Note(String event, String fields) {
    }

    private final OffsetOrigin origin;
    private final List<VideoCut> videoCuts;
    private final List<Long> videoJoinDurationsUs;
    private final List<AudioRun> audioRuns;
    private final List<Placement> placements;
    private final Map<Long, Long> headLeadBySeq;
    private final long lengthUs;
    private final long resultEndSamples;
    private final boolean lengthCapped;
    private final List<Note> notes;
    private final List<PlacedSubtitle> subtitles;

    private RenderTimeline(Layout layout) {
        this.origin = layout.plan.offsetOrigin();
        this.videoCuts = List.copyOf(layout.videoCuts);
        this.videoJoinDurationsUs = List.copyOf(layout.joinDurations);
        this.audioRuns = List.copyOf(layout.runs);
        this.placements = List.copyOf(layout.placements);
        this.headLeadBySeq = Map.copyOf(layout.headLeadBySeq);
        this.lengthUs = layout.lengthUs;
        this.resultEndSamples = layout.totalSamples;
        this.lengthCapped = layout.capped;
        List<Note> all = new ArrayList<>(layout.notes);
        this.subtitles = placeSubtitles(layout.plan.subtitles(), all);
        this.notes = List.copyOf(all);
    }

    /**
     * 확보 조각 판독이 이미 {@code timings} 에 있을 때 한 번에 짠다({@link #draft} 다음 {@link Draft#finish}). 결과가 쓰는 확보
     * 조각의 판독은 {@code timings} 에 있어야 한다.
     *
     * @throws RenderFailure {@link #draft} · {@link Draft#finish} 와 같다
     */
    public static RenderTimeline of(RenderPlan plan, List<SourceSegment> sources, Map<Long, SegmentTiming> timings,
                                    List<Integer> trackIds) {
        Draft draft = draft(plan, sources, timings, trackIds);
        return draft.finish(draft.capture().map(c -> timings.get(c.seq())).orElse(null));
    }

    /**
     * 결과 끝 소유 · 영상 · 배치표까지 짜고 확보 조각을 쓸지 정한다. 확보 조각 판독은 쓰지 않는다.
     *
     * @param sources  주문서 {@code sourceKeys} 그대로(역할 · J 표지)
     * @param timings  seq → 판독. 위 입력 약속(확보 조각은 없어도 된다)
     * @param trackIds 고른 트랙 번호(레시피 순서)
     * @throws RenderFailure {@code SOURCE_MISMATCH}(timescale 다름 · 영상 패킷 없음 · 파일 머리가 영상 뒤 · 판독 값이 정수식을
     *                       넘김 · 이음 불변식이 깨짐) · {@code SOURCE_MISSING}(고른 트랙 없음) · {@code SOURCE_RANGE}(자를
     *                       프레임 없음)
     */
    public static Draft draft(RenderPlan plan, List<SourceSegment> sources, Map<Long, SegmentTiming> timings,
                              List<Integer> trackIds) {
        return asSourceMismatch(() -> new Draft(new Layout(plan, sources, timings, trackIds).build()));
    }

    /**
     * 산식의 입력은 주문서 검사를 지난 계획과 조각 판독뿐이다. 넘침(…Exact)이나 이음 불변식 깨짐은 그 조각 꼴 탓이라 다시 받아도
     * 같다 — 일시 실패(재시도)로 새지 않게 종결로 바꾼다(sec M1). 신호에만 쓰는 산식은 여기까지 오지 않는다({@link AudioFlow}).
     */
    private static <T> T asSourceMismatch(Supplier<T> step) {
        try {
            return step.get();
        } catch (ArithmeticException | IllegalStateException e) {
            throw new RenderFailure(ErrorCode.SOURCE_MISMATCH, "영상 조각 시각으로 결과를 짜지 못했다", false, e);
        }
    }

    /**
     * 결과 끝 소유까지 마친 계획 — 소리 묶음만 남았다. 확보 조각을 쓸지가 여기서 정해진다(원래 out 조각이 결과 마지막으로 남았을
     * 때만 · 설계 7.4 소리 묶음 8). 이 결정은 이 한 곳에서만 내고, 부르는 쪽은 {@link #capture()} 가 있을 때만 그 조각을 받아
     * 판독해 {@link #finish} 에 넘긴다 — 쓰지 않을 조각의 받기 · 판독 실패(시한 초과 포함)가 주문에 걸리지 않는다. 한 번만 마친다.
     */
    public static final class Draft {

        private final Layout layout;
        private boolean finished;

        private Draft(Layout layout) {
            this.layout = layout;
        }

        /** 결과가 쓸 확보 조각. 쓰지 않으면(없거나 끝 소유로 빠짐) 비었다 — 그때 그 조각은 받지도 판독하지도 않는다. */
        public Optional<SourceSegment> capture() {
            return layout.usesCapture ? Optional.of(layout.captureSource) : Optional.empty();
        }

        /**
         * 소리 묶음을 짜 계획을 마친다.
         *
         * @param capture {@link #capture()} 조각의 판독. 쓰지 않으면 null
         * @throws IllegalArgumentException 쓸 조각인데 판독이 없거나, 쓰지 않는데 판독을 넘김(부른 쪽 결함)
         * @throws RenderFailure            {@code SOURCE_MISMATCH}(확보 조각 영상 시간 단위가 다름 · 판독 값이 정수식을 넘김)
         */
        public RenderTimeline finish(SegmentTiming capture) {
            if (finished) {
                throw new IllegalStateException("이미 마친 계획이다");
            }
            if (layout.usesCapture != (capture != null)) {
                throw new IllegalArgumentException(layout.usesCapture ? "결과가 쓰는 확보 조각의 판독이 없다"
                        : "결과가 쓰지 않는 확보 조각의 판독이 왔다");
            }
            finished = true;
            return asSourceMismatch(() -> new RenderTimeline(layout.withAudio(capture)));
        }
    }

    public List<VideoCut> videoCuts() {
        return videoCuts;
    }

    /** A′ 이음 목록 {@code duration}(µs 정수 · 중간 파일 수 − 1개) = rnd(R_{j+1} µs) − rnd(R_j µs)(설계 7.5). */
    public List<Long> videoJoinDurationsUs() {
        return videoJoinDurationsUs;
    }

    public List<AudioRun> audioRuns() {
        return audioRuns;
    }

    public List<Placement> placements() {
        return placements;
    }

    /** 결과 길이 E(µs · 반올림). 본문 맨 위 {@code lengthUs} 이고 자막 · 배치표가 같은 끝을 쓴다. */
    public long lengthUs() {
        return lengthUs;
    }

    /** 결과 끝(48kHz 표본 · 결과 축 정확값을 내림). 마지막 묶음 {@code resultEndSample} 과 같다. */
    public long resultEndSamples() {
        return resultEndSamples;
    }

    /** 결과 끝이 180초 상한으로 잘렸나. */
    public boolean lengthCapped() {
        return lengthCapped;
    }

    public List<Note> notes() {
        return notes;
    }

    public List<PlacedSubtitle> subtitles() {
        return subtitles;
    }

    /**
     * 자막 끝점 → 결과 µs(설계 7.6). 매체 자리 = {@code zeroAtUs + clamp(x′, fromUs, toUs)} · 틈 자리 = afterSeq 다음에
     * 남은 조각의 결과 시작(없으면 lengthUs) · 결과에서 빠진 조각 자리 = 가까운 남은 경계. 늘 {@code [0, lengthUs]} 안이다.
     */
    public long resultUsOf(SubtitlePoint point) {
        long us = switch (point) {
            case SubtitlePoint.AtMedia m -> atMedia(m.at().seq(), m.at().offsetUs());
            case SubtitlePoint.InGap g -> startAfter(g.afterSeq());
        };
        return Math.clamp(us, 0, lengthUs);
    }

    private long atMedia(long seq, long offsetUs) {
        Placement before = null;
        for (Placement p : placements) {
            if (p.seq() == seq) {
                long xPrime = origin == OffsetOrigin.FILE_HEAD ? offsetUs - headLeadBySeq.get(seq) : offsetUs;
                return p.zeroAtUs() + Math.clamp(xPrime, p.fromUs(), p.toUs());
            }
            if (p.seq() < seq) {
                before = p;
            }
        }
        return before == null ? 0 : before.zeroAtUs() + before.toUs();
    }

    private long startAfter(long afterSeq) {
        for (Placement p : placements) {
            if (p.seq() > afterSeq) {
                return p.zeroAtUs() + p.fromUs();
            }
        }
        return lengthUs;
    }

    private List<PlacedSubtitle> placeSubtitles(List<SubtitleSpan> spans, List<Note> sink) {
        List<PlacedSubtitle> placed = new ArrayList<>();
        for (SubtitleSpan span : spans) {
            long start = resultUsOf(span.start());
            long end = resultUsOf(span.end());
            if (end <= start) {
                sink.add(new Note("render.subtitle_dropped", "index=" + span.index() + " reason=empty"));
                continue;
            }
            placed.add(new PlacedSubtitle(span.index(), start, end));
        }
        return List.copyOf(placed);
    }

    // ── 계산 ──────────────────────────────────────────────────────────

    /**
     * 결과에 드는 조각 하나. {@code joins} = J(첫 매체는 표지와 무관하게 늘 머리 — 설계 5.3 3). PTS 열은 판독이 내줄 때마다 복사하므로
     * 한 번만 받아 둔다.
     */
    record Seg(long seq, boolean joins, SegmentTiming t, long[] pts) {

        Seg(long seq, boolean joins, SegmentTiming t) {
            this(seq, joins, t, t.videoPtsSorted());
        }

        long d0() {
            return t.videoFirstDts();
        }

        long v() {
            return t.videoLengthTicks();
        }

        long headLeadUs() {
            return t.headLeadUs();
        }

        /** c = 첫 PTS − D0. */
        long c() {
            return pts[0] - d0();
        }

        long lastPts() {
            return pts[pts.length - 1];
        }

        /** 마지막 프레임 간격 = PTS 간격 중앙값(판독 · 설계 7.3 4). 프레임이 하나뿐이라 간격이 없으면 V. */
        long interval() {
            return t.frameIntervalTicks() > 0 ? t.frameIntervalTicks() : v();
        }
    }

    /** 만드는 동안만 쓰는 작업대. 끝나면 값만 {@link RenderTimeline} 으로 옮긴다. 소리 묶음은 {@link RunLayout} 이 더한다. */
    static final class Layout {

        private final RenderPlan plan;
        final List<SourceSegment> sources;
        final Map<Long, SegmentTiming> timings;
        final List<Integer> trackIds;
        private final List<Seg> media = new ArrayList<>();
        private final Map<Long, Long> headLeadBySeq = new HashMap<>();
        final List<Note> notes = new ArrayList<>();
        long ts;
        private Seg origIn;
        private Seg origOut;
        private long fInOrig;
        private Long xOut;

        final List<Seg> order = new ArrayList<>();
        final Map<Long, Long> delta = new HashMap<>();
        private boolean toSegmentEnd;
        long fIn;
        private long b;
        private long x;
        private boolean capped;
        String captureLost;
        SourceSegment captureSource;
        boolean usesCapture;
        SegmentTiming captureTiming;

        private final List<VideoCut> videoCuts = new ArrayList<>();
        private final List<Long> joinDurations = new ArrayList<>();
        final List<AudioRun> runs = new ArrayList<>();
        private final List<Placement> placements = new ArrayList<>();
        long endU;
        private long lengthUs;
        long totalSamples;

        Layout(RenderPlan plan, List<SourceSegment> sources, Map<Long, SegmentTiming> timings, List<Integer> trackIds) {
            this.plan = plan;
            this.sources = sources;
            this.timings = timings;
            this.trackIds = List.copyOf(trackIds);
        }

        Layout build() {
            readMedia();
            pickEdges();
            order.addAll(media);
            trimEndSide();
            trimStartSide();
            computeDeltas();
            ownEnd();
            endU = Math.addExact(Math.multiplyExact(b, MICROS), Math.multiplyExact(x, ts));
            lengthUs = roundDiv(endU, ts);
            totalSamples = Math.floorDiv(Math.multiplyExact(endU, OUTPUT_RATE), ts * MICROS);
            collectVideo();
            collectPlacements();
            decideCapture();
            return this;
        }

        /** 원래 out 조각이 마지막으로 남았을 때만 확보 조각을 쓴다(7.4 소리 묶음 8). 판독은 보지 않는다. */
        private void decideCapture() {
            captureSource = sources.stream().filter(s -> s.ref().role() == SourceRole.AUDIO_CAPTURE).findFirst()
                    .orElse(null);
            usesCapture = captureSource != null && order.getLast() == origOut;
        }

        /** 소리 묶음(쓸 확보 조각이면 그 판독과 함께). 쓸 확보 조각의 영상 시간 단위는 결과 조각과 같아야 한다. */
        Layout withAudio(SegmentTiming capture) {
            if (capture != null && capture.videoTimescale() != ts) {
                throw RenderFailure.permanent(ErrorCode.SOURCE_MISMATCH, "조각마다 영상 시간 단위가 다르다");
            }
            captureTiming = capture;
            new RunLayout(this).collect();
            return this;
        }

        // ── 판독 · 틱 선택(7.3) ──

        private void readMedia() {
            Map<Long, SourceSegment> bySeq = new HashMap<>();
            for (SourceSegment s : sources) {
                if (s.ref().role() == SourceRole.SEGMENT) {
                    bySeq.put(s.seq(), s);
                }
            }
            int needed = trackIds.stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
            for (long seq : plan.mediaSeqs()) {
                SourceSegment s = bySeq.get(seq);
                SegmentTiming t = timings.get(seq);
                if (s == null || t == null) {
                    throw new IllegalArgumentException("결과 조각 " + seq + " 의 주문 항목이나 판독이 없다");
                }
                checkTiming(t);
                if (t.audio().size() < needed) {
                    throw RenderFailure.permanent(ErrorCode.SOURCE_MISSING,
                            "고른 오디오 트랙이 방송에 없다(방송 소리 트랙 " + t.audio().size() + "개)");
                }
                Seg seg = new Seg(seq, !media.isEmpty() && s.joinsPrevious(), t);
                media.add(seg);
                headLeadBySeq.put(seq, seg.headLeadUs());
            }
        }

        private void checkTiming(SegmentTiming t) {
            if (t.videoTimescale() <= 0) {
                throw new IllegalArgumentException("영상 timescale 이 양수가 아니다");
            }
            if (ts == 0) {
                ts = t.videoTimescale();
            } else if (t.videoTimescale() != ts) {
                throw RenderFailure.permanent(ErrorCode.SOURCE_MISMATCH, "조각마다 영상 시간 단위가 다르다");
            }
            if (t.frameCount() == 0) {
                throw RenderFailure.permanent(ErrorCode.SOURCE_MISMATCH, "영상 조각에 화면이 없다");
            }
            if (t.headLeadUs() < 0) {
                throw RenderFailure.permanent(ErrorCode.SOURCE_MISMATCH, "조각 파일 머리가 영상 첫 화면보다 늦다");
            }
        }

        private long normalize(Seg s, long offsetUs) {
            return plan.offsetOrigin() == OffsetOrigin.FILE_HEAD ? offsetUs - s.headLeadUs() : offsetUs;
        }

        /** {@code PTS ≼ x′}(tol +1) · {@code PTS ≺ x′}(tol −1) — 2·10⁶ 배 정수식(설계 7.3 2). */
        private boolean upTo(Seg s, long pts, long xPrime, int tol) {
            long left = Math.multiplyExact(TWO_MICROS, pts);
            long right = Math.addExact(Math.multiplyExact(TWO_MICROS, s.d0()),
                    Math.multiplyExact(Math.addExact(Math.multiplyExact(2, xPrime), tol), ts));
            return left <= right;
        }

        private void pickEdges() {
            origIn = media.getFirst();
            origOut = media.getLast();
            long xIn = normalize(origIn, plan.in().offsetUs());
            fInOrig = origIn.pts()[0];
            for (long p : origIn.pts()) {
                if (upTo(origIn, p, xIn, 1)) {
                    fInOrig = p;
                }
            }
            toSegmentEnd = plan.out().toSegmentEnd();
            xOut = toSegmentEnd ? null : normalize(origOut, plan.out().offsetUs());
        }

        /**
         * 조각 s 의 남는 프레임. 시작 · 끝 지시(원래 in · out 조각) · 표시 소유(지금 순서의 뒤 이웃이 끊긴 이웃이면 그 첫
         * 프레임이 결과에 나오는 순간 앞까지 · B8) · {@code withEnd} 면 결과 끝 소유(7.4 3)를 모두 건다. 표시 소유를 지금 순서로
         * 다시 세므로, 끝 후퇴로 빠진 뒤 이웃이 가져갔던 프레임은 앞 조각에 돌아온다.
         */
        private List<Long> kept(Seg s, boolean withEnd) {
            int i = order.indexOf(s);
            Seg next = i + 1 < order.size() ? order.get(i + 1) : null;
            List<Long> out = new ArrayList<>();
            for (long p : s.pts()) {
                if (s == origIn && p < fInOrig) {
                    continue;
                }
                if (s == origOut && xOut != null && !upTo(s, p, xOut, -1)) {
                    continue;
                }
                if (next != null && !next.joins() && p >= s.d0() + s.v() + next.c()) {
                    continue;
                }
                if (withEnd && !beforeEnd(p + delta.get(s.seq()))) {
                    continue;
                }
                out.add(p);
            }
            return out;
        }

        /** 결과 틱 r 이 결과 끝 {@code (b, x)} 의 {@code ≺} 인가(7.4 결과 끝 소유 3). */
        private boolean beforeEnd(long resultTicks) {
            long left = Math.multiplyExact(TWO_MICROS, resultTicks);
            long right = Math.addExact(Math.multiplyExact(TWO_MICROS, b),
                    Math.multiplyExact(Math.subtractExact(Math.multiplyExact(2, x), 1), ts));
            return left <= right;
        }

        // ── 끝 쪽 · 시작 쪽(7.3 6) ──

        private void trimEndSide() {
            Seg last = order.getLast();
            if (order.size() > 1 && kept(last, false).isEmpty() && !last.joins()) {
                retreat();
            }
        }

        private void retreat() {
            Seg dropped = order.removeLast();
            notes.add(new Note("render.out_retreated", "fromSeq=" + dropped.seq() + " toSeq=" + order.getLast().seq()));
            toSegmentEnd = true;
            if (dropped == origOut && captureLost == null) {
                captureLost = "out_retreated";
            }
        }

        private void trimStartSide() {
            while (kept(order.getFirst(), false).isEmpty()) {
                if (order.size() == 1) {
                    throw RenderFailure.permanent(ErrorCode.SOURCE_RANGE, "자를 프레임이 없다");
                }
                Seg dropped = order.removeFirst();
                notes.add(new Note("render.in_advanced",
                        "fromSeq=" + dropped.seq() + " toSeq=" + order.getFirst().seq()));
            }
            fIn = order.getFirst() == origIn ? fInOrig : order.getFirst().pts()[0];
        }

        /** Δ_first = −f_in · Δ_{j+1} = Δ_j + (D0_j + V_j) − D0_{j+1}(7.4 영상 이동량). */
        private void computeDeltas() {
            Seg prev = null;
            for (Seg s : order) {
                long d = prev == null ? -fIn : delta.get(prev.seq()) + prev.d0() + prev.v() - s.d0();
                delta.put(s.seq(), d);
                prev = s;
            }
        }

        // ── 결과 끝 소유(7.4 1–4) ──

        private void ownEnd() {
            int framesBefore = keptFrames(false);
            List<Long> droppedSeqs = new ArrayList<>();
            setEnd();
            while (true) {
                droppedSeqs.addAll(dropLateSegments());
                Seg last = order.getLast();
                if (order.size() == 1 || !kept(last, true).isEmpty() || last.joins()) {
                    break;
                }
                retreat();
                setEnd();
            }
            if (kept(order.getFirst(), true).isEmpty()) {
                throw RenderFailure.permanent(ErrorCode.SOURCE_RANGE, "자를 프레임이 없다");
            }
            noteEndOwned(framesBefore - keptFrames(true), droppedSeqs);
        }

        /** 지금 순서에서 남는 프레임 수. {@code withEnd} 면 결과 끝 소유까지 건다. */
        private int keptFrames(boolean withEnd) {
            int n = 0;
            for (Seg s : order) {
                n += kept(s, withEnd).size();
            }
            return n;
        }

        /**
         * {@code render.end_owned}(설계 15절) — 끝 소유가 뭔가를 뺐을 때만 한 줄. {@code endUs} = 확정한 끝(= lengthUs) ·
         * {@code droppedFrames} = 끝 소유 앞에 남던 프레임 수 − 뒤에 남은 수(빠진 조각 · 끝 뒤 프레임 · 물림으로 빠진 조각의
         * 프레임) · {@code droppedSeqs} = 결과 시작이 끝 이상이라 뺀 조각(물림은 {@code render.out_retreated} 몫).
         */
        private void noteEndOwned(int droppedFrames, List<Long> droppedSeqs) {
            if (droppedFrames <= 0 && droppedSeqs.isEmpty()) {
                return;
            }
            long endUs = roundDiv(Math.addExact(Math.multiplyExact(b, MICROS), Math.multiplyExact(x, ts)), ts);
            notes.add(new Note("render.end_owned", "endUs=" + endUs + " droppedFrames=" + droppedFrames
                    + " droppedSeqs=" + droppedSeqs + " capped=" + capped));
        }

        /** 끝을 「기준 틱 b + µs 오프셋 x」 로 적고 180초를 넘으면 상한으로 바꾼다(7.4 1 · 2 · 물린 뒤 재상한). */
        private void setEnd() {
            Seg last = order.getLast();
            long d = delta.get(last.seq());
            if (toSegmentEnd) {
                b = d + last.lastPts() + last.interval();
                x = 0;
            } else {
                b = d + last.d0();
                x = xOut;
            }
            long scaled = Math.addExact(Math.multiplyExact(TWO_MICROS, b), Math.multiplyExact(2 * x, ts));
            capped = scaled > Math.multiplyExact(2 * CAP_US, ts);
            if (capped) {
                b = 0;
                x = CAP_US;
                notes.add(new Note("render.length_capped", "actualMs=" + CAP_US / 1000));
            }
        }

        /** 결과 시작(소리가 처음 드는 자리)이 끝 이상인 조각을 뺀다(7.4 4). 첫 조각은 남긴다. 뺀 조각 seq 를 돌려준다. */
        private List<Long> dropLateSegments() {
            long endScaled = Math.addExact(Math.multiplyExact(TWO_MICROS, b), Math.multiplyExact(2 * x, ts));
            List<Seg> late = new ArrayList<>();
            for (int i = 1; i < order.size(); i++) {
                if (resultStartScaled(order.get(i), i) >= endScaled) {
                    late.add(order.get(i));
                }
            }
            if (late.isEmpty()) {
                return List.of();
            }
            order.removeAll(late);
            if (late.contains(origOut) && captureLost == null) {
                captureLost = capped ? "capped" : "end_owned";
            }
            return late.stream().map(Seg::seq).toList();
        }

        /** 2·10⁶ 배 결과 시작. J 이음 조각은 파일 머리 자리(Δ + D0 − m), 묶음 머리는 영상 첫 DTS 자리. */
        private long resultStartScaled(Seg s, int i) {
            long base = Math.multiplyExact(TWO_MICROS, delta.get(s.seq()) + s.d0());
            return i > 0 && s.joins() ? base - Math.multiplyExact(2 * s.headLeadUs(), ts) : base;
        }

        // ── 영상 · 배치표 ──

        private void collectVideo() {
            for (Seg s : order) {
                List<Long> k = kept(s, true);
                if (!k.isEmpty()) {
                    videoCuts.add(new VideoCut(s.seq(), k.getFirst(), k.getLast() + 1, delta.get(s.seq())));
                }
                noteOverlap(s);
            }
            for (int j = 0; j + 1 < videoCuts.size(); j++) {
                VideoCut a = videoCuts.get(j);
                VideoCut c = videoCuts.get(j + 1);
                if (a.endPts() - a.startPts() <= 0 || c.resultTicks() - a.resultTicks() < a.endPts() - a.startPts()) {
                    throw new IllegalStateException("이음 불변식 R_{j+1} − R_j ≥ e_j − a_j > 0 이 깨졌다(seq "
                            + a.seq() + " → " + c.seq() + ")");
                }
                joinDurations.add(ticksToUs(c.resultTicks()) - ticksToUs(a.resultTicks()));
            }
        }

        private void noteOverlap(Seg s) {
            int i = order.indexOf(s);
            if (i + 1 >= order.size() || order.get(i + 1).joins()) {
                return;
            }
            Seg next = order.get(i + 1);
            List<Long> dropped = new ArrayList<>();
            for (long p : s.pts()) {
                if (p >= s.d0() + s.v() + next.c() && !(s == origIn && p < fInOrig)) {
                    dropped.add(p);
                }
            }
            if (!dropped.isEmpty()) {
                notes.add(new Note("render.frame_overlap",
                        "beforeSeq=" + s.seq() + " afterSeq=" + next.seq() + " droppedPts=" + dropped));
            }
        }

        private void collectPlacements() {
            for (int i = 0; i < order.size(); i++) {
                Seg s = order.get(i);
                long zeroU = Math.multiplyExact(delta.get(s.seq()) + s.d0(), MICROS);
                long zeroAtUs = roundDiv(zeroU, ts);
                long startU = i == 0 ? 0 : s.joins() ? zeroU - Math.multiplyExact(s.headLeadUs(), ts) : zeroU;
                long endUOf = endOfShare(i);
                placements.add(new Placement(s.seq(), zeroAtUs, roundDiv(startU, ts) - zeroAtUs,
                        roundDiv(endUOf, ts) - zeroAtUs, s.headLeadUs()));
            }
        }

        /** 조각 i 몫의 끝(µs × ts): 뒤 조각에 프레임이 있으면 그 첫 프레임 자리(마지막 프레임 표시 끝), 아니면 결과 끝. */
        private long endOfShare(int i) {
            if (i + 1 < order.size()) {
                Seg next = order.get(i + 1);
                List<Long> k = kept(next, true);
                if (!k.isEmpty()) {
                    return Math.multiplyExact(k.getFirst() + delta.get(next.seq()), MICROS);
                }
            }
            return endU;
        }

        private long ticksToUs(long ticks) {
            return roundDiv(Math.multiplyExact(ticks, MICROS), ts);
        }
    }

    /** a ÷ d 를 반 올림(half up · d > 0). */
    static long roundDiv(long a, long d) {
        return Math.floorDiv(Math.addExact(Math.multiplyExact(2, a), d), Math.multiplyExact(2, d));
    }
}
