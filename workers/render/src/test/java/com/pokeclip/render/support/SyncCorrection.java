package com.pokeclip.render.support;

import com.pokeclip.render.support.DirectSegments.Origin;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 소리 싱크 오라클의 판정(PDT 설계 r7 13.5 「싱크 판정선」 · 「보정식」). 시험 도우미다 — 제품 코드는 이 보정을 하지 않는다.
 *
 * <ul>
 *   <li>조각 k 의 tfdt 몫 {@code τ_k = V_k − Σ(영상 패킷 길이)}(틱). 연결 첫 조각도 실제 패킷 길이 합으로 센다(글자 그대로의
 *       {@code V − n·간격} 이 아니다).</li>
 *   <li>{@code Σ(q)} = 확인점 조각 q 가 속한 소리 묶음의 머리부터 q 바로 앞 조각까지 τ 의 합. 묶음에 준비 조각이 실리면 그 τ 가
 *       첫 항이다. 묶음 경계에서 0 으로 다시 센다.</li>
 *   <li>보정 뒤 잔차 = 원시 오차 + Σ × (1000 ÷ 90000)ms. 원시 오차 = 출력 소리 자리 − 영상 계획 기대 자리(기준 자리 반올림 없음).</li>
 *   <li>이상 사건({@code |τ| > 1} 이거나 소리 패킷 간격이 1024 ± 1 밖) 조각이 확인점 묶음 안 q 까지(q 포함 · 준비 조각 포함)에
 *       있으면 그 확인점은 판정에서 빼고 따로 센다. 하나라도 빠지면 PASS 가 아니다.</li>
 *   <li>보정은 녹화기를 거친 자료(녹화 · 녹화 사본)에만 쓴다. 바로 쓴 합성은 보정 없이 같은 판정선이다.</li>
 * </ul>
 *
 * <p>판정선 비교는 반올림 전 정확값으로 한다: {@code |잔차| ≥ 0.1ms} 면 FAIL — 정확히 판정선과 같은 차도 FAIL 이다.
 */
public final class SyncCorrection {

    /** 합성 자료 소리 싱크 판정선(ms). */
    public static final BigDecimal LINE_MS = new BigDecimal("0.1");
    /** 1틱(1/90000초)의 ms 분모 — 잔차 × 90 = 원시 × 90 + Σ. */
    private static final BigDecimal TICKS_PER_MS = BigDecimal.valueOf(90);

    public enum Verdict { PASS, FAIL, EXCLUDED }

    /**
     * 녹화 원본 조각 판독 한 줄(소리 흐름 순서).
     *
     * @param prime     디코더 준비 조각(바로 뒤 머리 묶음의 첫 항)
     * @param audioGaps 소리 패킷 간격이 1024 ± 1 밖인 자리 수
     */
    public record Segment(long seq, int frames, long videoTicks, long packetTicks, long intervalTicks,
                          boolean runHead, boolean prime, int audioGaps) {

        public long tau() {
            return videoTicks - packetTicks;
        }

        public boolean anomaly() {
            return Math.abs(tau()) > 1 || audioGaps > 0;
        }
    }

    public record Checkpoint(long seq, BigDecimal rawErrorMs) {
    }

    public record Judged(Checkpoint checkpoint, long sigmaTicks, BigDecimal residualTimes90, boolean excluded) {

        /** 보정 뒤 잔차(ms · 표시용 반올림). 판정은 {@link #residualTimes90} 정확값으로 한다. */
        public double residualMs() {
            return residualTimes90.doubleValue() / 90;
        }
    }

    private SyncCorrection() {
    }

    public static boolean corrects(Origin origin) {
        return origin != Origin.DIRECT;
    }

    /** 확인점 조각 q 의 Σ(q)(틱). */
    public static long sigma(List<Segment> flow, long seq) {
        long sum = 0;
        for (Segment s : run(flow, seq)) {
            if (s.seq() == seq && !s.prime()) {
                break;
            }
            sum += s.tau();
        }
        return sum;
    }

    /** q 가 속한 묶음의 준비 조각 · 머리 · … · q 까지. */
    static List<Segment> run(List<Segment> flow, long seq) {
        int at = -1;
        for (int i = 0; i < flow.size(); i++) {
            if (!flow.get(i).prime() && flow.get(i).seq() == seq) {
                at = i;
            }
        }
        if (at < 0) {
            throw new IllegalArgumentException("조각 " + seq + " 이 흐름에 없다");
        }
        int head = at;
        while (!flow.get(head).runHead()) {
            head--;
        }
        if (head > 0 && flow.get(head - 1).prime()) {
            head--;
        }
        return new ArrayList<>(flow.subList(head, at + 1));
    }

    public static List<Judged> judge(Origin origin, List<Segment> flow, List<Checkpoint> checkpoints) {
        List<Judged> out = new ArrayList<>();
        for (Checkpoint c : checkpoints) {
            boolean excluded = corrects(origin) && run(flow, c.seq()).stream().anyMatch(Segment::anomaly);
            long sigma = corrects(origin) ? sigma(flow, c.seq()) : 0;
            BigDecimal times90 = c.rawErrorMs().multiply(TICKS_PER_MS).add(BigDecimal.valueOf(sigma));
            out.add(new Judged(c, sigma, times90, excluded));
        }
        return out;
    }

    /** 빠진 확인점이 있으면 EXCLUDED, 남은 확인점 가운데 하나라도 |잔차| ≥ 0.1ms 면 FAIL. */
    public static Verdict verdict(List<Judged> judged) {
        BigDecimal line90 = LINE_MS.multiply(TICKS_PER_MS);
        if (judged.stream().anyMatch(Judged::excluded)) {
            return Verdict.EXCLUDED;
        }
        boolean over = judged.stream().anyMatch(j -> j.residualTimes90().abs().compareTo(line90) >= 0);
        return over ? Verdict.FAIL : Verdict.PASS;
    }
}
