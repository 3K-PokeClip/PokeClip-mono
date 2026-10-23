package com.pokeclip.chat.detector.db;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code POSTGRES_AUTH=iam}이면 Boot가 만든 Hikari 풀에 토큰 공급자와 TLS 설정을 건다(POK-406 스파이크).
 * 기본({@code password})이면 이 구성은 아예 안 실리고 지금과 똑같다.
 *
 * <p>Boot 4.1.0은 {@code HikariCredentialsProvider} 빈을 풀에 자동으로 꽂아 주지 않는다. 그래서
 * 풀이 처음 연결을 만들기 전, 빈 후처리 단계에서 직접 건다. Boot의 접속정보 후처리기는
 * {@code PriorityOrdered}라 이것보다 먼저 돌아 URL이 이미 확정돼 있다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "pokeclip.datasource.auth", havingValue = "iam")
public class RdsIamDataSourceConfiguration {

    /**
     * 토큰은 15분 동안 다시 쓸 수 있는 자격이라 중간자에게 내줄 수 없다. 그래서 verify-full 하나만
     * 건다 — 서버 인증서를 CA 번들로 검증하고 호스트 이름까지 맞춘다(media pgconf와 같은 규칙).
     */
    static final String SSL_MODE = "verify-full";

    /** 후처리기는 static으로 둔다 — 다른 빈보다 먼저 만들어져야 해서 설정 클래스를 끌어오면 안 된다. */
    @Bean
    static BeanPostProcessor rdsIamHikariPostProcessor(Environment env, ObjectProvider<RdsTokenSigner> signer) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof HikariDataSource ds) {
                    apply(ds, env, signer);
                }
                return bean;
            }
        };
    }

    private static void apply(HikariDataSource ds, Environment env, ObjectProvider<RdsTokenSigner> signer) {
        Path rootCert = rootCert(env.getProperty("pokeclip.datasource.ssl-root-cert", ""));
        URI uri = URI.create(ds.getJdbcUrl().substring("jdbc:".length()));
        if (uri.getHost() == null) {
            // 여러 호스트 · 유닉스 소켓 꼴이면 토큰을 서명한 대상과 실제 접속 대상이 갈라진다.
            throw new IllegalStateException("iam에서는 DB_HOST가 TCP 호스트 하나여야 한다");
        }
        int port = uri.getPort() == -1 ? 5432 : uri.getPort();
        Region region = Region.of(env.getProperty("pokeclip.datasource.region", "ap-northeast-2"));

        ds.setCredentialsProvider(new RdsIamCredentialsProvider(
                signer.getIfAvailable(() -> RdsTokenSigner.rds(region, DefaultCredentialsProvider.builder().build())),
                uri.getHost(), port, ds.getUsername()));
        // 덮어쓰지 않고 더한다 — yml의 socketTimeout이 같은 자리에 있다.
        ds.addDataSourceProperty("sslmode", SSL_MODE);
        ds.addDataSourceProperty("sslrootcert", rootCert.toString());
    }

    private static Path rootCert(String raw) {
        if (raw.isBlank()) {
            throw new IllegalStateException("iam에서는 POSTGRES_SSLROOTCERT(RDS CA 번들의 절대 경로)가 필요하다");
        }
        Path path = Path.of(raw.trim());
        if (!path.isAbsolute() || !Files.isReadable(path)) {
            throw new IllegalStateException("POSTGRES_SSLROOTCERT(" + raw + ")는 읽을 수 있는 절대 경로여야 한다");
        }
        return path;
    }
}
