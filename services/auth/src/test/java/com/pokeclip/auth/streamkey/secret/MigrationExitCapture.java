package com.pokeclip.auth.streamkey.secret;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 이행 실행기 부팅 시험이 종료 코드를 받는 자리. <b>시험 클래스 안에 두지 않는다</b>: 안쪽 {@code @TestConfiguration}은
 * 스프링이 그 시험 클래스 컨텍스트에도 자동으로 끼워 넣어 컨텍스트가 하나 더 뜬다. {@code @TestConfiguration}이라
 * 컴포넌트 스캔에도 안 걸린다. 부팅 시험이 앱 원천으로 직접 넘길 때만 쓰인다.
 */
@TestConfiguration
class MigrationExitCapture {

    static final AtomicInteger EXIT = new AtomicInteger(-1);
    static final AtomicReference<ConfigurableApplicationContext> SEEN = new AtomicReference<>();

    /** 실행기가 끝내기 직전(컨텍스트가 닫히기 전)에 본 빈 이름 전부. 닫힌 컨텍스트에는 물을 수 없다. */
    static final AtomicReference<List<String>> BEANS = new AtomicReference<>(List.of());

    /** 실행기가 이 빈을 꺼내는 순간은 컨텍스트를 닫기 전이다({@code exit.getIfAvailable(...)}가 인자보다 먼저 평가된다). */
    @Bean
    ProcessExit captureExit(ConfigurableApplicationContext context) {
        SEEN.set(context);
        BEANS.set(List.of(context.getBeanDefinitionNames()));
        return EXIT::set;
    }
}
