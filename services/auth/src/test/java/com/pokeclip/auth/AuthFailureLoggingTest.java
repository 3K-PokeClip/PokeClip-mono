package com.pokeclip.auth;

import ch.qos.logback.classic.Level;
import com.pokeclip.auth.api.AuthExceptionHandler;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 핸들러만 직접 부른다. 스프링 컨텍스트가 필요 없고, 사유별 레벨이 이 클래스
 * 하나에 모여 있어 규칙이 바뀌면 여기만 본다.
 */
class AuthFailureLoggingTest {

    private final AuthExceptionHandler handler = new AuthExceptionHandler();

    @Test
    void 인증_실패는_WARN이고_사유가_남는다() {
        try (LogCaptor captor = new LogCaptor()) {
            handler.handle(new AuthException(AuthFailure.REFRESH_TOKEN_EXPIRED, "만료된 refresh 토큰이다"));

            assertThat(captor.levelOf("auth.failed")).isEqualTo(Level.WARN);
            assertThat(captor.messages()).anyMatch(m -> m.contains("REFRESH_TOKEN_EXPIRED"));
        }
    }

    /**
     * 유예 창 안의 중복 회전은 정상 동작이다 — SPA 두 탭이 동시에 만료를 감지하거나
     * 버튼을 두 번 누르면 나온다. 정상 트래픽으로 WARN을 채우면 알람 피로가 쌓인다.
     */
    @Test
    void 유예_창_안의_중복_회전은_INFO다() {
        try (LogCaptor captor = new LogCaptor()) {
            handler.handle(new AuthException(AuthFailure.REFRESH_TOKEN_ALREADY_ROTATED, "이미 회전된 refresh 토큰이다"));

            assertThat(captor.levelOf("auth.failed")).isEqualTo(Level.INFO);
        }
    }

    /** 응답은 401로 같고 로그만 다르다. 우리 DB가 어긋났다는 뜻이라 ERROR다. */
    @Test
    void 데이터_불일치는_ERROR이고_userId가_남는다() {
        try (LogCaptor captor = new LogCaptor()) {
            handler.handle(new DataInconsistencyException(
                    AuthFailure.USER_NOT_FOUND, "토큰의 주인이 없다", 42L));

            assertThat(captor.levelOf("auth.failed")).isEqualTo(Level.ERROR);
            assertThat(captor.messages()).anyMatch(m -> m.contains("userId=42"));
        }
    }

    @Test
    void 데이터_불일치도_응답은_401이다() {
        assertThat(handler.handle(new DataInconsistencyException(
                AuthFailure.USER_NOT_FOUND, "토큰의 주인이 없다", 42L))
                .getStatusCode().value()).isEqualTo(401);
    }

    /** 사유는 로그에만 있고 본문에는 없다. 이유를 알려주면 공격자에게 단서가 된다. */
    @Test
    void 응답_본문은_사유를_알려주지_않는다() {
        assertThat(handler.handle(new AuthException(AuthFailure.REFRESH_TOKEN_REUSED,
                "유예 창을 넘겨 재사용된 refresh 토큰이다 — 이 사용자의 세션을 전부 끊었다"))
                .getBody())
                .containsExactly(java.util.Map.entry("message", "인증에 실패했습니다"));
    }
}
