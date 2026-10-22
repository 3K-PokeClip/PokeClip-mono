package com.pokeclip.clip.playback;

import com.pokeclip.clip.support.TestPlaybackKeys;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「prod에서 서명 설정이 통째로 비면 clip이 안 뜬다」를 실제 바인딩 · 부팅으로 잰다(POK-321, 엣지 T9b 대체).
 * 단위 시험은 생성자를 직접 부르므로 yml 키 이름 · 기본값이 틀려도 초록이다. 여기서는 설정 키로 넣는다.
 */
class PlaybackPropertiesContextTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(BoundProperties.class);

    @Test
    void 필수인데_셋_다_비면_컨텍스트가_뜨지_않고_빠진_칸_이름을_말한다() {
        runner.withPropertyValues(
                        "pokeclip.playback.required=true",
                        "pokeclip.playback.key-pair-id=",
                        "pokeclip.playback.private-key-pem=",
                        "pokeclip.playback.resource-base-url=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("CLOUDFRONT_KEY_PAIR_ID")
                            .hasMessageContaining("CLOUDFRONT_PRIVATE_KEY_PEM")
                            .hasMessageContaining("MEDIA_BASE_URL");
                });
    }

    /** 기본값(로컬 · CI · dev)은 지금처럼 꺼짐으로 뜬다. 이 갈래가 없으면 위 시험은 「늘 실패」로도 초록이다. */
    @Test
    void 필수_표시가_없으면_셋_다_비어도_꺼짐으로_뜬다() {
        runner.withPropertyValues(
                        "pokeclip.playback.key-pair-id=",
                        "pokeclip.playback.private-key-pem=",
                        "pokeclip.playback.resource-base-url=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(PlaybackProperties.class).enabled()).isFalse();
                });
    }

    /** 서명은 켜졌는데 쿠키 도메인만 비면 prod 재생이 전부 CDN 403이다(POK-317). 키 이름으로 넣어 바인딩까지 잰다. */
    @Test
    void 필수이고_켜졌는데_쿠키_도메인이_비면_컨텍스트가_뜨지_않고_변수_이름을_말한다() {
        runner.withPropertyValues(켜진_셋("pokeclip.playback.required=true", "pokeclip.playback.cookie-domain="))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("PLAYBACK_COOKIE_DOMAIN");
                });
    }

    /** 위 시험의 대조. 도메인이 있으면 뜬다 — 없으면 위 시험은 「켜진 셋이 늘 실패」로도 초록이다. */
    @Test
    void 필수이고_쿠키_도메인이_있으면_켜진_채로_뜬다() {
        runner.withPropertyValues(켜진_셋("pokeclip.playback.required=true", "pokeclip.playback.cookie-domain=.pokeclip.com"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(PlaybackProperties.class).enabled()).isTrue();
                });
    }

    /** 로컬 · dev는 clip과 영상이 같은 호스트라 빈 도메인이 정상이다. 필수 표시가 없으면 지금처럼 뜬다. */
    @Test
    void 필수_표시가_없으면_쿠키_도메인이_비어도_켜진_채로_뜬다() {
        runner.withPropertyValues(켜진_셋("pokeclip.playback.cookie-domain="))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(PlaybackProperties.class).enabled()).isTrue();
                });
    }

    private static String[] 켜진_셋(String... more) {
        // PEM은 여러 줄이라 리터럴 \n으로 한 줄에 싣는다(운영 환경변수와 같은 모양)
        String pem = TestPlaybackKeys.privateKeyPem().replace("\n", "\\n");
        String[] base = {
                "pokeclip.playback.key-pair-id=K1",
                "pokeclip.playback.private-key-pem=" + pem,
                "pokeclip.playback.resource-base-url=https://media.test"};
        String[] all = java.util.Arrays.copyOf(base, base.length + more.length);
        System.arraycopy(more, 0, all, base.length, more.length);
        return all;
    }

    /**
     * 위 두 시험은 키를 직접 넣어 yml을 안 거친다. prod가 넣는 값은 환경변수 {@code PLAYBACK_REQUIRED}이니
     * yml이 그것을 이 키로 잇는지, 기본이 false인지를 따로 잰다. 문자열로 훑지 않고 YAML로 풀어 비교한다
     * (주석 안 예시에 걸리지 않게).
     */
    @Test
    void yml이_PLAYBACK_REQUIRED를_이_키로_잇고_기본은_false다() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource("src/main/resources/application.yml"));
        Properties props = yaml.getObject();
        assertThat(props).isNotNull();
        assertThat(props.getProperty("pokeclip.playback.required")).isEqualTo("${PLAYBACK_REQUIRED:false}");
        assertThat(props.getProperty("pokeclip.playback.cookie-domain")).isEqualTo("${PLAYBACK_COOKIE_DOMAIN:}");
    }

    @EnableConfigurationProperties(PlaybackProperties.class)
    static class BoundProperties {
    }
}
