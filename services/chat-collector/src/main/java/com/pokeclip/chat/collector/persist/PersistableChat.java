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
        long receivedAtMillis) {

    public PersistableChat {
        // PG TEXT가 거부하는 것은 사실상 NUL뿐 — 제거로 포이즌을 원천 소멸시키고
        // 채팅은 보존한다(2026-08-15 사용자 결정). 저장 직전(toRow)이 아니라 생성
        // 지점인 이유: 지문 해시와 저장이 <b>같은 정규화된 본문</b>을 써야 중복
        // 판정이 안 갈린다 — 여기 한 곳이면 그 불변식이 구조로 지켜진다.
        content = content.replace("\0", "");
    }
}
