package com.pokeclip.chat.detector.metrics;

import java.util.ArrayList;
import java.util.List;

/**
 * 시각을 창 눈금으로 내린다. <b>이 서버 전체가 눈금에 기대고 있다.</b>
 *
 * <p>창을 밀며 보지 않고 눈금에 맞추는 이유: 같은 창을 두 번 판정하지 않기 위해서다.
 * 밀며 보면 같은 급증이 매 바퀴 새 창으로 잡힌다.
 *
 * <p>🔴 <b>「clip의 제약이 두 번째 겹」이라고 읽지 마라</b>(계획 검증 F14). clip에 보내는
 * {@code window_start_ms}는 이 눈금이 아니라 <b>변환된 영상 위치</b>다. 보정값은
 * 「세션·시점에 따라 변한다」고 수집 서버가 실측해 뒀으므로, 같은 눈금이라도 변환 시점이
 * 다르면 위치가 달라져 clip의 {@code UNIQUE}가 안 접는다.
 *
 * <p><b>실질 방어선은 우리 {@code published_at} 하나다.</b> 실패해도 되돌리지 않으므로
 * 실무상 한 번만 나가지만, 「두 겹이니 안심」이라는 문장에 기대지 마라.
 */
public final class WindowGrid {

    private WindowGrid() {
    }

    /**
     * epoch ms는 음수가 아니므로(1970 이후) 자바의 나눗셈이 0 방향 절사여도 내림과 같다.
     * 음수 입력은 이 서버에 들어올 길이 없다 — 채팅 시각은 창구가 epoch 0 이상만 받는다.
     */
    public static long floorTo(long epochMs, long windowSizeMs) {
        return epochMs / windowSizeMs * windowSizeMs;
    }

    /**
     * {@code [fromMs, toMs]} 안에서 <b>이미 닫힌</b> 창들의 시작 눈금을 오름차순으로.
     *
     * <p>「닫혔다」는 창의 끝(시작 + 크기)이 {@code toMs} 이하라는 뜻이다. 아직 안 닫힌 창을
     * 집계하면 그 창의 채팅이 더 들어올 수 있어 수가 나중에 바뀐다 — 한 번 보낸 카드는
     * 못 고치므로 닫힌 것만 본다.
     */
    public static List<Long> closedWindowsBetween(long fromMs, long toMs, long windowSizeMs) {
        List<Long> windows = new ArrayList<>();
        for (long start = floorTo(fromMs, windowSizeMs); start + windowSizeMs <= toMs; start += windowSizeMs) {
            windows.add(start);
        }
        return windows;
    }
}
