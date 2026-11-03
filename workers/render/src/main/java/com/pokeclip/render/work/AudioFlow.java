package com.pokeclip.render.work;

import com.pokeclip.render.media.SegmentTiming;
import com.pokeclip.render.work.RenderTimeline.Note;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 소리 묶음 흐름(concat demuxer 가 이은 파일들) 위의 소리 자리 산식(PDT 설계 r7 7.4 소리 묶음 5 · 6)과, 그 값을 싣는
 * {@code render.audio_pad} 신호(설계 15절). 소리 time_base 가 영상과 달라 정수로 맞지 않는 자리라 큰 정수로 센다.
 * {@link RenderTimeline} 의 소리 묶음 작업대만 쓴다.
 */
final class AudioFlow {

    private static final long MICROS = 1_000_000;
    private static final long OUTPUT_RATE = RenderTimeline.OUTPUT_RATE;

    private AudioFlow() {
    }

    /** 트랙 패킷이 있는 첫 파일 번호. 없으면 −1(그 트랙은 무음 대체). */
    static int firstWithPackets(List<SegmentTiming> files, int track) {
        for (int i = 0; i < files.size(); i++) {
            if (hasPackets(files.get(i), track)) {
                return i;
            }
        }
        return -1;
    }

    private static int lastWithPackets(List<SegmentTiming> files, int track) {
        for (int i = files.size() - 1; i >= 0; i--) {
            if (hasPackets(files.get(i), track)) {
                return i;
            }
        }
        throw new IllegalStateException("트랙 " + track + " 패킷이 묶음에 없다");
    }

    private static boolean hasPackets(SegmentTiming f, int track) {
        return track < f.audio().size() && f.audio().get(track).packets() > 0;
    }

    /**
     * 흐름 시각 {@code S_fi + first/T − st_fi} 가 자를 구간 시작보다 늦은 만큼(표본 · 반올림).
     *
     * @param ts       영상 원래 틱
     * @param offsetUs 그 파일의 흐름 자리 S(µs)
     * @param startU   자를 구간 시작(µs × ts)
     */
    static long frontSilence(SegmentTiming f, int track, long ts, long offsetUs, long startU) {
        BigInteger bts = BigInteger.valueOf(ts);
        BigInteger tb = BigInteger.valueOf(f.audio().get(track).timescale());
        BigInteger micros = BigInteger.valueOf(MICROS);
        BigInteger fileU = BigInteger.valueOf(offsetUs - f.formatStartUs()).multiply(bts)
                .subtract(BigInteger.valueOf(startU));
        BigInteger num = fileU.multiply(tb)
                .add(BigInteger.valueOf(f.audio().get(track).firstPts()).multiply(bts).multiply(micros));
        if (num.signum() <= 0) {
            return 0;
        }
        BigInteger den = tb.multiply(bts).multiply(micros);
        BigInteger scaled = num.multiply(BigInteger.valueOf(OUTPUT_RATE));
        return scaled.shiftLeft(1).add(den).divide(den.shiftLeft(1)).longValueExact();
    }

    /**
     * {@code render.audio_pad}(DEBUG) — 고른 트랙마다 앞 무음({@code adelay})과 끝 무음({@code apad}). 끝 무음 = 묶음 길이 −
     * (흐름 안 트랙 소리 끝과 자를 구간 끝 중 앞선 것 − 자를 구간 시작)이다. 무음 대체 트랙은 {@code render.track_silent} 가 이미
     * 말하고, 둘 다 1ms 미만이면 줄을 내지 않는다(묶음 · 트랙마다 쌓이지 않게).
     *
     * <p>이 값은 신호에만 쓴다. 산식이 넘치거나 불변식이 깨져도 결과를 짜는 데는 걸리지 않게, 그 트랙 줄만 WARN
     * {@code render.signal_skipped} 로 바꾸고 이어 간다.
     */
    static List<Note> padNotes(AudioRun run, List<SegmentTiming> files, long[] offsetUs) {
        List<Note> notes = new ArrayList<>();
        for (AudioRun.TrackStart track : run.tracks()) {
            if (track.silent()) {
                continue;
            }
            try {
                padNote(run, track, files, offsetUs).ifPresent(notes::add);
            } catch (ArithmeticException | IllegalStateException e) {
                notes.add(new Note("render.signal_skipped", "signal=render.audio_pad run=" + run.index()
                        + " track=" + track.trackId() + " reason="
                        + (e instanceof ArithmeticException ? "overflow" : "invariant")));
            }
        }
        return notes;
    }

    private static Optional<Note> padNote(AudioRun run, AudioRun.TrackStart track, List<SegmentTiming> files,
                                          long[] offsetUs) {
        int li = lastWithPackets(files, track.trackId());
        SegmentTiming f = files.get(li);
        long soundEnd = flowSample(f, track.trackId(), offsetUs[li], f.audio().get(track.trackId()).endPts());
        long covered = Math.min(run.toSample(), soundEnd) - run.fromSample();
        long back = Math.clamp(run.lengthSamples() - covered, 0, Math.max(run.lengthSamples(), 0));
        long frontMs = samplesToMs(track.frontSilenceSamples());
        long backMs = samplesToMs(back);
        if (frontMs <= 0 && backMs <= 0) {
            return Optional.empty();
        }
        return Optional.of(new Note("render.audio_pad", "run=" + run.index() + " track=" + track.trackId()
                + " frontMs=" + frontMs + " backMs=" + backMs));
    }

    /** 파일 f 의 소리 PTS 가 흐름에 놓이는 자리(48kHz 표본 · 반올림) — {@code S_f + pts/T − st_f}. */
    private static long flowSample(SegmentTiming f, int track, long offsetUs, long pts) {
        BigInteger tb = BigInteger.valueOf(f.audio().get(track).timescale());
        BigInteger micros = BigInteger.valueOf(MICROS);
        BigInteger num = BigInteger.valueOf(offsetUs - f.formatStartUs()).multiply(tb)
                .add(BigInteger.valueOf(pts).multiply(micros)).multiply(BigInteger.valueOf(OUTPUT_RATE));
        BigInteger den = tb.multiply(micros);
        BigInteger[] qr = num.shiftLeft(1).add(den).divideAndRemainder(den.shiftLeft(1));
        return (qr[1].signum() < 0 ? qr[0].subtract(BigInteger.ONE) : qr[0]).longValueExact();
    }

    /** 표본 → ms(반올림 · 표본 ≥ 0). */
    private static long samplesToMs(long samples) {
        return Math.floorDiv(Math.addExact(Math.multiplyExact(samples, 2_000), OUTPUT_RATE), 2 * OUTPUT_RATE);
    }
}
