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
        // 닉네임·역할은 「없으면 null」이다. asString(null) 하나로 끝난다 —
        // Jackson 3은 <b>칸이 없을 때(MissingNode)도 JSON null일 때(NullNode)도</b>
        // 기본값을 그대로 돌려준다(Jackson 2의 asText()는 문자열 "null"이었다).
        // isMissingNode 갈래를 앞에 뒀다가 지웠다 — 두 경로의 답이 같아 그 갈래를
        // 재는 시험을 만들 수 없었고(감사 라운드 1 C3: 지워도 18건 전부 초록),
        // 「방어는 있는데 그물이 없는」 코드가 된다. 라이브러리가 바뀌면
        // ChatEventDecoderTest의 「닉네임과_역할을_뽑고_없으면_null이다」와
        // 「닉네임이_JSON_null이어도_null이다」 둘이 잡는다 — 그것이 그물이다.
        JsonNode profile = inner.path("profile");
        return new ChatMessage(
                inner.path("channelId").asString(""),
                inner.path("senderChannelId").asString(""),
                inner.path("content").asString(""),
                messageTime,
                innerText,
                profile.path("nickname").asString(null),
                inner.path("userRoleCode").asString(null));
    }

    /**
     * 42["DONATION","{…}"] 를 푼다. 이름이 DONATION이 아니거나 안쪽이 객체가 아니면 null이다.
     * 채팅과 달리 시각 칸이 없으므로 시각으로 거르는 갈래도 없다.
     */
    public static DonationEvent decodeDonation(String eventPayload) {
        String innerText = innerText(eventPayload, "DONATION");
        if (innerText == null) {
            return null;
        }
        JsonNode inner = parseInner(innerText);
        if (inner == null || !inner.isObject()) {
            return null;
        }
        Long amount;
        try {
            String rawAmount = inner.path("payAmount").asString("");
            amount = rawAmount.isEmpty() ? null : Long.parseLong(rawAmount.replace(",", ""));
        } catch (NumberFormatException e) {
            // 금액 표기가 바뀌어도 후원 자체는 버리지 않는다 — 버리면 하이라이트 신호가
            // 통째로 사라지고, 그때 디코더는 실패라고 말하지도 않는다.
            amount = null;
        }
        return new DonationEvent(
                inner.path("channelId").asString(""),
                inner.path("donatorChannelId").asString(""),
                inner.path("donatorNickname").asString(""),
                inner.path("donationType").asString(""),
                amount,
                inner.path("donationText").asString(""),
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
        return new SystemEvent(type,
                inner.path("data").path("sessionKey").asString(""),
                inner.path("data").path("eventType").asString(""));
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
