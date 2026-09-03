package com.pokeclip.chat.collector.persist;

/**
 * 표에 넣을 후원 한 건. {@code PersistableChat}과 같은 규칙으로 toString을 두지 않는다 —
 * 닉네임·후원 문구가 개인식별값이자 본문이다.
 *
 * <p><b>시각 칸이 하나뿐이다.</b> 치지직 후원 이벤트에는 시각이 없어서(공식 문서 확인)
 * 우리가 받은 시각만 남는다 — 채팅({@code message_time}, 치지직 시계)과 <b>축이 다르다</b>.
 *
 * @param payAmount    숫자로 못 읽으면 null. 그래도 후원 자체는 버리지 않는다
 * @param donationText 없으면 빈 문자열이다(표가 NOT NULL) — 「문구 없는 후원」이 정상이다
 */
public record PersistableDonation(
        String streamId,
        String channelId,
        String donatorChannelId,
        String donatorNickname,
        String donationType,
        Long payAmount,
        String donationText,
        long receivedAtMillis) {

    public PersistableDonation {
        // PG TEXT가 거부하는 것은 사실상 NUL뿐이다. 채팅과 같은 자리(생성 지점)에서 지운다 —
        // 후원에는 지문 해시가 없으므로 「해시와 저장이 같은 본문」이라는 이유는 없지만,
        // 한 글자 때문에 배치 전체가 22021로 죽는 것은 똑같다.
        donatorNickname = donatorNickname == null ? null : donatorNickname.replace("\0", "");
        donationText = donationText == null ? "" : donationText.replace("\0", "");
    }
}
