package com.pokeclip.clip.paging;

/**
 * 목록 문 둘(방송·보관함)이 같이 쓰는 요청 칸 해석. 개수는 {@link ListLimit}에 따로 있다.
 *
 * <p>{@link ListLimit}과 같은 이유로 한 자리다: 두 문이 같은 칸 이름({@code streamerId})으로 같은 계약을 웹에 약속한다.
 */
public final class ListParams {

    /**
     * 채널 스위처(POK-390)가 고른 스트리머 번호. <b>빈 값·공백만은 「안 줬다」</b>다: {@code cursor}·{@code status}의
     * 빈 문자열을 「안 줬다」로 접는 것과 같은 규칙이다. 선행 0은 숫자로 읽혀 같은 번호가 된다(auth 답이 숫자다).
     *
     * @return {@code null}이면 좁히지 않는다(섞어서 전부)
     * @throws InvalidListParamException 숫자가 아니거나 long을 넘는다 (400). auth 왕복 전에 부른다
     */
    public static Long streamerIdOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new InvalidListParamException("streamerId");
        }
    }

    private ListParams() {
    }
}
