package com.pokeclip.chat.collector.chzzk;

/**
 * 후원 한 건. <b>치지직 후원 이벤트에는 시각 칸이 없다</b>(공식 문서 확인) —
 * 시각은 받은 쪽(receivedAt)이 찍는다. 그래서 채팅(치지직 시계 `messageTime`)과
 * <b>축이 다르다</b>: 후원은 우리 기계 시계다.
 *
 * <p>toString을 두지 않는다 — 닉네임·후원 문구·raw가 전부 개인식별값이거나 본문이라
 * ChatMessage와 같은 규칙이다.
 *
 * @param payAmount 문서상 문자열("원")이라 숫자로 못 바꾸면 <b>null</b>이다.
 *                  칸이 없는 것과 못 읽은 것을 안 가른다 — 둘 다 「금액을 모른다」다
 * @param raw       치지직이 보낸 안쪽 JSON 문자열 그대로(이중 인코딩의 안쪽)
 */
public record DonationEvent(String channelId, String donatorChannelId, String donatorNickname,
                            String donationType, Long payAmount, String donationText, String raw) { }
