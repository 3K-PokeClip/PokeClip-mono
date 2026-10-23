package com.pokeclip.clip.mark.api;

import com.pokeclip.clip.jumpcard.JumpCardErrors.HotkeyBroadcastGoneException;
import com.pokeclip.clip.jumpcard.JumpCardErrors.HotkeyEventOwnedByOtherException;
import com.pokeclip.clip.mark.MarkErrors;
import com.pokeclip.clip.mark.MarkErrors.MarkRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 마킹 문의 거절 본문 {@code {"reason":…}}(계약4 4B). 이 문에만 건다.
 *
 * <p>🔴 <b>{@code assignableTypes}는 우선권을 주지 않는다</b>({@code SegmentExceptionHandler} 주석). 지금 응답이 옳은 근거는
 * 「좁혔다」가 아니라 <b>여기 타입 셋이 전역 {@code JumpCardExceptionHandler}에 없다</b>는 것이다. 전역에 이 타입(또는 그
 * 상위 타입)의 처리기를 더하면 이 문의 본문이 {@code {"error":…}}로 바뀐다: {@code MarkControllerTest}의 본문 바이트 단언이 잡는다.
 */
@RestControllerAdvice(assignableTypes = MarkController.class)
public class MarkExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(MarkExceptionHandler.class);

    @ExceptionHandler(MarkRejectedException.class)
    ResponseEntity<Map<String, String>> rejected(MarkRejectedException e) {
        log.info("clip.mark.rejected status={} reason={}", e.status().value(), e.reason());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(e.status());
        if (e.retryAfterSeconds() != null) {
            response.header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()));
        }
        if (e.reason() == null) {
            return response.build();
        }
        return response.contentType(MediaType.APPLICATION_JSON).body(Map.of("reason", e.reason()));
    }

    /** 남의 카드의 eventId(감사 1회차 A4). 그 카드를 안 싣는다. */
    @ExceptionHandler(HotkeyEventOwnedByOtherException.class)
    ResponseEntity<Map<String, String>> ownedByOther(HotkeyEventOwnedByOtherException e) {
        return reason(HttpStatus.BAD_REQUEST, MarkErrors.INVALID_REQUEST);
    }

    /** 고른 방송이 잠그는 사이 사라졌거나 이 회원의 것이 아니다(감사 1회차 A1·A4). 없는 방송과 같은 본문. */
    @ExceptionHandler(HotkeyBroadcastGoneException.class)
    ResponseEntity<Map<String, String>> broadcastGone(HotkeyBroadcastGoneException e) {
        return reason(HttpStatus.NOT_FOUND, MarkErrors.BROADCAST_NOT_FOUND);
    }

    private static ResponseEntity<Map<String, String>> reason(HttpStatus status, String reason) {
        log.info("clip.mark.rejected status={} reason={}", status.value(), reason);
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(Map.of("reason", reason));
    }
}
