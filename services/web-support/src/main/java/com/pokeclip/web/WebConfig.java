package com.pokeclip.web;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class WebConfig {

    /**
     * 시큐리티 체인보다 앞에 둔다. spring.security.filter.order의 기본값이 -100이라
     * 뒤에 두면 시큐리티가 뱉는 401에는 상관 ID가 안 붙는다.
     *
     * <p>Boot 4.1에는 SecurityProperties.DEFAULT_FILTER_ORDER 상수가 없다.
     * 기준값은 위 프로퍼티의 기본값이다.
     *
     * <p>HIGHEST_PRECEDENCE를 그대로 쓰지 않는 이유는 Boot의
     * OrderedCharacterEncodingFilter가 같은 Integer.MIN_VALUE라 순서가 동점이 되기
     * 때문이다. 동점이면 실행 순서가 정해지지 않는다 — 인코딩 필터가 요청 인코딩을
     * 세팅하기 전에 우리가 헤더를 읽을 수 있다. +1로 한 칸 뒤에 서면 인코딩 다음,
     * 시큐리티(-100)보다는 한참 앞이라는 것이 둘 다 확정된다.
     */
    @Bean
    public FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> registration =
                new FilterRegistrationBean<>(new RequestIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }
}
