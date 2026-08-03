package com.pokeclip.auth;

/**
 * 서명은 통과했는데 토큰의 주인이 DB에 없다. 인증 실패가 아니라 데이터 불일치다.
 *
 * <p><b>그래도 AuthException을 상속한다</b> — 응답이 401로 같으니 같은 계층이 맞고,
 * 다른 것은 로그뿐이다. 상속은 의미 분류가 아니라 <b>응답 매핑을 물려받기 위한
 * 것</b>이다. 401이 아니게 되는 날 상속을 끊는다.
 *
 * <p>401인 이유: 상태 코드는 "무슨 상황이냐"가 아니라 "클라이언트가 뭘 해야 하냐"를
 * 알린다. 여기서 필요한 것은 재인증이고, 다시 로그인하면 자동 가입으로 해결되므로
 * 재로그인 루프가 되지 않는다. 500은 "기다려라"인데 기다려도 안 고쳐지고,
 * 알람이 울리는 코드라 DB를 날린 개발자 때문에 알람 피로가 쌓인다.
 *
 * <p>재검토 조건: 탈퇴 기능이 생길 때.
 */
public class DataInconsistencyException extends AuthException {

    private final Long userId;

    public DataInconsistencyException(AuthFailure failure, String message, Long userId) {
        super(failure, message);
        this.userId = userId;
    }

    public Long userId() {
        return userId;
    }
}
