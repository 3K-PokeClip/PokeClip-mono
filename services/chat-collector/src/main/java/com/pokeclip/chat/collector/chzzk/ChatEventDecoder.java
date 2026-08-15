package com.pokeclip.chat.collector.chzzk;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 42["CHAT","{…}"] 를 푼다. <b>바깥 배열 한 번, 안쪽 문자열 한 번 — 두 번 파싱한다.</b>
 *
 * <p>실패하면 예외를 던지지 않고 null을 준다. Jackson 3의 JacksonException은
 * RuntimeException이라 컴파일러가 catch를 강제하지 않는데, 안 잡으면 수신
 * 콜백에서 튀어 onError로 가고 <b>그 한 건 때문에 방송 전체 수신이 멈춘다.</b>
 */
public final class ChatEventDecoder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ChatEventDecoder() { }

    public static ChatMessage decodeChat(String eventPayload) {
        // 안쪽 문자열을 파싱하기 전에 따로 붙든다 — 아카이브가 원문 그대로 쌓는 것이 이것이다.
        String innerText = innerText(eventPayload, "CHAT");
        if (innerText == null) {
            return null;
        }
        JsonNode inner = parseInner(innerText);
        if (inner == null) {
            return null;
        }
        // 시각이 없으면 통째로 버린다. 0을 채워 돌려주면 수신 1건으로 세면서
        // 전달 지연 분포의 최소값을 약 -56년으로 만들고 순서 위반 건수도 튀게 하는데,
        // 디코더가 실패라고 말하지 않으니 decodeFailures도 안 오른다.
        // 안쪽이 객체가 아닌 경우(null·숫자·배열·문자열)도 path가 MissingNode를 줘
        // 여기서 같이 걸린다.
        long messageTime = inner.path("messageTime").asLong(0L);
        if (messageTime <= 0) {
            return null;
        }
        return new ChatMessage(
                inner.path("channelId").asString(""),
                inner.path("senderChannelId").asString(""),
                inner.path("content").asString(""),
                messageTime,
                innerText);
    }

    public static SystemEvent decodeSystem(String eventPayload) {
        String innerText = innerText(eventPayload, "SYSTEM");
        if (innerText == null) {
            return null;
        }
        JsonNode inner = parseInner(innerText);
        if (inner == null) {
            return null;
        }
        // 종류가 비면 connected인지 revoked인지 못 가른다 — 세션 수립도
        // 철회 감지도 이 값 하나로 갈리므로 빈 종류를 통과시키지 않는다.
        String type = inner.path("type").asString("");
        if (type.isEmpty()) {
            return null;
        }
        return new SystemEvent(type, inner.path("data").path("sessionKey").asString(""));
    }

    /** 바깥 배열을 풀어 이름이 맞으면 안쪽 문자열을 <b>파싱하지 않고</b> 돌려준다. */
    private static String innerText(String eventPayload, String expectedName) {
        try {
            JsonNode outer = MAPPER.readTree(eventPayload);
            if (!outer.isArray() || outer.size() < 2 || !expectedName.equals(outer.get(0).asString())) {
                return null;
            }
            return outer.get(1).asString();
        } catch (RuntimeException e) {
            // 본문을 로그에 남기지 않는다 — 깨진 JSON이 곧 채팅 본문이다.
            return null;
        }
    }

    private static JsonNode parseInner(String innerText) {
        try {
            return MAPPER.readTree(innerText);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
