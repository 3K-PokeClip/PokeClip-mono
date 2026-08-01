package com.pokeclip.core.auth.token;

import com.pokeclip.core.auth.AuthException;
import com.pokeclip.core.auth.AuthFailure;
import com.pokeclip.core.auth.DataInconsistencyException;
import com.pokeclip.core.auth.user.User;
import com.pokeclip.core.auth.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class TokenService {

    private static final String ISSUER = "pokeclip-auth";

    /**
     * 이 시간 안에 회전된 토큰이 다시 오면 중복 요청으로 본다. 탈취 재사용은
     * 이보다 훨씬 늦게 오므로, PRD가 요구하는 "재사용 시 전 세션 무효화"는
     * 그대로 지켜진다.
     */
    private static final Duration REUSE_GRACE = Duration.ofSeconds(10);

    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final SecureRandom random = new SecureRandom();

    @Transactional
    public TokenPair issue(User user) {
        Instant now = Instant.now();
        String refresh = randomToken();

        refreshTokenRepository.save(RefreshToken.of(
                user.getId(), hash(refresh),
                now.plus(jwtProperties.refreshTokenTtl()), now));

        return new TokenPair(accessToken(user.getId(), now), refresh);
    }

    /**
     * refresh를 회전한다. 실패는 전부 AuthException이다.
     *
     * <p><b>이 메서드는 트랜잭션의 최상단이어야 한다.</b> 호출하는 쪽에
     * {@code @Transactional}을 붙이면 안 된다. noRollbackFor는 자기가 물리
     * 트랜잭션을 열었을 때만 커밋을 만든다 — 상위 트랜잭션에 참여하면 예외가
     * 상위 인터셉터로 올라가고 거기 기본 롤백 규칙이 적용돼, 재사용 감지의
     * 무효화가 조용히 사라진다. 테스트는 AuthException이 나는지만 보므로 통과한다.
     *
     * <p>noRollbackFor가 필요한 이유는 재사용 감지다. revokeAllOfUser는 반드시
     * 커밋돼야 한다 — 탈취를 감지했으니 세션을 끊는 것이 이 경로의 목적이고,
     * 예외는 호출자에게 401을 주기 위한 신호일 뿐이다. AuthException이
     * RuntimeException이라 기본 규칙으로는 그 무효화가 함께 롤백된다.
     *
     * <p>쓰기가 일어난 뒤 던지는 경로는 재사용 감지와 "토큰의 주인이 없다" 둘이다.
     * 둘 다 커밋되는 것이 맞다 — 후자는 이미 무효화한 토큰을 되살리지 않는 쪽이 안전하다.
     */
    @Transactional(noRollbackFor = AuthException.class)
    public TokenPair rotate(String refreshToken) {
        String tokenHash = hash(refreshToken);

        Long userId = refreshTokenRepository.findUserIdByTokenHash(tokenHash)
                .orElseThrow(() -> new AuthException(AuthFailure.REFRESH_TOKEN_UNKNOWN, "모르는 refresh 토큰이다"));

        // 같은 사용자의 회전을 직렬화한다. 이 락이 없으면 재사용 감지가 도는 사이
        // 다른 요청이 발급한 토큰을 놓친다 — PostgreSQL READ COMMITTED에서 UPDATE는
        // 문장 시작 이후 커밋된 행을 대상 집합에 넣지 않기 때문이다. 잠글 토큰 행이
        // 아직 없으므로 사용자 행을 잠근다.
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new DataInconsistencyException(
                        AuthFailure.USER_NOT_FOUND, "토큰의 주인이 없다", userId));

        RefreshToken stored = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new AuthException(AuthFailure.REFRESH_TOKEN_UNKNOWN, "모르는 refresh 토큰이다"));

        if (stored.getRevokedAt() != null) {
            // 방금 회전된 토큰이면 같은 클라이언트의 중복 요청으로 본다. SPA의 두 탭이
            // 동시에 access 만료를 감지하거나, 타임아웃 후 자동 재시도하거나, 버튼을
            // 두 번 누르면 여기 온다. 위의 사용자 행 락이 이 요청들을 직렬화하므로,
            // 진 쪽은 이긴 쪽이 회전을 끝낸 직후에 반드시 이 자리로 온다.
            //
            // 이것을 탈취로 보고 세션을 끊으면 정상 사용자가 전 기기에서 로그아웃되고,
            // 이긴 쪽이 방금 받은 새 토큰까지 죽는다. 유예 창을 넘긴 재사용만 탈취로 다룬다.
            //
            // 대가를 알고 택했다: 탈취범이 정상 회전 후 10초 안에 재사용하면 감지를
            // 피한다(토큰을 얻지는 못한다). 10초는 탭 간 경합에는 넉넉하고 공격자가
            // 노리기에는 짧다. 그 대가로 정상 사용자가 로그아웃되는 것을 막는다.
            if (stored.getRevokedAt().isAfter(Instant.now().minus(REUSE_GRACE))) {
                throw new AuthException(AuthFailure.REFRESH_TOKEN_ALREADY_ROTATED, "이미 회전된 refresh 토큰이다");
            }

            // 오래전에 쓴 토큰이 다시 왔다. 탈취를 의심하고 이 사용자의 세션을 전부 끊는다.
            refreshTokenRepository.revokeAllOfUser(stored.getUserId(), Instant.now());
            throw new AuthException(AuthFailure.REFRESH_TOKEN_REUSED, "이미 사용된 refresh 토큰이다");
        }

        if (stored.getExpiresAt().isBefore(Instant.now())) {
            throw new AuthException(AuthFailure.REFRESH_TOKEN_EXPIRED, "만료된 refresh 토큰이다");
        }

        if (refreshTokenRepository.revokeIfAlive(stored.getId(), Instant.now()) == 0) {
            // 사용자 행 락이 회전을 직렬화하므로 여기까지 오려면 락 밖에서 도는 것이
            // 끼어들어야 한다 — 지금은 logout뿐이다. 회전끼리 경합한 경우는 위의
            // revokedAt 검사에서 걸러진다.
            throw new AuthException(AuthFailure.REFRESH_TOKEN_ALREADY_USED, "이미 사용된 refresh 토큰이다");
        }

        // issue를 프록시 없이 직접 부르는 것은 의도다. 같은 트랜잭션에 묶어야
        // 무효화와 재발급이 한 원자 단위가 된다. 프록시를 거치게 '고치면'
        // 옛 토큰은 죽었는데 새 토큰은 없는 창이 생긴다.
        return issue(user);
    }

    @Transactional
    public void logout(String refreshToken) {
        refreshTokenRepository.findByTokenHash(hash(refreshToken))
                .ifPresent(token -> refreshTokenRepository.revokeIfAlive(token.getId(), Instant.now()));
    }

    private String accessToken(Long userId, Instant now) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiresAt(now.plus(jwtProperties.accessTokenTtl()))
                .build();

        return jwtEncoder.encode(
                JwtEncoderParameters.from(JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없다", e);
        }
    }
}
