package com.pokeclip.auth.profile;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 프로필 사진 설정. <b>bucket이 비면 사진 기능이 통째로 꺼진다</b>(chat-collector의 S3_BUCKET 관례) —
 * CI·팀원 로컬의 기본 상태이고, 1번의 창고 준비를 안 기다리고 개발할 수 있는 이유다.
 * 자격증명은 여기 없다 — SDK 표준 체인(환경변수·프로파일·역할)이 찾는다.
 *
 * <p><b>중간 상태를 컴팩트 생성자가 세운다.</b> 창고만 차고 표 서명키나 주소 앞부분이 비면
 * 사진을 올릴 수는 있는데 볼 수가 없다 — 그 상태로 뜨면 사진을 올리는 순간에야 오류가 나고
 * 원인이 설정이라는 것을 아무도 모른다. ChzzkProperties.App·YoutubeProperties.App이 앱 셋을
 * 한 덩어리로 세우는 것과 같은 방식이고, 같은 이유로 {@code @NotBlank}가 아니라 생성자다 —
 * 필드별 제약이면 원인이 갈래로 흩어진다.
 *
 * <p><b>PhotoToken이 이 검증에 기대고 있다.</b> 서명키가 빈 문자열이면 {@code SecretKeySpec}이
 * {@code IllegalArgumentException}을 던지는데 그것은 {@code GeneralSecurityException}이 아니라
 * {@code sign}의 catch를 빠져나간다 — 여기서 안 막으면 그 예외가 사진 경로의 500이 된다.
 */
@ConfigurationProperties(prefix = "pokeclip.profile-photo")
public record PhotoProperties(
        String bucket,
        String region,
        /** 비면 진짜 AWS. LocalStack·MinIO 등 호환 스토리지 주소(scheme+host+port). */
        String endpoint,
        boolean forcePathStyle,
        /** 사진 표 서명키. 로그인 토큰(JWT_SECRET)과 <b>다른 값이어야 한다</b>. */
        String tokenSecret,
        /** 사진 주소의 앞부분. 화면과 서버가 다른 주소에 있어 절대 주소여야 한다. */
        String baseUrl
) {

    public PhotoProperties {
        if (present(bucket)) {
            require(tokenSecret, "PROFILE_PHOTO_TOKEN_SECRET");
            require(baseUrl, "PROFILE_PHOTO_BASE_URL");
        }
    }

    public boolean enabled() {
        return present(bucket);
    }

    public boolean hasEndpoint() {
        return present(endpoint);
    }

    /** 값은 메시지에 넣지 않는다 — 바인딩 실패 리포트는 로그·CI 출력에 그대로 남는다. */
    private static void require(String value, String envName) {
        if (!present(value)) {
            throw new IllegalStateException(
                    "%s가 비었다 — 창고 이름이 있으면 이 값도 있어야 한다. 사진을 올릴 수는 있는데 볼 수가 없는 상태가 된다"
                            .formatted(envName));
        }
    }

    /**
     * 스프링이 값을 못 찾으면 리터럴 {@code ${VAR}}을 그대로 바인딩하고 서버는 그냥 뜬다
     * (POK-160 실측). 그 글자를 값으로 받아들이면 켜진 것처럼 굴다가 창고 호출에서만 실패한다.
     */
    private static boolean present(String value) {
        return value != null && !value.isBlank() && !value.startsWith("${");
    }
}
