package com.pokeclip.clip.jumpcard.api;

import com.pokeclip.clip.jumpcard.JumpCardErrors.BroadcastNotFoundException;
import com.pokeclip.clip.jumpcard.JumpCardErrors.ClaimedByOtherException;
import com.pokeclip.clip.jumpcard.JumpCardErrors.InvalidHighlightException;
import com.pokeclip.clip.jumpcard.JumpCardErrors.JumpCardNotFoundException;
import com.pokeclip.clip.jumpcard.JumpCardErrors.NotClaimOwnerException;
import com.pokeclip.clip.jumpcard.JumpCardErrors.StreamLimitExceededException;
import com.pokeclip.clip.jumpcard.JumpCardErrors.TokenAlreadyExpiredException;
import com.pokeclip.clip.jumpcard.JumpCardSnapshot;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
        return json(HttpStatus.NOT_FOUND, error("broadcast_not_found"));
    }

    @ExceptionHandler(JumpCardNotFoundException.class)
    ResponseEntity<Map<String, Object>> jumpCardNotFound(JumpCardNotFoundException e) {
        return json(HttpStatus.NOT_FOUND, error("jump_card_not_found"));
    }

    /**
     * 409의 본문은 오류 봉투가 아니라 <b>현재 카드 스냅샷</b>이다 — 웹이 "누가 잡고 있는지"를
     * 새로고침 없이 바로 띄워야 편집자가 상황을 안다.
     */
    @ExceptionHandler(ClaimedByOtherException.class)
    ResponseEntity<JumpCardSnapshot> claimedByOther(ClaimedByOtherException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_JSON).body(e.current());
    }

    @ExceptionHandler(NotClaimOwnerException.class)
    ResponseEntity<Map<String, Object>> notClaimOwner(NotClaimOwnerException e) {
        return json(HttpStatus.FORBIDDEN, error("not_claim_owner"));
    }

    /**
     * 503. {@code scope}를 실어야 웹이 "탭을 닫아라"(user)와 "잠시 뒤 다시"(total)를 구분해 안내한다.
     * SSE 경로에서 나가지만 본문은 JSON이다 — 연결이 서기 전에 끝나므로 event-stream이 아니다.
     */
    @ExceptionHandler(StreamLimitExceededException.class)
    ResponseEntity<Map<String, Object>> streamLimit(StreamLimitExceededException e) {
        Map<String, Object> body = error("stream_limit");
        body.put("scope", e.scope());
        return json(HttpStatus.SERVICE_UNAVAILABLE, body);
    }

    /** 401. 다른 401(체인이 내는 것)과 상태 코드가 같아야 프론트가 한 갈래로 처리한다. */
    @ExceptionHandler(TokenAlreadyExpiredException.class)
    ResponseEntity<Map<String, Object>> tokenExpired(TokenAlreadyExpiredException e) {
        return json(HttpStatus.UNAUTHORIZED, error("token_expired"));
    }

    @ExceptionHandler(InvalidHighlightException.class)
    ResponseEntity<Map<String, Object>> invalid(InvalidHighlightException e) {
        Map<String, Object> body = error("invalid_request");
        body.put("field", e.field());
        return json(HttpStatus.BAD_REQUEST, body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> beanValidation(MethodArgumentNotValidException e) {
        Map<String, Object> body = error("invalid_request");
        if (e.getFieldError() != null) {
            body.put("field", e.getFieldError().getField());
        }
        return json(HttpStatus.BAD_REQUEST, body);
    }

    /**
     * <b>Content-Type을 명시해야 한다.</b> SSE 문은 {@code produces=text/event-stream}이고
     * 브라우저 EventSource는 {@code Accept: text/event-stream}을 보낸다 — 그 상태로 JSON을 돌려주면
     * 협상에 실패해 {@code HttpMediaTypeNotAcceptableException}이 나고 <b>404·503이 500으로 둔갑한다</b>
     * (실측: 없는 방송 → 500, 상한 초과 → 500). 명시하면 협상을 건너뛴다.
     */
    private ResponseEntity<Map<String, Object>> json(HttpStatus status, Map<String, Object> body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private Map<String, Object> error(String code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        return body;
    }
}
