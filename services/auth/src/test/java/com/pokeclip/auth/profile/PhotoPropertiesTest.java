package com.pokeclip.auth.profile;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 「전부 있거나 전부 없거나」만 정상이다. 창고 이름은 차 있는데 표 서명키나 주소 앞부분이 비면
 * <b>사진을 올릴 수는 있는데 볼 수가 없다</b> — 꺼지지도 켜지지도 않은 채로 뜨고, 사진을 올리는
 * 순간에야 원인 모를 오류가 난다. 그때는 원인이 설정이라는 것을 아무도 모른다.
 *
 * <p>ChzzkProperties.App·YoutubeProperties.App이 앱 셋을 한 덩어리로 세우는 것과 같은 방식이다.
 *
 * <p><b>PhotoToken이 이 검증 위에 서 있다.</b> 서명키가 빈 문자열이면 {@code SecretKeySpec}이
 * {@code IllegalArgumentException}을 던지는데 그것은 {@code GeneralSecurityException}이 아니라
 * {@code sign}의 catch를 빠져나간다. 여기서 부팅을 세우지 않으면 그 예외가 사진 경로의 500이 된다.
 */
class PhotoPropertiesTest {

    /** 검사 대상이 아닌 자리에 쓰는 유효한 값. 짧은 키·상대 주소는 이제 부팅을 세운다. */
    private static final String OK_SECRET = "photo-token-secret-32-bytes-ok!!";
    private static final String OK_URL = "http://localhost:8082";

    private static PhotoProperties of(String bucket, String secret, String baseUrl) {
        return new PhotoProperties(bucket, "ap-northeast-2", "", false, secret, baseUrl);
    }

    @Test
    void 창고_이름이_비면_사진_기능이_꺼진다() {
        assertThat(of("", "", "").enabled()).isFalse();
        assertThat(of(null, null, null).enabled()).isFalse();
        assertThat(of("   ", "", "").enabled()).isFalse();   // 공백만도 꺼짐이다
    }

    @Test
    void 창고_이름이_차면_표_서명키가_없을_때_부팅에서_죽는다() {
        assertThatThrownBy(() -> of("bucket", "", "http://localhost:8082"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PROFILE_PHOTO_TOKEN_SECRET");
    }

    @Test
    void 창고_이름이_차면_주소_앞부분이_없을_때_부팅에서_죽는다() {
        assertThatThrownBy(() -> of("bucket", OK_SECRET, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PROFILE_PHOTO_BASE_URL");
    }

    @Test
    void 셋이_다_차면_켜진다() {
        assertThat(of("bucket", OK_SECRET, OK_URL).enabled()).isTrue();
    }

    /**
     * 스프링이 값을 못 찾으면 리터럴 "${VAR}"을 그대로 바인딩해 <b>서버가 그냥 뜬다</b>
     * (POK-160 실측 — 기본값을 지워도 부팅이 안 죽는다). 그 글자를 값으로 받아들이면
     * 사진 기능이 켜진 것처럼 굴다가 창고 호출에서만 실패한다.
     */
    @Test
    void 치환되지_않은_리터럴은_값으로_안_본다() {
        assertThat(of("${PROFILE_PHOTO_S3_BUCKET}", "s", "u").enabled()).isFalse();   // 꺼지면 나머지는 안 본다
    }

    /**
     * 창고 주소는 있을 수도 없을 수도 있다 — 비면 진짜 AWS다. 리터럴도 「없음」으로 본다:
     * 치환이 안 된 글자를 주소로 쓰면 {@code URI.create}가 던져 사진 경로만 통째로 죽는다.
     */
    @Test
    void 창고_주소는_비어_있거나_리터럴이면_없는_것이다() {
        assertThat(new PhotoProperties("b", "ap-northeast-2", "", false, OK_SECRET, OK_URL).hasEndpoint()).isFalse();
        assertThat(new PhotoProperties("b", "ap-northeast-2", "${PROFILE_PHOTO_S3_ENDPOINT}", false, OK_SECRET, OK_URL)
                .hasEndpoint()).isFalse();
        assertThat(new PhotoProperties("b", "ap-northeast-2", "http://localhost:14566", false, OK_SECRET, OK_URL)
                .hasEndpoint()).isTrue();
    }

    /**
     * 🔴 <b>비어 있지 않은 것만으로는 부족하다</b>(PR #133 codex P1, 실측: 길이 1도 통과했다).
     *
     * <p>사진 주소는 <b>서명과 그 재료를 함께 실어</b> 브라우저에 내보낸다. 주소 하나면 후보 키를
     * 오프라인에서 무한히 시험할 수 있고, 맞히면 <b>아무 회원 번호로나 표를 만든다</b> —
     * 로그인 토큰과 키를 가른 것이 그 순간 무의미해진다.
     */
    @Test
    void 짧은_표_서명키는_부팅에서_죽는다() {
        for (String weak : new String[]{"secret", "a", "1234", "31-bytes-is-one-short-of-ok!!!!"}) {
            assertThatThrownBy(() -> of("bucket", weak, OK_URL))
                    .as("길이 %d", weak.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("PROFILE_PHOTO_TOKEN_SECRET")
                    .hasMessageNotContaining(weak);   // 값이 부팅 실패 리포트에 실리면 안 된다
        }
        assertThat(of("bucket", OK_SECRET, OK_URL).enabled()).as("32바이트는 통과한다").isTrue();
    }

    /**
     * 🔴 <b>상대 주소는 조용히 실패한다</b>(PR #133 codex P2, 실측: {@code "not a url"}도 통과했다).
     *
     * <p>이 값이 <b>그대로 앞에 붙어</b> 나가므로 스킴이 없으면 브라우저가 <b>화면의 주소를
     * 기준으로 풀어</b> 엉뚱한 곳을 찾는다. 올리기는 성공하고 저장도 되는데 <b>그림만 안 보이고</b>
     * 서버 로그에도 흔적이 없다.
     */
    @Test
    void 절대_주소가_아니면_부팅에서_죽는다() {
        for (String bad : new String[]{"dev.pokeclip.com:8082", "/photos", "not a url", "ftp://x.example",
                "http://localhost:8082/",
                // 🔴 쿼리·조각이 있으면 뒤에 경로가 이어 붙어 주소가 깨진다 (PR #135 codex)
                "https://api.example?x=1", "https://api.example#x"}) {
            assertThatThrownBy(() -> of("bucket", OK_SECRET, bad))
                    .as("%s", bad)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("PROFILE_PHOTO_BASE_URL");
        }
        assertThat(of("bucket", OK_SECRET, "https://dev.pokeclip.com").enabled()).as("https 도 된다").isTrue();
        assertThat(of("bucket", OK_SECRET, OK_URL).enabled()).as("포트가 있어도 된다").isTrue();
        assertThat(of("bucket", OK_SECRET, "https://api.example/sub").enabled())
                .as("경로는 막지 않는다 — 프록시 뒤 서브패스 배포에서 필요하고 이어 붙여도 뜻이 유지된다")
                .isTrue();
    }
}
