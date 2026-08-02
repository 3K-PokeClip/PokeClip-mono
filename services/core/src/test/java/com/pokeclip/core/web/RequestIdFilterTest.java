package com.pokeclip.core.web;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    /** 필터 안에서 본 MDC 값을 꺼내오기 위한 체인. */
    private String idSeenInside(MockHttpServletRequest request) throws Exception {
        String[] seen = new String[1];
        FilterChain chain = (req, res) -> seen[0] = MDC.get(RequestIdFilter.MDC_KEY);
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        return seen[0];
    }

    @Test
    void 헤더가_없으면_새_id를_만든다() throws Exception {
        assertThat(idSeenInside(new MockHttpServletRequest())).isNotBlank();
    }

    @Test
    void 정상적인_헤더는_그대로_쓴다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "abc-123");

        assertThat(idSeenInside(request)).isEqualTo("abc-123");
    }

    /**
     * 개행이 든 헤더를 그대로 MDC에 넣으면 로그에 가짜 줄을 만들어 넣을 수 있다.
     * 위조 가능한 로그는 가시성이 아니다.
     */
    @Test
    void 개행이_든_헤더는_버리고_새로_만든다() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "abc\nERROR fake.log.line");

        assertThat(idSeenInside(request)).doesNotContain("fake.log.line");
    }

    /**
     * "버린다"를 못박는다. 길이만 보면(hasSizeLessThan) 앞 32자를 잘라 쓰는 구현도
     * 통과하는데, 그건 인젝션 방어가 아니다 — 개행이 앞쪽에 있으면 그대로 통과한다.
     */
    @Test
    void 너무_긴_헤더는_버리고_새로_만든다() throws Exception {
        String tooLong = "a".repeat(33);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", tooLong);

        String seen = idSeenInside(request);
        assertThat(seen).isNotEqualTo(tooLong);
        assertThat(seen).doesNotStartWith("aaaa");
        assertThat(seen).hasSizeLessThanOrEqualTo(32);
    }

    /** 톰캣이 스레드를 재사용한다. 안 지우면 다음 요청이 남의 id를 달고 찍힌다. */
    @Test
    void 요청이_끝나면_MDC를_비운다() throws Exception {
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (req, res) -> {
                });

        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    /**
     * spring.security.filter.order의 기본값이 -100이다. 뒤에 두면 시큐리티가 뱉는
     * 401에 상관 ID가 안 붙는다 — 인증 실패야말로 이어 봐야 하는 로그다.
     */
    @Test
    void 필터는_시큐리티_체인보다_앞_순서다() {
        assertThat(new WebConfig().requestIdFilter().getOrder()).isLessThan(-100);
    }
}
