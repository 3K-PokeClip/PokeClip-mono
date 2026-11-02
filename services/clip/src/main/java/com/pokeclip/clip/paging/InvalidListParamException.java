package com.pokeclip.clip.paging;

/**
 * 400. 목록 문의 요청 칸 값이 우리가 받는 범위 밖이다({@code state}·{@code limit}).
 *
 * <p><b>{@code IllegalArgumentException}으로 두면 500이 된다</b> — 전역 조언이 그 타입을
 * 일부러 안 잡으므로 {@code ServletException}으로 빠진다(계획 검증 m1 실측).
 *
 * <p>어느 칸인지를 싣는다. <b>여기엔 감출 것이 없다</b> — 감추는 것은 404 쪽이고, 이쪽은
 * 웹이 고칠 수 있는 자기 요청의 문제다. 값 자체는 안 싣는다(자유 입력을 그대로 되돌려주지 않는다).
 *
 * <p>{@code paging}에 두는 이유 — 목록 문 <b>둘</b>이 같은 칸 규칙을 쓴다. 한쪽 패키지에 두면
 * 다른 쪽이 남의 패키지를 참조하고, 셋째 목록이 생길 때 자리가 또 문제가 된다.
 */
public class InvalidListParamException extends RuntimeException {

    private final String field;

    public InvalidListParamException(String field) {
        super("목록 요청 칸이 잘못됐다: " + field);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
