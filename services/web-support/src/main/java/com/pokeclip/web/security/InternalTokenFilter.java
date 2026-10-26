package com.pokeclip.web.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 서버끼리 부르는 창구에서 호출자가 토큰 헤더로 자기를 증명하게 하는 필터. 헤더 값이 기대한 토큰과 같아야 체인 뒤로
 * 보내고, 아니면 본문 없이 401 로 끝낸다. 헤더 이름은 생성자로 받는다: 공용 {@value #HEADER} 와 출발지 잠금 창구의 전용
 * 헤더가 같은 필터를 쓴다.
 *
 * <p>쓰는 자리: auth 공용 체인({@code InternalSecurityConfig}) · auth resolve 전용 체인
 * ({@code StreamKeyResolveSecurityConfig}) · clip 만료 방송 전용 체인
 * ({@code ExpiredBroadcastSecurityConfig}). 전용 체인은 이 필터 앞에 {@link SourceNetworkFilter}를 둔다. clip 공용
 * 체인은 clip 자기 {@code InternalTokenFilter}를 쓴다.
 *
 * <p>빈 토큰은 생성자가 거부한다. 빈 토큰으로 만들면 빈 헤더가 통과한다
 * ({@code MessageDigest.isEqual(빈, 빈)}이 참). 창구를 닫으려면 이 필터 대신 {@link DenyAllFilter}를 단다.
 *
 * <p>비교에 String.equals를 쓰지 않는다. 앞자리가 다르면 즉시 끝나므로 응답 시간으로 한 글자씩 맞춰볼 수 있다.
 * MessageDigest.isEqual은 길이가 같으면 전체를 훑는다.
 *
 * <p>실패 로그를 남기지 않는다. 이 경로는 인증 없이 호출할 수 있어, 찍으면 미인증 트래픽이 로그를 무한
 * 생성한다(/api/auth/refresh와 같은 함정).
 */
public final class InternalTokenFilter extends OncePerRequestFilter {

  static final String HEADER = "X-Internal-Token";

  private final byte[] expected;
  private final String headerName;

  public InternalTokenFilter(String token) {
    this(token, HEADER);
  }

  /**
   * 토큰을 {@code headerName} 헤더에서 받는다. 위 생성자는 {@value #HEADER}를 본다.
   *
   * @throws IllegalArgumentException 토큰이 null 이거나 비었거나 공백뿐일 때. 값은 메시지에 싣지 않는다
   */
  public InternalTokenFilter(String token, String headerName) {
    if (token == null || token.isBlank()) {
      throw new IllegalArgumentException(
          "토큰이 비었다: 빈 토큰은 빈 헤더를 통과시킨다. 창구를 닫으려면 DenyAllFilter 를 단다");
    }
    this.expected = token.getBytes(StandardCharsets.UTF_8);
    this.headerName = headerName;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String presented = request.getHeader(headerName);

    if (presented == null
        || !MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expected)) {
      response.setStatus(HttpStatus.UNAUTHORIZED.value());
      return;
    }
    chain.doFilter(request, response);
  }
}
