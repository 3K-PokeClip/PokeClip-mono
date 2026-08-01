package com.pokeclip.core.auth.api;

import com.pokeclip.core.auth.AuthException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class AuthExceptionHandler {

    /**
     * 실패 이유를 나누어 알리지 않는다. "코드가 만료됐다"와 "서명이 틀렸다"를
     * 구분해 주면 공격자에게 단서가 된다. e.getMessage()를 본문에 넣지 않는
     * 것이 핵심이다 — 이유별로 다른 문구가 들어 있다.
     */
    @ExceptionHandler(AuthException.class)
    public ResponseEntity<Map<String, String>> handle(AuthException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("message", "인증에 실패했습니다"));
    }
}
