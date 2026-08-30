package com.pokeclip.auth.withdrawal.api;

import com.pokeclip.auth.AuthException;
import com.pokeclip.auth.AuthFailure;
import com.pokeclip.auth.withdrawal.WithdrawalService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 탈퇴 창구. 회원 정보 조회·수정({@code /api/auth/me})과 주소가 같고 메서드만 다르다 —
 * 지우는 대상이 「내 계정」이라 주소가 그것을 그대로 말한다.
 *
 * <p><b>클래스를 따로 둔 이유</b>: {@code AuthController}는 로그인 조립부(구글 교환·토큰 회전)에
 * 묶여 있고 탈퇴는 {@code withdrawal/}의 자기 완결 기능이다. 다음 태스크가 회수·정리를 더할 때
 * 그 코드가 로그인 창구 옆에 쌓이면 두 기능이 한 파일에서 얽힌다.
 *
 * <p>🔴 <b>이 클래스에 {@code @Transactional}을 붙이지 않는다.</b>
 * {@code WithdrawalService.withdraw}가 트랜잭션의 최상단이어야 한다.
 */
@RestController
@RequiredArgsConstructor
public class WithdrawalController {

    private final WithdrawalService withdrawalService;

    /**
     * 회원 번호를 받지 않는다 — 표의 주인만 자기 것을 지운다.
     *
     * <p><b>두 번째 호출은 여기까지 오지 않는다</b> — 전면 차단 필터가 401로 막는다.
     * 서비스 자체는 멱등이라 필터를 우회해 직접 불러도 안전하다. 둘은 겹치는 방어다.
     * <b>「없는 것을 지워도 204다」가 아니다</b> — 두 번째 요청이 받는 답은 401이고,
     * 토큰의 주인이 표에 없으면 그것도 401이다.
     */
    @DeleteMapping("/api/auth/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void withdraw(@AuthenticationPrincipal Jwt jwt) {
        withdrawalService.withdraw(userId(jwt));
    }

    /**
     * {@code AuthController.userId}·{@code ProfilePhotoController.userId}와 <b>같은 모양</b>으로 감싼다 —
     * 한쪽만 감싸면 같은 입력이 한쪽에서는 401이고 다른 쪽에서는 500이 된다.
     * {@code TokenSubjectRejectionTest}가 셋을 나란히 잰다.
     *
     * <p>오늘은 닿지 않는다 — 우리 발급기는 {@code sub}에 항상 회원 번호를 넣고 서명 검증을 통과한
     * 토큰만 여기까지 온다. <b>아무도 안 밟기 때문에 더 갈라지기 쉬운 자리다.</b>
     *
     * <p>🔴 <b>이 모양인 것은 열 중 셋뿐이다.</b> 나머지 일곱은 auth/CLAUDE.md 「알려진 구멍」 22에
     * 전수로 적혀 있다 — 새로 만드는 자리는 감싼 쪽에 넣는다는 것이 그 항목의 지시다.
     */
    private static Long userId(Jwt jwt) {
        try {
            return Long.valueOf(jwt.getSubject());
        } catch (NumberFormatException e) {
            throw new AuthException(AuthFailure.ACCESS_TOKEN_SUBJECT_INVALID, "토큰의 주체를 읽을 수 없다", e);
        }
    }
}
