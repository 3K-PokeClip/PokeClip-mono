package com.pokeclip.auth.youtube;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@ConfigurationProperties(prefix = "pokeclip.youtube")
@Validated
public record YoutubeProperties(
        @Valid @NotNull App app,
        @NotBlank String authorizeUri,
        @NotBlank String tokenUri,
        @NotBlank String revokeUri,
        @NotBlank String apiBaseUri,
        @NotNull Duration stateTtl,
        @NotNull Duration resolveMinRemaining,
        @Valid @NotNull Check check) {

    /**
     * GCP 콘솔에 등록한 앱 하나의 값 셋. 한 덩어리로 검증한다 — 하나만 빠져도
     * 연동이 통째로 안 되므로 원인을 세 갈래로 흩어 놓지 않는다.
     *
     * <p>로그인용 구글 앱(`pokeclip.google.*`)과 <b>다른 앱</b>이다 — 업로드 권한은 프로젝트 단위
     * 심사 대상이라 폭발 반경을 나눴다(PRD 결정).
     *
     * <p>{@code @NotBlank}가 아니라 컴팩트 생성자인 이유는 <b>셋을 한 메시지로 모으려고</b>다.
     * 값은 메시지에 넣지 않는다.
     */
    public record App(String clientId, String clientSecret, String redirectUri) {
        public App {
            if (isBlank(clientId) || isBlank(clientSecret) || isBlank(redirectUri)) {
                throw new IllegalStateException(
                        "유튜브 앱 설정(YOUTUBE_CLIENT_ID·YOUTUBE_CLIENT_SECRET·YOUTUBE_REDIRECT_URI)이 비었다. "
                                + "셋은 한 앱의 것이라 하나만 빠져도 연동이 통째로 안 된다");
            }
        }

        private static boolean isBlank(String s) {
            return s == null || s.isBlank();
        }
    }

    /**
     * 철회 점검. 치지직의 「만료 임박 선갱신」과 축이 다르다 — 구글 access는 1시간이라
     * 선갱신이 성립하지 않는다. 대신 「오래 확인 안 한 연동」을 골라 갱신을 시도해
     * 사용자가 구글 쪽에서 권한을 끊은 것을 드러낸다.
     */
    public record Check(boolean enabled, @NotNull Duration interval, @NotNull Duration staleness) {
    }
}
