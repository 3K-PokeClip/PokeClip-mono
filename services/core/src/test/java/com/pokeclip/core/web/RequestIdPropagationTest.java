package com.pokeclip.core.web;

import ch.qos.logback.classic.LoggerContext;
import com.pokeclip.core.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdPropagationTest extends IntegrationTestSupport {

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
}
