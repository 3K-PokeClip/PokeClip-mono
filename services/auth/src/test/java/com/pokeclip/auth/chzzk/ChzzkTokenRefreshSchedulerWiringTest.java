package com.pokeclip.auth.chzzk;

import com.pokeclip.auth.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/** 테스트 프로파일에서는 빈이 없어야 한다 — 컨텍스트마다 틱이 돌면 DB를 뒤지고 HTTP를 시도한다. */
class ChzzkTokenRefreshSchedulerWiringTest extends IntegrationTestSupport {

    private final ApplicationContext context;

    ChzzkTokenRefreshSchedulerWiringTest(ApplicationContext context) {
        this.context = context;
    }

    @Test
    void 테스트_프로파일에서는_스케줄러_빈이_없다() {
        assertThat(context.getBeanNamesForType(ChzzkTokenRefreshScheduler.class)).isEmpty();
    }
}
