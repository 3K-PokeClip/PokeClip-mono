package com.pokeclip.auth.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.regex.Pattern;

/**
 * 구글 로그인 교환 요청. codeVerifier는 동의 URL에 challenge로 실었던 PKCE 원문이다(POK-483).
 *
 * <p><b>codeVerifier의 @NotBlank가 "빠지면 거부"의 유일한 스위치다.</b> 과도기 허용으로
 * 바꾸려면 이것만 뗀다 — 아래 형식 검사는 null을 통과시키고, GoogleTokenClient는 null이면
 * code_verifier를 싣지 않는다. web과 auth가 같은 배포로 나가므로 지금은 거부다.
 *
 * <p><b>형식 검사를 @Pattern·@Size로 하지 않는다.</b> 바인딩 실패 리포트가 거부된 값을
 * 평문으로 찍는다(SecretLeakTest가 못박는다). 대신 생성자가 값 없이 거부하고, 역직렬화
 * 실패라 깨진 JSON과 같은 400 경로를 탄다.
 */
public record GoogleLoginRequest(@NotBlank String code, @NotBlank String codeVerifier) {

    /** RFC 7636 4.1 — unreserved 문자([A-Z] / [a-z] / [0-9] / "-" / "." / "_" / "~")로 43–128자. */
    private static final Pattern CODE_VERIFIER = Pattern.compile("[A-Za-z0-9._~-]{43,128}");

    public GoogleLoginRequest {
        if (codeVerifier != null && !CODE_VERIFIER.matcher(codeVerifier).matches()) {
            throw new IllegalArgumentException("codeVerifier가 RFC 7636 형식이 아니다. 값은 남기지 않는다.");
        }
    }
}
