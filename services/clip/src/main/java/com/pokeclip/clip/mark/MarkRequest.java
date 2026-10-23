package com.pokeclip.clip.mark;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * 마킹 본문(계약4 4B). 플러그인이 보내는 칸 넷: {@code streamid}(스트림키 원문) · {@code eventId}(UUID v4) ·
 * {@code pressedAt}·{@code sentAt}(플러그인 시계의 epoch ms). 모르는 칸은 무시한다(계약이 칸을 늘려도 받는다).
 *
 * <p>🔴 <b>{@code @Valid}로 검사하지 않고 본문을 String으로 받아 여기서 읽는다</b>(계획 검증 E1). 바인딩 실패·검증 실패
 * 예외는 전역 조언이 먼저 잡아 4B가 아닌 봉투로 나가고, 바인딩 실패 보고는 거부된 값(스트림키)을 평문으로 담는다.
 *
 * <p>{@code toString}을 덮는다: {@code streamid}가 비밀이다.
 */
public record MarkRequest(String streamid, String eventId, long pressedAt, long sentAt) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 본문 상한(감사 2회차 B2). 이 문은 <b>키를 확인하기 전에</b> 본문을 읽는다: 이 서버에서 인증 없이 본문을 읽는 첫 문이라
     * 상한이 없으면 큰 본문 하나가 통째로 힙에 오른다. 정상 본문은 칸 넷이라 300바이트 안팎이다.
     */
    static final int MAX_BODY_BYTES = 4 * 1024;

    /** streamid 상한. auth로 그대로 넘어가는 값이다. 정상 값은 {@code #!::r=<26자>,m=publish}(40자 안팎). */
    static final int MAX_STREAMID_CHARS = 256;

    /**
     * 계약4는 UUID v4다. {@code UUID.fromString}을 쓰지 않는다(계획 검증 E10): 그것은 「1-1-1-1-1」 같은 짧은 꼴도 받는다.
     * 대소문자는 둘 다 받는다(플러그인은 소문자를 낸다).
     */
    private static final Pattern UUID_V4 =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}");

    /**
     * 본문을 상한 + 1바이트까지만 읽는다. 넘으면 400이다: 나머지는 안 읽는다(Content-Length 없는 청크 본문도 같다).
     *
     * @throws MarkErrors.MarkRejectedException 400 {@code invalid_request}
     */
    public static String readBody(InputStream in) {
        byte[] bytes;
        try {
            bytes = in.readNBytes(MAX_BODY_BYTES + 1);
        } catch (IOException e) {
            throw MarkErrors.invalidRequest();
        }
        if (bytes.length > MAX_BODY_BYTES) {
            throw MarkErrors.invalidRequest();
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** @throws MarkErrors.MarkRejectedException 400 {@code invalid_request}: 무엇이 틀렸는지는 응답에 안 싣는다 */
    static MarkRequest parse(String body) {
        if (body == null || body.isBlank()) {
            throw MarkErrors.invalidRequest();
        }
        JsonNode json;
        try {
            json = MAPPER.readTree(body);
        } catch (RuntimeException e) {
            // 파서 메시지는 본문 조각을 인용한다: 안 싣는다.
            throw MarkErrors.invalidRequest();
        }
        if (json == null || !json.isObject()) {
            throw MarkErrors.invalidRequest();
        }
        JsonNode streamid = json.path("streamid");
        JsonNode eventId = json.path("eventId");
        if (!streamid.isString() || streamid.asString().isBlank()
                || streamid.asString().length() > MAX_STREAMID_CHARS) {
            // 빈 streamid를 auth에 넘기면 auth의 @NotBlank가 400을 주고, 그 400을 우리는 「못 물음」(503)으로 읽는다.
            throw MarkErrors.invalidRequest();
        }
        if (resourceCount(streamid.asString()) > 1) {
            // 우리는 첫 r=로 방송을 고르고 auth는 마지막 r=로 키를 찾는다(PR #338 codex P2). 둘이 갈리면 한 키로 확인받고
            // 다른 키의 방송을 고를 수 있다. 플러그인은 r=를 하나만 싣는다.
            throw MarkErrors.invalidRequest();
        }
        if (!eventId.isString() || !UUID_V4.matcher(eventId.asString()).matches()) {
            throw MarkErrors.invalidRequest();
        }
        return new MarkRequest(streamid.asString(), eventId.asString(),
                positiveMillis(json.path("pressedAt")), positiveMillis(json.path("sentAt")));
    }

    /** 0 초과 정수만. 소수·문자열·long 밖은 거절한다(7.9가 7로 잘리면 시각이 조용히 틀린다). */
    private static long positiveMillis(JsonNode node) {
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() <= 0) {
            throw MarkErrors.invalidRequest();
        }
        return node.longValue();
    }

    /** {@code r=} 구성 요소의 수. 쉼표로 가르는 것은 auth의 {@code StreamId.parse}와 같다. */
    private static int resourceCount(String raw) {
        String body = raw.startsWith("#!::") ? raw.substring(4) : raw;
        int count = 0;
        for (String part : body.split(",")) {
            if (part.startsWith("r=")) {
                count++;
            }
        }
        return count;
    }

    /**
     * streamid의 {@code r=} 값(스트림키 토큰). 못 찾으면 null. {@link #parse}가 둘 이상을 거절하므로 하나뿐이다.
     * 방송이 둘 이상일 때 물리 키와 맞대는 데만 쓴다(계획 검증 E3).
     * 형식 검증은 auth가 했다: 여기서는 칸을 꺼내기만 한다.
     */
    String streamKeyToken() {
        String raw = streamid.startsWith("#!::") ? streamid.substring(4) : streamid;
        for (String part : raw.split(",")) {
            if (part.startsWith("r=")) {
                return part.substring(2);
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "MarkRequest[eventId=" + eventId + ", pressedAt=" + pressedAt + ", sentAt=" + sentAt + "]";
    }
}
