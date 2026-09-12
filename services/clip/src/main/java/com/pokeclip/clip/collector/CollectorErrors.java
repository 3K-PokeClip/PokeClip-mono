package com.pokeclip.clip.collector;

/**
 * 수집기를 못 부른 것 하나.
 *
 * <p><b>「수집기가 400을 줬다」는 여기 없다.</b> 그것은 우리가 잘못 물은 것이고 본문을 그대로
 * 넘기면 프론트가 사유를 읽는다 — 예외로 바꾸면 그 낱말이 사라진다.
 */
public final class CollectorErrors {

    /**
     * 503. <b>빈 목록으로 접지 않는다</b> — 화면이 「그 구간에 채팅이 없었다」로 단정하면
     * 수집기가 살아난 뒤에도 편집자는 다시 누르지 않는다. 「장애」와 「0건」은 다른 상태다.
     */
    public static class CollectorUnavailableException extends RuntimeException {

        public CollectorUnavailableException(String cause) {
            // 사유는 우리 코드가 정한 고정 문자열이거나 예외 클래스 이름이다 — 수집기가 준
            // 본문은 안 담는다(그 안에 무엇이 들었는지는 저쪽이 정한다).
            super("수집기를 못 불렀다: " + cause);
        }
    }

    private CollectorErrors() {
    }
}
