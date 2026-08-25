package com.pokeclip.auth.profile.api;

import com.pokeclip.auth.profile.ProfileUpdateException;
import com.pokeclip.auth.profile.ProfileUpdateFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
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
     * 크기 상한은 <b>서블릿 층</b>이 자르므로 우리 코드가 바이트를 만지기 전에 터진다 —
     * ProfileUpdateException으로 오지 않는다. 여기서 같은 사유 코드로 바꿔야 화면이
     * 「줄여서 다시」를 말할 수 있다. auth의 멀티파트 창구는 사진 하나뿐이라 이 예외를
     * 그 사유로 뭉뚱그려도 갈래가 섞이지 않는다 — <b>다른 파일 창구가 생기면 갈라야 한다.</b>
     *
     * <p>예외 메시지를 옮기지 않는다: 상한 값이 그대로 실려 있어 본문에 넣을 이유가 없다.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handle(MaxUploadSizeExceededException e) {
        return handle(new ProfileUpdateException(ProfileUpdateFailure.PHOTO_TOO_LARGE, "파일이 상한을 넘는다"));
    }

    /**
     * 413은 「파일이 크다」, 415는 「그림이 아니다」, 503은 「창고가 꺼져 있어 지금은 못 받는다」다.
     * 셋을 400으로 뭉치면 화면이 "줄여서 다시"와 "잠시 뒤에 다시"를 구분해 말할 수 없다.
     *
     * <p>세 갈래 모두 재는 자리가 있다 — 413은 ProfilePhotoSizeLimitTest(진짜 톰캣),
     * 415는 ProfilePhotoUploadTest, 503은 ProfilePhotoDisabledTest.
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
