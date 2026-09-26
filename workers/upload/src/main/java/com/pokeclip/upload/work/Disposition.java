package com.pokeclip.upload.work;

import java.time.Duration;

/**
 * 주문 하나를 끝낸 뒤 줄의 쪽지를 어떻게 할지(렌더 일꾼과 같은 모양).
 *
 * <ul>
 *   <li>{@link #DELETE}: 끝났다(올림·실패·확인 중·이미 끝난 주문·모르는 주문·못 읽는 쪽지).</li>
 *   <li>{@link #LEAVE}: 손대지 않는다. clip이 답을 안 했거나 결과를 아직 못 정했다. 숨김 시간이 끝나면 다시 온다.</li>
 *   <li>{@link #delay}: 다시 해 볼 실패. 조금 뒤 다시 받는다. 🔴 다시 받아도 새로 올리지 않는다 — clip에 적힌 주소로 잇는다.</li>
 * </ul>
 * 세 번 넘게 돌면 실패 큐로 가고 clip 정리기가 닫는다(주소가 적혔으면 {@code checking}).
 */
public record Disposition(Kind kind, Duration delay) {

    public enum Kind { DELETE, LEAVE, DELAY }

    public static final Disposition DELETE = new Disposition(Kind.DELETE, null);
    public static final Disposition LEAVE = new Disposition(Kind.LEAVE, null);

    public static Disposition delay(Duration delay) {
        return new Disposition(Kind.DELAY, delay);
    }
}
