package com.pokeclip.ledger.timeline;

import com.pokeclip.ledger.timeline.LedgerTimelineCases.Axis;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 공유 시험 표({@code ledger-timeline-cases.json})를 services 몫으로 읽는다(설계 r7 3.5 · r6 3.4).
 *
 * <p>services 몫은 {@code F} · {@code length}다. {@code audioJoin}은 렌더 일꾼(R-c1) · 웹 몫이지만, 렌더가 읽기
 * 전까지 표의 숫자가 r6 3.4 식과 맞는지 아무도 보지 않으므로 여기서 식으로 대조만 한다(제품 코드는 없다).
 *
 * <p>표가 깨졌을 때 초록이 되지 않게 읽는 쪽은 fail-closed다 — 아래 「거절한다」 시험들이 그 규칙이다.
 */
class LedgerTimelineCasesTest {

    private static final LedgerTimelineCases TABLE = LedgerTimelineCases.load();

    @TestFactory
    Stream<DynamicTest> F_사례마다_locate_결과와_원점_표지가_기대와_같다() {
        return TABLE.ofKind("F").stream().map(c -> DynamicTest.dynamicTest(c.get("id").asString(), () -> {
            List<LedgerRow> rows = LedgerTimelineCases.rowsOf(c);
            long t = c.get("t").asLong();
            Place actual = switch (c.get("op").asString()) {
                case "locate" -> LedgerTimeline.locate(rows, t);
                case "locateEnd" -> LedgerTimeline.locateEnd(rows, t);
                default -> throw new AssertionError("op은 표를 읽을 때 걸렀다: " + c.get("op"));
            };

            assertThat(actual).isEqualTo(LedgerTimelineCases.expectedPlaceOf(c));
            if (actual instanceof Place.Media media) {
                assertThat(c.get("expect").get("offsetOrigin").asString())
                        .as("매체 자리의 원점 표지는 축 규칙(설계 r7 3.2 표)대로다")
                        .isEqualTo(LedgerTimelineCases.originOf(c, media.seq()));
            }
        }));
    }

    @TestFactory
    Stream<DynamicTest> length_사례마다_예상_길이가_기대와_같다() {
        return TABLE.ofKind("length").stream().map(c -> DynamicTest.dynamicTest(c.get("id").asString(), () -> {
            List<Long> parts = new ArrayList<>();
            c.get("joinedCutPartMs").forEach(n -> parts.add(n.asLong()));

            assertThat(LedgerTimeline.lengthMs(c.get("inMs").asLong(), c.get("outMs").asLong(), parts))
                    .isEqualTo(c.get("expectLengthMs").asLong());
        }));
    }

    /** r6 3.4 식 — gap_T = (next.startT − next.videoStart) − (prev.endT − prev.videoEnd), 반 프레임 이상이면 그 값. */
    @TestFactory
    Stream<DynamicTest> audioJoin_사례의_숫자가_r6_식과_맞다() {
        return TABLE.ofKind("audioJoin").stream().map(c -> DynamicTest.dynamicTest(c.get("id").asString(), () -> {
            JsonNode prev = c.get("prev");
            JsonNode next = c.get("next");
            boolean applies = prev.has("videoStartMs") && next.has("videoStartMs");

            assertThat(c.get("applies").asBoolean()).isEqualTo(applies);
            JsonNode expected = c.get("expectSilenceMs");
            assertThat(expected.size()).isEqualTo(next.get("tracks").size());
            for (int i = 0; i < expected.size(); i++) {
                assertThat(expected.get(i).asDouble()).as("트랙 %d", i).isEqualTo(silenceOf(prev, next, i, applies));
            }
        }));
    }

    private static double silenceOf(JsonNode prev, JsonNode next, int track, boolean applies) {
        if (!applies) {
            return 0;
        }
        JsonNode p = prev.get("tracks").get(track);
        double gap = (next.get("tracks").get(track).get("startMs").asDouble() - next.get("videoStartMs").asDouble())
                - (p.get("endMs").asDouble() - prev.get("videoEndMs").asDouble());
        double halfFrameMs = 512 * 1000.0 / p.get("sampleRate").asDouble();
        return gap >= halfFrameMs ? gap : 0;
    }

    /** 표에 세 축이 다 있어야 웹 · clip 이 자기 축 사례를 찾는다(Q-R5). 빈 축이 초록이 되지 않게 본다. */
    @Test
    void 표는_세_축의_F_사례를_모두_싣는다() {
        List<String> axes = TABLE.ofKind("F").stream().map(c -> c.get("axis").asString()).distinct().toList();

        assertThat(axes).containsExactlyInAnyOrder("wall", "pdt", "startPts");
    }

    @Test
    void 모르는_axis면_표를_거절한다() {
        String json = table(fCase("\"editor\"", row(1, null)));

        assertThatThrownBy(() -> LedgerTimelineCases.parse(json))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("axis");
    }

    /** 판 NULL 은 서버 시계 출처도 start_wall_utc 축이라 pdt 사례에 들 수 없다(설계 r7 3.5 · cc 부수 8). */
    @Test
    void pdt_사례에_판_NULL_행이_섞이면_표를_거절한다() {
        String json = table(fCase("\"pdt\"", row(1, "2") + ", " + row(2, null)));

        assertThatThrownBy(() -> LedgerTimelineCases.parse(json))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pdt");
    }

    @Test
    void 모르는_kind면_표를_거절한다() {
        String json = table("{\"id\": \"x\", \"kind\": \"resultOffset\"}");

        assertThatThrownBy(() -> LedgerTimelineCases.parse(json))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kind");
    }

    @Test
    void 모르는_op면_표를_거절한다() {
        String json = table(fCase("\"wall\"", row(1, null)).replace("\"locate\"", "\"at\""));

        assertThatThrownBy(() -> LedgerTimelineCases.parse(json))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("op");
    }

    @Test
    void version이_다르면_표를_거절한다() {
        String json = table(fCase("\"wall\"", row(1, null))).replace("\"version\": 2", "\"version\": 1");

        assertThatThrownBy(() -> LedgerTimelineCases.parse(json))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version");
    }

    /** services 몫 kind 가 비면 표가 비어도 초록이 된다(r6 3.4). */
    @Test
    void services_몫_kind가_비면_표를_거절한다() {
        String json = "{\"version\": 2, \"cases\": [" + fCase("\"wall\"", row(1, null)) + "]}";

        assertThatThrownBy(() -> LedgerTimelineCases.parse(json))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("length");
    }

    @Test
    void 축마다_원점_표지_규칙이_설계_3_2_표와_같다() {
        assertThat(Axis.WALL.originOf(2)).isEqualTo("FILE_HEAD");
        assertThat(Axis.WALL.originOf(null)).isEqualTo("FILE_HEAD");
        assertThat(Axis.PDT.originOf(2)).isEqualTo("VIDEO_START");
        assertThat(Axis.START_PTS.originOf(2)).isEqualTo("VIDEO_START");
        assertThat(Axis.START_PTS.originOf(null)).isEqualTo("FILE_HEAD");
    }

    private static String row(long seq, String timingVersion) {
        return "{\"seq\": " + seq + ", \"startAtMs\": " + (seq * 4000) + ", \"durationMs\": 4000, "
                + "\"continuesPrevious\": false, \"timingVersion\": " + timingVersion + "}";
    }

    private static String fCase(String axis, String rows) {
        return "{\"id\": \"f\", \"kind\": \"F\", \"op\": \"locate\", \"axis\": " + axis + ", \"rows\": [" + rows + "], "
                + "\"t\": 4500, \"expect\": {\"place\": \"afterLast\"}}";
    }

    /** 거절 시험용 최소 표 — 깨뜨린 사례 하나 + services 몫을 채우는 length · audioJoin 사례. */
    private static String table(String brokenCase) {
        return "{\"version\": 2, \"cases\": [" + brokenCase + ", "
                + "{\"id\": \"l\", \"kind\": \"length\", \"inMs\": 0, \"outMs\": 1, \"joinedCutPartMs\": [], "
                + "\"expectLengthMs\": 1}, "
                + "{\"id\": \"a\", \"kind\": \"audioJoin\", \"timingVersion\": null, "
                + "\"prev\": {\"tracks\": [{\"startMs\": 0, \"endMs\": 1, \"sampleRate\": 48000}]}, "
                + "\"next\": {\"tracks\": [{\"startMs\": 0}]}, \"applies\": false, \"expectSilenceMs\": [0]}]}";
    }
}
