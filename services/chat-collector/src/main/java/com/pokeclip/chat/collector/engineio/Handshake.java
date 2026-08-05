package com.pokeclip.chat.collector.engineio;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

/**
 * 서버가 연 프레임(0)으로 준 값과, 거기서 나오는 타이밍 전부.
 *
 * <p><b>25000·60000을 상수로 박지 않는다.</b> 치지직이 값을 바꾸는 날 조용히
 * 죽고, 테스트에서 값을 줄여 빨리 돌릴 수도 없어진다.
 */
public record Handshake(String sid, Duration pingInterval, Duration pingTimeout) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * @return 파싱 실패 시 {@code null}. 예외를 던지지 않는다 —
     *         이 메서드는 WS 수신 콜백 안에서 불린다. Jackson 3의
     *         {@code JacksonException}은 unchecked라 컴파일러가 catch를
     *         강제하지 않는데, 밖으로 튀면 {@code onError → onClosed}로 가
     *         <b>깨진 프레임 한 건 때문에 방송 전체 수신이 멈춘다.</b>
     *         ChatEventDecoder가 같은 이유로 같은 방어선을 둔다.
     */
    public static Handshake parse(String payload) {
        try {
            JsonNode node = MAPPER.readTree(payload);
            long interval = node.path("pingInterval").asLong(0L);
            long timeout = node.path("pingTimeout").asLong(0L);
            // 빈 문자열은 예외가 아니라 MissingNode를 준다 → 0이 담긴 Handshake가
            // 나온다. null보다 나쁘다 — 아래 null 검사를 통과하고 sendPeriod()=0이
            // scheduleAtFixedRate(…, 0, 0, …)로 가서 ping 스레드가 폭주한다.
            if (interval <= 0 || timeout <= 0) {
                return null;
            }
            return new Handshake(node.path("sid").asString(""),
                    Duration.ofMillis(interval), Duration.ofMillis(timeout));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 송신 주기. pingInterval 그대로 보내면 지터·GC 한 번에 관측 간격이
     * 임계에 닿는다. 2026-08-01 실측 노트가 "20초마다"를 쓴 이유다.
     */
    public Duration sendPeriod() {
        return scaled(pingInterval, 8, 10);
    }

    /** 우리가 통제하는 값이라 "한 사이클을 통째로 걸렀다"가 판정 의미가 된다. */
    public Duration pingThreshold() {
        return sendPeriod().multipliedBy(2);
    }

    /**
     * 서버 응답이라 우리 통제 밖이고, 서버가 상정한 여유는 pingTimeout이다.
     * ping과 같은 배수를 쓰면 정상 지연에서 빨간불이 난다.
     */
    public Duration pongThreshold() {
        return sendPeriod().plus(scaled(pingTimeout, 1, 2));
    }

    public Duration survivalDeadline() {
        return pingInterval.plus(pingTimeout);
    }

    private static Duration scaled(Duration base, long numerator, long denominator) {
        return Duration.ofMillis(base.toMillis() * numerator / denominator);
    }
}
