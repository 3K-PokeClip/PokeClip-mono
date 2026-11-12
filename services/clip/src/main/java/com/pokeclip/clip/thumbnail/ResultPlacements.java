package com.pokeclip.clip.thumbnail;

import com.pokeclip.clip.segment.OffsetOrigin;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
 *
 * <p><b>칸을 엄격히 거른다</b>(모르는 칸 · 정수 아닌 칸은 거절) — 받는 자리가 렌더 보고를 그대로 JSONB 에 쓰므로, 아는 정수 칸만
 * 있어야 NUL 같은 JSONB 가 못 받는 글자가 DB 에 닿지 않는다(커밋 리뷰 r1). 그래서 렌더가 줄이나 묶음에 칸을 더하면 <b>clip 을
 * 먼저 배포</b>한다 — 렌더가 먼저 나가면 모든 배치표가 WARN 하나만 남기고 버려져 옛 식으로 떨어진다(성공은 남는다).
 */
public final class ResultPlacements {

    private static final long MICROS_PER_MILLI = 1_000;

    /**
     * 결과 길이 상한(µs) — 렌더 결과 끝 상한 180초({@code workers/render} {@code RenderTimeline.CAP_US} · 계약6 컷 5–180초 ·
     * {@code RecipeValidator.MAX_CUT_MS}). 렌더가 이보다 긴 결과를 낼 길이 없다.
     */
    public static final long MAX_LENGTH_US = 180_000_000;

    /**
     * 배치 칸({@code zeroAtUs} · {@code fromUs} · {@code toUs} · {@code headLeadUs}) 절댓값 상한(µs) — 결과 길이 상한 위 60초 여유.
     * 첫 조각 x′ 0 자리는 결과 앞(음수 {@code zeroAtUs})에, 몫 끝은 조각 길이(4초 조각 · 길어야 수 초)만큼 결과 밖에 놓일 수 있어
     * 결과 길이만으로는 좁고, 60초면 어느 조각 하나의 길이보다 넉넉하다. 이 안의 값끼리는 자리 계산의 exact 연산이 넘치지 않는다.
     */
    public static final long MAX_ABS_US = MAX_LENGTH_US + 60_000_000;

    /** 주문서 크기 상한(UTF-8 바이트) — 계약1 2절 200KB · {@code pokeclip.render.max-message-bytes}(204800). */
    static final int MAX_ORDER_BYTES = 204_800;

    /**
     * 매체 조각 하나가 주문서에서 차지하는 최소 바이트 — 창고 · 키가 빈 {@code sourceKeys} {@code segment} 항목(쉼표 포함 119)과
     * {@code plan.mediaSeqs} 한 자리(쉼표 포함 2). 실제 항목은 창고 이름 · 키만큼 더 길다.
     */
    static final int MIN_ORDER_BYTES_PER_MEDIA = 119 + 2;

    /**
     * 읽는 쪽 배치 줄 수 상한 — 주문서 크기 상한에 들어갈 수 있는 매체 조각 수의 최댓값(204,800 ÷ 121 = 1,692). 렌더는 매체 조각마다
     * 배치 한 줄을 내고(결과 끝 소유로 빠진 조각은 줄이 없다) 렌더 · 계획에 조각 수 상한이 따로 없으므로, 정상 표는 이 수를 넘을 수
     * 없다. 받는 자리는 그 잡 주문의 실제 매체 조각 수로 더 좁힌다({@link #parse(JsonNode, int)}). 운영 설정의 주문서 상한을 올리면
     * 이 값도 함께 올린다.
     */
    public static final int MAX_ROWS = MAX_ORDER_BYTES / MIN_ORDER_BYTES_PER_MEDIA;

    /**
     * 가장 긴 배치 한 줄의 글자 수(쉼표 포함) — seq 19자리(long 최대) · {@code zeroAtUs} · {@code fromUs} · {@code toUs} 가
     * −{@link #MAX_ABS_US}(10자) · {@code headLeadUs} 가 {@link #MAX_ABS_US}(9자).
     */
    static final int MAX_ROW_CHARS = 111;

    /** 묶음 껍데기 {@code {"placements":[],"lengthUs":180000000}} 의 글자 수. */
    static final int LAYOUT_SHELL_CHARS = 38;

    private static final Set<String> LAYOUT_FIELDS = Set.of("placements", "lengthUs");
    private static final Set<String> ROW_FIELDS = Set.of("seq", "zeroAtUs", "fromUs", "toUs", "headLeadUs");

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
     * 완성 영상 줄에 적힌 묶음({@code {"placements": [...], "lengthUs": n}})을 읽는다. 받는 자리(렌더 성공 보고 저장)와 읽는 자리가
     * 같은 검사를 쓴다 — 저장 전에 걸러 JSONB 가 받지 못할 값이 DB 에 닿지 않게 하고, 이미 저장된 옛 값도 읽을 때 거른다.
     *
     * <ul>
     *   <li>묶음 칸은 {@code placements} · {@code lengthUs} 뿐이고, 줄마다 칸은 {@code seq} · {@code zeroAtUs} · {@code fromUs}
     *       · {@code toUs} · {@code headLeadUs} 다섯뿐이며 모두 정수다 — 글자 칸이 없으므로 NUL 같은 글자도 없다.</li>
     *   <li>배치는 1–{@link #MAX_ROWS} 줄(받는 자리는 그 잡 주문의 매체 조각 수까지) · seq 는 0 이상이고 늘어나는 순서 · {@code fromUs ≤ toUs}.</li>
     *   <li>{@code 0 ≤ lengthUs ≤ }{@link #MAX_LENGTH_US} · 배치 칸 절댓값 ≤ {@link #MAX_ABS_US} · {@code headLeadUs ≥ 0}.</li>
     * </ul>
     *
     * @throws IllegalArgumentException 모양 · 범위가 틀렸다(칸 경로를 싣는다 — 입력 값은 싣지 않는다)
     */
    public static ResultPlacements parse(JsonNode stored) {
        return parse(stored, MAX_ROWS);
    }

    /**
     * {@link #parse(JsonNode)} 와 같되 줄 수 상한을 {@code maxRows}(그 잡 주문의 매체 조각 수 — 받는 자리)로 좁힌다. 렌더는 매체
     * 조각마다 한 줄을 내므로 정상 표는 이 수를 넘지 않는다. {@link #MAX_ROWS} 보다 크게 주어도 {@link #MAX_ROWS} 다.
     */
    public static ResultPlacements parse(JsonNode stored, int maxRows) {
        if (stored == null || !stored.isObject()) {
            throw new IllegalArgumentException("placements: 묶음이 아니다");
        }
        onlyFields(stored, LAYOUT_FIELDS, "layout");
        JsonNode rows = stored.get("placements");
        if (rows == null || !rows.isArray() || rows.isEmpty()) {
            throw new IllegalArgumentException("placements: 배치가 없다");
        }
        if (rows.size() > Math.min(maxRows, MAX_ROWS)) {
            throw new IllegalArgumentException("placements: 줄 수 상한을 넘었다");
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
        if (lengthUs < 0 || lengthUs > MAX_LENGTH_US) {
            throw new IllegalArgumentException("lengthUs: 범위 밖");
        }
        return new ResultPlacements(placements, lengthUs);
    }

    /**
     * 줄 {@code rows} 개짜리 정상 배치표 묶음이 글로 가질 수 있는 최대 글자 수 — 받는 자리의 글 길이 상한이다. 정상 표는 이 길이를
     * 넘을 수 없고, 넘으면 모르는 칸 · 공백 같은 덧붙임이 든 것이라 칸을 읽기 전에 버린다.
     */
    public static int maxStoredChars(int rows) {
        return Math.multiplyExact(Math.min(rows, MAX_ROWS), MAX_ROW_CHARS) + LAYOUT_SHELL_CHARS;
    }

    private static Placement placement(JsonNode row, String path) {
        if (row == null || !row.isObject()) {
            throw new IllegalArgumentException(path + ": 줄이 아니다");
        }
        onlyFields(row, ROW_FIELDS, path);
        Placement p = new Placement(integer(row, "seq", path), bounded(row, "zeroAtUs", path),
                bounded(row, "fromUs", path), bounded(row, "toUs", path), bounded(row, "headLeadUs", path));
        if (p.seq() < 0) {
            throw new IllegalArgumentException(path + ".seq: 음수");
        }
        if (p.headLeadUs() < 0) {
            throw new IllegalArgumentException(path + ".headLeadUs: 음수");
        }
        if (p.fromUs() > p.toUs()) {
            throw new IllegalArgumentException(path + ": fromUs > toUs");
        }
        return p;
    }

    private static void onlyFields(JsonNode node, Set<String> allowed, String path) {
        for (String name : node.propertyNames()) {
            if (!allowed.contains(name)) {
                throw new IllegalArgumentException(path + ": 모르는 칸");
            }
        }
    }

    /** 배치 칸 — 정수이고 절댓값이 {@link #MAX_ABS_US} 이하. */
    private static long bounded(JsonNode node, String field, String path) {
        long value = integer(node, field, path);
        if (value < -MAX_ABS_US || value > MAX_ABS_US) {
            throw new IllegalArgumentException(path + "." + field + ": 범위 밖");
        }
        return value;
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
