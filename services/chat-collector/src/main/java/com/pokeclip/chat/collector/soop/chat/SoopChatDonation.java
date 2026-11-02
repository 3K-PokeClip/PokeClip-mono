package com.pokeclip.chat.collector.soop.chat;

/**
 * 페이지가 넘긴 SOOP 후원 한 건(POK-431). 받는 종류는 별풍선 · 애드벌룬 · 영상풍선 · 스티커 · 대결 미션 · 도전 미션 여섯이고 그 밖은 페이지가
 * 버린다({@link SoopChatPage}).
 *
 * <p>칸 이름(count · fromVod · fromStation · relaysBroad)은 SDK 문서에서 읽은 것이라 실방송에서 그 이름으로 오는지 확인해야 한다.
 * 불리언은 페이지가 글로 바꿔 넘기므로 {@code "true"}일 때만 참이다(문자열 {@code "true"}로 와도 같다).
 *
 * @param action 후원 종류(SDK action 이름). 표의 {@code donation_type}에 그대로 간다
 * @param count  개수. 숫자로 못 읽으면 null(후원은 버리지 않는다)
 */
public record SoopChatDonation(String action, String userId, String nickname, Long count, boolean fromVod,
                               boolean fromStation, boolean relaysBroad) {

    /** 페이지가 넘긴 글들에서 만든다. */
    static SoopChatDonation of(String action, String userId, String nickname, String count, String fromVod,
                               String fromStation, String relaysBroad) {
        return new SoopChatDonation(action, userId, nickname, parseCount(count), "true".equals(fromVod),
                "true".equals(fromStation), "true".equals(relaysBroad));
    }

    private static Long parseCount(String count) {
        try {
            return Long.valueOf(count.trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 아이디 · 닉네임이 로그에 안 새게 종류만. */
    @Override
    public String toString() {
        return "SoopChatDonation[action=" + action + "]";
    }
}
