package com.pokeclip.chat.collector.broadcast;

/**
 * 판정기가 버린 편지를 종류별로 센 값. 읽는 시점의 스냅샷이다.
 *
 * <p><b>로그만으로는 안 보이는 것을 드러내려고 센다.</b> 1번이 식별자 체계를 바꾸거나
 * 계약에 없던 종류를 보내기 시작하면 편지는 계속 오는데 방송이 하나도 안 걷힌다 —
 * 서버는 UP이고 오류도 없다. health가 이 값을 내보낸다(태스크 13).
 *
 * <p><b>셋을 한 값으로 합치지 않는다. 1번이 고칠 자리가 다르기 때문이다</b> —
 * {@code unknownTypes}는 「우리가 모르는 종류를 보낸다」, {@code unreadableStreamerIds}는
 * 「식별자 체계가 바뀌었다」, {@code malformedEnvelopes}는 「봉투의 칸이 비었거나 너무 길다」이다.
 */
public record BroadcastCounters(long unreadableStreamerIds, long unknownTypes, long malformedEnvelopes) {
}
