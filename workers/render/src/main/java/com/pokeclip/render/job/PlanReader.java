package com.pokeclip.render.job;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 주문서 {@code plan}을 {@link RenderPlan}으로 읽는다(PDT 설계 r7 5.2). 봉투와 달리 <b>계획은 fail-closed</b>다 — 모르는 칸이
 * 어디에 오든 거절한다. 거둔 갈래(채움 · 틈 근거)가 되살아나 조용히 무시되지 않게 하려는 것이다(설계 5.3 2).
 *
 * <p>칸의 꼴이 틀리거나 모르는 칸이 오면 그 칸이 속한 검사 규칙 번호로 거절한다(원점 · 판 = 1, 계획 맨 위 · 매체 열 = 2,
 * 시작 · 끝 = 4, 자막 = 5, 길이 = 6). 칸 사이 · 주문서 항목과의 맞물림은 {@link PlanCheck}가 본다.
 *
 * <p>거절 문장은 로그와 사용자 화면({@code clips.error_message})까지 가므로 입력에서 온 글자(칸 이름 · 값)를 싣지 않는다.
 */
final class PlanReader {

    static final int PLAN_VERSION = 1;

    private static final Set<String> PLAN_FIELDS =
            Set.of("planVersion", "offsetOrigin", "in", "out", "mediaSeqs", "subtitles", "lengthMs");
    private static final Set<String> COORD_FIELDS = Set.of("seq", "offsetUs");
    private static final Set<String> SEGMENT_END_FIELDS = Set.of("seq", "toSegmentEnd");
    private static final Set<String> SUBTITLE_FIELDS = Set.of("index", "start", "end");
    private static final Set<String> GAP_FIELDS = Set.of("afterSeq", "intoGapMs");

    private PlanReader() {
    }

    static RenderPlan read(JsonNode plan) {
        if (!plan.isObject()) {
            throw new PlanViolation(1, "plan이 객체가 아니다");
        }
        onlyKnown(plan, PLAN_FIELDS, 2, "plan");
        JsonNode version = plan.get("planVersion");
        if (!JsonIntegers.isLong(version) || version.asLong() != PLAN_VERSION) {
            throw new PlanViolation(1, "plan.planVersion을 지원하지 않는다");
        }
        return new RenderPlan(PLAN_VERSION, origin(plan.get("offsetOrigin")), in(plan.get("in")), out(plan.get("out")),
                mediaSeqs(plan.get("mediaSeqs")), subtitles(plan.get("subtitles")),
                whole(plan, "lengthMs", 6, "plan"));
    }

    private static OffsetOrigin origin(JsonNode node) {
        if (node == null || !node.isString()) {
            throw new PlanViolation(1, "plan.offsetOrigin이 없다");
        }
        try {
            return OffsetOrigin.valueOf(node.asString());
        } catch (IllegalArgumentException e) {
            throw new PlanViolation(1, "plan.offsetOrigin은 모르는 값이다");
        }
    }

    private static MediaCoord in(JsonNode node) {
        object(node, 4, "plan.in");
        onlyKnown(node, COORD_FIELDS, 4, "plan.in");
        return new MediaCoord(whole(node, "seq", 4, "plan.in"), whole(node, "offsetUs", 4, "plan.in"));
    }

    private static EndBoundary out(JsonNode node) {
        object(node, 4, "plan.out");
        long seq = whole(node, "seq", 4, "plan.out");
        if (node.has("toSegmentEnd") && node.has("offsetUs")) {
            throw new PlanViolation(4, "plan.out은 offsetUs와 toSegmentEnd 가운데 하나만 적는다");
        }
        if (node.has("toSegmentEnd")) {
            onlyKnown(node, SEGMENT_END_FIELDS, 4, "plan.out");
            if (!node.get("toSegmentEnd").isBoolean() || !node.get("toSegmentEnd").asBoolean()) {
                throw new PlanViolation(4, "plan.out.toSegmentEnd는 true만 적는다");
            }
            return new EndBoundary(seq, null, true);
        }
        onlyKnown(node, COORD_FIELDS, 4, "plan.out");
        return new EndBoundary(seq, whole(node, "offsetUs", 4, "plan.out"), false);
    }

    private static List<Long> mediaSeqs(JsonNode node) {
        if (node == null || !node.isArray()) {
            throw new PlanViolation(2, "plan.mediaSeqs가 배열이 아니다");
        }
        List<Long> seqs = new ArrayList<>();
        for (JsonNode seq : node) {
            if (!JsonIntegers.isLong(seq)) {
                throw new PlanViolation(2, "plan.mediaSeqs[]가 정수가 아니다");
            }
            seqs.add(seq.asLong());
        }
        return seqs;
    }

    private static List<SubtitleSpan> subtitles(JsonNode node) {
        if (node == null || !node.isArray()) {
            throw new PlanViolation(5, "plan.subtitles가 배열이 아니다");
        }
        List<SubtitleSpan> spans = new ArrayList<>();
        for (JsonNode s : node) {
            object(s, 5, "plan.subtitles[]");
            onlyKnown(s, SUBTITLE_FIELDS, 5, "plan.subtitles[]");
            JsonNode index = s.get("index");
            if (!JsonIntegers.isInt(index)) {
                throw new PlanViolation(5, "plan.subtitles[].index가 정수가 아니다");
            }
            spans.add(new SubtitleSpan(index.asInt(), point(s.get("start")), point(s.get("end"))));
        }
        return spans;
    }

    private static SubtitlePoint point(JsonNode node) {
        String where = "plan.subtitles[] 끝점";
        object(node, 5, where);
        if (node.has("afterSeq")) {
            onlyKnown(node, GAP_FIELDS, 5, where);
            return new SubtitlePoint.InGap(whole(node, "afterSeq", 5, where), whole(node, "intoGapMs", 5, where));
        }
        onlyKnown(node, COORD_FIELDS, 5, where);
        return new SubtitlePoint.AtMedia(new MediaCoord(whole(node, "seq", 5, where), whole(node, "offsetUs", 5, where)));
    }

    private static void object(JsonNode node, int rule, String where) {
        if (node == null || !node.isObject()) {
            throw new PlanViolation(rule, where + "가 객체가 아니다");
        }
    }

    /** 모르는 칸이 있으면 {@code rule} 로 거절한다. 칸 이름은 입력이라 문장에 싣지 않는다. */
    static void onlyKnown(JsonNode node, Set<String> known, int rule, String where) {
        for (String name : node.propertyNames()) {
            if (!known.contains(name)) {
                throw new PlanViolation(rule, where + "에 모르는 칸이 있다");
            }
        }
    }

    private static long whole(JsonNode node, String field, int rule, String where) {
        JsonNode value = node.get(field);
        if (!JsonIntegers.isLong(value)) {
            throw new PlanViolation(rule, where + "." + field + "가 정수가 아니다");
        }
        return value.asLong();
    }
}
