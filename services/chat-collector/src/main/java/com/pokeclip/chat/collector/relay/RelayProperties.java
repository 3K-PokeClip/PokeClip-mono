package com.pokeclip.chat.collector.relay;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 채팅 중계 설정(POK-234 PR-B).
 *
 * <p><b>{@code clipBaseUrl}을 {@code ReattachProperties}와 같은 환경변수({@code CLIP_BASE_URL})로 받되
 * record는 따로 둔다</b> — 재부착이 꺼진 배포에서도 중계는 켤 수 있어야 한다.
 * 내부 토큰은 여기 없다 — {@code LinkProperties.internalToken()} 하나를 서버 넷이 나눠 쓴다
 * (프로퍼티를 새로 만들면 같은 값을 두 곳에서 읽어 한쪽만 고쳐지는 날 갈라진다).
 *
 * <p>검증을 애노테이션이 아니라 {@link #validate()}로 둔 이유는 {@code ReattachProperties}와 같다 —
 * {@code @ConfigurationPropertiesScan}이 모든 컨텍스트에 올리므로, 걸면 중계를 안 쓰는 부팅까지 죽는다.
 *
 * @param bufferCapacity 수신 → 중계 바구니 상한(건). 넘치면 오래된 것부터 버리고 센다
 * @param flushMaxDelay  바구니가 비었을 때 다시 볼 주기 — 곧 한가할 때 추가되는 최대 지연이다.
 *                       환경변수로 안 뺐다(F13): 묶음은 보내는 동안 쌓인 것이 저절로 묶이므로
 *                       운영자가 만질 값이 아니다
 */
@ConfigurationProperties(prefix = "pokeclip.relay")
public record RelayProperties(boolean enabled, int bufferCapacity, Duration flushMaxDelay, String clipBaseUrl) {

    /** @throws IllegalStateException clip 주소가 비어 있으면 */
    public void validate() {
        if (clipBaseUrl == null || clipBaseUrl.isBlank()) {
            throw new IllegalStateException(
                    "pokeclip.relay.clip-base-url이(가) 비어 있다. CLIP_BASE_URL 환경변수를 준다.");
        }
    }
}
