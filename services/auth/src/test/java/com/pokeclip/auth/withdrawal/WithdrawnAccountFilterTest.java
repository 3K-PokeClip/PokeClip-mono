package com.pokeclip.auth.withdrawal;

import com.pokeclip.auth.config.WithdrawnAccountFilter;
import com.pokeclip.auth.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 필터의 <b>통과 갈래</b>를 창구 없이 직접 잰다.
 *
 * <p><b>왜 따로 두나</b> — 통과 갈래 셋(주체 없음 · 주체가 JWT가 아님 · {@code sub}을 회원 번호로
 * 못 읽음)은 <b>HTTP로 만들 수 없다.</b> 우리 발급기는 {@code sub}에 항상 회원 번호를 넣고,
 * 서명 검증을 통과한 표만 여기까지 온다. 그래서 창구로 재려 하면 그 갈래가 <b>영영 안 밟히고</b>
 * 감싸기를 지워도 아무 검사가 안 깨진다(주입으로 확인했다 — 감싸기를 빼면 이 파일만 빨간불이다).
 *
 * <p>{@code TokenSubjectRejectionTest}가 재는 것은 <b>창구</b>의 같은 판정이고 필터는 안 탄다.
 * 「쌍둥이를 다 맞췄다」로 읽지 않도록 여기 따로 둔다(auth/CLAUDE.md 「알려진 구멍」 22).
 */
class WithdrawnAccountFilterTest {

    private final UserRepository users = mock(UserRepository.class);
    private final WithdrawnAccountFilter filter = new WithdrawnAccountFilter(users);

    @AfterEach
    void 컨텍스트를_비운다() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 인증이_없으면_표를_읽지도_않고_통과시킨다() throws Exception {
        MockFilterChain chain = doFilterWith(null);

        assertThat(chain.getRequest()).as("체인이 이어져야 한다").isNotNull();
        verify(users, never()).findDeletedAtById(any());
    }

    /** 내부 창구처럼 주체가 JWT가 아닌 인증이 오면 회원 번호를 읽을 근거가 없다. */
    @Test
    void 주체가_JWT가_아니면_표를_읽지도_않고_통과시킨다() throws Exception {
        MockFilterChain chain = doFilterWith(
                new UsernamePasswordAuthenticationToken("media-server", null, List.of()));

        assertThat(chain.getRequest()).isNotNull();
        verify(users, never()).findDeletedAtById(any());
    }

    /**
     * 🔴 감싸기를 빼면 {@code NumberFormatException}이 필터에서 그대로 올라가 <b>500</b>이 된다.
     * 401이 아니라 500이라는 것이 요점이다 — 인증 경로가 서버 오류를 내면 프론트가 재로그인이 아니라
     * 장애로 읽는다.
     */
    @Test
    void 주체를_회원_번호로_못_읽으면_표를_읽지도_않고_통과시킨다() throws Exception {
        MockFilterChain chain = doFilterWith(new JwtAuthenticationToken(jwtWithSubject("숫자가-아니다")));

        assertThat(chain.getRequest()).isNotNull();
        verify(users, never()).findDeletedAtById(any());
    }

    /** {@code sub}이 아예 없는 표도 같은 갈래다 — {@code Long.valueOf(null)}도 같은 예외를 던진다. */
    @Test
    void 주체가_비어_있어도_통과시킨다() throws Exception {
        MockFilterChain chain = doFilterWith(new JwtAuthenticationToken(jwtWithSubject(null)));

        assertThat(chain.getRequest()).isNotNull();
        verify(users, never()).findDeletedAtById(any());
    }

    /** 빈손은 「막을 이유가 없다」다 — 살아있는 회원과 없는 회원이 둘 다 여기로 온다. */
    @Test
    void 탈퇴_시각이_빈손이면_통과시킨다() throws Exception {
        given(users.findDeletedAtById(7L)).willReturn(Optional.empty());

        MockFilterChain chain = doFilterWith(new JwtAuthenticationToken(jwtWithSubject("7")));

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void 탈퇴_시각이_있으면_체인을_안_잇고_401을_낸다() throws Exception {
        given(users.findDeletedAtById(7L)).willReturn(Optional.of(Instant.parse("2026-08-31T12:00:00Z")));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwtWithSubject("7")));

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).as("막았으면 뒤가 안 돌아야 한다").isNull();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).as("사유를 실으면 탈퇴 사실이 샌다").isEmpty();
    }

    private MockFilterChain doFilterWith(Authentication authentication) throws Exception {
        if (authentication != null) {
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/auth/me"),
                new MockHttpServletResponse(), chain);
        return chain;
    }

    /** 서명 검증은 이미 끝난 뒤의 모양이다 — 필터는 값만 읽는다. */
    private static Jwt jwtWithSubject(String subject) {
        return Jwt.withTokenValue("검증은_이미_끝난_뒤다")
                .header("alg", "HS256")
                .subject(subject)
                .claim("sub", subject)
                .build();
    }
}
