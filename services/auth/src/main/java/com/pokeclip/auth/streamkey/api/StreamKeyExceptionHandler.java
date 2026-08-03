package com.pokeclip.auth.streamkey.api;

import com.pokeclip.auth.streamkey.StreamKeyException;
import com.pokeclip.auth.streamkey.StreamKeyFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * AuthExceptionHandler와 정책이 반대다 — 사유를 상태 코드와 본문으로 나눠 내보낸다.
 * 근거는 StreamKeyFailure의 주석에 있다.
 *
 * <p>레벨은 INFO다. 교환 엔드포인트가 permitAll이라 미인증 트래픽이 이 줄을 무한
 * 생성할 수 있다 — /api/auth/refresh가 WARN이라서 "건수로 알람 걸지 마라"가
 * 함정으로 남은 것과 같은 구조다. 여기서는 처음부터 INFO로 둔다.
 *
 * <p>예외 메시지를 로그에도 본문에도 넣지 않는다. 한국어이기도 하고, 거부된
 * 코드가 딸려 들어올 경로를 애초에 막는다.
 */
@RestControllerAdvice
public class StreamKeyExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(StreamKeyExceptionHandler.class);

    @ExceptionHandler(StreamKeyException.class)
    public ResponseEntity<Map<String, String>> handle(StreamKeyException e) {
        log.info("auth.streamkey.failed reason={}", e.failure());

        return ResponseEntity.status(statusOf(e.failure()))
                .body(Map.of("reason", e.failure().name()));
    }

    private HttpStatus statusOf(StreamKeyFailure failure) {
        return switch (failure) {
            case PAIRING_CODE_NOT_FOUND, STREAM_KEY_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case PAIRING_CODE_EXPIRED -> HttpStatus.GONE;
            case PAIRING_CODE_ALREADY_USED -> HttpStatus.CONFLICT;
            case PAIRING_CODE_RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
        };
    }
}
