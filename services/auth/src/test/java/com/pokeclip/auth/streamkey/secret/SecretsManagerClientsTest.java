package com.pokeclip.auth.streamkey.secret;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerServiceClientConfiguration;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 조립한 클라이언트에 시한과 재시도 모드가 실제로 박혔는지 SDK 설정을 되읽어 잰다({@code PhotoS3ClientsTest}와 같은
 * 방식). 빌더에서 한 줄을 지우면 여기가 빨간불이어야 한다. Apache5 연결·소켓 시한은 이 자리에서 되읽을 수 없어
 * {@link SecretsManagerDeadlineTest}가 동작으로 잰다.
 */
class SecretsManagerClientsTest {

    private static StreamKeySecretStoreProperties props(String endpoint) {
        return new StreamKeySecretStoreProperties(StreamKeySecretStoreProperties.Type.AWS, "p/",
                Set.of(StreamKeySecretStoreProperties.Type.AWS), endpoint, "ap-northeast-2");
    }

    /**
     * 재시도는 standard(시도 셋)다. SDK 기본은 legacy(시도 넷)이고 환경 변수·프로필이 바꿀 수 있다. 코드에서
     * 못박지 않으면 환경마다 시도 수가 달라 시한 시험이 재는 것과 운영이 갈린다(1번 10-05 정정 1).
     */
    @Test
    void 웹_경로_클라이언트는_standard_재시도와_1초_시도_3초_안전망이다() {
        try (SecretsManagerClient client = SecretsManagerClients.forRequests(props(null))) {
            SecretsManagerServiceClientConfiguration c = client.serviceClientConfiguration();
            assertThat(c.region().id()).isEqualTo("ap-northeast-2");
            assertThat(c.endpointOverride()).isEmpty();
            assertThat(c.overrideConfiguration().apiCallAttemptTimeout()).contains(Duration.ofSeconds(1));
            assertThat(c.overrideConfiguration().apiCallTimeout()).contains(Duration.ofSeconds(3));
            assertThat(c.overrideConfiguration().retryStrategy()).get()
                    .extracting(strategy -> strategy.maxAttempts())
                    .as("standard면 셋, legacy면 넷이다")
                    .isEqualTo(3);
        }
    }

    @Test
    void 이행_클라이언트는_따로_30초다() {
        try (SecretsManagerClient client = SecretsManagerClients.forMigration(props("http://localhost:14566"))) {
            SecretsManagerServiceClientConfiguration c = client.serviceClientConfiguration();
            assertThat(c.endpointOverride()).contains(URI.create("http://localhost:14566"));
            assertThat(c.overrideConfiguration().apiCallTimeout()).contains(Duration.ofSeconds(30));
            assertThat(c.overrideConfiguration().retryStrategy()).get()
                    .extracting(strategy -> strategy.maxAttempts()).isEqualTo(3);
        }
    }

    /** 웹 경로의 HTTP 시한은 시도 시한(1초)보다 짧다. 기본값 2초·30초면 시도 시한보다 길다. */
    @Test
    void 웹_경로_HTTP_시한은_시도_시한보다_짧다() {
        assertThat(SecretsManagerClients.REQUEST_CONNECT_TIMEOUT).isLessThan(SecretsManagerSecretStore.ATTEMPT_TIMEOUT);
        assertThat(SecretsManagerClients.REQUEST_SOCKET_TIMEOUT).isLessThan(SecretsManagerSecretStore.ATTEMPT_TIMEOUT);
    }
}
