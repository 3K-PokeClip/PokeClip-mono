package com.pokeclip.auth.config;

import com.pokeclip.auth.user.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 탈퇴한 회원의 남은 접근 표를 막는다(PRD D3).
 *
 * <p><b>왜 필요한가</b> — 접근 표는 서버가 저장하지 않아 회수할 수단이 없고 최대 30분 산다.
 * 그동안 이름·사진을 다시 채우고 스트림키를 새로 받고 채널을 재연동할 수 있어
 * <b>탈퇴가 사실상 되돌려진다.</b>
 *
 * <p>🔴 <b>「전면」은 auth 체인 안에서만이다.</b> clip은 표를 독립으로 검증하므로(ADR-049)
 * 그 30분 동안 clip 창구는 열려 있다. 그래도 목적은 지켜진다 — 그쪽엔 개인정보를 다시 채우는
 * 경로가 없다. 넓히려면 계약 변경이라 혼자 정하지 않는다(PRD 비목표).
 *
 * <p><b>인증된 요청만 본다.</b> 로그인 없이 부르는 경로(로그인·재발급·로그아웃·페어링 교환·
 * 사진 내보내기·오류·헬스체크)는 주체가 없어 그냥 지나간다.
 * 🔴 <b>페어링 교환은 여기서 못 막는다</b> — 코드 자체가 자격증명이라 로그인이 없다.
 * 그 경로는 태스크 4가 「살아있는 코드를 닫는 것」으로 막는다.
 *
 * <p>회원을 못 찾으면 통과시킨다. 「토큰의 주인이 없다」는 각 창구가 자기 사유로 이미 다룬다.
 *
 * <p>🔴 <b>빈으로 등록하지 않는다.</b> {@link SecurityConfig}가 {@code new}로 만들어 기본 체인에만
 * 끼운다({@link InternalTokenFilter} 선례). {@code @Component}를 붙이면 서블릿이 전역 등록해
 * 끼우는 자리가 명시가 아니라 등록 순서에 딸려 가고, 보안 체인 밖에서도 돈다.
 *
 * <p>다만 <b>「그러면 {@code /internal/**}이 막힌다」는 재현되지 않았다</b> — 실제로
 * {@code @Component}를 붙여 봤는데 내부 창구 검사가 초록이었다(2026-08-31 실측). 그 체인은 JWT 인증을
 * 아예 안 해 <b>주체가 없고</b>, 주체가 없으면 이 필터는 아무것도 안 하기 때문이다.
 * <b>규칙은 그대로 지킨다</b>(자리를 명시로 두는 값이 있다). 응답으로는 안 보이는 회귀라
 * {@code WithdrawnAccountBlockTest}가 <b>빈 목록을 직접 세어</b> 못박는다.
 */
public class WithdrawnAccountFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(WithdrawnAccountFilter.class);

    private final UserRepository users;

    public WithdrawnAccountFilter(UserRepository users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Long userId = authenticatedUserId();

        // 빈손이면 통과다 — 행이 없을 때와 값이 null일 때가 둘 다 빈손이고
        // 둘 다 「막을 이유가 없다」로 같다(UserRepository.findDeletedAtById의 계약).
        if (userId != null && users.findDeletedAtById(userId).isPresent()) {
            // 탈퇴 직후 30분은 화면이 아직 옛 표를 들고 있어 이 줄이 계속 온다 —
            // 장애가 아니라 정상 트래픽이라 INFO다. 응답에는 사유를 싣지 않는다.
            log.info("auth.withdrawn.blocked userId={}", userId);
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * 인증이 끝난 뒤에만 값을 준다. 주체가 없거나 {@code sub}을 회원 번호로 못 읽으면 {@code null}이고
     * 그때는 <b>막지 않는다</b> — 그 판정은 각 창구가 이미 자기 사유로 한다(auth/CLAUDE.md 「알려진 구멍」 22).
     * 여기서 401을 내면 그 아홉 자리가 어떻게 답하는지가 이 필터의 판단으로 덮인다.
     */
    private static Long authenticatedUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return null;
        }
        try {
            return Long.valueOf(jwt.getSubject());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
