package com.pokeclip.auth.db;

import com.pokeclip.auth.AuthApplication;
import com.pokeclip.auth.support.IntegrationTestSupport;
import com.pokeclip.db.support.IamBootSupport;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * 이 서버가 db-support의 로그인 설정을 실제로 끌어오는지 잰다(POK-407). 거부 조합 · TLS 거절 같은 갈래는
 * db-support {@code DataSourceLoginConfigurationTest}가 잰다. 여기서는 @Import와 이 서버 yml이 iam 풀까지 닿는지만
 * 본다. @Import를 지우면 공급자가 안 걸려 빨간불이다. 이 서버 yml에는 드라이버 속성이 없어(socketTimeout도 없다,
 * POK-414) iam이 넣는 둘만 있어야 한다.
 */
class DbLoginWiringTest extends IntegrationTestSupport {

    @TempDir
    Path tmp;

    @Test
    void iam이면_이_서버_풀에_토큰_공급자와_verify_full이_걸린다() {
        Path ca = IamBootSupport.fakeCaFile(tmp);
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(AuthApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("test")
                .initializers(IamBootSupport.fakeSigner(POSTGRES, new AtomicInteger()))
                // 시험 컨테이너는 TLS가 없어 verify-full로 못 붙는다. 기동 중에 DB에 붙는 셋을 끈다:
                // 마이그레이션 · 스키마 검증 · 방언을 정하려고 메타데이터를 읽는 Hibernate.
                .run(IamBootSupport.iamArgs(POSTGRES, IamBootSupport.plainUrl(POSTGRES), ca,
                        "--spring.flyway.enabled=false",
                        "--spring.jpa.hibernate.ddl-auto=none",
                        "--spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
                        "--spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false"))) {
            HikariDataSource ds = context.getBean(HikariDataSource.class);

            assertThat(ds.getCredentialsProvider()).isNotNull();
            assertThat(ds.getDataSourceProperties()).containsOnly(
                    entry("sslmode", "verify-full"), entry("sslrootcert", ca.toString()));
        }
    }
}
