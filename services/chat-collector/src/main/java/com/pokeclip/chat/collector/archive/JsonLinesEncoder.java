package com.pokeclip.chat.collector.archive;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 파일 한 줄 = 채팅 한 건. {@code {"receivedAtMillis":N,"raw":"…"}\n}
 *
 * <p>raw를 <b>JSON 문자열 값으로 감싼다</b>(객체로 끼워 넣지 않는다). 원문에 줄바꿈이
 * 있어도 한 줄이 유지되고, 읽는 쪽이 파싱하면 바이트 하나 안 틀리는 원문이 나온다 —
 * "내려받아 원문과 대조"를 줄 단위 비교로 쓸 수 있는 이유다. 이스케이프는 Jackson에
 * 맡긴다 — 직접 짜면 제어문자·서로게이트에서 틀린다.
 *
 * <p>Jackson 3: {@code writeValueAsBytes}·{@code readTree}가 unchecked
 * {@code JacksonException}을 던진다 — 여기서는 잡지 않는다. 호출자(MinuteBatcher)가
 * 건 단위로 잡는다.
 */
public final class JsonLinesEncoder {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final byte[] NEWLINE = "\n".getBytes(StandardCharsets.UTF_8);

    private JsonLinesEncoder() { }

    public static byte[] encodeLine(ArchivableChat chat) {
        // Jackson 3은 put("raw", null)을 예외 없이 "raw":null로 쓴다(plan-critic 실측) — 그러면 파일에
        // 스키마 위반 줄이 섞이고 인코드 실패 경로는 영영 안 탄다. null은 여기서 거부한다.
        Objects.requireNonNull(chat.raw(), "raw");
        ObjectNode node = MAPPER.createObjectNode();
        node.put("receivedAtMillis", chat.receivedAtMillis());
        node.put("raw", chat.raw());
        byte[] json = MAPPER.writeValueAsBytes(node);
        byte[] line = new byte[json.length + NEWLINE.length];
        System.arraycopy(json, 0, line, 0, json.length);
        System.arraycopy(NEWLINE, 0, line, json.length, NEWLINE.length);
        return line;
    }

    /** 대조·수동 검증용 역방향. 채널은 줄에 없어(키가 든다) 밖에서 받는다. */
    public static ArchivableChat decodeLine(String channelId, String line) {
        JsonNode node = MAPPER.readTree(line);
        return new ArchivableChat(channelId,
                node.get("receivedAtMillis").asLong(),
                node.get("raw").asString());
    }
}
