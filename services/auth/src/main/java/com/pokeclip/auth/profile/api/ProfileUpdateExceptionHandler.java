package com.pokeclip.auth.profile.api;

import com.pokeclip.auth.profile.ProfileUpdateException;
import com.pokeclip.auth.profile.ProfileUpdateFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * YoutubeLinkExceptionHandler와 같은 모양 — 사유를 상태 코드와 본문으로 나눠 내보낸다.
 * 거부된 이름은 어디에도 싣지 않는다(사용자가 방금 입력한 값이라 로그에 남길 이유가 없다).
 */
@RestControllerAdvice
public class ProfileUpdateExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProfileUpdateExceptionHandler.class);

    @ExceptionHandler(ProfileUpdateException.class)
    public ResponseEntity<Map<String, String>> handle(ProfileUpdateException e) {
        log.info("auth.profile.update.failed reason={}", e.failure());
        return ResponseEntity.status(statusOf(e.failure()))
                .body(Map.of("reason", e.failure().name()));
    }

    /**
     * 413은 「파일이 크다」, 415는 「그림이 아니다」, 503은 「창고가 꺼져 있어 지금은 못 받는다」다.
     * 셋을 400으로 뭉치면 화면이 "줄여서 다시"와 "잠시 뒤에 다시"를 구분해 말할 수 없다.
     *
     * <p>사진 세 갈래는 아직 던지는 자리가 없다(태스크 6·7이 연다). 매핑을 미리 두는 이유는
     * enum과 상태 코드가 한 파일에서 같이 읽혀야 나중에 갈래를 더할 때 빠뜨리지 않기 때문이다.
     */
    private static HttpStatus statusOf(ProfileUpdateFailure failure) {
        return switch (failure) {
            case NAME_BLANK, NAME_TOO_LONG -> HttpStatus.BAD_REQUEST;
            // PAYLOAD_TOO_LARGE는 Spring 7에서 deprecated다. 코드는 같은 413이다.
            case PHOTO_TOO_LARGE -> HttpStatus.CONTENT_TOO_LARGE;
            case PHOTO_NOT_AN_IMAGE -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case PHOTO_STORAGE_DISABLED -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }
}
