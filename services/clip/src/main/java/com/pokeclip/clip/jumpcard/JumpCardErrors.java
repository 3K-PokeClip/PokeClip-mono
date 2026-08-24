package com.pokeclip.clip.jumpcard;

/**
 * 카드 관련 예외를 한 파일에 모은다. 흩어 두면 어떤 상태 코드가 나가는지 보려고
 * 파일 넷을 열어야 한다. 매핑은 {@code JumpCardExceptionHandler} 하나가 한다.
 */
public final class JumpCardErrors {

    /** 404. FK 위반(500)으로 두지 않는 이유 — 판별기가 재시도 상한을 세려면 404를 받아야 한다. */
    public static class BroadcastNotFoundException extends RuntimeException {
        private final String streamId;

        public BroadcastNotFoundException(String streamId) {
            super("없는 방송이다: " + streamId);
            this.streamId = streamId;
        }

        public String streamId() {
            return streamId;
        }
    }

    /**
     * 400. <b>{@code IllegalArgumentException}을 통째로 400으로 잡지 않으려고</b> 좁혀 던지는 타입이다 —
     * 내부 버그로 나온 같은 예외가 「요청이 잘못됐다」로 둔갑하면 판별기가 재시도를 멈춘다.
     */
    public static class InvalidHighlightException extends RuntimeException {
        private final String field;

        public InvalidHighlightException(String field) {
            super("요청이 잘못됐다: " + field);
            this.field = field;
        }

        public String field() {
            return field;
        }
    }

    /** 404. 카드 자체가 없다. */
    public static class JumpCardNotFoundException extends RuntimeException {
        private final long id;

        public JumpCardNotFoundException(long id) {
            super("없는 카드다: " + id);
            this.id = id;
        }

        public long id() {
            return id;
        }
    }

    /**
     * 409. 남이 잡고 있다. <b>현재 카드를 함께 싣는다</b> — 웹이 "누가 잡고 있는지"를
     * 바로 보여줘야 편집자가 새로고침 없이 상황을 안다.
     */
    public static class ClaimedByOtherException extends RuntimeException {
        private final transient JumpCardSnapshot current;

        public ClaimedByOtherException(JumpCardSnapshot current) {
            super("남이 잡고 있는 카드다: " + current.id());
            this.current = current;
        }

        public JumpCardSnapshot current() {
            return current;
        }
    }

    /** 403. 남이 잡은 카드를 놓으려 했다. 아무도 안 잡은 카드를 놓는 것은 성공이다(멱등). */
    public static class NotClaimOwnerException extends RuntimeException {
        private final long id;

        public NotClaimOwnerException(long id) {
            super("내가 잡은 카드가 아니다: " + id);
            this.id = id;
        }

        public long id() {
            return id;
        }
    }

    /**
     * 503. 연결 상한을 넘었다. {@code scope}가 「어느 상한인가」를 말한다 — 본문에 실어야
     * 웹이 "탭을 닫아라"(user)와 "잠시 뒤 다시"(total)를 구분해 안내한다.
     */
    public static class StreamLimitExceededException extends RuntimeException {
        private final String scope;

        public StreamLimitExceededException(String scope) {
            super("연결 상한을 넘었다: " + scope);
            this.scope = scope;
        }

        public String scope() {
            return scope;
        }
    }

    /**
     * 401. 토큰은 서명·형식이 멀쩡한데 <b>이미 만료</b>다.
     *
     * <p>디코더의 clock skew 허용치(기본 60초) 안쪽 토큰이 인증을 통과해 여기까지 온다.
     * 그대로 열면 남은 수명이 <b>음수</b>가 되고, 서블릿 규약상 timeout ≤ 0은 「시한 없음」이라
     * <b>만료된 토큰일수록 연결이 더 오래 산다</b>(실측: exp 59초 전 → timeout -59311ms →
     * 45초 뒤에도 살아 있고 하트비트 23개 수신). 그래서 아예 열지 않는다.
     */
    public static class TokenAlreadyExpiredException extends RuntimeException {

        public TokenAlreadyExpiredException() {
            super("이미 만료된 토큰으로는 통로를 열지 않는다");
        }
    }

    private JumpCardErrors() {
    }
}
