package com.pokeclip.auth.delegation.api;

import com.pokeclip.auth.delegation.DelegationException;
import com.pokeclip.auth.delegation.DelegationFailure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * ChzzkLinkExceptionHandler와 같은 모양 — 사유를 상태 코드와 본문으로 나눠 내보낸다.
 * 예외 메시지는 로그에도 본문에도 넣지 않는다.
 */
@RestControllerAdvice(assignableTypes = EditorInvitationController.class)   // 태스크 8에서 위임 컨트롤러를 더한다
public class DelegationExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(DelegationExceptionHandler.class);

    @ExceptionHandler(DelegationException.class)
    public ResponseEntity<Map<String, String>> handle(DelegationException e) {
        log.info("auth.delegation.failed reason={}", e.getFailure());
        return ResponseEntity.status(statusOf(e.getFailure()))
                .body(Map.of("reason", e.getFailure().name()));
    }

    /**
     * 제약 위반이 여기까지 온 경우다. <b>어느 제약인지 반드시 가른다</b> — 뭉뚱그려 409
     * ALREADY_EDITOR로 답하면 초대 쪽 경합에 틀린 이유가 나간다.
     *
     * <p>실제로 여기 도달하는 것은 수락 시점의 위임 유일 인덱스뿐이다. 초대 유일 인덱스
     * 위반은 InvitationService가 재조회로 흡수해 예외로 나오지 않는다(그래도 방어로 남긴다).
     *
     * <p><b>모르는 제약은 다시 던진다.</b> 조용히 409로 바꾸면 진짜 버그가 정상 응답에 숨는다.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> handle(DataIntegrityViolationException e) {
        DelegationFailure failure = failureOf(e);
        log.info("auth.delegation.failed reason={} byDb=true", failure);
        return ResponseEntity.status(statusOf(failure)).body(Map.of("reason", failure.name()));
    }

    /**
     * 제약 이름으로 가른다. Postgres가 메시지에 이름을 담고 Hibernate가 그대로 올려 보낸다 —
     * 마이그레이션에서 인덱스 이름을 바꾸면 여기도 같이 바꿔야 한다(V108과 쌍이다).
     */
    private static DelegationFailure failureOf(DataIntegrityViolationException e) {
        String cause = e.getMostSpecificCause().getMessage();
        if (cause == null) {
            throw e;
        }
        if (cause.contains("uq_delegations_alive_pair")) {
            return DelegationFailure.ALREADY_EDITOR;
        }
        if (cause.contains("uq_invitations_pending_pair")) {
            return DelegationFailure.INVITATION_NOT_PENDING;
        }
        throw e;
    }

    private static HttpStatus statusOf(DelegationFailure failure) {
        return switch (failure) {
            case SELF_INVITE -> HttpStatus.BAD_REQUEST;
            case INVITEE_NOT_FOUND, INVITATION_NOT_FOUND, DELEGATION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ALREADY_EDITOR, TOO_MANY_PENDING, INVITATION_NOT_PENDING -> HttpStatus.CONFLICT;
            case INVITATION_EXPIRED -> HttpStatus.GONE;
        };
    }
}
