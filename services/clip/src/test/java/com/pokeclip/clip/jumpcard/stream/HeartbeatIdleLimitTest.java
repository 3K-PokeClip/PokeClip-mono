package com.pokeclip.clip.jumpcard.stream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

import java.time.Duration;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 운영 하트비트가 앞단이 조용한 연결을 끊는 시한보다 짧은지 잰다(POK-318).
 * {@link JumpCardStreamHeartbeatTest}는 주기를 1초로 덮어써 「하트비트가 온다」만 잰다. 운영 값이
 * 앞단 시한보다 길어져도 그 시험은 초록이고, 배포 뒤에야 SSE가 끊기는 것으로 드러난다.
 */
class HeartbeatIdleLimitTest {

    /**
     * 앞단 시한 가운데 가장 짧은 것: CloudFront 오리진 응답 시한 기본값 30초
     * (AWS 문서 「Amazon CloudFront Developer Guide」의 Response timeout(origin)). 공개 ALB 유휴 시한 기본값
     * 60초(「Elastic Load Balancing User Guide」의 Connection idle timeout)보다 짧다.
     * 1번 엣지 설정에서 이 값을 줄이면 이 상수도 같이 줄인다.
     */
    private static final Duration SHORTEST_EDGE_IDLE_LIMIT = Duration.ofSeconds(30);

    @Test
    void 운영_하트비트가_앞단_유휴_시한보다_짧다() {
        String raw = productionYml().getProperty("pokeclip.jump-card.stream.heartbeat");

        // 운영 값이 환경변수 자리(${...})로 바뀌면 이 시험은 실제 운영 값을 못 잰다. 그때는 이 시험부터 고친다.
        // 남는 길 하나: 스프링은 같은 키의 환경변수(POKECLIP_JUMPCARD_STREAM_HEARTBEAT 꼴)로도 이 값을 덮는다.
        // 운영 런타임 변수에 그 이름을 넣지 않는 것이 이 시험의 전제다.
        assertThat(raw).as("하트비트가 yml에 값 그대로 있어야 이 시험이 운영 값을 잰다").doesNotContain("${");
        assertThat(Duration.parse(raw)).isLessThan(SHORTEST_EDGE_IDLE_LIMIT);
    }

    private static Properties productionYml() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource("src/main/resources/application.yml"));
        Properties props = yaml.getObject();
        assertThat(props).isNotNull();
        return props;
    }
}
