package com.pokeclip.chat.collector.chzzk;

import org.junit.jupiter.api.Test;

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
}
