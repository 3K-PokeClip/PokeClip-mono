package com.pokeclip.auth;

import com.pokeclip.auth.google.GoogleIdTokenVerifier;
import com.pokeclip.auth.google.GoogleTokenClient;
import com.pokeclip.auth.google.GoogleUser;
import com.pokeclip.auth.token.TokenPair;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 이 클래스에 @Transactional을 붙이지 않는다. 붙으면 TokenService.rotate가
 * 참여 트랜잭션이 되고, 거기 걸린 noRollbackFor가 무력화돼 재사용 감지의
 * 무효화가 롤백된다. 그래도 테스트는 통과한다 — 테스트는 AuthException이
 * 던져지는 것만 보기 때문이다.
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final GoogleTokenClient googleTokenClient;
    private final GoogleIdTokenVerifier googleIdTokenVerifier;
    private final UserService userService;
    private final UserRepository userRepository;
    private final TokenService tokenService;

    public TokenPair loginWithGoogle(String code) {
        String idToken = googleTokenClient.exchangeCodeForIdToken(code);
        GoogleUser googleUser = googleIdTokenVerifier.verify(idToken);

        User user = userService.findOrCreate(
                googleUser.sub(), googleUser.email(),
                googleUser.name(), googleUser.profileImageUrl());

        TokenPair tokens = tokenService.issue(user);
        log.info("auth.login.success userId={}", user.getId());
        return tokens;
    }

    /**
     * access 토큰의 sub만 믿고 DB에서 다시 읽는다. 토큰에 담긴 프로필을 그대로
     * 돌려주지 않는 이유는, 토큰이 30분 살아 있는 동안 DB 쪽이 바뀔 수 있어서다.
     */
    public User me(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new DataInconsistencyException(
                        AuthFailure.USER_NOT_FOUND, "토큰의 주인이 없다", userId));
    }
}
