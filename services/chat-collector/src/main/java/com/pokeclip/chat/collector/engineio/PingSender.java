package com.pokeclip.chat.collector.engineio;

/**
 * ping 하나를 내보내는 것. <b>{@code Heartbeat}를 {@code EngineIoSocket}에서 떼어낸다.</b>
 *
 * <p>{@code EngineIoSocket}은 final이고 실제 소켓을 열어야 만들어져서, "송신이
 * 실패하는 상황"을 만들 수단이 없었다 — 톰캣 세션 API로는 어떤 종료든 클라이언트에
 * onClose가 먼저 가서 다른 신호가 발화한다. 여기를 열면 하트비트의 실패 처리를
 * 단위로 검사할 수 있다.
 */
public interface PingSender {
    void sendPing();
}
