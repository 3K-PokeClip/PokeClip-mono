package com.pokeclip.chat.collector.broadcast;

/**
 * 시작 편지 하나를 실제로 붙이는 자리 — 열쇠를 받아오고(태스크 7) 세션을 연다(태스크 9·10).
 *
 * <p><b>지금 구현체는 검사용 가짜뿐이다.</b> 그래도 이음매를 두는 이유는, 판정 규칙
 * (끝난 방송인가 · 신원을 읽을 수 있는가 · 재전송을 거르지 않는가)이 세션·HTTP보다 먼저
 * 굳어야 하고, 그것을 재려면 「붙이는 자리」가 주입 가능해야 하기 때문이다.
 * 태스크 10이 여기에 실제 배선을 끼운다.
 *
 * <p>판정값을 그대로 돌려준다 — 열쇠를 못 받은 이유에 따라 편지를 지울지 남길지가
 * 갈리는데(연동 없음이면 지우고, auth 장애면 남긴다) 그 구분은 여기 안쪽에서만 알 수 있다.
 */
@FunctionalInterface
public interface BroadcastStarter {

    ProcessResult start(LifecycleEnvelope envelope, StreamerId streamer);
}
