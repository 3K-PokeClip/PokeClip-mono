package com.pokeclip.auth.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.regex.Pattern;

/**
 * 구글 로그인 교환 요청. codeVerifier는 동의 URL에 challenge로 실었던 PKCE 원문이다(POK-483).
 *
 * <p><b>과도기라 codeVerifier는 선택값이다(POK-483 1단계).</b> auth는 develop 머지 때 web과
 * 따로 배포되므로, PKCE 이전의 web이 보낸 code만 있는 요청도 받아야 로그인이 끊기지 않는다.
 * 없으면(null) GoogleTokenClient가 code_verifier를 싣지 않고 예전처럼 교환한다. 빈 값·공백은
 * 누락이 아니라 형식 위반이다.
 *
 * <p>2단계(web 배포 뒤 · 운영 개통 전): 필수화 = 이 필드에 @NotBlank를 다시 거는 것 하나다.
 *
 * <p><b>형식 검사를 @Pattern·@Size로 하지 않는다.</b> 바인딩 실패 리포트가 거부된 값을
 * 평문으로 찍는다(SecretLeakTest가 못박는다). 대신 생성자가 값 없이 거부하고, 역직렬화
 * 실패라 깨진 JSON과 같은 400 경로를 탄다.
 */
public record GoogleLoginRequest(@NotBlank String code, String codeVerifier) {

    /** RFC 7636 4.1 — unreserved 문자([A-Z] / [a-z] / [0-9] / "-" / "." / "_" / "~")로 43–128자. */
    private static final Pattern CODE_VERIFIER = Pattern.compile("[A-Za-z0-9._~-]{43,128}");

    public GoogleLoginRequest {
        if (codeVerifier != null && !CODE_VERIFIER.matcher(codeVerifier).matches()) {
            throw new IllegalArgumentException("codeVerifier가 RFC 7636 형식이 아니다. 값은 남기지 않는다.");
        }
    }
}
