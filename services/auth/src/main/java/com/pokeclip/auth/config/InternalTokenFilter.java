package com.pokeclip.auth.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Media(Go)가 자기를 증명하는 유일한 수단. 이 필터가 지키는 엔드포인트는
 * passphrase를 내려주므로, 뚫리면 전체 스트리머의 송출 경로가 열린다.
 *
 * <p>비교에 String.equals를 쓰지 않는다. 앞자리가 다르면 즉시 끝나므로 응답
 * 시간으로 한 글자씩 맞춰볼 수 있다. MessageDigest.isEqual은 길이가 같으면
 * 전체를 훑는다.
 *
 * <p>실패 로그를 남기지 않는다. 이 경로는 인증 없이 호출할 수 있어, 찍으면
 * 미인증 트래픽이 로그를 무한 생성한다(/api/auth/refresh와 같은 함정).
 */
public class InternalTokenFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Internal-Token";

    private final byte[] expected;

    public InternalTokenFilter(String token) {
        this.expected = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String presented = request.getHeader(HEADER);

        if (presented == null
                || !MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expected)) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            return;
        }
        chain.doFilter(request, response);
    }
}
