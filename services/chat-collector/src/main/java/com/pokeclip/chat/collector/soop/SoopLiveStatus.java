package com.pokeclip.chat.collector.soop;

/**
 * SOOP 방송 여부 체크 한 번의 결과(POK-397).
 *
 * @param outcome  방송 중 · 방송 아님 · 거부(토큰이 안 먹힌다) · 일시(다음 틱에 다시)
 * @param title    방송 중일 때만. 응답에 없으면 null
 * @param category 늘 null이다. SOOP은 카테고리를 번호({@code broad_cate_no})로만 주고 이름 표가 없다({@link SoopLiveClient})
 * @param viewers  방송 중일 때만. 응답에 없거나 못 읽으면 null(0이 아니다)
 */
public record SoopLiveStatus(Outcome outcome, String title, String category, Integer viewers) {

    public enum Outcome { ON_AIR, OFF_AIR, REJECTED, UNAVAILABLE }

    static SoopLiveStatus of(Outcome outcome) {
        return new SoopLiveStatus(outcome, null, null, null);
    }
}
