package com.pokeclip.chat.collector.status;

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
 * clip이 자기를 증명하는 유일한 수단. <b>auth의 {@code config/InternalTokenFilter}와 쌍둥이다</b> —
 * 한쪽을 고치면 나란히 놓고 비교한다. {@code common/}은 계약만 두는 곳이고 {@code web-support}는
 * CORS 주소가 비면 부팅이 죽어 둘 다 못 올렸다.
 *
 * <p>비교에 {@code String.equals}를 쓰지 않는다(응답 시간으로 한 글자씩 맞춰 볼 수 있다).
 * 실패 로그를 남기지 않는다(미인증 트래픽이 로그를 무한 생성한다).
 *
 * <p><b>auth와 다른 갈래 하나 — 설정 토큰이 비어 있으면 무조건 401.</b> 이 서버는 편지 경로가 꺼진
 * 프로세스(CI·팀원 로컬)가 토큰 없이 떠야 해서 부팅을 못 막는다. 그런데
 * {@code MessageDigest.isEqual("", "")}은 true라, 이 갈래가 없으면 빈 헤더로 창구가 열린다.
 */
public class InternalTokenFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Internal-Token";

    private final byte[] expected;
    private final boolean locked;

    public InternalTokenFilter(String token) {
        this.expected = token == null ? new byte[0] : token.getBytes(StandardCharsets.UTF_8);
        this.locked = expected.length == 0;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        if (locked || presented == null
                || !MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), expected)) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            return;
        }
        chain.doFilter(request, response);
    }
}
