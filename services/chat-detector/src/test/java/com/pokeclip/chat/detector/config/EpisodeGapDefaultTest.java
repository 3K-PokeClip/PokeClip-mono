package com.pokeclip.chat.detector.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code application.yml}의 판별 기본값을 <b>환경변수 없이</b> 풀어 잰다(POK-260).
 *
 * <p>스프링 컨텍스트를 띄우지 않는 이유: 띄우면 OS 환경변수가 yml보다 앞서, 로컬처럼
 * {@code DETECTION_EPISODE_GAP=20s}를 주는 곳에서는 yml 기본값이 10초로 되돌아가도 초록이다
 * (봇 리뷰 1판, codex P2). 여기서는 yml 한 파일만 속성 원천으로 두고 {@code ${X:기본값}}을 푼다.
 *
 * <p>레코드로 묶는 것 자체가 부팅 검증(되돌아보기 폭 · 앞당김)을 새 기본값으로 통과했다는 뜻이다.
 */
class EpisodeGapDefaultTest {

    private static DetectionProperties yml_기본값() throws IOException {
        List<PropertySource<?>> yml = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        return new Binder(ConfigurationPropertySources.from(yml), new PropertySourcesPlaceholdersResolver(yml))
                .bind("pokeclip.detection", DetectionProperties.class)
                .get();
    }

    /** 10초였을 때 채팅이 잠깐 끊겼다 다시 터지면 한 장면이 카드 두 장으로 쪼개졌다. */
    @Test
    void 튄_창을_20초_간격으로_묶는다() throws IOException {
        assertThat(yml_기본값().episodeGap()).isEqualTo(Duration.ofSeconds(20));
    }

    /**
     * 되돌린 사건을 다음 바퀴가 통째로 다시 읽으려면 되돌아보기 폭이 「상한 + 간격 + 창」(115초)보다
     * 길어야 하고, 그 위로 발행 대기·호출 시간만큼 여유가 더 있어야 한다. 2분이면 여유가 5초 남짓이라
     * 발행이 줄에 밀리면 사건 앞부분이 목록에서 빠진다(봇 리뷰 1판, codex P2).
     */
    @Test
    void 되돌아보기_폭은_사건_전체보다_30초_이상_넉넉하다() throws IOException {
        DetectionProperties props = yml_기본값();
        Duration 사건_전체 = props.episodeMaxSpan().plus(props.episodeGap()).plusMillis(props.publishWindowMs());
        assertThat(props.collectLookback()).isGreaterThanOrEqualTo(사건_전체.plusSeconds(30));
    }
}
