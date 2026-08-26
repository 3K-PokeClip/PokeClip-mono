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
        assertThatThrownBy(() -> of("bucket", "secret", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PROFILE_PHOTO_BASE_URL");
    }

    @Test
    void 셋이_다_차면_켜진다() {
        assertThat(of("bucket", "secret", "http://localhost:8082").enabled()).isTrue();
    }

    /**
     * 스프링이 값을 못 찾으면 리터럴 "${VAR}"을 그대로 바인딩해 <b>서버가 그냥 뜬다</b>
     * (POK-160 실측 — 기본값을 지워도 부팅이 안 죽는다). 그 글자를 값으로 받아들이면
     * 사진 기능이 켜진 것처럼 굴다가 창고 호출에서만 실패한다.
     */
    @Test
    void 치환되지_않은_리터럴은_값으로_안_본다() {
        assertThat(of("${PROFILE_PHOTO_S3_BUCKET}", "s", "u").enabled()).isFalse();
    }

    /**
     * 창고 주소는 있을 수도 없을 수도 있다 — 비면 진짜 AWS다. 리터럴도 「없음」으로 본다:
     * 치환이 안 된 글자를 주소로 쓰면 {@code URI.create}가 던져 사진 경로만 통째로 죽는다.
     */
    @Test
    void 창고_주소는_비어_있거나_리터럴이면_없는_것이다() {
        assertThat(new PhotoProperties("b", "ap-northeast-2", "", false, "s", "u").hasEndpoint()).isFalse();
        assertThat(new PhotoProperties("b", "ap-northeast-2", "${PROFILE_PHOTO_S3_ENDPOINT}", false, "s", "u")
                .hasEndpoint()).isFalse();
        assertThat(new PhotoProperties("b", "ap-northeast-2", "http://localhost:14566", false, "s", "u")
                .hasEndpoint()).isTrue();
    }
}
