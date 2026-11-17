package com.pokeclip.ledger.timeline;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 공유 시험 표 {@code ledger-timeline-cases.json} 의 services 쪽 읽개(설계 r7 3.5 · r6 3.4).
 *
 * <p>표는 services · 렌더 일꾼 · 웹 세 곳이 읽는 계약이라 읽는 쪽은 fail-closed다 — 뿌리 {@code version} 이
 * 다르거나, 모르는 {@code kind} · {@code op} · {@code axis} 가 있거나, {@code pdt} 사례에 판 NULL 행이 섞였거나,
 * services 몫 갈래가 비면 {@link IllegalStateException} 으로 시험을 실패시킨다. 조용히 건너뛰면 표가 깨져도
 * 초록이 된다.
 */
final class LedgerTimelineCases {

    /**
     * 표 뿌리 판. r6 3.4 의 1 에서 r7 이 {@code F} 에 {@code axis} · {@code timingVersion} · {@code offsetOrigin} 칸을
     * 더하고, r6 의 {@code resultOffset} 갈래를 거두고 {@code length} 갈래를 새로 두어 2.
     */
    static final int VERSION = 2;

    private static final String RESOURCE = "/ledger-timeline-cases.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 이 표가 아는 갈래 전부. services 몫이 아닌 갈래도 알아야 모르는 갈래와 가를 수 있다. */
    private static final Set<String> KINDS = Set.of("F", "length", "audioJoin");
    /** 비어 있으면 안 되는 갈래 — services 몫(F · length)과, 여기서 식 대조를 하는 audioJoin. */
    private static final List<String> REQUIRED_KINDS = List.of("F", "length", "audioJoin");
    private static final Set<String> OPS = Set.of("locate", "locateEnd");

    private final List<JsonNode> cases;

    private LedgerTimelineCases(List<JsonNode> cases) {
        this.cases = List.copyOf(cases);
    }

    static LedgerTimelineCases load() {
        try (InputStream in = LedgerTimelineCases.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("공유 시험 표가 클래스패스에 없다: " + RESOURCE);
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static LedgerTimelineCases parse(String json) {
        JsonNode root = MAPPER.readTree(json);
        int version = root.path("version").asInt(-1);
        if (version != VERSION) {
            throw new IllegalStateException("표 version 이 " + VERSION + " 이 아니다: " + root.get("version"));
        }
        List<JsonNode> cases = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode c : root.path("cases")) {
            String id = c.path("id").asString("");
            if (id.isEmpty() || !ids.add(id)) {
                throw new IllegalStateException("사례 id 가 비었거나 겹친다: " + c.get("id"));
            }
            requireKnownShape(c);
            cases.add(c);
        }
        LedgerTimelineCases table = new LedgerTimelineCases(cases);
        for (String kind : REQUIRED_KINDS) {
            if (table.ofKind(kind).isEmpty()) {
                throw new IllegalStateException("갈래 " + kind + " 사례가 하나도 없다");
            }
        }
        return table;
    }

    List<JsonNode> ofKind(String kind) {
        return cases.stream().filter(c -> c.get("kind").asString().equals(kind)).toList();
    }

    static List<LedgerRow> rowsOf(JsonNode fCase) {
        List<LedgerRow> rows = new ArrayList<>();
        fCase.get("rows").forEach(r -> rows.add(new LedgerRow(r.get("seq").asLong(), r.get("startAtMs").asLong(),
                r.get("durationMs").asInt(), r.get("continuesPrevious").asBoolean())));
        return rows;
    }

    static Place expectedPlaceOf(JsonNode fCase) {
        JsonNode e = fCase.get("expect");
        return switch (e.get("place").asString()) {
            case "media" -> new Place.Media(e.get("seq").asLong(), e.get("offsetMs").asLong());
            case "gap" -> new Place.Gap(e.get("beforeSeq").asLong(), e.get("afterSeq").asLong(),
                    e.get("intoGapMs").asLong(), e.get("gapMs").asLong());
            case "beforeFirst" -> new Place.BeforeFirst();
            case "afterLast" -> new Place.AfterLast();
            default -> throw new IllegalStateException("모르는 place: " + e.get("place"));
        };
    }

    /** 사례의 축과 고른 행(seq)의 판으로 정한 원점 표지(설계 r7 3.2 표 · 3.5). */
    static String originOf(JsonNode fCase, long seq) {
        Axis axis = Axis.of(fCase.get("axis").asString());
        for (JsonNode r : fCase.get("rows")) {
            if (r.get("seq").asLong() == seq) {
                return axis.originOf(timingVersionOf(r));
            }
        }
        throw new IllegalStateException(fCase.get("id") + ": 고른 seq " + seq + " 행이 rows 에 없다");
    }

    /** F 사례의 입력 칸 {@code axis}. 원점 표지 규칙은 설계 r7 3.2 표 그대로다. */
    enum Axis {
        /** {@code start_wall_utc} — 편집기 출처. 판과 무관하게 파일 머리. */
        WALL("wall"),
        /** {@code playback_pdt} — 서버 시계 판 2. 판 2 행만 싣는다. */
        PDT("pdt"),
        /** {@code start_pts_ms} — 카드 축. 고른 행의 판으로 정한다. */
        START_PTS("startPts");

        private final String json;

        Axis(String json) {
            this.json = json;
        }

        static Axis of(String json) {
            return Arrays.stream(values()).filter(a -> a.json.equals(json)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("모르는 axis: " + json));
        }

        String originOf(Integer timingVersion) {
            return switch (this) {
                case WALL -> "FILE_HEAD";
                case PDT -> "VIDEO_START";
                case START_PTS -> timingVersion == null ? "FILE_HEAD" : "VIDEO_START";
            };
        }
    }

    private static void requireKnownShape(JsonNode c) {
        String kind = c.path("kind").asString("");
        if (!KINDS.contains(kind)) {
            throw new IllegalStateException(c.get("id") + ": 모르는 kind: " + c.get("kind"));
        }
        if (!kind.equals("F")) {
            return;
        }
        if (!OPS.contains(c.path("op").asString(""))) {
            throw new IllegalStateException(c.get("id") + ": 모르는 op: " + c.get("op"));
        }
        Axis axis = Axis.of(c.path("axis").asString(""));
        for (JsonNode r : c.path("rows")) {
            Integer timingVersion = timingVersionOf(r);
            if (axis == Axis.PDT && timingVersion == null) {
                throw new IllegalStateException(c.get("id") + ": pdt 사례에 판 NULL 행이 섞였다(seq "
                        + r.get("seq") + ") — 판 NULL 은 서버 시계 출처도 start_wall_utc 축이다");
            }
        }
    }

    /** 행마다 {@code timingVersion} 칸은 꼭 있어야 하고 값은 2 또는 null 이다(설계 r7 3.5). */
    private static Integer timingVersionOf(JsonNode row) {
        JsonNode v = row.get("timingVersion");
        if (v == null) {
            throw new IllegalStateException("행에 timingVersion 칸이 없다: seq " + row.get("seq"));
        }
        if (v.isNull()) {
            return null;
        }
        if (v.isInt() && v.asInt() == 2) {
            return 2;
        }
        throw new IllegalStateException("timingVersion 은 2 또는 null 이다: seq " + row.get("seq") + " 값 " + v);
    }
}
