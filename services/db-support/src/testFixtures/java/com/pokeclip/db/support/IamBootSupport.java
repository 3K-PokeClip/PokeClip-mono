package com.pokeclip.db.support;

import com.pokeclip.db.RdsTokenSigner;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * 서버를 {@code POSTGRES_AUTH=iam}으로 실제 부팅하는 시험의 도우미다(POK-407). 서버 넷과 db-support가 같이 쓴다.
 *
 * <p>시험 컨테이너는 TLS가 없어 verify-full로는 못 붙는다. 그래서 이 도우미로 띄운 서버는 풀 설정까지만 잴 수
 * 있고, 실제 연결은 「TLS가 없다」(08004)로 거절된다. 그 거절이 verify-full이 걸렸다는 증거다.
 */
public final class IamBootSupport {

    private IamBootSupport() {
    }

    /**
     * 시험 컨테이너 URL에는 쿼리({@code ?loggerLevel=OFF})가 붙어 온다. iam은 URL 쿼리를 안 받으므로 뗀 꼴을 쓴다.
     * 뒤에 {@code ?}를 또 붙이면 드라이버가 그 키를 안 읽는다(POK-406 첫 판이 글자 포함 검사로 우연히 통과했던 자리).
     */
    public static String plainUrl(PostgreSQLContainer postgres) {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432)
                + "/" + postgres.getDatabaseName();
    }

    /** 내용 없는 CA 파일이다. 부팅 검사(읽을 수 있는 절대 경로)만 통과시킨다. */
    public static Path fakeCaFile(Path dir) {
        Path ca = dir.resolve("ca.pem");
        try {
            if (!Files.exists(ca)) {
                Files.writeString(ca, "-----BEGIN CERTIFICATE-----\n-----END CERTIFICATE-----\n");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return ca;
    }

    /**
     * 서명기를 가짜로 갈아 끼운다. 시험이 실제 AWS 자격 증명을 찾아 나가면 안 된다. 돌려주는 값은 컨테이너
     * 비밀번호라, TLS만 없으면 실제로 붙는다.
     */
    public static ApplicationContextInitializer<ConfigurableApplicationContext> fakeSigner(
            PostgreSQLContainer postgres, AtomicInteger signed) {
        return ctx -> ((GenericApplicationContext) ctx).registerBean(RdsTokenSigner.class,
                () -> (host, port, user) -> {
                    signed.incrementAndGet();
                    return postgres.getPassword();
                });
    }

    /**
     * iam 부팅 인자. {@code ca}가 null이면 CA 경로를 비운다. 서버마다 다른 값(Flyway 끄기 등)은 {@code extra}로.
     *
     * <p>{@code POSTGRES_PASSWORD}를 빈 값으로 덮는다. iam은 그 env가 있으면 부팅을 거부하는데, 로컬에서 {@code .env}를
     * 셸에 실은 채 시험을 돌리면 그 값이 시험 JVM까지 온다. 명령행 인자가 env보다 앞선다.
     */
    public static String[] iamArgs(PostgreSQLContainer postgres, String jdbcUrl, Path ca, String... extra) {
        return Stream.concat(Stream.of(
                        "--spring.datasource.url=" + jdbcUrl,
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=",
                        "--POSTGRES_PASSWORD=",
                        "--pokeclip.datasource.auth=iam",
                        "--pokeclip.datasource.ssl-root-cert=" + (ca == null ? "" : ca.toString())),
                Stream.of(extra)).toArray(String[]::new);
    }
}
