package com.pokeclip.auth.web;

import com.pokeclip.web.RequestIdFilter;
import ch.qos.logback.classic.LoggerContext;
import com.pokeclip.auth.support.IntegrationTestSupport;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

@AutoConfigureMockMvc
class RequestIdPropagationTest extends IntegrationTestSupport {

    private final MockMvc mockMvc;

    RequestIdPropagationTest(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    /**
     * application.yml의 logging.pattern.level이 실제로 로그백에 실렸는지 본다.
     * Boot는 이 값을 LOG_LEVEL_PATTERN 컨텍스트 프로퍼티로 넣고, 기본 콘솔 패턴이
     * 그걸 참조한다(spring-boot-4.1.0.jar의 logback/defaults.xml에서 확인됨).
     *
     * <p>이 단언이 없으면 logging.pattern.level 줄을 지워도 전부 초록이라,
     * 4단계의 "안 먹으면 여기서 멈춘다"를 실행할 방법이 없다.
     */
    @Test
    void 로그_형식에_상관_ID가_들어가_있다() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

        assertThat(context.getProperty("LOG_LEVEL_PATTERN"))
                .as("logging.pattern.level이 로그백에 안 실렸다")
                .contains("requestId");
    }

    @Test
    void 한_요청에서_나온_로그는_같은_상관_ID를_갖는다() throws Exception {
        try (LogCaptor captor = new LogCaptor()) {
            refreshWithTraceId("trace-one");

            assertThat(captor.mdcOf("auth.failed", RequestIdFilter.MDC_KEY))
                    .as("로그 줄에 상관 ID가 안 실렸다")
                    .isEqualTo("trace-one");
        }
    }

    @Test
    void 서로_다른_요청은_다른_상관_ID를_갖는다() throws Exception {
        try (LogCaptor captor = new LogCaptor()) {
            refreshWithTraceId("trace-a");
            refreshWithTraceId("trace-b");

            assertThat(captor.events())
                    .extracting(e -> e.getMDCPropertyMap().get(RequestIdFilter.MDC_KEY))
                    .contains("trace-a", "trace-b");
        }
    }

    private void refreshWithTraceId(String traceId) throws Exception {
        mockMvc.perform(post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", traceId)
                .content("""
                         {"refreshToken":"never-issued"}
                         """));
    }
}
