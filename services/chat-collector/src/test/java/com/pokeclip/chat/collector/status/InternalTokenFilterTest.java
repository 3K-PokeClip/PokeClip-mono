package com.pokeclip.chat.collector.status;

import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 순수 단위다 — 스프링을 안 띄운다. 경로 좁히기(<code>/internal/*</code>만)·필터 등록·health 분리는
 * 이 필터가 아니라 {@link InternalApiConfiguration}이 하는 일이라
 * {@code ChatCollectionEndpointTest}가 실제 HTTP로 잰다(태스크 5).
 */
class InternalTokenFilterTest {

    @Test
    void 맞는_토큰이면_지나간다() throws Exception {
        InternalTokenFilter filter = new InternalTokenFilter("secret-1");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/streams/s1/chat-collection");
        request.addHeader("X-Internal-Token", "secret-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).as("체인이 불렸다").isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    // 문항 2: 로그 단언(noneMatch)은 <b>로그가 한 줄도 없어도 참</b>이다 — 여기서는 그것이 곧 요구사항이라
    //         양성 대조를 둘 수 없다. 대신 같은 갈래에서 체인 미호출·401·빈 본문을 같이 단언해
    //         「아무 일도 안 일어났다」가 아니라 「막혔다」임을 못박는다.
    @Test
    void 헤더가_없거나_틀리면_401이고_본문도_로그도_없다() throws Exception {
        InternalTokenFilter filter = new InternalTokenFilter("secret-1");
        for (String presented : new String[] { null, "", "secret-2", "secret-1 " }) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/streams/s1/chat-collection");
            if (presented != null) request.addHeader("X-Internal-Token", presented);
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            try (LogCaptor captor = new LogCaptor()) {
                filter.doFilter(request, response, chain);
                assertThat(captor.messages())
                        .as("미인증 트래픽이 로그를 무한 생성하면 안 된다(auth 필터와 같은 이유)")
                        .noneMatch(m -> m.contains("Internal") || m.contains("401"));
            }
            assertThat(chain.getRequest()).as("%s: 체인이 불리면 안 된다", presented).isNull();
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getContentAsString()).isEmpty();
        }
    }

    // 문항 5: 이 갈래를 빼면 토큰 설정이 빈 프로세스에서 빈 헤더로 창구가 열린다 — isEqual("","")은 true다.
    @Test
    void 설정_토큰이_비어_있으면_빈_헤더로도_401이다() throws Exception {
        InternalTokenFilter filter = new InternalTokenFilter("");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/streams/s1/chat-collection");
        request.addHeader("X-Internal-Token", "");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(401);
    }
}
