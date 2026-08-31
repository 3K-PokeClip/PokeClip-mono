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
 * 사진 내보내기·오류·헬스체크)는 <b>보통</b> 주체가 없어 그냥 지나간다.
 * 🔴 <b>다만 그 경로에 표를 실어 보내면 주체가 생겨 막힌다</b>(plan-critic 필터 회귀 감사 실측, 2026-08-31: 탈퇴 회원 +
 * Authorization 헤더로 재발급·로그아웃이 401. 헤더가 없으면 각각 200·204).
 * {@code BearerTokenAuthenticationFilter}는 {@code permitAll} 경로에서도 헤더가 있으면 인증을 끝낸다.
 * 실질 무해하다 — 탈퇴가 갱신 표를 전부 폐기하므로 어느 쪽이든 실패한다.
 * 하지만 <b>「permitAll이면 안 걸린다」는 틀린 문장이다.</b>
 * 🔴 <b>페어링 교환은 여기서 못 막는다</b> — 코드 자체가 자격증명이라 로그인이 없다.
 * 그 경로는 태스크 4가 「살아있는 코드를 닫는 것」으로 막는다.
 *
 * <p>회원을 못 찾으면 통과시킨다. 「토큰의 주인이 없다」는 각 창구가 자기 사유로 이미 다룬다.
 *
 * <p>🔴 <b>빈으로 등록하지 않는다.</b> {@link SecurityConfig}가 {@code new}로 만들어 기본 체인에만
 * 끼운다({@link InternalTokenFilter} 선례). {@code @Component}를 붙이면 서블릿이 전역 등록해
 * 끼우는 자리가 명시가 아니라 등록 순서에 딸려 가고, 보안 체인 밖에서도 돈다.
 *
 * <p>🔴 <b>「그러면 {@code /internal/**}이 막힌다」는 재현되지 않았다.</b> 그 체인은 JWT 인증을
 * 아예 안 해 주체가 없고, 주체가 없으면 이 필터는 막지 않는다. <b>참인 것은 거기까지다.</b>
 * <b>「아무 일도 안 일어난다」는 거짓이다</b> — {@code @Component}를 붙여 전후를 세니
 * <b>필터 진입 15 → 23 · 표 조회 13 → 19 · 인스턴스 1 → 2</b>였다(plan-critic 필터 회귀 감사, 2026-08-31).
 * 숫자가 맞아떨어진다: 막히지 않고 통과한 8회가 서블릿 체인에서 한 번 더 돌고, 그중 주체가 있는
 * 6회가 회원 표를 한 번 더 조회한다. <b>막힌 요청은 첫 필터에서 끝나 두 번째를 안 탄다.</b>
 * 두 인스턴스가 둘 다 도는 이유는 {@code OncePerRequestFilter}의 중복 방지 표식이
 * <b>인스턴스가 아니라 이름 기준</b>이기 때문이다 — 빈 쪽은 빈 이름, {@code new} 쪽은 클래스 이름이라
 * 서로를 못 알아본다.
 * <b>규칙은 그대로 지킨다</b>(자리를 명시로 두는 값이 있다). <b>응답으로는 안 보이는 회귀라</b>
 * {@code WithdrawnAccountBlockTest}가 <b>빈 목록을 직접 세어</b> 못박는다.
 *
 * <p><b>이 필터는 인증된 요청마다 표 조회를 하나 더 만든다. 그런데 커넥션 압력은 안 늘었다</b> —
 * 풀 10·동시 25인 해제 갈래를 필터 유무로 재니 75~78ms 대 73~78ms, 피크는 양쪽 10으로 구분되지 않았다
 * (구현자·plan-critic 필터 회귀 감사가 각각 3회씩 독립 측정).
 * 🔴 <b>이유는 {@code open-in-view}가 아니다</b>(그 설명은 그 회귀 감사가 반증했다 — {@code true}로 켜고
 * 재도 필터 진입 시점에 바인딩된 자원이 비어 있다. OSIV는 {@code DispatcherServlet} 안 인터셉터고
 * <b>필터는 그 앞</b>이라 켜져 있어도 못 쓴다). 진짜 이유는 리포지토리 호출이 <b>자기 짧은 읽기
 * 트랜잭션에서 끝나고 커넥션을 그 자리에서 반납</b>하는 것이고, 그것은 {@code open-in-view} 값과 무관하다.
 * <b>이 측정을 무효로 만드는 변경은 셋이다</b>: ① 🔴 이 필터를 {@code HandlerInterceptor}나 AOP로
 * 옮기는 날(그 자리는 OSIV보다 뒤라 요청의 EntityManager에 합류한다) ② 필터의 조회를 창구 트랜잭션
 * 안으로 넣는 변경 ③ 필터에 조회가 늘거나 외부 호출이 생기는 변경.
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
     * 그때는 <b>막지 않는다</b> — 그 판정은 각 창구가 이미 자기 사유로 한다.
     * 여기서 401을 내면 나머지 자리가 어떻게 답하는지가 이 필터의 판단으로 덮인다.
     *
     * <p>🔴 <b>이 줄도 전수 명부의 한 자리다</b> — auth에서 {@code Long.valueOf(jwt.getSubject())}를
     * 하는 자리 중 하나이고, <b>감싸는 모양이 창구 쪽과 반대</b>다(그쪽은 던져서 401,
     * 여기는 {@code null}로 통과). <b>여기에 개수를 적지 않는다</b> — 숫자는 명부 한 줄에만 둔다. 명부는 {@code ProfilePhotoController.userId} javadoc에 있고
     * {@code TokenSubjectRegistryTest}가 기계로 센다 — <b>POK-171이 이 자리를 만들고도
     * 한 커밋 뒤에 자기를 못 세어</b> 그 검사가 생겼다.
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
