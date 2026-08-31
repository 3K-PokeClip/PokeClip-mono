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
 * <p><b>재검토했다 — 401을 유지한다</b>(POK-171, 2026-08-31). 「탈퇴 기능이 생길 때」가 재검토 조건이었고
 * 도달했다. 탈퇴는 회원 행을 <b>지우지 않고 익명화</b>하므로 이 예외가 나는 상태(서명은 통과했는데 주인이
 * 표에 없다)를 새로 만들지 않는다 — 탈퇴한 계정은 {@code WithdrawnAccountFilter}가 앞에서 401로 막고,
 * 행이 정말 없는 것은 여전히 「DB를 날렸다」쪽이다. 클라이언트가 할 일도 그대로 재인증 하나뿐이라
 * 상태 코드를 가를 근거가 이 카드에서 나오지 않았다.
 *
 * <p>다음 재검토 조건: <b>탈퇴가 회원 행을 실제로 지우게 되는 날</b>(보관 기한이 끝난 뒤 물리 삭제를
 * 붙이는 카드). 그때는 이 예외가 평시에 나기 시작하므로 로그 레벨(ERROR)부터 다시 본다 —
 * 지운 계정의 늦은 요청마다 알람이 울린다.
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
