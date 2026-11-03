package com.pokeclip.render.job;

import com.pokeclip.render.support.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class EnvelopeParserTest {

    private final EnvelopeParser parser = new EnvelopeParser(Fixtures.MAPPER);
    private final UUID jobId = UUID.randomUUID();

    @Test
    void 주문서를_읽고_산출_자리를_가른다() {
        JobEnvelope job = parser.parse(body(Fixtures.envelope(jobId, Fixtures.recipe("s1"))));
        assertThat(job.jobId()).isEqualTo(jobId);
        assertThat(job.outputBucket()).isEqualTo("clips");
        assertThat(job.outputKey("tok", "o1.mp4")).isEqualTo("clips/42/tok/o1.mp4");
        assertThat(job.sources()).hasSize(3);
        assertThat(job.sources().get(1).sourceStartAtMs()).isEqualTo(Fixtures.BASE + 3999);
    }

    @Test
    void 봉투의_모르는_칸은_무시한다() {
        ObjectNode e = Fixtures.envelope(jobId, Fixtures.recipe("s1"));
        e.put("futureField", "x");
        assertThat(parser.parse(body(e)).jobId()).isEqualTo(jobId);
    }

    @Test
    void jobId를_못_읽으면_보고할_곳이_없다() {
        assertThatThrownBy(() -> parser.parse("not json")).isInstanceOf(EnvelopeParser.Unreadable.class);
        ObjectNode e = Fixtures.envelope(jobId, Fixtures.recipe("s1"));
        e.put("jobId", "zzz");
        assertThatThrownBy(() -> parser.parse(body(e))).isInstanceOf(EnvelopeParser.Unreadable.class);
    }

    @Test
    void preflight_실패는_코드별로_가른다() {
        ObjectNode v2 = Fixtures.envelope(jobId, Fixtures.recipe("s1"));
        v2.put("schemaVersion", 2);
        rejected(v2, ErrorCode.SCHEMA_VERSION);

        ObjectNode ai = Fixtures.envelope(jobId, Fixtures.recipe("s1"));
        ai.put("jobType", "AI");
        rejected(ai, ErrorCode.ENVELOPE_VALIDATION);

        ObjectNode prefix = Fixtures.envelope(jobId, Fixtures.recipe("s1"));
        prefix.put("outputPrefix", "clips/42");
        rejected(prefix, ErrorCode.ENVELOPE_VALIDATION);

        ObjectNode order = Fixtures.envelope(jobId, Fixtures.recipe("s1"));
        ((ObjectNode) order.get("sourceKeys").get(2)).put("seq", 0);
        rejected(order, ErrorCode.ENVELOPE_VALIDATION);

        ObjectNode recipe = Fixtures.envelope(jobId, Fixtures.recipe("s1"));
        ((ObjectNode) recipe.get("recipe")).put("extra", 1);
        rejected(recipe, ErrorCode.VALIDATION);

        ObjectNode other = Fixtures.envelope(jobId, Fixtures.recipe("s1"));
        other.put("streamId", "s2");
        rejected(other, ErrorCode.VALIDATION);
    }

    @Test
    void 빈_조각_목록은_여기서_거절하지_않는다() {
        ObjectNode e = Fixtures.envelope(jobId, Fixtures.recipe("s1"));
        e.putArray("sourceKeys");
        assertThat(parser.parse(body(e)).sources()).isEmpty();
    }

    /** P9 — 옛 꼴(계획 없음 · 조각 시작 시각 있음)은 계획 없이 지금 경로 그대로 간다(설계 r7 5.4). */
    @Test
    void 옛_꼴_주문은_계획_없이_읽고_옛_꼴로_남긴다(CapturedOutput output) {
        JobEnvelope job = parser.parse(body(Fixtures.envelope(jobId, Fixtures.recipe("s1"))));

        assertThat(job.plan()).isNull();
        assertThat(job.sources()).extracting(SourceSegment::ref).containsExactly(
                new SourceRef(0, SourceRole.SEGMENT, null), new SourceRef(1, SourceRole.SEGMENT, null),
                new SourceRef(2, SourceRole.SEGMENT, null));
        assertThat(job.sources()).extracting(SourceSegment::sourceStartAtMs)
                .containsExactly(Fixtures.start(0), Fixtures.start(1), Fixtures.start(2));
        assertThat(output.getOut()).contains("render.legacy_order jobId=" + jobId + " sources=3");
    }

    /** P9 — 판별은 두 칸(계획 · 조각 시작 시각)이다. 계획도 시작 시각도 없으면 어느 꼴도 아니다(설계 r7 5.3 1). */
    @Test
    void 계획이_없는데_조각_시작_시각도_없으면_규칙1로_거절한다() {
        ObjectNode e = Fixtures.envelope(jobId, Fixtures.recipe("s1"));
        ((ObjectNode) e.get("sourceKeys").get(1)).remove("sourceStartAtMs");

        assertThatThrownBy(() -> parser.parse(body(e)))
                .isInstanceOfSatisfying(EnvelopeParser.Rejected.class, r -> {
                    assertThat(r.failure().code()).isEqualTo(ErrorCode.ENVELOPE_VALIDATION);
                    assertThat(r.failure()).isInstanceOfSatisfying(PlanViolation.class,
                            v -> assertThat(v.rule()).isEqualTo(1));
                });
    }

    @Test
    void 옛_꼴에는_확보_준비_조각을_받지_않는다() {
        ObjectNode capture = Fixtures.envelope(jobId, Fixtures.recipe("s1"));
        ((ObjectNode) capture.get("sourceKeys").get(2)).put("role", "audio_capture");
        rejected(capture, ErrorCode.ENVELOPE_VALIDATION);

        ObjectNode prime = Fixtures.envelope(jobId, Fixtures.recipe("s1"));
        ((ObjectNode) prime.get("sourceKeys").get(0)).put("role", "audio_prime");
        rejected(prime, ErrorCode.ENVELOPE_VALIDATION);
    }

    /** int 칸에 int 로 담기지 않는 정수 — 잘려 1 이 되거나 예외로 새지 않고 거절한다. */
    @Test
    void 봉투의_int_칸이_int_범위_밖이면_거절한다() {
        ObjectNode schema = Fixtures.envelope(jobId, Fixtures.recipe("s1"));
        schema.put("schemaVersion", (1L << 32) + 1);
        rejected(schema, ErrorCode.SCHEMA_VERSION);

        ObjectNode schemaHuge = Fixtures.envelope(jobId, Fixtures.recipe("s1"));
        schemaHuge.put("schemaVersion", RenderPlanCheckTest.OVER_LONG);
        rejected(schemaHuge, ErrorCode.SCHEMA_VERSION);

        ObjectNode recipeVersion = Fixtures.envelope(jobId, Fixtures.recipe("s1"));
        recipeVersion.put("recipeVersion", 1L << 32);
        rejected(recipeVersion, ErrorCode.ENVELOPE_VALIDATION);
    }

    /** 옛 꼴도 같은 자리면 같이 거절한다. 범위 안 정수는 끝값까지 그대로 받는다(허용 집합을 줄이지 않는다). */
    @Test
    void 옛_꼴_조각_항목의_정수_칸이_long_범위_밖이면_거절하고_안이면_받는다() {
        for (String field : new String[] {"seq", "sourceStartAtMs", "durationMs"}) {
            ObjectNode e = Fixtures.envelope(jobId, Fixtures.recipe("s1"));
            ((ObjectNode) e.get("sourceKeys").get(2)).put(field, RenderPlanCheckTest.OVER_LONG);
            rejected(e, ErrorCode.ENVELOPE_VALIDATION);
        }

        ObjectNode max = Fixtures.envelope(jobId, Fixtures.recipe("s1"));
        ((ObjectNode) max.get("sourceKeys").get(2)).put("seq", Long.MAX_VALUE);
        assertThat(parser.parse(body(max)).sources().getLast().seq()).isEqualTo(Long.MAX_VALUE);
    }

    private void rejected(ObjectNode envelope, ErrorCode code) {
        assertThatThrownBy(() -> parser.parse(body(envelope)))
                .isInstanceOfSatisfying(EnvelopeParser.Rejected.class, r -> {
                    assertThat(r.jobId()).isEqualTo(jobId);
                    assertThat(r.failure().code()).isEqualTo(code);
                });
    }

    private static String body(ObjectNode node) {
        return Fixtures.MAPPER.writeValueAsString(node);
    }
}
