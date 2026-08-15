package com.pokeclip.chat.collector.archive;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class JsonLinesEncoderTest {

    @Test
    void 인코드한_줄을_다시_읽으면_raw가_바이트_단위로_같다() {
        // 원문에 줄바꿈·따옴표·역슬래시·이모지·제어문자를 넣는다 — 파일이 줄 단위인데
        // 원문에 줄바꿈이 있어도 한 줄로 남아야 한다. 줄바꿈(\n)과 제어문자(0x01)는 이스케이프
        // 문자열이 아니라 **실제** 0x0A·0x01 문자다 — 그래야 "한 줄" 단언이 자동 참이 아니다(리뷰 1회차 사소 3).
        String raw = "{\"content\":\"a\nb\\\"c\\\\d 😀 \u0001\",\"messageTime\":1754300000000}";
        assertThat(raw).contains("\n").contains("\u0001");
        ArchivableChat chat = new ArchivableChat("CH1", 1_754_300_000_185L, raw);

        byte[] line = JsonLinesEncoder.encodeLine(chat);
        String text = new String(line, StandardCharsets.UTF_8);

        assertThat(text).endsWith("\n");
        assertThat(text.substring(0, text.length() - 1)).doesNotContain("\n");   // 한 줄
        ArchivableChat back = JsonLinesEncoder.decodeLine("CH1", text.trim());
        assertThat(back.raw()).isEqualTo(raw);
        assertThat(back.raw().getBytes(StandardCharsets.UTF_8)).isEqualTo(raw.getBytes(StandardCharsets.UTF_8));
        assertThat(back.receivedAtMillis()).isEqualTo(1_754_300_000_185L);
    }

    @Test
    void 줄의_키_이름은_receivedAtMillis와_raw_둘뿐이다() {
        String text = new String(JsonLinesEncoder.encodeLine(
                new ArchivableChat("CH1", 5L, "{}")), StandardCharsets.UTF_8);
        tools.jackson.databind.JsonNode node = new tools.jackson.databind.ObjectMapper().readTree(text);
        assertThat(node.properties().stream().map(java.util.Map.Entry::getKey))
                .containsExactlyInAnyOrder("receivedAtMillis", "raw");
        assertThat(node.get("receivedAtMillis").asLong()).isEqualTo(5L);
    }
}
