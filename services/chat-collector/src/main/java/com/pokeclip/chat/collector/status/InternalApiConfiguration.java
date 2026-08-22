package com.pokeclip.chat.collector.status;

import com.pokeclip.chat.collector.link.LinkProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code /internal/*}에만 내부 토큰 검사를 건다. 보안 프레임워크는 안 넣는다 — 이 서버에 없고,
 * 필터 하나를 위해 체인·기본 설정을 끌어오면 actuator까지 영향을 받는다.
 *
 * <p>토큰은 auth를 부를 때 쓰는 {@code INTERNAL_API_TOKEN}을 <b>받는 쪽에서도</b> 쓴다 —
 * 새 환경변수를 만들지 않는다(PRD). 비어 있으면 필터가 전부 401로 막고 여기서 한 줄 경고한다.
 */
@Configuration
public class InternalApiConfiguration {

    private static final Logger log = LoggerFactory.getLogger(InternalApiConfiguration.class);

    @Bean
    public FilterRegistrationBean<InternalTokenFilter> internalTokenFilter(LinkProperties link) {
        String token = link.internalToken();
        if (token == null || token.isBlank()) {
            log.warn("chat.internal_api.locked reason=INTERNAL_API_TOKEN_EMPTY — /internal/* 은 전부 401이다");
        }
        FilterRegistrationBean<InternalTokenFilter> registration =
                new FilterRegistrationBean<>(new InternalTokenFilter(token));
        registration.addUrlPatterns("/internal/*");
        registration.setOrder(0);
        return registration;
    }
}
