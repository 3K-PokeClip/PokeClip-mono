package com.pokeclip.auth.soop;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * {@code pokeclip.soop.*}. 🔴 Java 기본값을 두지 않는다: 값은 전부 {@code application.yml}에 있다(계획 검증 C5).
 * 스케줄러의 {@code @Scheduled} 자리표시는 이 record가 아니라 Environment에서 풀리므로, 여기 기본값을 두면
 * 시험은 초록인데 운영에서만 「Could not resolve placeholder」로 부팅이 죽는 길이 열린다.
 */
@ConfigurationProperties(prefix = "pokeclip.soop")
@Validated
public record SoopProperties(
        @Valid @NotNull App app,
        @NotBlank String authorizeUri,
        @NotBlank String apiBaseUri,
        @NotNull Duration stateTtl,
        @NotNull Duration refreshAhead,
        @NotNull Duration resolveMinRemaining,
        @Valid @NotNull Check check) {

    /**
     * SOOP 개발자 콘솔에 등록한 앱 하나의 값 셋. 치지직 {@code App}과 같은 이유로 컴팩트 생성자에서 한 메시지로 모은다
     * (필드별 제약이면 원인이 세 갈래로 흩어진다). 값은 메시지에 넣지 않는다.
     */
    public record App(String clientId, String clientSecret, String redirectUri) {
        public App {
            if (isBlank(clientId) || isBlank(clientSecret) || isBlank(redirectUri)) {
                throw new IllegalStateException(
                        "SOOP 앱 설정(SOOP_CLIENT_ID·SOOP_CLIENT_SECRET·SOOP_REDIRECT_URI)이 비었다. "
                                + "셋은 한 앱의 것이라 하나만 빠져도 연동이 통째로 안 된다");
            }
        }

        private static boolean isBlank(String s) {
            return s == null || s.isBlank();
        }
    }

    /**
     * 갱신 점검. {@code rejectBrake}는 한 틱에서 갱신 거절이 연달아 이만큼 나면 그 틱을 멈추는 수다(SOOP은 앱 설정
     * 오류의 오류 코드를 몰라 치지직의 INVALID_CLIENT 예외 대신 이것으로 전 회원 BROKEN을 막는다).
     * {@code batchLimit}은 한 틱이 보는 후보 상한이다: 스프링 기본 스케줄러는 스레드 하나라 다른 틱(치지직·유튜브·청소)과 나눈다.
     */
    public record Check(boolean enabled, @NotNull Duration interval, @NotNull Duration staleness,
                        @NotNull Integer rejectBrake, @NotNull @Positive Integer batchLimit) {
    }
}
