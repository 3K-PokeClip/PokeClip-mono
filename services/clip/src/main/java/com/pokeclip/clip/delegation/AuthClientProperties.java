package com.pokeclip.clip.delegation;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * auth의 내부 API 주소. 토큰은 여기 없다 — {@code pokeclip.internal-api.token}을 그대로 쓴다
 * (서버 간 토큰은 하나다, {@code InternalApiProperties}).
 *
 * <p><b>기본값이 빈 문자열이다</b>({@code ${AUTH_BASE_URL:}}). 기본값을 아예 안 주면 리터럴
 * {@code "${AUTH_BASE_URL}"}이 그대로 바인딩돼 <b>서버는 뜨고 헬스체크도 통과하는데 세그먼트
 * 미리보기만 전부 503</b>이 된다({@code services/CLAUDE.md}의 규칙).
 *
 * <p><b>검증을 {@code @NotBlank}로 걸지 않고 {@link #validate()}로 둔 이유</b>: 이 record는
 * {@code @ConfigurationPropertiesScan}이 모든 컨텍스트에 올린다. 애노테이션으로 걸면
 * 바인딩 실패 리포트가 <b>거부된 값을 평문으로 찍고</b>(JwtProperties와 같은 이유),
 * 어느 환경변수를 채워야 하는지도 안 알려준다. 대신 {@link DelegationResolveClient}
 * 생성자가 이것을 부르므로, <b>이 클라이언트를 만드는 부팅은 반드시 죽는다</b> —
 * clip에서는 그 클라이언트가 조건 없이 올라가므로 곧 모든 부팅이다.
 */
@ConfigurationProperties(prefix = "pokeclip.auth-client")
public record AuthClientProperties(String baseUrl) {

    /** @throws IllegalStateException 주소가 비어 있으면 */
    public void validate() {
        if (baseUrl == null || baseUrl.isBlank()) {
            // 값은 메시지에 넣지 않는다 — 부팅 실패 메시지는 로그에 그대로 찍힌다.
            // 지금은 주소뿐이지만 이 record에 칸이 늘어도 같은 규칙이 서 있어야 한다.
            throw new IllegalStateException(
                    "pokeclip.auth-client.base-url이(가) 비어 있다. AUTH_BASE_URL 환경변수를 준다.");
        }
    }
}
