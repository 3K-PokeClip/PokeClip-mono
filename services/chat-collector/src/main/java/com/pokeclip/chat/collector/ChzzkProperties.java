package com.pokeclip.chat.collector;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * <p><b>enabled 기본값이 false다.</b> 켜져 있으면 CI·테스트·남의 로컬이 뜰 때마다
 * 치지직에 붙으려 하고, 연결 상한 3개(Access Token 기준)를 말없이 먹는다.
 * 그러면 정작 실측할 때 막히는데 원인이 어디에도 안 보인다.
 * 실측은 프로파일 local에서만 켠다.
 *
 * <p><b>accessToken이 @NotBlank를 통과해도 만료됐을 수 있다.</b> 그때는 부팅이
 * 성공하고 연결만 실패한다 — 그래서 실패 사유를 로그와 health 양쪽에 남긴다.
 */
@ConfigurationProperties(prefix = "pokeclip.chzzk")
@Validated
public record ChzzkProperties(
        boolean enabled,
        String accessToken,
        @NotBlank String baseUrl,
        Duration establishTimeout
) {

    /**
     * 토큰 검증을 <b>켜져 있을 때만</b> 건다.
     *
     * <p>{@code @NotBlank}를 그냥 붙이면 {@code enabled=false}에서도 걸려
     * <b>기본 설정으로는 서버가 아예 못 뜬다.</b> 기본값을 false로 둔 이유가
     * "CI·남의 로컬이 뜰 때마다 치지직에 붙는 것"을 막으려는 것인데, 그러면
     * 그들이 부팅조차 못 한다 — 실제로 그렇게 됐다.
     *
     * <p><b>{@code services/CLAUDE.md}의 "{@code ${VAR:}} + 검증" 규칙은 그대로 지켜진다.</b>
     * 그 규칙의 목적은 "서버는 뜨고 그 기능만 조용히 실패하는 것"을 막는 것이고,
     * <b>켜놓고 토큰이 비면 여기서 여전히 부팅이 죽는다.</b> 꺼져 있을 때는
     * 실패할 기능 자체가 없으므로 규칙이 겨누는 상황이 아니다.
     *
     * <p>메시지에 값을 넣지 않는다 — 검증 실패 메시지는 부팅 로그에 그대로 찍힌다.
     */
    @AssertTrue(message = "pokeclip.chzzk.enabled=true인데 access-token이 비어 있다")
    public boolean isAccessTokenPresentWhenEnabled() {
        return !enabled || (accessToken != null && !accessToken.isBlank());
    }
}
