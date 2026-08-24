package com.pokeclip.auth.youtube.api;

import com.pokeclip.auth.youtube.YoutubeLinkException;
import com.pokeclip.auth.youtube.YoutubeLinkFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * ChzzkLinkExceptionHandler와 같은 모양 — 사유를 상태 코드와 본문으로 나눠 내보낸다.
 * 예외 메시지는 로그에도 본문에도 넣지 않는다(한국어 설명이라 옮길 값도 아니다).
 */
@RestControllerAdvice
public class YoutubeLinkExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(YoutubeLinkExceptionHandler.class);

    @ExceptionHandler(YoutubeLinkException.class)
    public ResponseEntity<Map<String, String>> handle(YoutubeLinkException e) {
        log.info("auth.youtube.link.failed reason={}", e.failure());
        return ResponseEntity.status(statusOf(e.failure()))
                .body(Map.of("reason", e.failure().name()));
    }

    /**
     * 400은 「사용자가 다시 동의하면 풀린다」, 409는 「다른 자원과 부딪혔다」, 502는 「구글 쪽이니 재시도하라」다.
     * 할당량 소진(403 quotaExceeded)이 400으로 나가면 사용자가 몇 번을 다시 동의해도 같은 자리에서 막힌다 —
     * 그래서 그것은 클라이언트에서 502(YOUTUBE_UNAVAILABLE)로 분류돼 여기 온다.
     */
    private static HttpStatus statusOf(YoutubeLinkFailure failure) {
        return switch (failure) {
            case INVALID_STATE, INVALID_CODE, SCOPE_MISSING, NO_CHANNEL -> HttpStatus.BAD_REQUEST;
            case CHANNEL_ALREADY_LINKED, LINK_BROKEN -> HttpStatus.CONFLICT;
            case NOT_LINKED -> HttpStatus.NOT_FOUND;
            case YOUTUBE_UNAVAILABLE -> HttpStatus.BAD_GATEWAY;
        };
    }
}
