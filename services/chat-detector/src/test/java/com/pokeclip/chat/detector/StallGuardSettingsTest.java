package com.pokeclip.chat.detector;

import com.pokeclip.chat.detector.support.IntegrationTestSupport;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.task.TaskSchedulingProperties;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>판정이 영영 멈추는 것</b>을 막는 설정 둘이 실제로 실렸는지 잰다. 계획 검증이 치명으로
 * 짚은 자리이고(F9 · F11), 둘 다 지워도 다른 검사 다섯이 전부 초록이라 그물이 없었다
 * (2026-08-25 결함 주입으로 실측).
 *
 * <p><b>🔴 이 클래스가 재는 것은 「설정이 실렸다」까지다.</b> 아래 두 검사 모두 그 설정이
 * 실제로 그 일을 하는지는 재지 않는다 — 무엇을 안 재는지는 각 검사에 적었다. 값 단언이
 * 약한 것을 알면서 넣는 이유는 <b>지금 그물이 0이기 때문</b>이다. 설정을 지우거나 키를
 * 잘못 적는 것이 현실의 회귀이고, 그 둘은 여기서 잡힌다.
 */
@SpringBootTest
class StallGuardSettingsTest extends IntegrationTestSupport {

    private final TaskSchedulingProperties scheduling;
    private final DataSource dataSource;

    StallGuardSettingsTest(TaskSchedulingProperties scheduling, DataSource dataSource) {
        this.scheduling = scheduling;
        this.dataSource = dataSource;
    }

    /**
     * 주기 작업이 셋인데(판정 1초 · 치우기 10분 · 늦은도착 10분) 스프링 기본 풀 크기는
     * <b>1</b>이다 — 하나면 보관 기간이 지난 수십만 줄을 지우는 동안 판정이 통째로 멈춘다
     * (계획 검증 F9가 실제 컨텍스트에서 {@code getPoolSize()=1}을 확인했다).
     *
     * <p><b>안 재는 것: 실제 스케줄러가 스레드를 셋 받는지.</b> 이 태스크에는
     * {@code @EnableScheduling}이 아직 없어(태스크 6 몫) <b>{@code taskScheduler} 빈이
     * 컨텍스트에 아예 없다</b> — 2026-08-25 측정: {@code containsBean("taskScheduler")=false}.
     * 그래서 빈 대신 바인딩된 설정을 잰다. 실제 풀을 행동으로 재는 것은 태스크 6에서
     * {@code @EnableScheduling}이 들어온 뒤에야 가능하다.
     *
     * <p>그래도 이 검사가 잡는 것 둘 — yml에서 그 블록을 <b>지우는 것</b>과, 키를 잘못
     * 적어 <b>조용히 안 실리는 것</b>. 후자는 이 저장소가 {@code spring.http.client}(단수형)로
     * 세 번 밟은 모양이고 오류 없이 기본값으로 돌아간다.
     */
    @Test
    void 주기_작업이_스레드_하나를_나눠_쓰지_않도록_설정이_실린다() {
        assertThat(scheduling.getPool().getSize())
                .as("spring.task.scheduling.pool.size가 안 실렸다 — 기본 1이면 치우기가 도는 동안 판정이 멈춘다")
                .isGreaterThanOrEqualTo(2);
    }

    /**
     * JDBC 반개방(연결은 살았는데 응답이 없음)을 끊는 유일한 시한이다 — {@code queryTimeout}과
     * Hikari {@code connection-timeout}은 20~35초 넘게 매달린다({@code services/CLAUDE.md} 실측).
     * 이 서버는 DB 접근이 전부 스케줄러 스레드 위에서 돌아 반개방 한 번이면 판정이 영영
     * 멈추는데 아무 신호가 없다(계획 검증 F11).
     *
     * <p><b>안 재는 것: 반개방에서 실제로 끊는지.</b> 값이 설정에 있다는 것만 잰다. 행동으로
     * 재려면 {@code chat-collector}의 {@code DatasourceTimeoutTest} 모양(응답을 안 주는 가짜
     * 서버에 붙여 걸리는 시간을 잼)이 필요하고 <b>이 태스크 범위 밖이다</b>. 다음 사람이
     * 「왜 값만 재나」를 다시 조사하지 않도록 여기 적어 둔다.
     *
     * <p>문자열로 단언하는 이유: Hikari의 {@code dataSourceProperties}는 {@code Properties}라
     * yml 값이 String으로 들어온다. 정수로 주면 Hikari가 조용히 무시한다.
     */
    @Test
    void 반개방을_끊는_socketTimeout이_설정에_실린다() {
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        assertThat(((HikariDataSource) dataSource).getDataSourceProperties().getProperty("socketTimeout"))
                .as("socketTimeout이 안 실렸다 — 반개방 한 번이면 판정이 영영 멈추는데 신호가 없다")
                .isEqualTo("10");
    }
}
