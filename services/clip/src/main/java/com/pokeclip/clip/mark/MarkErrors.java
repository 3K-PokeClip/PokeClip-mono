package com.pokeclip.clip.mark;

import org.springframework.http.HttpStatus;

/**
 * 수동 마킹 문의 거절(계약4 4B/2B). 본문은 {@code {"reason":…}} 하나이고 매핑은 {@code MarkExceptionHandler}가 한다.
 *
 * <p>🔴 <b>기존 예외 타입을 다시 쓰지 않는다</b>(계획 검증 E1). 전역 {@code JumpCardExceptionHandler}가 같은 타입을 먼저
 * 잡아 {@code {"error":…}} 봉투로 내보낸다({@code assignableTypes}는 우선권을 안 준다). 이 타입은 전역 조언에 없다.
 */
public final class MarkErrors {

    /** 400 {@code invalid_request}: 본문·헤더가 계약 모양이 아니다. 플러그인은 버린다. */
    public static final String INVALID_REQUEST = "invalid_request";

    /** 401 {@code invalid_stream_key}: 키가 없거나 폐기됐거나 파생 값이 다르다. 셋을 안 가른다. */
    public static final String INVALID_STREAM_KEY = "invalid_stream_key";

    /** 404 {@code broadcast_not_found}: 이 회원의 켜진 방송이 없다. 플러그인은 다시 보낸다(방송 시작 신호가 늦을 수 있다). */
    public static final String BROADCAST_NOT_FOUND = "broadcast_not_found";

    /** 503 {@code timeline_not_ready}: 첫 조각이 아직 없어 시각 기준점이 없다. {@code Retry-After}를 단다. */
    public static final String TIMELINE_NOT_READY = "timeline_not_ready";

    /**
     * 거절 하나. {@code reason}이 null이면 본문 없이 상태만 낸다(auth를 못 물었을 때의 503: 계약이 사유를 안 정했고,
     * 플러그인은 사유 없는 503을 재시도한다).
     */
    public static class MarkRejectedException extends RuntimeException {
        private final HttpStatus status;
        private final String reason;
        private final Integer retryAfterSeconds;

        MarkRejectedException(HttpStatus status, String reason, Integer retryAfterSeconds) {
            // 메시지에 요청 값을 싣지 않는다: 스택이 로그로 갈 수 있다.
            super("mark rejected: " + status.value() + " " + reason);
            this.status = status;
            this.reason = reason;
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public HttpStatus status() {
            return status;
        }

        public String reason() {
            return reason;
        }

        public Integer retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    static MarkRejectedException invalidRequest() {
        return new MarkRejectedException(HttpStatus.BAD_REQUEST, INVALID_REQUEST, null);
    }

    static MarkRejectedException invalidStreamKey() {
        return new MarkRejectedException(HttpStatus.UNAUTHORIZED, INVALID_STREAM_KEY, null);
    }

    static MarkRejectedException broadcastNotFound() {
        return new MarkRejectedException(HttpStatus.NOT_FOUND, BROADCAST_NOT_FOUND, null);
    }

    static MarkRejectedException timelineNotReady(int retryAfterSeconds) {
        return new MarkRejectedException(HttpStatus.SERVICE_UNAVAILABLE, TIMELINE_NOT_READY, retryAfterSeconds);
    }

    static MarkRejectedException keyCheckUnavailable() {
        return new MarkRejectedException(HttpStatus.SERVICE_UNAVAILABLE, null, null);
    }

    private MarkErrors() {
    }
}
