package com.pokeclip.chat.collector.persist;

/**
 * 표에 넣을 채팅 한 건. toString을 두지 않는다 — record 기본 toString이
 * content를 통째로 찍는데, 로그에 실수로 넘기면 평문이 나간다(ChatMessage와 같은 규칙).
 */
public record PersistableChat(
        String channelId,
        String senderChannelId,
        String content,
        long messageTimeMillis,
        long receivedAtMillis) { }
