package com.pokeclip.auth.streamkey;

/**
 * 메시지는 한국어라 로그에 넣지 않는다. 로그에는 failure 이름만 찍는다.
 *
 * <p>Lombok @Getter를 쓰지 않고 접근자를 손으로 쓴다. 기존 AuthException.failure()·
 * DataInconsistencyException.userId()가 전부 이 형태이고, 이 코드베이스의 예외
 * 클래스 중 @Getter를 쓰는 것이 하나도 없다(@Getter는 엔티티만 쓴다).
 * 그래야 AuthExceptionHandler와 StreamKeyExceptionHandler가 같은 모양이 된다.
 */
public class StreamKeyException extends RuntimeException {

    private final StreamKeyFailure failure;

    public StreamKeyException(StreamKeyFailure failure, String message) {
        super(message);
        this.failure = failure;
    }

    public StreamKeyFailure failure() {
        return failure;
    }
}
