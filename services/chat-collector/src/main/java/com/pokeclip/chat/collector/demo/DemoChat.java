package com.pokeclip.chat.collector.demo;

import com.pokeclip.chat.collector.Platform;

/**
 * 발표 데모용 임시 채팅 한 건. 실제 플랫폼에서 온 것이 아니다({@link DemoChatScript}).
 *
 * @param messageTimeMillis 채팅이 찍힌 시각. 급증은 넣는 순간이고, 평소 채팅은 지난 시각이다
 */
record DemoChat(Platform platform, String senderId, String nickname, String text, long messageTimeMillis) {
}
