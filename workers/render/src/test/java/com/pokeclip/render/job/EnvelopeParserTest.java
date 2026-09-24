package com.pokeclip.render.job;

import com.pokeclip.render.support.Fixtures;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
