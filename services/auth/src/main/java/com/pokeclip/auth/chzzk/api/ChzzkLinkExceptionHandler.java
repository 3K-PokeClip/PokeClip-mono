package com.pokeclip.auth.chzzk.api;

import com.pokeclip.auth.chzzk.ChzzkLinkException;
import com.pokeclip.auth.chzzk.ChzzkLinkFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * StreamKeyExceptionHandler와 같은 모양 — 사유를 상태 코드와 본문으로 나눠 내보낸다.
 * 예외 메시지는 로그에도 본문에도 넣지 않는다.
 */
@RestControllerAdvice
public class ChzzkLinkExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ChzzkLinkExceptionHandler.class);

    @ExceptionHandler(ChzzkLinkException.class)
    public ResponseEntity<Map<String, String>> handle(ChzzkLinkException e) {
        log.info("auth.chzzk.link.failed reason={}", e.failure());
        return ResponseEntity.status(statusOf(e.failure()))
                .body(Map.of("reason", e.failure().name()));
    }

    private static HttpStatus statusOf(ChzzkLinkFailure failure) {
        return switch (failure) {
            case INVALID_STATE, INVALID_CODE -> HttpStatus.BAD_REQUEST;
            case CHANNEL_ALREADY_LINKED -> HttpStatus.CONFLICT;
            case CHZZK_UNAVAILABLE -> HttpStatus.BAD_GATEWAY;
        };
    }
}
