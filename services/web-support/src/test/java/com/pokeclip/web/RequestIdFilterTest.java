package com.pokeclip.web;

import com.pokeclip.web.support.LogCaptor;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /**
     * 500으로 끝난 요청이 상관 ID와 함께 남아야 한다.
     *
     * <p>여기가 안 남으면 <b>가장 조사가 필요한 순간에 추적이 끊긴다.</b>
     * 예외가 필터 밖으로 나가면 톰캣이 스택트레이스를 찍는데, 그때는 아래
     * finally가 MDC를 이미 비운 뒤다. 이어서 도는 ERROR 디스패치도
     * {@code shouldNotFilterErrorDispatch()}가 기본 true라 이 필터를 건너뛴다.
     * 두 경로 다 상관 ID가 없으므로, MDC가 살아 있는 이 자리에서 남긴다.
     */
    @Test
    void 요청이_예외로_끝나면_상관_ID와_함께_남긴다() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.addHeader("X-Request-Id", "trace-boom");

        try (LogCaptor captor = new LogCaptor()) {
            assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(),
                    (req, res) -> {
                        throw new IllegalStateException("구글 응답 파싱이 깨졌다");
                    }))
                    .as("예외를 삼키면 500이 200이 된다")
                    .isInstanceOf(IllegalStateException.class);

            assertThat(captor.mdcOf("request.failed", RequestIdFilter.MDC_KEY))
                    .as("500으로 끝난 요청에 상관 ID가 안 남았다")
                    .isEqualTo("trace-boom");
        }
    }

    /**
     * 예외 메시지에는 구글 응답 본문 같은 것이 붙어 온다. AuthExceptionHandler가
     * causeType만 찍는 것과 같은 이유로 여기서도 타입 이름만 남긴다.
     *
     * <p>유출 없음만 단언하면 <b>로그를 한 줄도 안 찍는 구현에서 초록이다.</b>
     * 그래서 줄이 실제로 있는지를 먼저 못박는다.
     */
    @Test
    void 실패_로그에_예외_메시지를_넣지_않는다() {
        String secretish = "client_secret=GOCSPX-절대-안-찍혀야-한다";

        try (LogCaptor captor = new LogCaptor()) {
            assertThatThrownBy(() -> filter.doFilter(
                    new MockHttpServletRequest("GET", "/api/auth/me"), new MockHttpServletResponse(),
                    (req, res) -> {
                        throw new IllegalStateException(secretish);
                    }))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(captor.messages())
                    .as("검사할 로그 줄 자체가 없다")
                    .anyMatch(line -> line.startsWith("request.failed"));
            assertThat(captor.messages())
                    .as("예외 메시지가 로그로 샜다")
                    .noneMatch(line -> line.contains(secretish));
        }
    }
}
