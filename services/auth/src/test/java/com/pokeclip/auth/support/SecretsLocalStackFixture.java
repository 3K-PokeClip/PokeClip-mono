package com.pokeclip.auth.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.localstack.LocalStackContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;

import java.net.URI;
import java.util.Optional;

/**
 * 가짜 Secrets Manager(LocalStack)를 한 번만 띄우는 정적 픽스처(POK-272). {@link PhotoLocalStackFixture}와 같은
 * 모양이고 같은 이미지 태그(4.14.0, 커뮤니티 마지막 SemVer)를 쓴다.
 *
 * <p><b>확인용 클라이언트는 운영 코드를 안 거친다</b>: 저장소가 이름을 잘못 쓰거나 안 지워도 이 클라이언트로 직접
 * 보면 드러난다. 자격증명도 이 클라이언트에는 정적으로 준다. 운영 코드(표준 체인)가 쓸 값은 시스템 프로퍼티 자리에
 * 넣는다.
 */
public final class SecretsLocalStackFixture {

    public static final String PREFIX = "pokeclip/test/stream-key/";

    private static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer("localstack/localstack:4.14.0").withServices("secretsmanager");

    private static final SecretsManagerClient DIRECT;

    static {
        LOCALSTACK.start();
        System.setProperty("aws.accessKeyId", LOCALSTACK.getAccessKey());
        System.setProperty("aws.secretAccessKey", LOCALSTACK.getSecretKey());
        DIRECT = SecretsManagerClient.builder()
                .region(Region.of(LOCALSTACK.getRegion()))
                .endpointOverride(URI.create(endpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                .build();
    }

    private SecretsLocalStackFixture() { }

    /** 스트림키 저장소를 aws로 켠 컨텍스트. 병행 기간 dev와 같게 양쪽에서 지운다. */
    public static void registerAws(DynamicPropertyRegistry registry) {
        registry.add("pokeclip.stream-key-secret-store.type", () -> "aws");
        registry.add("pokeclip.stream-key-secret-store.retire-from", () -> "postgres,aws");
        registry.add("pokeclip.stream-key-secret-store.ref-prefix", () -> PREFIX);
        registry.add("pokeclip.stream-key-secret-store.endpoint", SecretsLocalStackFixture::endpoint);
        registry.add("pokeclip.stream-key-secret-store.region", SecretsLocalStackFixture::region);
    }

    public static String endpoint() {
        return LOCALSTACK.getEndpoint().toString();
    }

    public static String region() {
        return LOCALSTACK.getRegion();
    }

    public static Optional<String> read(String name) {
        try {
            return Optional.of(DIRECT.getSecretValue(b -> b.secretId(name)).secretString());
        } catch (ResourceNotFoundException e) {
            return Optional.empty();
        }
    }

    public static void write(String name, String value) {
        DIRECT.createSecret(b -> b.name(name).secretString(value));
    }
}
