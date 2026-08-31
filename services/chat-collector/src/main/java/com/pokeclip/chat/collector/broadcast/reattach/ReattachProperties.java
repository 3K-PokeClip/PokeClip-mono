package com.pokeclip.chat.collector.broadcast.reattach;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 재부착 설정 — clip 주소·켜기·주기·첫 지연 <b>넷뿐이다</b>.
 *
 * <p>🔴 <b>내부 토큰은 여기 없다</b>(계획 검증 C4). {@code LinkProperties.internalToken()}이
 * 갖는다 — 서버 넷이 공유하는 비밀 하나이고, 프로퍼티를 새로 만들면 같은 값을 두 곳에서
 * 읽게 되어 한쪽만 고쳐지면 갈라진다. 이 서버에 이미 그 관례가 있다:
 * {@code status/InternalApiConfiguration}이 수집 상태 창구의 토큰을 같은 자리에서 가져오고
 * <b>그 창구도 auth를 안 부른다</b>. {@code docker-compose.dev.yml}도
 * {@code INTERNAL_API_TOKEN} 하나만 넘긴다.
 *
 * <p><b>{@code clipBaseUrl}의 기본값이 빈 문자열이다</b>({@code ${VAR:}}). 기본값을 아예 안
 * 주면 리터럴 {@code "${CLIP_BASE_URL}"}이 그대로 바인딩돼 <b>서버는 뜨고 헬스체크도 통과하는데
 * 재부착만 매 회차 실패</b>한다({@code services/CLAUDE.md}의 규칙).
 *
 * <p><b>검증을 {@code @NotBlank}로 걸지 않고 {@link #validate()}로 둔 이유</b>: 이 record는
 * {@code @ConfigurationPropertiesScan}이 모든 컨텍스트에 올린다. 애노테이션으로 걸면
 * <b>재부착을 안 쓰는 부팅까지</b> 전부 죽는다({@code LinkProperties}·{@code ChzzkProperties}와
 * 같은 이유). 대신 {@link LiveBroadcastClient} 생성자가 이것을 부르므로,
 * <b>그 클라이언트를 실제로 만드는 부팅은 반드시 죽는다.</b>
 */
@ConfigurationProperties(prefix = "pokeclip.reattach")
public record ReattachProperties(String clipBaseUrl, boolean enabled,
                                 Duration interval, Duration initialDelay) {

    /** @throws IllegalStateException clip 주소가 비어 있으면 */
    public void validate() {
        if (clipBaseUrl == null || clipBaseUrl.isBlank()) {
            throw new IllegalStateException(
                    "pokeclip.reattach.clip-base-url이(가) 비어 있다. CLIP_BASE_URL 환경변수를 준다.");
        }
    }
}
