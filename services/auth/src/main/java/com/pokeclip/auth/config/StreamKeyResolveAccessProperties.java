package com.pokeclip.auth.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * resolve 전용 출입증의 설정(1번 T 설계 2-7). Media 는 이 토큰을 전용 헤더에 실어 허용 대역 안에서만 부른다.
 *
 * <p>부팅 검사는 여기 두지 않는다. 이 레코드는 모든 프로필에서 만들어지므로({@code
 * ConfigurationPropertiesScan}), 여기 두면 웹이 없는 이행 실행기도 그 검사를 탄다. 검사는 웹 전용인
 * {@link StreamKeyResolveSecurityConfig}가 한다.
 *
 * @param token 전용 토큰. 비어 있으면 창구가 닫힌다(401)
 * @param allowedCidrs 허용 출발지 IPv4 CIDR 목록. 설정이 없으면 빈 목록이다
 */
@ConfigurationProperties(prefix = "pokeclip.internal-api.stream-key-resolve")
public record StreamKeyResolveAccessProperties(String token, List<String> allowedCidrs) {

  public StreamKeyResolveAccessProperties {
    allowedCidrs = allowedCidrs == null ? List.of() : List.copyOf(allowedCidrs);
  }

  /** 전용 토큰이 있어 창구가 열리는지. */
  boolean isEnabled() {
    return token != null && !token.isBlank();
  }
}
