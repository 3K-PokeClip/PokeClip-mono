package com.pokeclip.chat.collector.link;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * auth의 내부 API를 부르는 데 필요한 둘 — 주소와 우리를 증명하는 토큰.
 *
 * <p><b>둘 다 기본값이 빈 문자열이다</b>({@code ${VAR:}}). 기본값을 아예 안 주면 리터럴
 * {@code "${INTERNAL_API_TOKEN}"}이 그대로 바인딩돼 <b>서버는 뜨고 헬스체크도 통과하는데
 * 토큰 조회만 전부 401</b>이 된다({@code services/CLAUDE.md}의 규칙).
 *
 * <p><b>검증을 {@code @NotBlank}로 걸지 않고 {@link #validate()}로 둔 이유</b>: 이 record는
 * {@code @ConfigurationPropertiesScan}이 모든 컨텍스트에 올린다. 애노테이션으로 걸면
 * <b>이 값을 쓰지도 않는 부팅까지</b> 전부 죽는다 — {@code ChzzkProperties}가 토큰 검증을
 * {@code enabled}일 때만 거는 것과 같은 이유다. 대신 {@link ChzzkLinkClient} 생성자가
 * 이것을 부르므로, <b>이 클라이언트를 실제로 만드는 부팅은 반드시 죽는다.</b>
 */
@ConfigurationProperties(prefix = "pokeclip.link")
public record LinkProperties(String authBaseUrl, String internalToken) {

    /** @throws IllegalStateException 둘 중 하나라도 비어 있으면 */
    public void validate() {
        require(authBaseUrl, "pokeclip.link.auth-base-url", "AUTH_BASE_URL");
        // 값은 메시지에 넣지 않는다 — 부팅 실패 메시지는 로그에 그대로 찍힌다.
        require(internalToken, "pokeclip.link.internal-token", "INTERNAL_API_TOKEN");
    }

    private static void require(String value, String property, String variable) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    property + "이(가) 비어 있다. " + variable + " 환경변수를 준다.");
        }
    }
}
