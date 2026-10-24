package com.pokeclip.chat.collector.soop.chat;

/**
 * SDK가 준 채팅 한 줄(POK-397 PR4). SOOP은 시각 · 메시지 ID를 안 준다(공식 문서 「채팅 메세지 조회」). 그래서 시각은 받은 쪽이 붙인다.
 *
 * <p>🔴 {@link #toString()}을 덮는다. 기본 toString은 본문 · 아이디 · 닉네임을 싣는다(치지직 {@code ChatMessage}와 같은 규칙).
 *
 * @param action   {@code MESSAGE} 또는 {@code MANAGER_MESSAGE}
 * @param userId   보낸 사람 SOOP 아이디
 * @param nickname 보낸 사람 닉네임
 * @param text     채팅 본문
 * @param raw      SDK가 준 메시지 객체를 그대로 JSON으로(원본 아카이브)
 */
public record SoopChatMessage(String action, String userId, String nickname, String text, String raw) {

    @Override
    public String toString() {
        return "SoopChatMessage[action=" + action + "]";
    }
}
