package com.pokeclip.chat.detector.db;

import com.pokeclip.chat.detector.DetectorApplication;
import com.pokeclip.chat.detector.support.IntegrationTestSupport;
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

/**
 * 이 서버가 db-support의 로그인 설정을 실제로 끌어오는지 잰다(POK-407). 거부 조합 · TLS 거절 같은 갈래는
 * db-support {@code DataSourceLoginConfigurationTest}가 잰다. 여기서는 @Import와 이 서버 yml의 socketTimeout이
 * iam 풀까지 닿는지만 본다. @Import를 지우면 공급자가 안 걸려 빨간불이다.
 */
class DbLoginWiringTest extends IntegrationTestSupport {

    @TempDir
    Path tmp;

    @Test
    void iam이면_이_서버_풀에_토큰_공급자와_verify_full이_걸리고_yml의_socketTimeout은_그대로다() {
        Path ca = IamBootSupport.fakeCaFile(tmp);
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(DetectorApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("test")
                .initializers(IamBootSupport.fakeSigner(POSTGRES, new AtomicInteger()))
                // 시험 컨테이너는 TLS가 없어 verify-full로 못 붙는다. 마이그레이션이 기동 중에 붙으려 하지 않게 끈다.
                .run(IamBootSupport.iamArgs(POSTGRES, IamBootSupport.plainUrl(POSTGRES), ca,
                        "--spring.flyway.enabled=false"))) {
            HikariDataSource ds = context.getBean(HikariDataSource.class);

            assertThat(ds.getCredentialsProvider()).isNotNull();
            assertThat(ds.getDataSourceProperties())
                    .containsEntry("sslmode", "verify-full")
                    .containsEntry("sslrootcert", ca.toString())
                    .containsEntry("socketTimeout", "10");
        }
    }
}
