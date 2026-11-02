package com.pokeclip.render.media;

import com.pokeclip.render.support.Fixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoudnessTest {

    @Test
    void 잰_값으로_선형_보정_필터를_만든다() {
        String stderr = "[Parsed_loudnorm_0 @ 0x1]\n{\n\t\"input_i\" : \"-23.51\",\n\t\"input_tp\" : \"-5.02\",\n"
                + "\t\"input_lra\" : \"1.20\",\n\t\"input_thresh\" : \"-33.72\",\n\t\"output_i\" : \"-14.1\",\n"
                + "\t\"target_offset\" : \"0.05\"\n}\n";
        assertThat(Loudness.correctFilter(stderr, Fixtures.MAPPER)).isEqualTo("loudnorm=I=-14:TP=-1.5:LRA=11"
                + ":measured_I=-23.51:measured_TP=-5.02:measured_LRA=1.20:measured_thresh=-33.72:offset=0.05:linear=true");
    }

    @Test
    void 무음이면_보정하지_않는다() {
        String stderr = "{\"input_i\" : \"-inf\", \"input_tp\" : \"-inf\", \"input_lra\" : \"0.00\", "
                + "\"input_thresh\" : \"-70.00\", \"target_offset\" : \"inf\"}";
        assertThat(Loudness.correctFilter(stderr, Fixtures.MAPPER)).isNull();
    }
}
