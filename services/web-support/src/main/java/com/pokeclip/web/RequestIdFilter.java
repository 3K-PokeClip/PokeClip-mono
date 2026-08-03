package com.pokeclip.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 요청 하나에서 나온 로그 줄을 잇는다.
 *
 * <p>auth·clip 어느 쪽에도 두지 않고 이 모듈에 두는 이유는 둘 다 쓰기 때문이다
 * (ADR-022로 프로세스가 갈렸다). 한쪽에 두고 다른 쪽이 복사해 가면 고칠 때
 * 한쪽만 고쳐진다. common에는 두지 않는다 — services/CLAUDE.md가 common에
 * 웹 계층을 금지한다.
 */
public class RequestIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

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
        } catch (Exception e) {
            // 여기가 상관 ID를 붙일 수 있는 마지막 자리다. 예외가 이 필터 밖으로
            // 나가면 톰캣이 스택트레이스를 찍는데, 그때는 아래 finally가 MDC를
            // 이미 비운 뒤다. 이어서 도는 ERROR 디스패치도 OncePerRequestFilter의
            // shouldNotFilterErrorDispatch()가 기본 true라 이 필터를 건너뛴다.
            // 두 경로 다 상관 ID가 없어, 500이야말로 추적이 끊기는 자리가 된다.
            //
            // 예외 메시지도 스택트레이스도 찍지 않는다 — AuthExceptionHandler가
            // causeType만 남기는 것과 같은 이유다. RestClientResponseException의
            // getMessage()에는 구글 응답 본문이 붙어 온다.
            //
            // 다시 던진다. 삼키면 500이 200이 되고, 이 로그는 그것대로 거짓이 된다.
            log.error("request.failed method={} uri={} causeType={}",
                    request.getMethod(), request.getRequestURI(), e.getClass().getSimpleName());
            throw e;
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
