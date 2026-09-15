package com.pokeclip.clip.library;

import com.pokeclip.clip.paging.InvalidListParamException;

/**
 * 보관함이 편집본 하나에 붙이는 상태 넷. <b>편집 기록과 그 최신 영상을 합쳐 파생한 값</b>이지 어느 표의 칸도 아니다 —
 * 규칙은 {@link LibraryQuery}의 SQL 한 곳에 있고 여기는 이름(화면의 거르기 값)만 안다.
 *
 * <ul>
 *   <li>{@code editing} — 지금 판으로 만든 영상이 없다(한 번도 안 만들었거나, 만든 뒤 편집본을 또 고쳤다)</li>
 *   <li>{@code rendering} — 지금 판의 영상이 주문됐거나 만드는 중이다</li>
 *   <li>{@code rendered} — 지금 판의 영상이 완성됐다(화면의 「업로드 대기」)</li>
 *   <li>{@code failed} — 지금 판의 마지막 시도가 실패했다</li>
 * </ul>
 *
 * <p>업로드 상태(올리는 중·올림)는 POK-220이 더한다 — 그때까지 그 값은 400이다.
 */
public enum LibraryStatus {
    EDITING("editing"), RENDERING("rendering"), RENDERED("rendered"), FAILED("failed");

    private final String param;

    LibraryStatus(String param) {
        this.param = param;
    }

    /** 요청·응답·SQL이 같은 문자열을 쓴다. */
    public String param() {
        return param;
    }

    /**
     * 소문자 그대로 옮긴다({@code BroadcastState.fromParam}과 같은 이유 — 열거형을 그대로 받으면 대소문자로 400).
     *
     * @throws InvalidListParamException 모르는 값이다 (400, {@code field=status})
     */
    public static LibraryStatus fromParam(String value) {
        for (LibraryStatus status : values()) {
            if (status.param.equals(value)) {
                return status;
            }
        }
        throw new InvalidListParamException("status");
    }
}
