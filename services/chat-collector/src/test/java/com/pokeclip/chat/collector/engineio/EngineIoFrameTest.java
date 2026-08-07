package com.pokeclip.chat.collector.engineio;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EngineIoFrameTest {

    @Test
    void 엔진IO_타입을_첫_글자로_가른다() {
        assertThat(EngineIoFrame.parse("0{\"sid\":\"a\"}").type()).isEqualTo(EngineIoFrame.Type.OPEN);
        assertThat(EngineIoFrame.parse("3").type()).isEqualTo(EngineIoFrame.Type.PONG);
        assertThat(EngineIoFrame.parse("1").type()).isEqualTo(EngineIoFrame.Type.CLOSE);
    }

    /** 4는 MESSAGE고 그 다음 글자가 소켓IO 타입이다. 40=CONNECT, 42=EVENT. */
    @Test
    void 메시지_프레임은_둘째_글자까지_봐야_갈린다() {
        assertThat(EngineIoFrame.parse("40").type()).isEqualTo(EngineIoFrame.Type.CONNECT);
        assertThat(EngineIoFrame.parse("42[\"CHAT\",\"{}\"]").type()).isEqualTo(EngineIoFrame.Type.EVENT);
    }

    @Test
    void 본문은_타입_접두를_뗀_나머지다() {
        assertThat(EngineIoFrame.parse("0{\"sid\":\"a\"}").payload()).isEqualTo("{\"sid\":\"a\"}");
        assertThat(EngineIoFrame.parse("42[\"CHAT\",\"x\"]").payload()).isEqualTo("[\"CHAT\",\"x\"]");
        assertThat(EngineIoFrame.parse("3").payload()).isEmpty();
    }

    /** 모르는 프레임에 예외를 던지면 그 한 건 때문에 수신이 통째로 멈춘다. */
    @Test
    void 모르는_프레임은_UNKNOWN이고_예외를_던지지_않는다() {
        assertThat(EngineIoFrame.parse("").type()).isEqualTo(EngineIoFrame.Type.UNKNOWN);
        assertThat(EngineIoFrame.parse("9zzz").type()).isEqualTo(EngineIoFrame.Type.UNKNOWN);
        assertThat(EngineIoFrame.parse("4").type()).isEqualTo(EngineIoFrame.Type.UNKNOWN);
    }
}
