package com.pokeclip.clip.render;

import com.pokeclip.clip.recipe.RecipeDocument;
import com.pokeclip.clip.segment.CutDecision.End;
import com.pokeclip.clip.segment.CutDecision.LedgerNeighbor;
import com.pokeclip.clip.segment.CutDecision.Prime;
import com.pokeclip.clip.segment.CutDecision.Ready;
import com.pokeclip.clip.segment.CutDecision.SubtitlePoint.AtMedia;
import com.pokeclip.clip.segment.CutDecision.SubtitlePoint.InGap;
import com.pokeclip.clip.segment.CutDecision.SubtitleSpan;
import com.pokeclip.clip.segment.MediaCoord;
import com.pokeclip.clip.segment.OffsetOrigin;
import com.pokeclip.clip.segment.RoundRow;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 새 꼴 렌더 주문서의 직렬화(POK-196 · 설계 r7 5.1 · 5.2). 렌더 R-c1 의 fail-closed 검사가 이 꼴만 받는다 — 칸 이름 · 상호 배제 ·
 * 빈 배열 · 역할별 칸 묶음이 하나라도 어긋나면 주문 전체가 {@code ENVELOPE_VALIDATION} 이다.
 */
class RenderEnvelopeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final RecipeDocument RECIPE = new RecipeDocument(2, "s-1", new RecipeDocument.Cut(0L, 9_000L),
            List.of(), new RecipeDocument.Audio(List.of()), null);

    /** 설계 5.1 예 — 준비 조각 100(머리 101) · 끊긴 이웃 102 · 이어진 103 · 자막 둘. */
    @Test
    void 설계_예_계획을_새_꼴로_싣는다() {
        Ready ready = new Ready(OffsetOrigin.FILE_HEAD, new MediaCoord(101, 1_000_000), new End(103, 2_000_000L),
                List.of(row(101, false, false), row(102, true, true), row(103, false, true)),
                List.of(new SubtitleSpan(0, new AtMedia(new MediaCoord(101, 1_500_000)),
                                new AtMedia(new MediaCoord(102, 500_000))),
                        new SubtitleSpan(1, new InGap(101, 500), new InGap(101, 1_500))),
                9_000, Optional.empty(), List.of(new Prime(101, new LedgerNeighbor(100, 4_000, "uploaded", "k100"))),
                List.of(), List.of(), List.of());

        JsonNode envelope = build(ready);

        JsonNode sources = envelope.get("sourceKeys");
        assertThat(sources).hasSize(4);
        assertThat(fields(sources.get(0))).containsExactlyInAnyOrder("role", "bucket", "s3Key", "seq", "ownerSeq",
                "durationMs");
        assertThat(sources.get(0).get("role").asString()).isEqualTo("audio_prime");
        assertThat(sources.get(0).get("seq").asLong()).isEqualTo(100);
        assertThat(sources.get(0).get("ownerSeq").asLong()).isEqualTo(101);
        assertThat(sources.get(0).get("s3Key").asString()).isEqualTo("k100");
        assertThat(fields(sources.get(1))).containsExactlyInAnyOrder("role", "bucket", "s3Key", "seq", "durationMs",
                "isDiscontinuity", "followsLedgerPrevious");
        assertThat(sources.get(2).get("isDiscontinuity").asBoolean()).isTrue();
        assertThat(sources.get(2).get("followsLedgerPrevious").asBoolean()).isTrue();
        assertThat(sources).allSatisfy(s -> {
            assertThat(s.get("durationMs").asInt()).isEqualTo(4_000);
            assertThat(s.get("bucket").asString()).isEqualTo("segments");
            assertThat(s.has("sourceStartAtMs")).isFalse();
        });
        JsonNode plan = envelope.get("plan");
        assertThat(fields(plan)).containsExactlyInAnyOrder("planVersion", "offsetOrigin", "in", "out", "mediaSeqs",
                "subtitles", "lengthMs");
        assertThat(plan.get("planVersion").asInt()).isEqualTo(1);
        assertThat(plan.get("offsetOrigin").asString()).isEqualTo("FILE_HEAD");
        assertThat(plan.get("in").toString()).isEqualTo("{\"seq\":101,\"offsetUs\":1000000}");
        assertThat(plan.get("out").toString()).isEqualTo("{\"seq\":103,\"offsetUs\":2000000}");
        assertThat(plan.get("mediaSeqs").toString()).isEqualTo("[101,102,103]");
        assertThat(plan.get("subtitles").toString()).isEqualTo("[{\"index\":0,\"start\":{\"seq\":101,\"offsetUs\":1500000},"
                + "\"end\":{\"seq\":102,\"offsetUs\":500000}},{\"index\":1,\"start\":{\"afterSeq\":101,\"intoGapMs\":500},"
                + "\"end\":{\"afterSeq\":101,\"intoGapMs\":1500}}]");
        assertThat(plan.get("lengthMs").asLong()).isEqualTo(9_000);
        assertThat(envelope.get("recipe").get("streamId").asString()).isEqualTo("s-1");
    }

    /** 끝 틈이면 {@code toSegmentEnd} 만 싣고 {@code offsetUs} 는 없다(둘 다 오면 렌더 규칙 4 거절). 자막이 없어도 칸은 {@code []} 다. */
    @Test
    void 조각_끝까지면_offsetUs_없이_toSegmentEnd_이고_자막이_없어도_빈_배열이다() {
        Ready ready = new Ready(OffsetOrigin.VIDEO_START, new MediaCoord(7, 0), new End(7, null), List.of(row(7, false,
                false)), List.of(), 3_000, Optional.empty(), List.of(), List.of(), List.of(), List.of());

        JsonNode plan = build(ready).get("plan");

        assertThat(plan.get("out").toString()).isEqualTo("{\"seq\":7,\"toSegmentEnd\":true}");
        assertThat(plan.get("subtitles").isArray()).isTrue();
        assertThat(plan.get("subtitles")).isEmpty();
        assertThat(plan.get("offsetOrigin").asString()).isEqualTo("VIDEO_START");
    }

    /**
     * K5 — 첫 매체의 {@code followsLedgerPrevious} 는 주문 안 바로 앞 매체가 없으니 늘 거짓이다. 컷이 회차 가운데서 시작하면 회차
     * 목록에서는 참이었을 수 있어 주문을 만들 때 거짓으로 고친다.
     */
    @Test
    void 첫_매체의_장부_이웃_표지는_거짓으로_싣는다() {
        Ready ready = new Ready(OffsetOrigin.FILE_HEAD, new MediaCoord(5, 0), new End(6, 1_000_000L),
                List.of(row(5, false, true), row(6, false, true)), List.of(), 5_000, Optional.empty(), List.of(),
                List.of(), List.of(), List.of());

        JsonNode sources = build(ready).get("sourceKeys");

        assertThat(sources.get(0).get("followsLedgerPrevious").asBoolean()).isFalse();
        assertThat(sources.get(1).get("followsLedgerPrevious").asBoolean()).isTrue();
    }

    /** 끝 소리 확보 조각은 맨 끝 하나 · 마지막 매체의 같은 녹화 이웃(두 표지를 싣는다). */
    @Test
    void 확보_조각은_맨_끝에_두_표지와_함께_싣는다() {
        Ready ready = new Ready(OffsetOrigin.VIDEO_START, new MediaCoord(5, 0), new End(5, 3_900_000L),
                List.of(row(5, false, false)), List.of(), 3_900, Optional.of(row(6, false, true)), List.of(),
                List.of(), List.of(), List.of());

        JsonNode sources = build(ready).get("sourceKeys");

        assertThat(sources).hasSize(2);
        assertThat(sources.get(1).get("role").asString()).isEqualTo("audio_capture");
        assertThat(sources.get(1).get("seq").asLong()).isEqualTo(6);
        assertThat(sources.get(1).get("isDiscontinuity").asBoolean()).isFalse();
        assertThat(sources.get(1).get("followsLedgerPrevious").asBoolean()).isTrue();
        assertThat(sources.get(1).get("durationMs").asInt()).isEqualTo(4_000);
    }

    private static JsonNode build(Ready ready) {
        return MAPPER.readTree(RenderEnvelope.build(MAPPER, UUID.randomUUID(), 42, "s-1", 3, RECIPE, ready, "segments",
                "clips", null, Instant.parse("2026-11-12T00:00:00Z")));
    }

    private static List<String> fields(JsonNode node) {
        return node.propertyNames().stream().toList();
    }

    private static RoundRow row(long seq, boolean discontinuity, boolean follows) {
        return new RoundRow(seq, seq * 4_000, seq * 4_000, 4_000, discontinuity, follows, "uploaded", null, "k" + seq);
    }
}
