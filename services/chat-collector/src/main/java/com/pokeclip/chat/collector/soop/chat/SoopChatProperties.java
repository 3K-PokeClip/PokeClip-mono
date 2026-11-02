package com.pokeclip.chat.collector.soop.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * SOOP 채팅 받기 설정(POK-397 PR4). 검증은 {@link #validate()}로 둔다({@code SoopWatchProperties}와 같은 이유: 애노테이션으로 걸면
 * 채팅을 안 켠 부팅까지 죽는다).
 *
 * @param enabled        켜면 SOOP 방송 지켜보기가 방송 중으로 본 방송마다 화면 없는 브라우저 탭에서 공식 Chat SDK로 채팅을 받는다. 기본 꺼짐
 * @param clientId       SOOP 앱 키. auth와 같은 값. 🔴 비밀값과 함께 SDK 생성자에 들어가 페이지 안에만 산다
 * @param clientSecret   🔴 비밀. SDK가 생성자에서 받는다(공식 문서 예시). 로그·예외 메시지에 안 싣는다
 * @param sdkUrl         SDK 스크립트 주소. 운영은 공식 주소, 시험은 가짜 SDK를 내리는 로컬 주소
 * @param pageOrigin     탭이 여는 빈 페이지의 출처. SDK가 출처를 보는지 문서에 없어 흔한 개발 출처(http://localhost)로 둔다
 * @param maxSessions    동시에 여는 탭 상한. Chromium 탭 하나가 수십 MB라 메모리 상한과 같이 본다
 * @param reconnectMin   끊긴 뒤 다시 붙는 첫 간격. 이어서 끊기면 두 배씩
 * @param reconnectMax   다시 붙는 간격 상한
 */
@ConfigurationProperties(prefix = "pokeclip.soop.chat")
public record SoopChatProperties(boolean enabled, String clientId, String clientSecret, String sdkUrl, String pageOrigin,
                                 int maxSessions, Duration reconnectMin, Duration reconnectMax) {

    /** @throws IllegalStateException 켜졌는데 앱 값 · 주소가 비었거나 상한 · 간격이 말이 안 되면. 값은 메시지에 안 싣는다 */
    public void validate() {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalStateException("pokeclip.soop.chat.enabled=true인데 SOOP_CLIENT_ID · SOOP_CLIENT_SECRET이 비어 있다.");
        }
        if (sdkUrl == null || sdkUrl.isBlank() || pageOrigin == null || pageOrigin.isBlank()) {
            throw new IllegalStateException("pokeclip.soop.chat.sdk-url · page-origin이 비어 있다.");
        }
        if (maxSessions < 1) {
            throw new IllegalStateException("pokeclip.soop.chat.max-sessions는 1 이상이어야 한다.");
        }
        if (reconnectMin == null || reconnectMax == null || reconnectMin.isNegative() || reconnectMin.isZero()
                || reconnectMax.compareTo(reconnectMin) < 0) {
            throw new IllegalStateException("pokeclip.soop.chat.reconnect-min · reconnect-max가 맞지 않다(0보다 크고 min ≤ max).");
        }
    }
}
