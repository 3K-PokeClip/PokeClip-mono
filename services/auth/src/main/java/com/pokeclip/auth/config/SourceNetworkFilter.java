package com.pokeclip.auth.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 출발지가 허용 IPv4 대역 밖이면 403 으로 끝낸다. 본문도 로그도 남기지 않는다 — 인증 없이 부를 수 있는
 * 길이라 찍으면 미인증 트래픽이 로그를 무한히 만든다({@link InternalTokenFilter}와 같은 이유).
 *
 * <p>출발지는 소켓 주소({@code getRemoteAddr()})만 본다. 전달 헤더를 믿게 하는 설정은 {@link
 * StreamKeyResolveSecurityConfig}의 부팅 검사가 막는다.
 *
 * <p>대역은 IPv4 만 받는다. Spring Security 7.1 의 {@link IpAddressMatcher}는 주소 계열을 보지 않는다
 * (spring-projects/spring-security#19733) — IPv6 대역에 IPv4 주소를 대면 배열 범위 예외가 날 수 있고,
 * 생성자는 받은 값을 실패 메시지에 싣는다. 그래서 꼴을 여기서 먼저 본다. 남는 한계: IPv4 대역의 앞 바이트와
 * 같은 IPv6 주소는 맞는다고 판정된다(1번 r7.1 설계 8-B 가 받아들인 한계 — 그런 IPv6 대역은 IETF 예약이다).
 */
final class SourceNetworkFilter extends OncePerRequestFilter {

  private static final String OCTET = "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";

  /** a.b.c.d/n — 마디 0–255, 접두 0–32. 앞자리 0 은 받지 않는다(8진수로 읽는 도구가 있어 뜻이 갈린다). */
  private static final Pattern IPV4_CIDR =
      Pattern.compile(OCTET + "(\\." + OCTET + "){3}/(3[0-2]|[12]?\\d)");

  private final List<IpAddressMatcher> allowed;

  SourceNetworkFilter(List<String> ipv4Cidrs) {
    for (String cidr : ipv4Cidrs) {
      if (!IPV4_CIDR.matcher(cidr).matches()) {
        // 값은 싣지 않는다. 부팅 실패 메시지는 로그와 CI 출력에 남는다.
        throw new IllegalArgumentException("허용 대역(allowed-cidrs)에 IPv4 CIDR(a.b.c.d/n)이 아닌 값이 있다");
      }
    }
    this.allowed = ipv4Cidrs.stream().map(IpAddressMatcher::new).toList();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!isAllowed(request.getRemoteAddr())) {
      response.setStatus(HttpStatus.FORBIDDEN.value());
      return;
    }
    chain.doFilter(request, response);
  }

  private boolean isAllowed(String remoteAddress) {
    try {
      return allowed.stream().anyMatch(matcher -> matcher.matches(remoteAddress));
    } catch (RuntimeException e) {
      // 대조 중 예외는 거부로 닫는다(403). 남기지 않는 이유는 머리 주석과 같다.
      return false;
    }
  }
}
