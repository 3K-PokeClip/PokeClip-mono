package com.pokeclip.auth.soop.api;

import com.pokeclip.auth.soop.SoopLinkException;
import com.pokeclip.auth.soop.SoopLinkFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 치지직 처리기와 같은 모양: 사유를 상태 코드와 본문 {@code {"reason": 이름}}으로 나눠 내보낸다(웹이 reason을 읽는다,
 * 계획 검증 C6). 예외 메시지는 로그에도 본문에도 넣지 않는다.
 */
@RestControllerAdvice(assignableTypes = SoopLinkController.class)
public class SoopLinkExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(SoopLinkExceptionHandler.class);

    @ExceptionHandler(SoopLinkException.class)
    public ResponseEntity<Map<String, String>> handle(SoopLinkException e) {
        log.info("auth.soop.link.failed reason={}", e.failure());
        return ResponseEntity.status(statusOf(e.failure()))
                .body(Map.of("reason", e.failure().name()));
    }

    private static HttpStatus statusOf(SoopLinkFailure failure) {
        return switch (failure) {
            case INVALID_STATE, INVALID_CODE -> HttpStatus.BAD_REQUEST;
            case CHANNEL_ALREADY_LINKED -> HttpStatus.CONFLICT;
            case IDENTITY_UNAVAILABLE -> HttpStatus.UNPROCESSABLE_CONTENT;
            case SOOP_UNAVAILABLE -> HttpStatus.BAD_GATEWAY;
        };
    }
}
