package com.pokeclip.clip.delegation;

/**
 * 자격 판정의 거절 둘.
 *
 * <p><b>{@code segment} 패키지에 이름이 같은 예외가 있지만 타입이 다르다</b> — 그쪽은
 * {@code SegmentExceptionHandler}가, 이쪽은 전역 조언이 다룬다. 타입이 다르면 안 겹치는 것을
 * 계획 검증이 확인했다. 합치는 것은 이 카드 범위 밖이다.
 */
public final class AccessErrors {

    /**
     * 404. <b>「없는 방송」과 「자격 없음」이 같은 예외다</b> — 응답에서 갈리면 방송 이름을 넣어
     * 보는 것만으로 그 방송의 실재를 알 수 있다. 사유는 로그로만 간다.
     */
    public static class NotViewableException extends RuntimeException {

        private final String reason;

        public NotViewableException(String reason) {
            super("볼 수 없다: " + reason);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    /**
     * 503. <b>404로 접지 않는다</b> — 화면이 「없는 방송」이라고 단정하면 auth가 살아난 뒤에도
     * 편집자는 다시 시도하지 않는다.
     */
    public static class AuthUnavailableException extends RuntimeException {

        public AuthUnavailableException() {
            super("자격을 물어보지 못했다");
        }
    }

    private AccessErrors() {
    }
}
