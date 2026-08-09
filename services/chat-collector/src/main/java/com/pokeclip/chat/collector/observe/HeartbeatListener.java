package com.pokeclip.chat.collector.observe;

import com.pokeclip.chat.collector.engineio.PingFailure;

import java.time.Duration;

/**
 * 하트비트가 밖으로 알리는 두 사건.
 *
 * <p><b>구현은 즉시 돌아와야 한다.</b> 이 콜백은 ping 전용 스케줄러 위에서 불리고,
 * 그 스케줄러에 무거운 일을 얹는 순간이 2026-08-01 사고를 재현하는 순간이다 —
 * ping이 다른 일과 스레드를 공유하다 74초간 못 나갔고 서버가 조용히 끊었다.
 * <b>재연결은 여기서 하지 않는다. 요청만 남기고 전용 스레드가 한다.</b>
 */
public interface HeartbeatListener {

    /** ping이 안 나갔다. {@code MISUSE}면 우리 잘못이라 재연결 대상이 아니다 */
    void onSendFailed(PingFailure.Cause cause);

    /** ping은 나가는데 pong이 임계를 넘도록 안 온다 — 좀비 연결 */
    void onPongTimeout(Duration gap);
}
