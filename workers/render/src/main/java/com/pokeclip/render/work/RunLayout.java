package com.pokeclip.render.work;

import com.pokeclip.render.job.SourceRole;
import com.pokeclip.render.job.SourceSegment;
import com.pokeclip.render.media.SegmentTiming;
import com.pokeclip.render.work.RenderTimeline.Layout;
import com.pokeclip.render.work.RenderTimeline.Note;
import com.pokeclip.render.work.RenderTimeline.Seg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link RenderTimeline} 의 소리 묶음(PDT 설계 r7 7.4 소리 묶음 1–8). 결과 끝 소유를 마친 작업대({@link Layout})의 순서 · Δ · 끝 ·
 * 확보 조각 결정을 읽기만 하고, 묶음과 그 신호를 작업대에 더한다.
 */
final class RunLayout {

    private static final long MICROS = 1_000_000;
    private static final long OUTPUT_RATE = RenderTimeline.OUTPUT_RATE;

    private final Layout l;

    RunLayout(Layout layout) {
        this.l = layout;
    }

    void collect() {
        List<List<Seg>> groups = new ArrayList<>();
        for (int i = 0; i < l.order.size(); i++) {
            Seg s = l.order.get(i);
            if (i == 0 || !s.joins()) {
                groups.add(new ArrayList<>());
            }
            groups.getLast().add(s);
        }
        Map<Long, SourceSegment> primeByOwner = primes(groups);
        SourceSegment capture = capture();
        for (int gi = 0; gi < groups.size(); gi++) {
            List<Seg> g = groups.get(gi);
            boolean lastRun = gi == groups.size() - 1;
            l.runs.add(layoutRun(gi, g, primeByOwner.get(g.getFirst().seq()), lastRun ? capture : null,
                    lastRun ? null : groups.get(gi + 1)));
        }
    }

    /** 남은 머리마다 쓸 준비 조각. 머리가 빠진 준비 조각은 쓰지 않는다(owner_dropped · 열화 아님). */
    private Map<Long, SourceSegment> primes(List<List<Seg>> groups) {
        List<Long> heads = groups.stream().map(g -> g.getFirst().seq()).toList();
        Map<Long, SourceSegment> byOwner = new HashMap<>();
        for (SourceSegment s : l.sources) {
            if (s.ref().role() != SourceRole.AUDIO_PRIME || !l.timings.containsKey(s.seq())) {
                continue;
            }
            long owner = s.ref().ownerSeq();
            if (!heads.contains(owner)) {
                l.notes.add(primeSkipped(s, "owner_dropped", false));
            } else if (l.timings.get(s.seq()).videoTimescale() != l.ts) {
                l.notes.add(primeSkipped(s, "mismatch", true));
            } else {
                byOwner.put(owner, s);
            }
        }
        return byOwner;
    }

    private static Note primeSkipped(SourceSegment s, String reason, boolean degraded) {
        return new Note("render.prime_skipped", "seq=" + s.seq() + " ownerSeq=" + s.ref().ownerSeq()
                + " reason=" + reason + " degraded=" + degraded);
    }

    /**
     * 작업대가 결과 끝 소유 뒤 판독 없이 정한 대로 확보 조각을 쓴다({@link RenderTimeline.Draft#capture}). 쓰지 않게 됐으면 그
     * 사유를 남긴다.
     */
    private SourceSegment capture() {
        if (l.captureSource == null || l.usesCapture) {
            return l.captureSource;
        }
        String reason = l.captureLost == null ? "out_retreated" : l.captureLost;
        l.notes.add(new Note("render.capture_dropped", "seq=" + l.captureSource.seq() + " reason=" + reason));
        return null;
    }

    private AudioRun layoutRun(int gi, List<Seg> g, SourceSegment prime, SourceSegment capture, List<Seg> nextRun) {
        Seg head = g.getFirst();
        List<SegmentTiming> files = new ArrayList<>();
        if (prime != null) {
            files.add(l.timings.get(prime.seq()));
        }
        g.forEach(s -> files.add(s.t()));
        if (capture != null) {
            files.add(l.captureTiming);
        }
        long[] offsetUs = flowOffsetsUs(files);
        int hi = prime == null ? 0 : 1;
        long hiU = Math.multiplyExact(offsetUs[hi], l.ts);
        long stHeadU = Math.multiplyExact(head.t().formatStartUs(), l.ts);
        long startU = gi == 0
                ? hiU + Math.multiplyExact(l.fIn, MICROS) - stHeadU
                : hiU + sigmaU(head.t());
        long endU;
        if (nextRun == null) {
            endU = l.endU - Math.multiplyExact(l.delta.get(head.seq()), MICROS) + hiU - stHeadU;
        } else {
            Seg tail = g.getLast();
            int ti = hi + g.size() - 1;
            endU = Math.multiplyExact(offsetUs[ti], l.ts) + sigmaU(tail.t()) + Math.multiplyExact(tail.v(), MICROS);
        }
        long resultStart = gi == 0 ? 0 : resultSample(head);
        long resultEnd = nextRun == null ? l.totalSamples : resultSample(nextRun.getFirst());
        List<Long> durations = new ArrayList<>();
        for (int i = 0; i + 1 < offsetUs.length; i++) {
            durations.add(offsetUs[i + 1] - offsetUs[i]);
        }
        List<AudioRun.TrackStart> tracks = trackStarts(gi, files, offsetUs, startU);
        AudioRun run = new AudioRun(gi, prime == null ? null : prime.seq(), g.stream().map(Seg::seq).toList(),
                capture == null ? null : capture.seq(), durations, samples(startU), samples(endU), tracks,
                resultStart, resultEnd);
        l.notes.add(new Note("render.audio_run", "run=" + gi + " seqs=" + run.seqs() + " prime=" + run.primeSeq()
                + " capture=" + run.captureSeq() + " fromSample=" + run.fromSample() + " toSample="
                + run.toSample() + " frontSilence="
                + tracks.stream().map(AudioRun.TrackStart::frontSilenceSamples).toList()
                + " resultStartSample=" + resultStart));
        l.notes.addAll(AudioFlow.padNotes(run, files, offsetUs));
        return run;
    }

    /** 흐름 안 파일 자리(µs): 누적 정확값 S(간격 = σ_i + V_i − σ_{i+1})를 µs 로 반올림(concat demuxer 오프셋). */
    private long[] flowOffsetsUs(List<SegmentTiming> files) {
        long[] out = new long[files.size()];
        long cumU = 0;
        for (int i = 1; i < files.size(); i++) {
            SegmentTiming a = files.get(i - 1);
            cumU += sigmaU(a) + Math.multiplyExact(a.videoLengthTicks(), MICROS) - sigmaU(files.get(i));
            out[i] = RenderTimeline.roundDiv(cumU, l.ts);
        }
        return out;
    }

    /** σ = D0 − st(µs × ts). */
    private long sigmaU(SegmentTiming t) {
        return Math.multiplyExact(t.videoFirstDts(), MICROS) - Math.multiplyExact(t.formatStartUs(), l.ts);
    }

    /** 묶음 머리 D0 의 결과 자리(표본 · 내림). */
    private long resultSample(Seg head) {
        return Math.floorDiv(Math.multiplyExact(l.delta.get(head.seq()) + head.d0(), OUTPUT_RATE), l.ts);
    }

    private long samples(long u) {
        return RenderTimeline.roundDiv(Math.multiplyExact(u, OUTPUT_RATE), l.ts * MICROS);
    }

    /** 트랙마다 앞 무음(자를 구간 시작보다 첫 표본이 늦은 만큼) · 무음 대체(묶음 전체 패킷 0)(7.4 소리 묶음 5 · 6). */
    private List<AudioRun.TrackStart> trackStarts(int gi, List<SegmentTiming> files, long[] offsetUs,
                                                  long startU) {
        List<AudioRun.TrackStart> out = new ArrayList<>();
        for (int n : l.trackIds) {
            int fi = AudioFlow.firstWithPackets(files, n);
            if (fi < 0) {
                l.notes.add(new Note("render.track_silent", "run=" + gi + " track=" + n + " reason=no_packets"));
                out.add(new AudioRun.TrackStart(n, true, 0));
                continue;
            }
            out.add(new AudioRun.TrackStart(n, false,
                    AudioFlow.frontSilence(files.get(fi), n, l.ts, offsetUs[fi], startU)));
        }
        return out;
    }
}
