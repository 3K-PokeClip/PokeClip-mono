package com.pokeclip.chat.collector.chzzk;

/**
 * 채팅 한 건. <b>시각은 messageTime(epoch ms)만 쓴다</b> —
 * eventSentAt은 오프셋 없는 KST라 UTC로 파싱하면 9시간 어긋나고 오류도 안 난다.
 *
 * <p>toString을 따로 두지 않는다. record 기본 toString이 content를 통째로 찍는데,
 * 로그에 실수로 객체를 넘기면 그대로 평문이 나간다. ChatLogLeakTest가 못박는다.
 */
public record ChatMessage(String senderChannelId, String content, long messageTimeMillis) { }
