package com.pokeclip.auth.streamkey.secret;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClientBuilder;

import java.net.URI;
import java.time.Duration;

/**
 * Secrets Manager 클라이언트를 만드는 한 곳(POK-272). <b>용도가 둘이라 클라이언트도 둘이다</b>(1번 10-05 정정 1).
 *
 * <ul>
 *   <li><b>웹 요청 경로</b>({@link #forRequests}). 연산마다 공유 3초 시한 안에서 돈다. HTTP 연결·소켓 시한을
 *       시도 시한(최대 1초)보다 짧게 따로 건다. Apache5 기본값이 2초·30초라 그대로 두면 시도 시한보다 길다.</li>
 *   <li><b>이행 실행기</b>({@link #forMigration}). 첫 호출 한 번을 30초로 둔다(KMS 기본 키가 첫 사용 때 만들어지며
 *       크게 늦을 수 있다). 위의 짧은 HTTP 시한이 그 호출을 막지 않게 따로 만든다.</li>
 * </ul>
 *
 * <p><b>재시도 모드를 코드에서 standard로 못박는다.</b> SDK 2.x의 기본은 standard가 아니라 legacy(시도 넷)이고,
 * {@code AWS_RETRY_MODE}·프로필 설정·DefaultsMode·{@code AWS_NEW_RETRIES_2026}이 그 기본을 바꿀 수 있다.
 * 환경에 따라 시도 수가 달라지면 시한 시험이 재는 것과 운영이 다르다.
 *
 * <p>자격증명은 SDK 표준 체인이다(dev는 EC2 역할). 정책은 이름 접두 {@code pokeclip/dev/stream-key/} 안의
 * 네 동작(Create·Put·Get·Delete)만 허용한다. 목록 조회({@code ListSecrets})는 없다.
 */
final class SecretsManagerClients {

    /** 웹 경로 연결 시한. 시도 시한 1초 안에서 TLS까지 끝나야 한다. */
    static final Duration REQUEST_CONNECT_TIMEOUT = Duration.ofMillis(500);

    /**
     * 웹 경로 소켓 시한. 시도 시한(최대 1초)보다 짧게: 응답이 끊긴 연결을 시도 시한 전에 놓는다. 너무 줄이지 않는다:
     * 0.9초 걸린 정상 응답(시한 시험의 「0.9초 뒤 이미 있음」)까지 끊으면 SDK가 그것을 재시도해 시한을 다 쓴다.
     */
    static final Duration REQUEST_SOCKET_TIMEOUT = Duration.ofMillis(950);

    /** 웹 경로의 안전망. 연산마다 요청 단위로 더 짧게 덮어쓴다. */
    static final Duration REQUEST_CALL_TIMEOUT = SecretsManagerSecretStore.OPERATION_DEADLINE;

    static final Duration MIGRATION_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration MIGRATION_SOCKET_TIMEOUT = Duration.ofSeconds(30);
    static final Duration MIGRATION_CALL_TIMEOUT = Duration.ofSeconds(30);

    private SecretsManagerClients() { }

    static SecretsManagerClient forRequests(StreamKeySecretStoreProperties p) {
        return build(p, REQUEST_CONNECT_TIMEOUT, REQUEST_SOCKET_TIMEOUT,
                SecretsManagerSecretStore.ATTEMPT_TIMEOUT, REQUEST_CALL_TIMEOUT);
    }

    static SecretsManagerClient forMigration(StreamKeySecretStoreProperties p) {
        return build(p, MIGRATION_CONNECT_TIMEOUT, MIGRATION_SOCKET_TIMEOUT,
                MIGRATION_CALL_TIMEOUT, MIGRATION_CALL_TIMEOUT);
    }

    private static SecretsManagerClient build(StreamKeySecretStoreProperties p, Duration connect, Duration socket,
                                              Duration attempt, Duration call) {
        SecretsManagerClientBuilder builder = SecretsManagerClient.builder()
                .region(Region.of(p.region()))
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .httpClientBuilder(Apache5HttpClient.builder()
                        .connectionTimeout(connect)
                        .socketTimeout(socket))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .retryStrategy(RetryMode.STANDARD)
                        .apiCallAttemptTimeout(attempt)
                        .apiCallTimeout(call)
                        .build());
        if (p.hasEndpoint()) {
            builder.endpointOverride(URI.create(p.endpoint()));
        }
        return builder.build();
    }
}
