package com.pokeclip.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;

import java.sql.Connection;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 공급자가 <b>연결마다</b> 토큰을 새로 받는지, 그리고 그 토큰이 RDS가 받는 꼴인지를 잰다(POK-406 · POK-407).
 *
 * <p>실제 RDS 없이 재는 범위는 여기까지다. 서버가 토큰을 받아 주는지(S1) · 15분 뒤에도 새 연결이
 * 붙는지(S2)는 chat-detector {@code RdsIamSpikeTest}가 실험 RDS에서 잰다.
 */
class RdsIamCredentialsProviderTest extends PostgresTestBase {

    @Test
    void 실제_서명기는_네트워크_없이_접속_토큰_꼴을_만든다() {
        // 가짜 키다. 서명은 로컬 계산이라 이 키로도 꼴은 나온다. 서버가 받아 줄지는 S1이 잰다.
        RdsTokenSigner signer = RdsTokenSigner.rds(Region.AP_NORTHEAST_2,
                StaticCredentialsProvider.create(AwsBasicCredentials.create("AKIDEXAMPLE", "fake-secret")));

        String token = signer.sign("spike.example.ap-northeast-2.rds.amazonaws.com", 5432, "detector_iam");

        assertThat(token)
                .startsWith("spike.example.ap-northeast-2.rds.amazonaws.com:5432/?")
                .contains("Action=connect", "DBUser=detector_iam", "X-Amz-Expires=900")
                .doesNotContain("fake-secret");
    }

    /**
     * Hikari가 새 연결을 만들 때마다 공급자를 부르는지. 풀 설정의 비밀번호는 일부러 틀리게 둔다.
     * 공급자를 안 거치면 접속이 거절돼 빨간불이 된다.
     */
    @Test
    void 새_연결마다_토큰을_새로_받고_풀_설정의_비밀번호는_안_쓴다() throws Exception {
        AtomicInteger signed = new AtomicInteger();
        RdsTokenSigner signer = (host, port, user) -> {
            signed.incrementAndGet();
            return POSTGRES.getPassword();
        };

        try (HikariDataSource ds = new HikariDataSource(poolConfig(signer))) {
            try (Connection first = ds.getConnection()) {
                assertThat(first.isValid(1)).isTrue();
            }
            // 풀을 띄울 때 한 번(initializeDataSource, 접속에는 안 쓰는 기본값) + 첫 연결 한 번.
            assertThat(signed.get()).as("풀 시작 + 첫 연결").isEqualTo(2);

            // 수명이 다한 연결을 회수하는 것과 같은 길이다. 다음 대여는 새 연결을 만든다.
            ds.getHikariPoolMXBean().softEvictConnections();
            try (Connection second = ds.getConnection()) {
                assertThat(second.isValid(1)).isTrue();
            }

            assertThat(signed.get()).as("회수 뒤 새 연결은 토큰을 다시 받아야 한다").isEqualTo(3);
        }
    }

    @Test
    void 토큰_생성이_실패하면_원인_문구_없이_종류만_싣는다() {
        RdsTokenSigner signer = (host, port, user) -> {
            throw new IllegalStateException("profile secret-profile-name not found");
        };
        RdsIamCredentialsProvider provider = new RdsIamCredentialsProvider(signer, "db.local", 5432, "detector_iam");

        Throwable thrown = catchThrowable(provider::getCredentials);

        assertThat(thrown).hasMessageContaining("IllegalStateException")
                .hasMessageNotContaining("secret-profile-name")
                .hasNoCause();
    }

    /**
     * 서명 자체는 로컬 계산이지만 자격 증명 조회(ECS 태스크 역할 엔드포인트)는 밖으로 나간다. 그 조회가 멈추면
     * 연결 생성이 끝없이 매달려 풀이 바닥난다. 그래서 시한을 둔다(media {@code iam.go}의 10초와 같다).
     */
    @Test
    void 토큰_생성이_시한을_넘기면_기다리지_않고_실패한다() {
        RdsTokenSigner hanging = (host, port, user) -> {
            try {
                Thread.sleep(Duration.ofSeconds(30));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "never";
        };
        RdsIamCredentialsProvider provider = new RdsIamCredentialsProvider(
                hanging, "db.local", 5432, "app_auth", Duration.ofMillis(200));

        long started = System.nanoTime();
        Throwable thrown = catchThrowable(provider::getCredentials);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        assertThat(thrown).hasMessageContaining("시한").hasNoCause();
    }

    @Test
    void 운영_시한은_10초다() {
        assertThat(RdsIamCredentialsProvider.SIGN_TIMEOUT).isEqualTo(Duration.ofSeconds(10));
    }

    private static HikariConfig poolConfig(RdsTokenSigner signer) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword("wrong-password");
        config.setMaximumPoolSize(1);
        config.setCredentialsProvider(new RdsIamCredentialsProvider(
                signer, POSTGRES.getHost(), POSTGRES.getMappedPort(5432), POSTGRES.getUsername()));
        return config;
    }
}
