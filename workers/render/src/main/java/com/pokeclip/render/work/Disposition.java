package com.pokeclip.render.work;

import java.time.Duration;

/**
 * 주문 하나를 끝낸 뒤 줄의 메시지를 어떻게 할지(계약1 4절 「워커 거동」).
 *
 * <ul>
 *   <li>{@link #DELETE}. 잡이 끝났다(성공·종결·{@code proceed:false}·TERMINAL·CANCELLED·404). 자기 영수증으로 지운다.</li>
 *   <li>{@link #LEAVE}. 손대지 않는다. SUPERSEDED(새 실행이 도는 중)거나 clip이 답을 안 했다. 숨김 시간이 끝나면 다시 온다.</li>
 *   <li>{@link #delay}. 다시 해 볼 실패. 숨김 시간을 {@code 60초 + 0~60초}로 바꿔 조금 뒤 다시 받는다.</li>
 * </ul>
 */
public record Disposition(Kind kind, Duration delay) {

    public enum Kind { DELETE, LEAVE, DELAY }

    public static final Disposition DELETE = new Disposition(Kind.DELETE, null);
    public static final Disposition LEAVE = new Disposition(Kind.LEAVE, null);

    public static Disposition delay(Duration delay) {
        return new Disposition(Kind.DELAY, delay);
    }
}
