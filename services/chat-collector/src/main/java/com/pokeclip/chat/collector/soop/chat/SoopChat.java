package com.pokeclip.chat.collector.soop.chat;

/**
 * SOOP 채팅 받기의 입구(POK-397 PR4). SOOP 방송 지켜보기가 부른다. 꺼져 있으면 {@link #NONE}이라 부르는 쪽은 켜짐을 모른다
 * ({@code SoopWatch}와 같은 꼴). 두 메서드 다 넣고 빼기만 한다(브라우저 일은 브라우저 스레드가 한다, 던지지 않는다).
 */
public interface SoopChat {

    /**
     * 이 방송의 채팅을 받는다. 이미 받고 있으면 토큰만 새것으로 둔다(다음에 다시 붙을 때 쓴다).
     *
     * @param channelId SOOP 아이디(표의 채널 칸)
     * @param token     스트리머의 SOOP 접근 토큰. 🔴 로그에 안 싣는다
     */
    void ensure(String streamId, String channelId, String token);

    /** 이 방송의 채팅 받기를 멈춘다. 없으면 무시한다. */
    void close(String streamId);

    SoopChat NONE = new SoopChat() {
        @Override
        public void ensure(String streamId, String channelId, String token) {
        }

        @Override
        public void close(String streamId) {
        }
    };
}
