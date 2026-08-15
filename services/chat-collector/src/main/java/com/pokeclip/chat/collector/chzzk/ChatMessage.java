package com.pokeclip.chat.collector.chzzk;

/**
 * 채팅 한 건. <b>시각은 messageTime(epoch ms)만 쓴다</b> —
 * eventSentAt은 오프셋 없는 KST라 UTC로 파싱하면 9시간 어긋나고 오류도 안 난다.
 *
 * <p>{@code raw}는 치지직이 보낸 안쪽 JSON 문자열 <b>그대로</b>다(이중 인코딩의 안쪽,
 * 이스케이프가 풀린 텍스트). S3 아카이브(POK-116)가 이것을 손대지 않고 쌓는다 —
 * 닉네임·이모티콘·배지처럼 여기서 안 뽑는 필드가 기준값 산출에 쓰인다.
 * <b>로그에 넘기지 마라</b> — content와 같은 규칙이다.
 *
 * <p>toString을 따로 두지 않는다. record 기본 toString이 content·raw를 통째로 찍는데,
 * 로그에 실수로 객체를 넘기면 그대로 평문이 나간다. ChatLogLeakTest가 못박는다.
 */
public record ChatMessage(String channelId, String senderChannelId,
                          String content, long messageTimeMillis, String raw) { }
