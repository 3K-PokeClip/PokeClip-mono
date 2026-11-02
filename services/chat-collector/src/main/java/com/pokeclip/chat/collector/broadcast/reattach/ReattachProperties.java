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
                                 Duration interval, Duration initialDelay,
                                 /**
                                  * 떼기(POK-244). 켜면 같은 회차에서 <b>내가 붙어 있는데 clip 명부에 없는 방송</b>의
                                  * 세션을 반납한다 — clip이 놓친 방송을 닫으면 그 다음 회차에 자리가 돌아온다.
                                  */
                                 boolean detachEnabled,
                                 /**
                                  * 방송 시작 뒤 이만큼은 명부에 없어도 안 뗀다. 같은 편지를 clip과 각자 받으므로
                                  * 우리가 먼저 붙고 clip이 아직 안 적은 순간이 있다 — 그 창에서 떼면 방금 붙은
                                  * 세션을 우리 손으로 끊는다.
                                  */
                                 Duration detachGrace) {

    /** @throws IllegalStateException clip 주소가 비어 있으면 · 떼기가 켜졌는데 유예가 없거나 0 이하면 */
    public void validate() {
        if (clipBaseUrl == null || clipBaseUrl.isBlank()) {
            throw new IllegalStateException(
                    "pokeclip.reattach.clip-base-url이(가) 비어 있다. CLIP_BASE_URL 환경변수를 준다.");
        }
        if (detachEnabled && (detachGrace == null || detachGrace.isZero() || detachGrace.isNegative())) {
            throw new IllegalStateException(
                    "pokeclip.reattach.detach-enabled=true인데 detach-grace가 양수가 아니다(" + detachGrace
                    + "). 0이면 clip이 아직 안 적은 방금 붙은 세션까지 뗀다. CHAT_DETACH_GRACE를 준다.");
        }
    }

    /** 떼기 유예. 꺼져 있으면 null — {@code Reattacher}가 null을 「떼지 않는다」로 읽는다. */
    public Duration detachGraceOrNull() {
        return detachEnabled ? detachGrace : null;
    }
}
