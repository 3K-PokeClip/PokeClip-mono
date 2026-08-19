package com.pokeclip.chat.collector.broadcast.intake;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;

import java.net.URI;

/**
 * 큐 클라이언트와 상태 빈. <b>러너는 아직 여기서 안 만든다</b> — 아래 {@code sqsClient} 주석 참고.
 */
@Configuration
// 운영 컨텍스트는 @ConfigurationPropertiesScan이 이미 잡지만, 검사가 이 설정만 떼어
// 띄울 때는 그것이 없다. 둘 다 같은 빈 이름이라 겹쳐도 문제없다.
@EnableConfigurationProperties(IntakeProperties.class)
public class IntakeConfiguration {

    private static final Logger log = LoggerFactory.getLogger(IntakeConfiguration.class);

    /**
     * 꺼져 있으면 클라이언트를 아예 등록하지 않는다.
     *
     * <p>🔴 <b>이 빈을 받는 쪽은 {@code ObjectProvider<SqsClient>}를 쓴다.
     * {@code Optional<SqsClient>}는 안 된다</b> — Spring이 {@code Optional<T>} 주입 지점을
     * 「T 타입 빈을 optional로 찾기」로 가로채, 빈이 실제로 있어도 늘 빈손이 온다.
     * 그러면 켜도 폴링이 영원히 시작되지 않는데 테스트는 전부 초록이다(clip 실측 2026-08-18).
     * <b>이 함정은 러너를 빈으로 올리는 태스크 10에서 실제로 열린다</b> —
     * {@code SqsIntakeRunner} 생성자가 null을 거부하므로 밟으면 부팅이 죽어서 드러난다.
     *
     * <p><b>HTTP 클라이언트를 명시한다.</b> {@code S3Clients}와 같은 이유이자 같은 구현이다 —
     * 시한을 기본값에 맡기지 않는다. 롱폴링(최대 20초)보다 소켓 시한이 짧으면 매 회차가
     * 끊긴다. <b>지금 클래스패스의 동기 HTTP 구현은 Apache 5 하나뿐이라, 명시를 빼도
     * 죽지는 않는다</b>(2026-08-19 실측: {@code sqs}를 더해도 runtimeClasspath의 동기 구현은
     * {@code apache5-client} 하나 — {@code netty-nio-client}는 비동기 SPI라 후보가 아니다).
     * 그래도 명시하는 이유는 시한 때문이고, {@code url-connection-client}를 나중에 누가
     * 더하면 그때 「Multiple HTTP implementations」로 죽는 것을 막는 효과가 덤이다.
     */
    @Bean
    @ConditionalOnProperty(prefix = "pokeclip.broadcast.intake", name = "enabled", havingValue = "true")
    public SqsClient sqsClient(IntakeProperties properties) {
        SqsClientBuilder builder = SqsClient.builder()
                .region(Region.of(properties.region()))
                .httpClient(Apache5HttpClient.builder()
                        .socketTimeout(properties.waitTime().plusSeconds(10))
                        .build());
        if (properties.hasEndpoint()) {
            builder.endpointOverride(URI.create(properties.endpoint()));
        }
        // 큐 주소는 안 찍는다 — 계정 번호가 들어 있고 로그로 나갈 이유가 없다.
        log.info("broadcast.intake.enabled region={} endpointOverride={}",
                properties.region(), properties.hasEndpoint());
        return builder.build();
    }

    /**
     * 켜짐/꺼짐과 무관하게 항상 등록한다 — health가 「빈이 없음」과 「꺼져 있음」을
     * 구분하려면 꺼졌다는 사실 자체를 알아야 한다.
     */
    @Bean
    public IntakeStatus intakeStatus(IntakeProperties properties) {
        return new IntakeStatus(properties.enabled());
    }
}
