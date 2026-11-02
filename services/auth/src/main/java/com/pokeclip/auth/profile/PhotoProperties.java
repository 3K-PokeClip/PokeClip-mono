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

    /**
     * 표 서명키의 최소 길이. HMAC-SHA256의 블록이 64바이트이고 그보다 짧은 키는 그만큼
     * 탐색 공간이 줄어든다. {@code JwtConfig}가 로그인 키에 쓰는 값과 같게 둔다 —
     * 두 키의 세기가 갈리면 약한 쪽이 전체의 세기가 된다.
     */
    private static final int MIN_SECRET_BYTES = 32;

    public PhotoProperties {
        if (present(bucket)) {
            require(tokenSecret, "PROFILE_PHOTO_TOKEN_SECRET");
            require(baseUrl, "PROFILE_PHOTO_BASE_URL");
            requireStrongSecret(tokenSecret);
            requireAbsoluteUrl(baseUrl);
        }
    }

    /**
     * 🔴 <b>비어 있지 않은 것만으로는 부족하다</b>(PR #133 codex P1, 실측: 길이 1도 통과했다).
     *
     * <p>사진 주소는 <b>서명과 그 재료를 함께 실어 브라우저에 내보낸다.</b> 주소 하나만 손에 넣으면
     * 후보 키를 오프라인에서 무한히 시험할 수 있고, 맞히면 <b>아무 회원 번호로나 표를 만들어</b>
     * 남의 사진을 연다 — 로그인 토큰과 키를 가른 것이 그 순간 무의미해진다.
     *
     * <p>{@code JwtConfig.jwtSecretKey}가 로그인 키에 하는 것과 같은 검사다. <b>값은 메시지에
     * 넣지 않는다</b> — 부팅 실패 리포트는 로그·CI 출력에 그대로 남는다.
     */
    private static void requireStrongSecret(String secret) {
        int bytes = secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (bytes < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "PROFILE_PHOTO_TOKEN_SECRET이 너무 짧다 — %d바이트 이상이어야 한다(지금 %d). 값은 로그에 남기지 않는다"
                            .formatted(MIN_SECRET_BYTES, bytes));
        }
    }

    /**
     * 🔴 <b>상대 주소는 조용히 실패한다</b>(PR #133 codex P2, 실측: {@code "not a url"}도 통과했다).
     *
     * <p>{@link PhotoUrls}가 이 값을 <b>그대로 앞에 붙여</b> 내보내므로, 스킴이 없으면 브라우저가
     * <b>화면의 주소를 기준으로 풀어</b> 엉뚱한 곳을 찾는다. 올리기는 성공하고 저장도 되는데
     * <b>그림만 안 보인다</b> — 서버 로그에도 아무 흔적이 없다.
     *
     * <p>끝의 슬래시도 막는다. 붙어 있으면 주소가 {@code …8082//api/…}가 되는데, 서버에 따라
     * 404가 되기도 하고 통과하기도 해서 <b>환경마다 갈린다.</b>
     */
    private static void requireAbsoluteUrl(String baseUrl) {
        java.net.URI uri;
        try {
            uri = java.net.URI.create(baseUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("PROFILE_PHOTO_BASE_URL이 주소 모양이 아니다. 값은 로그에 남기지 않는다");
        }
        boolean absolute = uri.isAbsolute()
                && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                && uri.getHost() != null;
        if (!absolute) {
            throw new IllegalStateException(
                    "PROFILE_PHOTO_BASE_URL이 절대 주소가 아니다 — http(s)://호스트[:포트] 모양이어야 한다. "
                            + "브라우저가 붙는 주소이므로 스킴이 없으면 화면 주소를 기준으로 풀려 사진만 조용히 안 보인다. "
                            + "값은 로그에 남기지 않는다");
        }
        if (baseUrl.endsWith("/")) {
            throw new IllegalStateException(
                    "PROFILE_PHOTO_BASE_URL은 끝에 슬래시를 두지 않는다 — 주소가 //api/…가 되어 환경마다 갈린다");
        }
        // 🔴 쿼리·조각이 붙어 있으면 그 뒤에 경로를 이어 붙이게 된다(PR #135 codex, 실측).
        // "…example?x=1" + "/api/profile-photos/7" = "…example?x=1/api/profile-photos/7" —
        // 브라우저는 호스트 뿌리를 부르고 그림은 영영 안 온다. 경로(/sub)는 막지 않는다:
        // 프록시 뒤 서브패스 배포에서 필요할 수 있고, 이어 붙여도 뜻이 유지된다.
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalStateException(
                    "PROFILE_PHOTO_BASE_URL에 쿼리나 조각을 두지 않는다 — 뒤에 경로가 이어 붙어 주소가 깨진다");
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
