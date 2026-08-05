package com.pokeclip.chat.collector.engineio;

/**
 * Engine.IO 3 / Socket.IO 2 프레임. 첫 글자가 Engine.IO 타입이고,
 * 그것이 4(MESSAGE)일 때만 둘째 글자가 Socket.IO 타입이다.
 *
 * <p>치지직은 Socket.IO 2.x 전용(EIO=3)이라 3.x 이상 규칙을 적용하면 서버가 거부한다.
 */
public record EngineIoFrame(Type type, String payload) {

    public enum Type { OPEN, CLOSE, PING, PONG, CONNECT, EVENT, UNKNOWN }

    /** 수집 중 우리가 내보내는 유일한 프레임이다. */
    public static final String PING_TEXT = "2";

    /**
     * 종료할 때 한 번 보낸다. 이걸 보내야 서버가 우리 의사로 끊긴 것을 알고
     * 세션을 곧바로 반납한다 — 안 보내면 죽은 전송을 알아챌 때까지 붙들고 있다.
     */
    public static final String CLOSE_TEXT = "1";

    public static EngineIoFrame parse(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new EngineIoFrame(Type.UNKNOWN, "");
        }
        return switch (raw.charAt(0)) {
            case '0' -> new EngineIoFrame(Type.OPEN, raw.substring(1));
            case '1' -> new EngineIoFrame(Type.CLOSE, raw.substring(1));
            case '2' -> new EngineIoFrame(Type.PING, raw.substring(1));
            case '3' -> new EngineIoFrame(Type.PONG, raw.substring(1));
            case '4' -> parseMessage(raw);
            default -> new EngineIoFrame(Type.UNKNOWN, raw);
        };
    }

    private static EngineIoFrame parseMessage(String raw) {
        if (raw.length() < 2) {
            return new EngineIoFrame(Type.UNKNOWN, raw);
        }
        return switch (raw.charAt(1)) {
            case '0' -> new EngineIoFrame(Type.CONNECT, raw.substring(2));
            case '2' -> new EngineIoFrame(Type.EVENT, raw.substring(2));
            default -> new EngineIoFrame(Type.UNKNOWN, raw);
        };
    }
}
