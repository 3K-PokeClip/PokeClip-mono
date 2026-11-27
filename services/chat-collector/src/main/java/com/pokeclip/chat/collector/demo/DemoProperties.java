package com.pokeclip.chat.collector.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 발표 데모 카드를 넣을 clip 주소. 재부착 · 중계 · 플랫폼 알림과 같은 {@code CLIP_BASE_URL}을 받되 값은 따로 든다 — 중계가 꺼진 배포에서도
 * 카드는 나가야 한다(화면은 저장된 채팅을 5초마다 읽어 데모 채팅을 띄운다).
 *
 * @param clipBaseUrl 비면 카드를 안 보낸다({@link DemoConfiguration})
 */
@ConfigurationProperties(prefix = "pokeclip.demo")
public record DemoProperties(String clipBaseUrl) {
}
