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
 * 큐 클라이언트와 상태 빈. <b>이 둘만 둔다</b> — 편지 경로의 나머지 부품은
 * {@link LetterPathConfiguration}이 만든다.
 *
 * <p><b>한 파일에 몰지 않는 이유가 실측으로 생겼다</b>: 검사가 이 설정만 떼어 띄우는데
 * ({@code SqsIntakeRunnerTest}가 {@code ApplicationContextRunner}로 큐 클라이언트의
 * 켜짐/꺼짐을 잰다), 중첩 {@code @Configuration}은 그 컨텍스트에도 같이 등록된다 —
 * 등록부·설정 빈이 없는 그곳에서 부팅이 깨져 <b>그 검사가 빨간불이 됐다.</b>
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
     * <p>🔴 <b>이 빈이 {@code Optional<SqsClient>}를 돌려주게 바꾸지 마라.</b> 그러면 컨테이너에
     * {@code SqsClient} 타입 빈이 없고, 받는 쪽의 {@code Optional<SqsClient>} 주입 지점은
     * {@code SqsClient}를 optional로 찾으므로 <b>이 빈을 영영 못 만난다</b> — 켜도 폴링이 시작되지
     * 않는다({@code OptionalBeanShapeProbeTest}가 실물 컨텍스트로 잰다).
     * <b>받는 쪽 파라미터가 {@code Optional}인 것 자체는 문제가 아니다</b>(2026-08-19 실측) —
     * 위험한 것은 빈의 타입이다. 받는 쪽은 {@code ObjectProvider}를 쓴다: 빈손이면
     * {@code SqsIntakeRunner} 생성자가 거부해 부팅이 죽으므로 조용히 안 도는 길이 없다.
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
