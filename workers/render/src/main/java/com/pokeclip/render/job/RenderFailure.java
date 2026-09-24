package com.pokeclip.render.job;

/**
 * 주문을 끝내는 실패 한 건. {@code retryable}이 「같은 주문을 다시 받으면 될 수도 있나」다. 
 * 레시피·소스가 틀린 것은 몇 번을 다시 해도 같으니 바로 종결하고, S3 일시 오류·ffmpeg 급사는 줄에 다시 맡긴다.
 *
 * <p>메시지는 사용자 화면까지 간다(clip이 {@code clips.error_message}로 적는다). 그래서 내부 경로·키를 넣지 않는다.
 */
public class RenderFailure extends RuntimeException {

    private final ErrorCode code;
    private final boolean retryable;

    public RenderFailure(ErrorCode code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public RenderFailure(ErrorCode code, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.retryable = retryable;
    }

    /** 다시 해도 같은 결과인 실패. */
    public static RenderFailure permanent(ErrorCode code, String message) {
        return new RenderFailure(code, message, false);
    }

    /** 다시 하면 될 수도 있는 실패. 코드는 늘 INTERNAL이다. */
    public static RenderFailure transientFailure(String message, Throwable cause) {
        return new RenderFailure(ErrorCode.INTERNAL, message, true, cause);
    }

    public ErrorCode code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
