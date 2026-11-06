package com.pokeclip.auth.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.regex.Pattern;

/**
 * 구글 로그인 교환 요청. codeVerifier는 동의 URL에 challenge로 실었던 PKCE 원문이다(POK-483).
 *
 * <p><b>codeVerifier는 필수다 — web이 항상 보낸다.</b> 이 PR은 새 web이 배포된 뒤에
 * 머지한다(옛 web은 400). 빠지면 @NotBlank가 400으로 거부해 구글까지 가지 않는다.
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
