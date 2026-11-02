package com.pokeclip.clip.mark;

import com.pokeclip.clip.jumpcard.JumpCardErrors.InvalidHighlightException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * 웹 마킹 본문(POK-392). 갈래가 둘이고 <b>하나만</b> 와야 한다.
 * <ul>
 *   <li>라이브: {@code eventId} · {@code pressedAt} · {@code sentAt}(브라우저 시계의 epoch ms). 서버가 시각을 정한다</li>
 *   <li>지난 방송: {@code eventId} · {@code positionMs}(기준점 축 영상 위치, 정수 ms). 범위 검사는 기준점을 안 뒤에 서비스가 한다</li>
 * </ul>
 * 모르는 칸은 무시한다.
 *
 * <p>🔴 <b>{@code @RequestBody} record로 받지 않는다</b>(계획 검증 m5). 바인딩 실패는 스프링 기본 봉투로 나가 웹이 같은 400에
 * 모양이 다른 본문 둘을 받고, 소수가 정수로 잘려도 조용히 통과한다. 그래서 OBS 문({@link MarkRequest})처럼 본문을 직접 읽는다.
 * 거절은 OBS 타입이 아니라 {@link InvalidHighlightException}이다: 웹 문의 봉투는 {@code {"error":…,"field":…}}다.
 */
public record WebMarkRequest(String eventId, Long pressedAt, Long sentAt, Long positionMs) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 본문 상한. 정상 본문은 칸 셋이라 100바이트 안팎이다. OBS 문과 같은 4KB. */
    static final int MAX_BODY_BYTES = 4 * 1024;

    /** OBS 문과 같은 UUID v4 꼴({@link MarkRequest} 주석: {@code UUID.fromString}은 짧은 꼴도 받는다). */
    private static final Pattern UUID_V4 =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}");

    /** 본문을 상한 + 1바이트까지만 읽는다. 넘으면 400 {@code field:"body"}. */
    public static String readBody(InputStream in) {
        byte[] bytes;
        try {
            bytes = in.readNBytes(MAX_BODY_BYTES + 1);
        } catch (IOException e) {
            throw new InvalidHighlightException("body");
        }
        if (bytes.length > MAX_BODY_BYTES) {
            throw new InvalidHighlightException("body");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** @throws InvalidHighlightException 400. {@code field}는 칸 이름 또는 {@code body}(모양 전체가 틀렸다) */
    static WebMarkRequest parse(String body) {
        if (body == null || body.isBlank()) {
            throw new InvalidHighlightException("body");
        }
        JsonNode json;
        try {
            json = MAPPER.readTree(body);
        } catch (RuntimeException e) {
            // 파서 메시지는 본문 조각을 인용한다: 안 싣는다.
            throw new InvalidHighlightException("body");
        }
        if (json == null || !json.isObject()) {
            throw new InvalidHighlightException("body");
        }
        JsonNode eventId = json.path("eventId");
        if (!eventId.isString() || !UUID_V4.matcher(eventId.asString()).matches()) {
            throw new InvalidHighlightException("eventId");
        }
        boolean live = present(json, "pressedAt") || present(json, "sentAt");
        boolean position = present(json, "positionMs");
        if (live == position) {
            // 둘 다 없거나 둘 다 있다: 어느 갈래인지 서버가 고르지 않는다.
            throw new InvalidHighlightException("body");
        }
        if (live) {
            return new WebMarkRequest(eventId.asString(),
                    positiveMillis(json.path("pressedAt"), "pressedAt"), positiveMillis(json.path("sentAt"), "sentAt"), null);
        }
        JsonNode pos = json.path("positionMs");
        if (!pos.isIntegralNumber() || !pos.canConvertToLong()) {
            // 소수는 받지 않는다(OBS와 같은 이유: 잘리면 시각이 조용히 틀린다). 반올림은 웹이 한다.
            throw new InvalidHighlightException("positionMs");
        }
        return new WebMarkRequest(eventId.asString(), null, null, pos.longValue());
    }

    /** 라이브 갈래인가. 아니면 지난 방송(위치) 갈래다. */
    boolean live() {
        return pressedAt != null;
    }

    private static boolean present(JsonNode json, String field) {
        return json.has(field) && !json.get(field).isNull();
    }

    /** 0 초과 정수만. 소수·문자열·long 밖은 거절한다. */
    private static long positiveMillis(JsonNode node, String field) {
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() <= 0) {
            throw new InvalidHighlightException(field);
        }
        return node.longValue();
    }
}
