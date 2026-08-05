package com.pokeclip.chat.collector.engineio;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class HandshakeTest {

    private static final String REAL = "{\"sid\":\"abc\",\"upgrades\":[],\"pingInterval\":25000,\"pingTimeout\":60000}";

    @Test
    void 핸드셰이크에서_주기와_시한을_읽는다() {
        Handshake h = Handshake.parse(REAL);

        assertThat(h.sid()).isEqualTo("abc");
        assertThat(h.pingInterval()).isEqualTo(Duration.ofSeconds(25));
        assertThat(h.pingTimeout()).isEqualTo(Duration.ofSeconds(60));
    }

    /**
     * 25000을 상수로 박지 않는 이유가 이 테스트다. 치지직이 값을 바꾸면
     * 파생값이 따라가야 하고, 가짜 서버가 값을 줄이면 테스트가 빨라져야 한다.
     */
    @Test
    void 타이밍은_전부_핸드셰이크에서_파생한다() {
        Handshake h = Handshake.parse(REAL);

        assertThat(h.sendPeriod()).isEqualTo(Duration.ofSeconds(20));         // 25 x 0.8
        assertThat(h.pingThreshold()).isEqualTo(Duration.ofSeconds(40));      // 20 x 2
        assertThat(h.pongThreshold()).isEqualTo(Duration.ofSeconds(50));      // 20 + 60 x 0.5
        assertThat(h.survivalDeadline()).isEqualTo(Duration.ofSeconds(85));   // 25 + 60
    }

    @Test
    void 가짜_서버가_값을_줄이면_파생값도_같은_비율로_줄어든다() {
        Handshake h = Handshake.parse("{\"sid\":\"t\",\"pingInterval\":1000,\"pingTimeout\":2400}");

        assertThat(h.sendPeriod()).isEqualTo(Duration.ofMillis(800));
        assertThat(h.pingThreshold()).isEqualTo(Duration.ofMillis(1600));
        assertThat(h.pongThreshold()).isEqualTo(Duration.ofMillis(2000));
    }

    /**
     * 이 메서드는 WS 수신 콜백 안에서 불린다. 예외가 밖으로 튀면 onError로 가
     * 수신이 통째로 멈춘다 — 깨진 프레임 한 건 때문에 방송 전체를 잃는다.
     */
    @Test
    void 깨진_핸드셰이크는_null이고_예외가_밖으로_나가지_않는다() {
        assertThat(Handshake.parse("abc")).isNull();
        assertThat(Handshake.parse("{깨짐")).isNull();
        // 빈 문자열은 예외가 아니라 MissingNode다. 0이 담긴 Handshake가 나오면
        // null 검사를 통과하고 sendPeriod()=0이 ping 스레드를 폭주시킨다.
        assertThat(Handshake.parse("")).isNull();
        assertThat(Handshake.parse("{}")).isNull();
        assertThat(Handshake.parse("{\"pingInterval\":0,\"pingTimeout\":0}")).isNull();
    }

    /** 두 임계에 같은 배수를 쓰지 않는다 — pong에는 서버 몫 지연이 더 붙는다. */
    @Test
    void pong_임계가_ping_임계보다_크다() {
        Handshake h = Handshake.parse(REAL);

        assertThat(h.pongThreshold()).isGreaterThan(h.pingThreshold());
        assertThat(h.pongThreshold()).isLessThan(h.survivalDeadline());
    }
}
