package com.pokeclip.clip.thumbnail;

import com.pokeclip.clip.segment.OffsetOrigin;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 결과 영상 안 자리를 내는 한 곳(POK-196 S-c5 · PDT 설계 r7 3.4 · 8.3). <b>렌더 배치표만 읽는다</b> — 렌더가 그래프에 넣은 값에서 바로
 * 만든 표({@code clips.placements})라, clip 이 장부 길이를 따로 쌓아 결과 자리를 셈하면 판 NULL · 이음 겹침에서 다른 장면이 나온다
 * (설계 8.3 대안 (라) 기각 · 예측 P14). 완성 사진 자리와 업로드 장면의 결과 끝이 이 값 하나를 쓴다.
 *
 * <p>배치 한 줄: 조각 {@code seq} 의 x′ = 0 이 결과 {@code zeroAtUs} 에 놓였고, 결과에 든 그 조각 몫이 x′ 로 {@code [fromUs, toUs]}
 * 다. {@code headLeadUs} = 그 조각 m(파일 머리 → 영상 첫 DTS). 이웃 조각 몫은 겹칠 수 있다 — 자리를 옮길 때 가리킨 조각 줄 하나만
 * 읽으므로 겹침이 옮긴 값을 바꾸지 않는다(설계 7.6).
 *
 * <p>모양이 틀린 표는 {@link #parse} 가 {@link IllegalArgumentException} 으로 거절한다 — 부르는 쪽은 옛 식 · 받은 값으로 떨어지고
 * 성공 처리를 되감지 않는다.
 */
public final class ResultPlacements {

    private static final long MICROS_PER_MILLI = 1_000;

    /** 배치 한 줄(렌더 {@code ClipReporter.PlacementItem} 과 같은 칸). */
    record Placement(long seq, long zeroAtUs, long fromUs, long toUs, long headLeadUs) {
    }

    private final List<Placement> placements;
    private final long lengthUs;

    private ResultPlacements(List<Placement> placements, long lengthUs) {
        this.placements = List.copyOf(placements);
        this.lengthUs = lengthUs;
    }

    /**
     * 완성 영상 줄에 적힌 묶음({@code {"placements": [...], "lengthUs": n}})을 읽는다. 배치는 하나 이상 · seq 가 늘어나는 순서 ·
     * 칸 다섯이 모두 정수 · {@code fromUs ≤ toUs} 여야 하고, {@code lengthUs} 는 0 이상 정수다.
     *
     * @throws IllegalArgumentException 모양이 틀렸다(칸 경로를 싣는다)
     */
    public static ResultPlacements parse(JsonNode stored) {
        if (stored == null || !stored.isObject()) {
            throw new IllegalArgumentException("placements: 묶음이 아니다");
        }
        JsonNode rows = stored.get("placements");
        if (rows == null || !rows.isArray() || rows.isEmpty()) {
            throw new IllegalArgumentException("placements: 배치가 없다");
        }
        List<Placement> placements = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Placement p = placement(rows.get(i), "placements[" + i + "]");
            if (!placements.isEmpty() && p.seq() <= placements.getLast().seq()) {
                throw new IllegalArgumentException("placements[" + i + "].seq: 늘어나는 순서가 아니다");
            }
            placements.add(p);
        }
        long lengthUs = integer(stored, "lengthUs", "lengthUs");
        if (lengthUs < 0) {
            throw new IllegalArgumentException("lengthUs: 음수");
        }
        return new ResultPlacements(placements, lengthUs);
    }

    private static Placement placement(JsonNode row, String path) {
        if (row == null || !row.isObject()) {
            throw new IllegalArgumentException(path + ": 줄이 아니다");
        }
        Placement p = new Placement(integer(row, "seq", path), integer(row, "zeroAtUs", path),
                integer(row, "fromUs", path), integer(row, "toUs", path), integer(row, "headLeadUs", path));
        if (p.fromUs() > p.toUs()) {
            throw new IllegalArgumentException(path + ": fromUs > toUs");
        }
        return p;
    }

    private static long integer(JsonNode node, String field, String path) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException(path + "." + field + ": 정수가 아니다");
        }
        return value.asLong();
    }

    /**
     * 조각 {@code seq} 안 {@code offsetMs}(원점 {@code origin} 에서 잰 ms) 자리가 결과 몇 ms 인가. 단위를 µs 로 맞춘 뒤({@code x′ =
     * offsetMs × 1000 − (FILE_HEAD ? headLeadUs : 0)}) 그 조각 몫으로 자르고 놓는다 — 놓은 뒤에 자르면 x′ 축과 결과 축이 섞인다
     * (cx2-2). 그 조각이 결과에 없으면 바로 앞 배치의 몫 끝(틈 뒤 · 계획 밖 뒤쪽), 앞 배치가 없으면 첫 배치의 몫 시작(계획 앞쪽)이다.
     * 마지막에 결과 {@code [0, lengthUs]} 로 자르고 ms 로 내린다.
     */
    public long resultMsOf(long seq, long offsetMs, OffsetOrigin origin) {
        long us = Math.clamp(placedUs(seq, offsetMs, origin), 0, lengthUs);
        return Math.floorDiv(us, MICROS_PER_MILLI);
    }

    private long placedUs(long seq, long offsetMs, OffsetOrigin origin) {
        Placement before = null;
        for (Placement p : placements) {
            if (p.seq() == seq) {
                long lead = origin == OffsetOrigin.FILE_HEAD ? p.headLeadUs() : 0;
                long x = Math.subtractExact(Math.multiplyExact(offsetMs, MICROS_PER_MILLI), lead);
                return Math.addExact(p.zeroAtUs(), Math.clamp(x, p.fromUs(), p.toUs()));
            }
            if (p.seq() < seq) {
                before = p;
            }
        }
        if (before != null) {
            return Math.addExact(before.zeroAtUs(), before.toUs());
        }
        Placement first = placements.getFirst();
        return Math.addExact(first.zeroAtUs(), first.fromUs());
    }

    /** 결과 길이(ms 내림). */
    public long lengthMs() {
        return Math.floorDiv(lengthUs, MICROS_PER_MILLI);
    }

    /** 결과 영상 ms 를 결과 안 {@code [0, 결과 끝)} 으로 자른다(ms 정수라 끝은 {@code lengthMs − 1} · 결과가 1ms 보다 짧으면 0). */
    public long clampToResult(long ms) {
        return Math.clamp(ms, 0, Math.max(0, lengthMs() - 1));
    }

    /** 결과 가운데(ms 내림) — 완성 사진에 고를 카드가 없을 때. */
    public long middleMs() {
        return Math.floorDiv(lengthUs / 2, MICROS_PER_MILLI);
    }
}
