package com.pokeclip.clip.jumpcard.api;

import com.pokeclip.clip.jumpcard.JumpCardErrors.BroadcastNotFoundException;
import com.pokeclip.clip.jumpcard.JumpCardErrors.ClaimedByOtherException;
import com.pokeclip.clip.jumpcard.JumpCardErrors.InvalidHighlightException;
import com.pokeclip.clip.jumpcard.JumpCardErrors.JumpCardNotFoundException;
import com.pokeclip.clip.jumpcard.JumpCardErrors.NotClaimOwnerException;
import com.pokeclip.clip.jumpcard.JumpCardSnapshot;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 카드 관련 예외를 상태 코드로 옮기는 유일한 자리.
 *
 * <p><b>{@code IllegalArgumentException}을 통째로 잡지 않는다.</b> 잡으면 내부 버그로 나온
 * 예외가 400으로 둔갑하고, 판별기는 그것을 「내가 잘못 보냈다」로 읽어 재시도를 멈춘다.
 * 400을 줄 자리는 컨트롤러가 {@code InvalidHighlightException}으로 좁혀 던진다.
 */
@RestControllerAdvice
public class JumpCardExceptionHandler {

    @ExceptionHandler(BroadcastNotFoundException.class)
    ResponseEntity<Map<String, Object>> broadcastNotFound(BroadcastNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error("broadcast_not_found"));
    }

    @ExceptionHandler(JumpCardNotFoundException.class)
    ResponseEntity<Map<String, Object>> jumpCardNotFound(JumpCardNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error("jump_card_not_found"));
    }

    /**
     * 409의 본문은 오류 봉투가 아니라 <b>현재 카드 스냅샷</b>이다 — 웹이 "누가 잡고 있는지"를
     * 새로고침 없이 바로 띄워야 편집자가 상황을 안다.
     */
    @ExceptionHandler(ClaimedByOtherException.class)
    ResponseEntity<JumpCardSnapshot> claimedByOther(ClaimedByOtherException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.current());
    }

    @ExceptionHandler(NotClaimOwnerException.class)
    ResponseEntity<Map<String, Object>> notClaimOwner(NotClaimOwnerException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(error("not_claim_owner"));
    }

    @ExceptionHandler(InvalidHighlightException.class)
    ResponseEntity<Map<String, Object>> invalid(InvalidHighlightException e) {
        Map<String, Object> body = error("invalid_request");
        body.put("field", e.field());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> beanValidation(MethodArgumentNotValidException e) {
        Map<String, Object> body = error("invalid_request");
        if (e.getFieldError() != null) {
            body.put("field", e.getFieldError().getField());
        }
        return ResponseEntity.badRequest().body(body);
    }

    private Map<String, Object> error(String code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        return body;
    }
}
