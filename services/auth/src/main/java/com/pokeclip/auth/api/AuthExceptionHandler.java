package com.pokeclip.auth.api;

import com.pokeclip.auth.AuthException;
import com.pokeclip.auth.AuthFailure;
import com.pokeclip.auth.DataInconsistencyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class AuthExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AuthExceptionHandler.class);

    private static final Map<String, String> BODY = Map.of("message", "인증에 실패했습니다");

    /**
     * 이 사유만 이유를 알려 준다. 나머지와 갈리는 근거는 <b>감출 이익이 없다</b>는 것이다 —
     * 다른 실패들은 "코드가 만료됐다"와 "서명이 틀렸다"를 구분해 주면 공격자에게 단서가 되지만,
     * 여기는 사용자가 직접 풀어야 하는 상태 충돌이라 알려주지 않으면 재시도만 반복한다.
     *
     * <p><b>이메일은 넣지 않는다.</b> 어느 주소인지는 사용자가 방금 로그인에 쓴 값이라 이미 알고,
     * 본문에 실으면 그 값이 로그·프록시·에러 리포터로 번진다.
     */
    private static final Map<String, String> EMAIL_TAKEN_BODY =
            Map.of("message", "이 이메일 주소는 이미 다른 계정이 쓰고 있습니다", "reason", "EMAIL_ALREADY_REGISTERED");

    /**
     * 실패 이유를 나누어 알리지 않는다. "코드가 만료됐다"와 "서명이 틀렸다"를
     * 구분해 주면 공격자에게 단서가 된다. e.getMessage()를 본문에 넣지 않는
     * 것이 핵심이다 — 이유별로 다른 문구가 들어 있다.
     *
     * <p>사유는 로그에만 남긴다. 예외 메시지는 한국어라 로그에 넣지 않고
     * enum 이름만 찍는다.
     *
     * <p>원인 예외의 본문은 찍지 않는다. 구글 응답 본문이 딸려 들어올 수 있고,
     * 그러면 시크릿이 로그로 새는 경로가 하나 생긴다. 대신 원인 타입만 남긴다 —
     * 구글 실패의 상세는 잃지만 그 대가로 유출 경로를 닫는다. 같은 이유로
     * e.getMessage()도 스택트레이스도 로그에 넣지 않는다.
     */
    @ExceptionHandler(AuthException.class)
    public ResponseEntity<Map<String, String>> handle(AuthException e) {
        if (e.failure() == AuthFailure.REFRESH_TOKEN_ALREADY_ROTATED) {
            // 유예 창 안의 중복 회전은 정상 동작이다. 정상 트래픽으로 WARN을 채우면
            // 알람 피로가 쌓인다.
            log.info("auth.failed reason={} causeType={}", e.failure(), causeType(e));
        } else {
            log.warn("auth.failed reason={} causeType={}", e.failure(), causeType(e));
        }
        if (e.failure() == AuthFailure.EMAIL_ALREADY_REGISTERED) {
            // 409: 요청 자체는 유효한데 현재 상태와 충돌한다. userId는 안 찍는다 —
            // 아직 계정이 없어서 찍을 것이 없다. 사유 코드만으로 문의를 추적할 수 있다.
            return ResponseEntity.status(HttpStatus.CONFLICT).body(EMAIL_TAKEN_BODY);
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(BODY);
    }

    /** 응답은 401로 같고 로그만 다르다. 우리 DB가 어긋났다는 뜻이라 ERROR다. */
    @ExceptionHandler(DataInconsistencyException.class)
    public ResponseEntity<Map<String, String>> handle(DataInconsistencyException e) {
        log.error("auth.failed reason={} userId={}", e.failure(), e.userId());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(BODY);
    }

    private String causeType(AuthException e) {
        return e.getCause() == null ? "none" : e.getCause().getClass().getSimpleName();
    }
}
