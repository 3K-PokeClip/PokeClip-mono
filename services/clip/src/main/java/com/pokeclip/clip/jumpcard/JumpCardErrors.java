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

    /**
     * 수동 마킹(4B)의 eventId가 이미 <b>남의 방송 카드</b>다(POK-391 계획 검증 E4). 그 카드를 내주면 남의 카드가
     * 응답 본문에 실린다. 마킹 문이 400 {@code invalid_request}로 바꾼다.
     *
     * <p>🔴 <b>{@code JumpCardExceptionHandler}에 이 타입의 처리기를 두지 마라.</b> 그 조언은 전역이라 마킹 문의
     * 좁힌 조언({@code assignableTypes})보다 먼저 잡고, 거절 본문이 4B 계약({@code {"reason":…}})이 아니게 된다
     * (계획 검증 E1). 같은 이유로 마킹 길은 {@link BroadcastNotFoundException}을 안 쓴다.
     */
    public static class HotkeyEventOwnedByOtherException extends RuntimeException {

        public HotkeyEventOwnedByOtherException() {
            super("이 eventId는 다른 회원의 카드다");
        }
    }

    /**
     * 수동 마킹이 고른 방송이 잠그려는 사이 사라졌다(계획 검증 E8). 마킹 문이 404 {@code broadcast_not_found}로
     * 바꾼다. 전역 조언에 두지 않는 이유는 {@link HotkeyEventOwnedByOtherException}과 같다.
     */
    public static class HotkeyBroadcastGoneException extends RuntimeException {

        public HotkeyBroadcastGoneException() {
            super("수동 카드를 넣을 방송이 없다");
        }
    }

    /**
     * 503. 웹 수동 마킹(POK-392)의 방송에 첫 조각이 아직 없어 시각 기준점이 없다. {@code Retry-After}를 단다.
     *
     * <p>전역 조언에 처리기를 둬도 되는 이유: OBS 마킹 문은 이 타입을 안 던진다(그쪽 503은 {@code MarkErrors}다). 그래서
     * 전역이 OBS 문의 {@code {"reason":…}} 봉투를 가로채는 일이 없다({@link HotkeyEventOwnedByOtherException} 주석과 반대 사정).
     */
    public static class TimelineNotReadyException extends RuntimeException {
        private final int retryAfterSeconds;

        public TimelineNotReadyException(int retryAfterSeconds) {
            super("시각 기준점이 아직 없다");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public int retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    private JumpCardErrors() {
    }
}
