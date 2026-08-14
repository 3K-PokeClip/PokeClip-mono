package com.pokeclip.chat.collector.persist;

import com.pokeclip.chat.collector.CollectorApplication;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * PR #56 P2. {@code reWriteBatchedInserts=true}는 배치 결과 행 수를 지워
 * persisted/conflicts 계상을 못 하게 한다 — 켜져 있으면 <b>부팅에서</b> 거부해야
 * 한다. 운영 중에 조용히 틀린 숫자를 내는 것보다 안 뜨는 쪽이 낫다.
 *
 * <p>별도 컨텍스트로 부팅하는 이유는 {@code DatasourceTimeoutTest}와 같다 — 검사
 * 대상이 yml 값이 아니라 <b>실물 DataSource가 드라이버에 넘기는 값</b>이고, 켜는
 * 자리가 둘(URL 쿼리·hikari.data-source-properties)이라 둘 다 실제 배선으로 잰다.
 */
class RewriteBatchedInsertsGuardTest extends IntegrationTestSupport {

    // Testcontainers URL에는 이미 ?loggerLevel=OFF가 붙어 있다 — '?'로 또 붙이면
    // pgjdbc가 옵션을 통째로 무시해 켠 적이 없는 것과 같아진다(실측 — 이 검사가
    // 초록인데 아무것도 안 잰 상태). 반드시 '&'다.

    @Test
    void JDBC_URL에_reWriteBatchedInserts가_켜져_있으면_부팅을_거부한다() {
        Throwable thrown = catchThrowable(() -> boot(POSTGRES.getJdbcUrl() + "&reWriteBatchedInserts=true"));

        assertThat(thrown)
                .as("이 옵션 아래서 뜨면 충돌이 저장으로 둔갑한 숫자가 조용히 나간다")
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause().hasMessageContaining("reWriteBatchedInserts");
    }

    @Test
    void hikari_data_source_properties로_켜도_부팅을_거부한다() {
        Throwable thrown = catchThrowable(() -> boot(POSTGRES.getJdbcUrl(),
                "--spring.datasource.hikari.data-source-properties.reWriteBatchedInserts=true"));

        assertThat(thrown)
                .as("URL만 보면 hikari 속성으로 켠 것을 놓친다")
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .rootCause().hasMessageContaining("reWriteBatchedInserts");
    }

    /** 양성 대조 — 명시적으로 꺼 둔 것까지 거부하면 검사가 과잉이다. */
    @Test
    void 명시적으로_끈_것은_거부하지_않는다() throws Exception {
        try (ConfigurableApplicationContext context = boot(POSTGRES.getJdbcUrl() + "&reWriteBatchedInserts=false")) {
            assertThat(context.isRunning()).isTrue();
        }
    }

    private static ConfigurableApplicationContext boot(String jdbcUrl, String... extraArgs) {
        String[] args = new String[3 + extraArgs.length];
        args[0] = "--spring.datasource.url=" + jdbcUrl;
        args[1] = "--spring.datasource.username=" + POSTGRES.getUsername();
        args[2] = "--spring.datasource.password=" + POSTGRES.getPassword();
        System.arraycopy(extraArgs, 0, args, 3, extraArgs.length);
        return new SpringApplicationBuilder(CollectorApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("test")
                .run(args);
    }
}
