package com.pokeclip.auth.api;

import com.pokeclip.auth.AuthException;
import com.pokeclip.auth.AuthFailure;
import com.pokeclip.auth.AuthService;
import com.pokeclip.auth.api.dto.GoogleLoginRequest;
import com.pokeclip.auth.api.dto.MeResponse;
import com.pokeclip.auth.api.dto.RefreshRequest;
import com.pokeclip.auth.api.dto.TokenResponse;
import com.pokeclip.auth.api.dto.UpdateNameRequest;
import com.pokeclip.auth.profile.PhotoUrls;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final TokenService tokenService;
    private final PhotoUrls photoUrls;

    @PostMapping("/google")
    public TokenResponse loginWithGoogle(@Valid @RequestBody GoogleLoginRequest request) {
        return TokenResponse.from(authService.loginWithGoogle(request.code()));
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        User user = authService.me(userId(jwt));
        return MeResponse.from(user, photoUrls.of(user, Instant.now()));
    }

    /**
     * 회원 번호를 본문으로 받지 않는다 — 토큰의 주인만 자기 것을 고친다.
     * 사진은 모양이 달라(파일) 별도 창구가 된다.
     */
    @PatchMapping("/me")
    public MeResponse updateName(@AuthenticationPrincipal Jwt jwt, @RequestBody UpdateNameRequest request) {
        User user = authService.updateName(userId(jwt), request.name());
        return MeResponse.from(user, photoUrls.of(user, Instant.now()));
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
            throw new AuthException(AuthFailure.ACCESS_TOKEN_SUBJECT_INVALID, "토큰의 주체를 읽을 수 없다", e);
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
