package com.pokeclip.chat.collector.soop;

/**
 * SOOP 방송 여부 체크 한 번의 결과(POK-397).
 *
 * @param outcome  방송 중 · 방송 아님 · 거부(토큰이 안 먹힌다) · 일시(다음 틱에 다시)
 * @param title    방송 중일 때만. 응답에 없으면 null
 * @param category 방송 중일 때만. SOOP은 번호({@code broad_cate_no})로만 주고 카테고리 리스트로 이름을 찾는다({@link SoopCategoryNames}).
 *                 표가 없거나 표에 없는 번호면 null
 * @param viewers  방송 중일 때만. 응답에 없거나 못 읽으면 null(0이 아니다)
 * @param categoryImageUrl 카테고리 사진. SOOP은 사진을 안 줘서 같은 이름의 치지직 카테고리 사진을 빌린다({@link BorrowedCategoryImages}, POK-462).
 *                         카테고리가 없거나 못 빌리면 null
 */
public record SoopLiveStatus(Outcome outcome, String title, String category, Integer viewers, String categoryImageUrl) {

    public enum Outcome { ON_AIR, OFF_AIR, REJECTED, UNAVAILABLE }

    public SoopLiveStatus(Outcome outcome, String title, String category, Integer viewers) {
        this(outcome, title, category, viewers, null);
    }

    static SoopLiveStatus of(Outcome outcome) {
        return new SoopLiveStatus(outcome, null, null, null);
    }
}
