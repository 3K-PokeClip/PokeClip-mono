package com.pokeclip.auth.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 모든 요청을 401 로 끝낸다. 창구를 여는 토큰이 설정되지 않았을 때 그 체인에 단다.
 *
 * <p>빈 토큰으로 {@link InternalTokenFilter}를 만들면 안 된다 — 빈 헤더를 보내면 {@code
 * MessageDigest.isEqual(빈, 빈)}이 참이라 통과한다. 본문도 로그도 남기지 않는다({@link InternalTokenFilter}
 * 머리 주석과 같은 이유).
 */
final class DenyAllFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain) {
    response.setStatus(HttpStatus.UNAUTHORIZED.value());
  }
}
