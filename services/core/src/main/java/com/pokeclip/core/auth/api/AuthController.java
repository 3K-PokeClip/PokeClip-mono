package com.pokeclip.core.auth.api;

import com.pokeclip.core.auth.AuthException;
import com.pokeclip.core.auth.api.dto.GoogleLoginRequest;
import com.pokeclip.core.auth.api.dto.MeResponse;
import com.pokeclip.core.auth.api.dto.RefreshRequest;
import com.pokeclip.core.auth.api.dto.TokenResponse;
import com.pokeclip.core.auth.token.TokenService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final TokenService tokenService;

    @PostMapping("/google")
    public TokenResponse loginWithGoogle(@Valid @RequestBody GoogleLoginRequest request) {
        return TokenResponse.from(authService.loginWithGoogle(request.code()));
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        return MeResponse.from(authService.me(userId(jwt)));
    }

    /**
     * 우리 발급기는 sub에 항상 사용자 id를 넣으므로 여기서 실패할 수 없다.
     * 그래도 잡는 이유는 인증 경로이기 때문이다 — 토큰을 못 읽으면 500이 아니라
     * 401이 맞다. 서명 검증은 이미 통과한 뒤라 위조로는 도달하지 못한다.
     */
    private Long userId(Jwt jwt) {
        try {
            return Long.valueOf(jwt.getSubject());
        } catch (NumberFormatException e) {
            throw new AuthException("토큰의 주체를 읽을 수 없다", e);
        }
    }

    /**
     * refresh 토큰은 본문으로만 받는다. 쿼리스트링에 실으면 접근 로그·프록시·
     * 브라우저 히스토리에 그대로 남는다.
     */
    @PostMapping("/refresh")
    public TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return TokenResponse.from(tokenService.rotate(request.refreshToken()));
    }

    /** 없는 토큰으로 불러도 204다. 존재 여부를 알려주지 않는다. */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest request) {
        tokenService.logout(request.refreshToken());
    }
}
