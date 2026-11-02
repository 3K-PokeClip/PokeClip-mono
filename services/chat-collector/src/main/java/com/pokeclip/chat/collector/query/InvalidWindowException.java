package com.pokeclip.chat.collector.query;

/**
 * 요청 값이 우리가 받을 수 있는 모양이 아니다. 400으로 나간다.
 *
 * <p><b>사유는 짧은 낱말 하나다</b>({@code missing}·{@code unreadable}·{@code out_of_range}·
 * {@code inverted}·{@code too_wide}·{@code limit}·{@code kinds}·{@code bucket}·
 * {@code too_many_buckets}). <b>받은 값을 여기 담지 않는다</b> — 400 본문에 그대로 실리면
 * 반사된 값이 로그와 화면으로 흐른다(영상 위치 창구의 같은 결정과 한 방향이다).
 */
public class InvalidWindowException extends RuntimeException {

    private final String reason;

    public InvalidWindowException(String reason) {
        super(reason);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
