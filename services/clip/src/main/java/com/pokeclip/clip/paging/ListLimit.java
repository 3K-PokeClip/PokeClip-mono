package com.pokeclip.clip.paging;

/**
 * 목록 문 <b>둘</b>이 나눠 쓰는 개수 규칙. 값(기본·상한)만 문마다 다르고 판정은 하나다.
 *
 * <p><b>왜 한 자리인가</b> — 두 문이 같은 칸 이름({@code limit})으로 같은 계약을 웹에 약속한다.
 * 각자 복사해 두면 한쪽의 판정을 고칠 때 다른 쪽이 조용히 갈리고, 그것이 이 저장소에서
 * <b>여섯 번</b> 난 「같은 뿌리인데 한 자리만 고침」이다. {@link InvalidListParamException}이
 * 이 패키지에 있는 것과 같은 이유다.
 *
 * <p>서비스가 부른다 — 컨트롤러가 아니다. 컨트롤러로 끌어오면 그 서비스를 직접 부르는 소비자가
 * 생기는 날 그 경로만 상한 없이 열린다({@code SegmentQueryService.MAX_RANGE_MS}와 같은 원칙).
 */
public final class ListLimit {

    /**
     * 안 주면 기본, 넘치면 깎고, <b>0 이하는 거절한다</b>.
     *
     * <p>0을 「기본으로 봐 주는」 길도 있었지만 안 골랐다 — 웹이 계산 실수로 0을 보낸 것과
     * 일부러 0장을 요구한 것이 구분이 안 되고, 조용히 기본값을 주면 그 실수가 안 드러난다.
     *
     * @param requested 웹이 준 값. {@code null}이면 「안 줬다」다 —
     *                  컨트롤러가 박스형으로 받는 이유가 「0을 줬다」와 가르기 위해서다
     * @throws InvalidListParamException 0 이하다 (400). {@code IllegalArgumentException}으로 두면
     *         전역 조언이 그 타입을 일부러 안 잡아 <b>500</b>이 된다(계획 검증 m1 실측)
     */
    public static int resolve(Integer requested, int 기본, int 상한) {
        if (requested == null) {
            return 기본;
        }
        if (requested <= 0) {
            throw new InvalidListParamException("limit");
        }
        return Math.min(requested, 상한);
    }

    private ListLimit() {
    }
}
