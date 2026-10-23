package com.pokeclip.chat.detector.db;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code POSTGRES_AUTH=iam}이면 Boot가 만든 Hikari 풀에 토큰 공급자와 TLS 설정을 건다(POK-406 스파이크).
 * 기본({@code password})이면 아무것도 안 건드리고 지금과 똑같다. 둘 다 아닌 값은 부팅을 거부한다.
 *
 * <p>Boot 4.1.0은 {@code HikariCredentialsProvider} 빈을 풀에 자동으로 꽂아 주지 않는다. 그래서
 * 풀이 처음 연결을 만들기 전, 빈 후처리 단계에서 직접 건다. Boot의 접속정보 후처리기는
 * {@code PriorityOrdered}라 이것보다 먼저 돌아 URL이 이미 확정돼 있다.
 */
@Configuration(proxyBeanMethods = false)
public class RdsIamDataSourceConfiguration {

    /**
     * 토큰은 15분 동안 다시 쓸 수 있는 자격이라 중간자에게 내줄 수 없다. 그래서 verify-full 하나만
     * 건다. 서버 인증서를 CA 번들로 검증하고 호스트 이름까지 맞춘다(media pgconf와 같은 규칙).
     */
    static final String SSL_MODE = "verify-full";

    /** yml이 넣는 드라이버 속성. 반개방 스톨을 끊는 유일한 장치라 iam에서도 그대로 받는다. */
    private static final Set<String> ALLOWED_DRIVER_PROPERTIES = Set.of("socketTimeout");

    /** 후처리기는 static으로 둔다. 다른 빈보다 먼저 만들어져야 해서 설정 클래스를 끌어오면 안 된다. */
    @Bean
    static BeanPostProcessor rdsIamHikariPostProcessor(Environment env, ObjectProvider<RdsTokenSigner> signer) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof HikariDataSource ds && isIam(env)) {
                    apply(ds, env, signer);
                }
                return bean;
            }
        };
    }

    /**
     * 오타(예: {@code IAM})가 password 모드로 조용히 떨어지면 빈 비밀번호로 접속하다 원인 없는
     * 인증 실패만 남는다. 그래서 두 값 말고는 거부한다(media pgconf와 같은 규칙).
     */
    private static boolean isIam(Environment env) {
        String auth = env.getProperty("pokeclip.datasource.auth", "password");
        return switch (auth) {
            case "iam" -> true;
            case "password" -> false;
            default -> throw new IllegalStateException(
                    "POSTGRES_AUTH(" + auth + ")는 iam · password 가운데 하나여야 한다(대소문자 구분)");
        };
    }

    private static void apply(HikariDataSource ds, Environment env, ObjectProvider<RdsTokenSigner> signer) {
        Path rootCert = rootCert(env.getProperty("pokeclip.datasource.ssl-root-cert", ""));
        URI uri = URI.create(ds.getJdbcUrl().substring("jdbc:".length()));
        if (uri.getHost() == null) {
            // 여러 호스트 · 유닉스 소켓 꼴이면 토큰을 서명한 대상과 실제 접속 대상이 갈라진다.
            throw new IllegalStateException("iam에서는 DB_HOST가 TCP 호스트 하나여야 한다");
        }
        rejectOverrides(uri, ds);
        int port = uri.getPort() == -1 ? 5432 : uri.getPort();
        Region region = Region.of(env.getProperty("pokeclip.datasource.region", "ap-northeast-2"));

        ds.setCredentialsProvider(new RdsIamCredentialsProvider(
                signer.getIfAvailable(() -> RdsTokenSigner.rds(region, DefaultCredentialsProvider.builder().build())),
                uri.getHost(), port, ds.getUsername()));
        // 덮어쓰지 않고 더한다. yml의 socketTimeout이 같은 자리에 있다.
        ds.addDataSourceProperty("sslmode", SSL_MODE);
        ds.addDataSourceProperty("sslrootcert", rootCert.toString());
    }

    /**
     * iam이면 접속을 바꾸는 값은 이 클래스가 넣는 것뿐이어야 한다. 그래서 막을 키를 고르지 않고 받을 것만 정한다.
     * URL 쿼리는 아예 안 받고, 드라이버 속성은 yml의 socketTimeout 하나만 받는다.
     *
     * <p>키를 골라 막으면 옆길이 계속 나왔다(PR #336 codex 1판 · 2판 P1). pgjdbc는 URL 쿼리 값을 속성보다
     * 앞세우므로 {@code sslmode}는 verify-full을 풀고, {@code sslfactory=NonValidatingFactory}는 인증서 검증을
     * 건너뛰고, {@code password=}는 iam인데 장기 비밀번호로 붙게 하고, {@code PGHOST=}는 토큰을 서명한 호스트와
     * 실제 접속 대상을 가른다. 어느 쪽이든 15분 동안 다시 쓸 수 있는 토큰이나 비밀번호가 엉뚱한 곳으로 간다.
     * 오류 문구에는 키만 싣고 값은 안 싣는다.
     */
    private static void rejectOverrides(URI uri, HikariDataSource ds) {
        if (uri.getQuery() != null) {
            List<String> keys = Arrays.stream(uri.getQuery().split("&")).map(pair -> pair.split("=", 2)[0]).toList();
            throw new IllegalStateException("iam에서는 DB URL 쿼리를 받지 않는다(접속 값은 이 서버가 정한다). 받은 키: " + keys);
        }
        Set<String> extra = new TreeSet<>(ds.getDataSourceProperties().stringPropertyNames());
        extra.removeAll(ALLOWED_DRIVER_PROPERTIES);
        if (!extra.isEmpty()) {
            throw new IllegalStateException("iam에서는 드라이버 속성으로 " + ALLOWED_DRIVER_PROPERTIES
                    + "만 받는다. 받은 키: " + extra);
        }
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
