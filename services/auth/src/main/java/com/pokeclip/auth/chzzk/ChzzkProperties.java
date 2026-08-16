package com.pokeclip.auth.chzzk;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@ConfigurationProperties(prefix = "pokeclip.chzzk")
@Validated
public record ChzzkProperties(
        @Valid @NotNull App app,
        @NotBlank String authorizeUri,
        @NotBlank String apiBaseUri,
        @NotNull Duration stateTtl,
        @NotNull Duration refreshAhead,
        @NotNull Duration resolveMinRemaining,
        @Valid @NotNull Refresh refresh) {

    /**
     * 치지직 개발자 센터에 등록한 앱 하나의 값 셋. 한 덩어리로 검증한다 — 하나만 빠져도
     * 연동이 통째로 안 되므로 원인을 세 갈래로 흩어 놓지 않는다.
     *
     * <p>{@code @NotBlank}가 아니라 컴팩트 생성자인 이유는 <b>셋을 한 메시지로 모으려고</b>다 — 필드별
     * 제약이면 원인이 세 갈래로 흩어진다. ({@code @NotBlank}는 빈 값일 때만 실패해 리포트에 값이 안 실리므로
     * 유출 때문은 아니다. 여기서도 값은 메시지에 넣지 않는다.)
     */
    public record App(String clientId, String clientSecret, String redirectUri) {
        public App {
            if (isBlank(clientId) || isBlank(clientSecret) || isBlank(redirectUri)) {
                throw new IllegalStateException(
                        "치지직 앱 설정(CHZZK_CLIENT_ID·CHZZK_CLIENT_SECRET·CHZZK_REDIRECT_URI)이 비었다. "
                                + "셋은 한 앱의 것이라 하나만 빠져도 연동이 통째로 안 된다");
            }
        }

        private static boolean isBlank(String s) {
            return s == null || s.isBlank();
        }
    }

    public record Refresh(boolean enabled, @NotNull Duration interval) {
    }
}
