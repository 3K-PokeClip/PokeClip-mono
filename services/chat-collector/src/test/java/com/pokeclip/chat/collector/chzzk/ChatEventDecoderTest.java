package com.pokeclip.chat.collector.chzzk;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ChatEventDecoderTest {

    /** 함정 5. 안쪽이 문자열이라 두 번 파싱해야 한다. */
    @Test
    void 이벤트_본문을_두_번_파싱한다() {
        String payload = "[\"CHAT\",\"{\\\"senderChannelId\\\":\\\"S1\\\","
                + "\\\"content\\\":\\\"ㅋㅋ\\\",\\\"messageTime\\\":1754300000000}\"]";

        ChatMessage message = ChatEventDecoder.decodeChat(payload);

        assertThat(message.senderChannelId()).isEqualTo("S1");
        assertThat(message.content()).isEqualTo("ㅋㅋ");
        assertThat(message.messageTimeMillis()).isEqualTo(1_754_300_000_000L);
    }

    /**
     * 함정 6. eventSentAt은 오프셋 없는 KST고 소수점 9자리라 RFC 3339 위반이다.
     * UTC로 파싱하면 9시간 어긋나고 오류도 안 난다.
     */
    @Test
    void eventSentAt이_와도_시각은_messageTime만_쓴다() {
        String payload = "[\"CHAT\",\"{\\\"content\\\":\\\"x\\\",\\\"messageTime\\\":1754300000000,"
                + "\\\"eventSentAt\\\":\\\"2026-08-04T21:00:00.123456789\\\"}\"]";

        assertThat(ChatEventDecoder.decodeChat(payload).messageTimeMillis())
                .isEqualTo(1_754_300_000_000L);
    }

    @Test
    void 봉투의_channelId를_담는다() {
        ChatMessage message = ChatEventDecoder.decodeChat(
                "[\"CHAT\",\"{\\\"channelId\\\":\\\"ch-1\\\",\\\"senderChannelId\\\":\\\"s-1\\\","
                        + "\\\"content\\\":\\\"hi\\\",\\\"messageTime\\\":1723600000000}\"]");
        assertThat(message.channelId()).isEqualTo("ch-1");
    }

    @Test
    void channelId가_없어도_버리지_않는다() {
        // 실측 0회인 필드다(PRD 가정). 없다는 이유로 채팅을 버리면 가정이 틀렸을 때
        // 적재가 통째로 0건이 된다 — 빈 값으로 남기고 행은 살린다.
        ChatMessage message = ChatEventDecoder.decodeChat(
                "[\"CHAT\",\"{\\\"senderChannelId\\\":\\\"s-1\\\","
                        + "\\\"content\\\":\\\"hi\\\",\\\"messageTime\\\":1723600000000}\"]");
        assertThat(message).isNotNull();
        assertThat(message.channelId()).isEmpty();
    }

    @Test
    void 시스템_이벤트에서_종류와_세션키를_읽는다() {
        String payload = "[\"SYSTEM\",\"{\\\"type\\\":\\\"connected\\\","
                + "\\\"data\\\":{\\\"sessionKey\\\":\\\"K1\\\"}}\"]";

        SystemEvent event = ChatEventDecoder.decodeSystem(payload);

        assertThat(event.type()).isEqualTo("connected");
        assertThat(event.sessionKey()).isEqualTo("K1");
    }

    /**
     * Jackson 3의 JacksonException은 RuntimeException이라 컴파일러가 catch를
     * 강제하지 않는다. 안 잡으면 onText에서 튀어 onError로 가고 수신이
     * 조용히 멈춘다 — 그 한 건 때문에 방송 전체를 잃는다.
     */
    @Test
    void 깨진_본문은_null이고_예외가_밖으로_나가지_않는다() {
        assertThat(ChatEventDecoder.decodeChat("[\"CHAT\",\"{깨짐\"]")).isNull();
        assertThat(ChatEventDecoder.decodeChat("완전히 아닌 것")).isNull();
        assertThat(ChatEventDecoder.decodeSystem("[\"SYSTEM\"]")).isNull();
    }

    /**
     * 파싱은 됐는데 쓸 내용이 없는 본문이다. 기본값을 채워 돌려주면 수신 1건으로
     * 세면서 판정 항목 둘을 조용히 오염시킨다 — messageTime=0은 전달 지연 분포의
     * 최소값을 약 -56년으로 만들고 순서 위반 건수도 튀게 한다.
     *
     * <p>게다가 그때 디코더는 실패라고 말하지 않으므로 decodeFailures도 안 오른다.
     * 로그 0줄·카운터 0인 채로 지표만 틀리는 것이 이 카드가 막으려는 실패 양식이다.
     */
    @Test
    void 시각이_없는_본문은_null이다() {
        assertThat(ChatEventDecoder.decodeChat("[\"CHAT\",\"{\\\"content\\\":\\\"x\\\"}\"]")).isNull();
        // 안쪽이 객체가 아닌 경우도 같은 자리에서 걸린다.
        assertThat(ChatEventDecoder.decodeChat("[\"CHAT\",\"null\"]")).isNull();
        assertThat(ChatEventDecoder.decodeChat("[\"CHAT\",\"123\"]")).isNull();
        assertThat(ChatEventDecoder.decodeChat("[\"CHAT\",\"[]\"]")).isNull();
        assertThat(ChatEventDecoder.decodeChat("[\"CHAT\",\"\\\"hello\\\"\"]")).isNull();
    }

    /** 종류가 비면 connected인지 revoked인지 못 가른다. 수립도 T10도 이 값으로 갈린다. */
    @Test
    void 종류가_없는_시스템_이벤트는_null이다() {
        assertThat(ChatEventDecoder.decodeSystem("[\"SYSTEM\",\"{}\"]")).isNull();
        assertThat(ChatEventDecoder.decodeSystem("[\"SYSTEM\",\"null\"]")).isNull();
    }

    /** SYSTEM 자리에 CHAT이 오면 다른 종류다. 섞으면 sessionKey를 못 받는다. */
    @Test
    void 이벤트_이름이_다르면_null이다() {
        String chat = "[\"CHAT\",\"{\\\"content\\\":\\\"x\\\",\\\"messageTime\\\":1}\"]";

        assertThat(ChatEventDecoder.decodeSystem(chat)).isNull();
    }

    /** S3 아카이브(POK-116)가 이 문자열을 손대지 않고 쌓는다 — 요약이 아니라 원문이다. */
    @Test
    void raw는_안쪽_JSON_문자열을_글자_그대로_담는다() {
        // 안쪽에 따옴표·줄바꿈·이모지·공백을 일부러 넣는다 — "글자 그대로"는 이런 것까지다.
        String inner = "{\"channelId\":\"CH1\",\"senderChannelId\":\"S1\","
                + "\"content\":\"a\\\"b\\nc 😀\",\"messageTime\":1754300000000,"
                + "\"profile\":{\"nickname\":\"닉\"}, \"extra\":  [1,2]}";
        String payload = "[\"CHAT\"," + quoteAsJson(inner) + "]";

        ChatMessage message = ChatEventDecoder.decodeChat(payload);

        assertThat(message).isNotNull();
        assertThat(message.raw()).isEqualTo(inner);
        assertThat(message.raw().getBytes(StandardCharsets.UTF_8))
                .isEqualTo(inner.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 닉네임은 화면에 「누가 말했나」를 띄우는 값이라 창구(POK-234)가 표에서 읽어 내보낸다.
     * 없으면 빈 문자열이 아니라 null이어야 한다 — 빈 문자열은 「이름이 빈 사람」과 못 가른다.
     */
    @Test
    void 닉네임과_역할을_뽑고_없으면_null이다() {
        String inner = "{\"channelId\":\"CH1\",\"senderChannelId\":\"S1\",\"content\":\"x\","
                + "\"messageTime\":1754300000000,\"profile\":{\"nickname\":\"겜돌이\"},"
                + "\"userRoleCode\":\"streamer\"}";
        ChatMessage m = ChatEventDecoder.decodeChat("[\"CHAT\"," + quoteAsJson(inner) + "]");
        assertThat(m.nickname()).isEqualTo("겜돌이");
        assertThat(m.userRole()).isEqualTo("streamer");

        String bare = "{\"channelId\":\"CH1\",\"senderChannelId\":\"S1\",\"content\":\"x\",\"messageTime\":1754300000000}";
        ChatMessage b = ChatEventDecoder.decodeChat("[\"CHAT\"," + quoteAsJson(bare) + "]");
        assertThat(b.nickname()).isNull();
        assertThat(b.userRole()).isNull();
    }

    /**
     * JSON null과 「칸이 없음」을 같게 다룬다. Jackson 3의 asString(null)은 NullNode에
     * null을 주지만(Jackson 2의 asText()는 문자열 "null"이었다) 그 차이에 기대지 않고
     * isMissingNode 갈래로 먼저 거른다 — 그 동작이 바뀌어도 이 단언이 잡는다.
     */
    @Test
    void 닉네임이_JSON_null이어도_null이다() {
        String inner = "{\"channelId\":\"CH1\",\"senderChannelId\":\"S1\",\"content\":\"x\","
                + "\"messageTime\":1754300000000,\"profile\":{\"nickname\":null},"
                + "\"userRoleCode\":null}";
        ChatMessage m = ChatEventDecoder.decodeChat("[\"CHAT\"," + quoteAsJson(inner) + "]");
        assertThat(m.nickname()).isNull();
        assertThat(m.userRole()).isNull();
    }

    /** 후원은 화면에서 하이라이트 신호로 쓰인다. raw는 채팅과 같은 규칙으로 원문 그대로다. */
    @Test
    void 후원_이벤트를_푼다() {
        String inner = "{\"donationType\":\"CHAT\",\"channelId\":\"CH1\",\"donatorChannelId\":\"D1\","
                + "\"donatorNickname\":\"도네초코\",\"payAmount\":\"5000\",\"donationText\":\"가즈아\"}";
        DonationEvent d = ChatEventDecoder.decodeDonation("[\"DONATION\"," + quoteAsJson(inner) + "]");
        assertThat(d.donatorNickname()).isEqualTo("도네초코");
        assertThat(d.payAmount()).isEqualTo(5000L);
        assertThat(d.raw()).isEqualTo(inner);
        assertThat(d.channelId()).isEqualTo("CH1");
        assertThat(d.donatorChannelId()).isEqualTo("D1");
        assertThat(d.donationType()).isEqualTo("CHAT");
        assertThat(d.donationText()).isEqualTo("가즈아");
    }

    /**
     * 금액은 문서상 문자열("원")이라 표기가 바뀔 수 있다. 숫자로 못 바꾼다고 후원 자체를
     * 버리면 <b>하이라이트 신호가 통째로 사라진다</b> — 금액만 null로 두고 이벤트는 살린다.
     */
    @Test
    void 후원_금액이_숫자가_아니면_null이고_이벤트는_버리지_않는다() {
        String inner = "{\"donationType\":\"VIDEO\",\"channelId\":\"CH1\",\"donatorChannelId\":\"D1\","
                + "\"donatorNickname\":\"n\",\"payAmount\":\"오천\",\"donationText\":\"\"}";
        DonationEvent d = ChatEventDecoder.decodeDonation("[\"DONATION\"," + quoteAsJson(inner) + "]");
        assertThat(d).isNotNull();
        assertThat(d.payAmount()).isNull();
        assertThat(d.donationType()).isEqualTo("VIDEO");
    }

    /** 천 단위 쉼표와 숫자 노드 — 둘 다 실물에서 올 수 있는 모양이고 금액은 살아야 한다. */
    @Test
    void 금액이_쉼표나_숫자로_와도_읽는다() {
        String comma = "{\"channelId\":\"CH1\",\"payAmount\":\"5,000\"}";
        assertThat(ChatEventDecoder.decodeDonation("[\"DONATION\"," + quoteAsJson(comma) + "]").payAmount())
                .isEqualTo(5000L);
        String number = "{\"channelId\":\"CH1\",\"payAmount\":5000}";
        assertThat(ChatEventDecoder.decodeDonation("[\"DONATION\"," + quoteAsJson(number) + "]").payAmount())
                .isEqualTo(5000L);
    }

    /** 금액 칸이 아예 없는 것과 「못 읽었다」를 같게 다룬다 — 둘 다 null이다. */
    @Test
    void 금액_칸이_없으면_null이다() {
        String inner = "{\"channelId\":\"CH1\",\"donatorNickname\":\"n\"}";
        DonationEvent d = ChatEventDecoder.decodeDonation("[\"DONATION\"," + quoteAsJson(inner) + "]");
        assertThat(d).isNotNull();
        assertThat(d.payAmount()).isNull();
    }

    /** 이름을 안 보면 채팅을 후원으로 세면서 지표가 조용히 갈린다. */
    @Test
    void CHAT을_DONATION으로_풀면_null이다() {
        assertThat(ChatEventDecoder.decodeDonation("[\"CHAT\",\"{}\"]")).isNull();
    }

    /** 깨진 본문·객체가 아닌 안쪽 — 채팅과 같은 자리에서 걸린다. */
    @Test
    void 깨진_후원_본문은_null이다() {
        assertThat(ChatEventDecoder.decodeDonation("[\"DONATION\",\"{깨짐\"]")).isNull();
        assertThat(ChatEventDecoder.decodeDonation("[\"DONATION\",\"null\"]")).isNull();
        assertThat(ChatEventDecoder.decodeDonation("[\"DONATION\",\"[]\"]")).isNull();
        assertThat(ChatEventDecoder.decodeDonation("완전히 아닌 것")).isNull();
    }

    /**
     * 권한 회수는 종류별로 온다(CHAT·DONATION·SUBSCRIPTION). 그 칸을 안 읽으면
     * <b>후원만 회수돼도 채팅 세션이 죽는다</b> — POK-234 태스크 4B의 축이다.
     * 없으면 빈 문자열이다(옛 모양·connected·subscribed).
     */
    @Test
    void revoked의_이벤트_종류를_읽고_없으면_빈_문자열이다() {
        String withType = "{\"type\":\"revoked\",\"data\":{\"eventType\":\"DONATION\",\"channelId\":\"CH\"}}";
        assertThat(ChatEventDecoder.decodeSystem("[\"SYSTEM\"," + quoteAsJson(withType) + "]").eventType())
                .isEqualTo("DONATION");

        String bare = "{\"type\":\"connected\",\"data\":{\"sessionKey\":\"K\"}}";
        assertThat(ChatEventDecoder.decodeSystem("[\"SYSTEM\"," + quoteAsJson(bare) + "]").eventType()).isEmpty();

        // data 자체가 없는 모양도 빈 문자열이다 — null을 주면 아래 equals 갈래가 NPE로 죽는다.
        String noData = "{\"type\":\"revoked\"}";
        assertThat(ChatEventDecoder.decodeSystem("[\"SYSTEM\"," + quoteAsJson(noData) + "]").eventType()).isEmpty();
    }

    /** 문자열을 JSON 문자열 리터럴로 — 가짜 서버(FakeChzzkBehavior.escape)와 같은 일을 한다. */
    private static String quoteAsJson(String s) {
        return new tools.jackson.databind.ObjectMapper().writeValueAsString(s);
    }
}
