package com.pokeclip.core.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 요청 하나에서 나온 로그 줄을 잇는다.
 *
 * <p>auth 밖에 두는 이유는 clip도 쓰기 때문이다. core/CLAUDE.md는 auth를 나중에
 * 별도 프로세스로 떼어낼 수 있게 한다고 적었는데, 이 필터가 auth 안에 있으면
 * 떼어내는 순간 clip이 상관 ID를 잃는다. common에는 두지 않는다 —
 * services/CLAUDE.md가 common에 웹 계층을 금지한다.
 */
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "requestId";

    static final String HEADER = "X-Request-Id";

    /**
     * 들어온 헤더를 그대로 MDC에 넣지 않는다. 개행이 든 값을 넣으면 로그에 가짜
     * 줄을 만들어 넣을 수 있다(로그 인젝션). 위조 가능한 로그는 가시성이 아니다.
     *
     * <p>검사는 반드시 matches()다. find()로 쓰면 "abc\nERROR ..."의 앞 세 글자가
     * 걸려 통과한다. [\w-] 대신 [A-Za-z0-9-]를 쓰는 이유도 같다 — \w는 밑줄을
     * 통과시키고, UNICODE_CHARACTER_CLASS가 켜지면 키릴 문자까지 통과해
     * 상관 ID 위조가 된다.
     */
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9-]{1,32}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        MDC.put(MDC_KEY, requestId(request.getHeader(HEADER)));
        try {
            chain.doFilter(request, response);
        } finally {
            // 톰캣이 스레드를 재사용한다. 안 지우면 다음 요청이 남의 id를 달고 찍힌다.
            MDC.remove(MDC_KEY);
        }
    }

    private String requestId(String fromHeader) {
        if (fromHeader != null && SAFE.matcher(fromHeader).matches()) {
            return fromHeader;
        }
        // 앞 8자만 쓰면 32비트뿐이라 약 7.7만 요청에서 50% 충돌한다. 동시 사용자
        // 100명 전제에서는 하루 안에 두 요청이 같은 id를 달고, 그러면 MDC.remove를
        // 빠뜨렸을 때와 똑같이 추적이 거짓말이 된다. 대시만 빼면 32자라 SAFE의
        // 길이 상한을 지키면서 128비트를 다 쓴다.
        return UUID.randomUUID().toString().replace("-", "");
    }
}
