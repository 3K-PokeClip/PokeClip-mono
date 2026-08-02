package com.pokeclip.core.web;

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
     */
    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> registration =
                new FilterRegistrationBean<>(new RequestIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
