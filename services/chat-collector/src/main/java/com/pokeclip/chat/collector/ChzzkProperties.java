package com.pokeclip.chat.collector;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * <p><b>{@code enabled}는 「옛 경로」의 스위치다</b>(POK-127). 편지 없이 <b>설정에 적힌 토큰
 * 하나로 한 채널만</b> 붙이는 길이고, 실측·디버깅용으로 남겨 뒀다 — 운영은 방송 편지로
 * 붙는다({@code pokeclip.broadcast.intake}). <b>둘을 같이 켜면 부팅이 거부된다</b>:
 * 여기서 연 세션이 영구 정지하면 {@code CollectorRunner}가 프로세스를 내려 편지로 연 세션
 * 전부를 끊는다({@code IntakeConfiguration.LetterPath} 생성자에 이유가 있다).
 *
 * <p><b>기본값이 false다.</b> 켜져 있으면 CI·테스트·남의 로컬이 뜰 때마다
 * 치지직에 붙으려 하고, 연결 상한 3개(Access Token 기준)를 말없이 먹는다.
 * 그러면 정작 실측할 때 막히는데 원인이 어디에도 안 보인다.
 * 실측은 프로파일 local에서만 켠다.
 *
 * <p><b>accessToken이 @NotBlank를 통과해도 만료됐을 수 있다.</b> 그때는 부팅이
 * 성공하고 연결만 실패한다 — 그래서 실패 사유를 로그와 health 양쪽에 남긴다.
 */
@ConfigurationProperties(prefix = "pokeclip.chzzk")
@Validated
public record ChzzkProperties(
        boolean enabled,
        String accessToken,
        @NotBlank String baseUrl,
        Duration establishTimeout,
        // 안 주면 null로 바인딩되고 아무도 안 읽는 동안은 조용하다. 재연결 루프가
        // 읽는 날 NPE로 죽는데, 그때 원인은 "설정에 값이 없다"가 아니라
        // "왜 여기서 NPE가 나지"로 보인다. 부팅에서 잡는다.
        @NotNull Duration reconnectFirstDelay,
        @NotNull Duration reconnectMaxDelay
) {

    /**
     * 토큰 검증을 <b>켜져 있을 때만</b> 건다.
     *
     * <p>{@code @NotBlank}를 그냥 붙이면 {@code enabled=false}에서도 걸려
     * <b>기본 설정으로는 서버가 아예 못 뜬다.</b> 기본값을 false로 둔 이유가
     * "CI·남의 로컬이 뜰 때마다 치지직에 붙는 것"을 막으려는 것인데, 그러면
     * 그들이 부팅조차 못 한다 — 실제로 그렇게 됐다.
     *
     * <p><b>{@code services/CLAUDE.md}의 "{@code ${VAR:}} + 검증" 규칙은 그대로 지켜진다.</b>
     * 그 규칙의 목적은 "서버는 뜨고 그 기능만 조용히 실패하는 것"을 막는 것이고,
     * <b>켜놓고 토큰이 비면 여기서 여전히 부팅이 죽는다.</b> 꺼져 있을 때는
     * 실패할 기능 자체가 없으므로 규칙이 겨누는 상황이 아니다.
     *
     * <p>메시지에 값을 넣지 않는다 — 검증 실패 메시지는 부팅 로그에 그대로 찍힌다.
     */
    @AssertTrue(message = "pokeclip.chzzk.enabled=true인데 access-token이 비어 있다")
    public boolean isAccessTokenPresentWhenEnabled() {
        return !enabled || (accessToken != null && !accessToken.isBlank());
    }

    /**
     * 재연결 간격은 <b>1밀리초 이상이어야 한다.</b>
     *
     * <p>{@code ReconnectPolicy.delayFor}는 곱셈이라 <b>0은 몇 번을 두 배 해도 0</b>이고,
     * 대기는 {@code CountDownLatch.await(0, MILLISECONDS)}라 즉시 돌아온다. 음수는
     * 그보다 더 빠르다. 그러면 재시도가 간격 없이 세션 발급 API를 두들기고,
     * <b>재시도가 스스로 자리를 태워 영영 못 붙는다</b> — 이 서버가 없애려던 실패다.
     *
     * <p>부팅에서 안 잡으면 <b>서버는 뜨고 헬스체크도 통과하는데 재연결만 폭주한다.</b>
     * {@code services/CLAUDE.md}가 "{@code ${VAR:}} + 검증"을 정한 이유와 같은 모양이다.
     *
     * <p>밀리초로 재는 이유는 대기가 밀리초 단위이기 때문이다 — 500마이크로초는
     * 값이 있어도 대기가 0이라 위와 똑같이 폭주한다.
     *
     * <p>{@code null}은 여기서 참으로 넘긴다. 그쪽은 {@code @NotNull}이 잡고,
     * 여기서 같이 잡으면 값이 없는 것과 값이 잘못된 것이 한 줄로 뭉친다.
     *
     * <p><b>상한에는 같은 검사를 두지 않는다 — 아래 순서 검사가 이미 덮는다.</b>
     * 첫 간격이 1ms 이상이고 첫 간격이 상한 이하면 상한도 1ms 이상이다. 상한만
     * 0이나 음수인 설정은 <b>반드시</b> 첫 간격보다 작아 순서 검사에서 죽는다.
     * 따로 달아 봤지만 어떤 설정으로도 그것 <b>혼자</b> 발화시킬 수 없어 —
     * 즉 지울 때 빨간불이 되는 검사가 없어 — 뺐다.
     */
    @AssertTrue(message = "pokeclip.chzzk.reconnect-first-delay는 1ms 이상이어야 한다")
    public boolean isReconnectFirstDelayPositive() {
        return reconnectFirstDelay == null || reconnectFirstDelay.toMillis() >= 1;
    }

    /**
     * 첫 간격이 상한보다 크면 <b>첫 간격이 조용히 버려진다.</b>
     *
     * <p>{@code delayFor}가 마지막에 상한으로 자르므로 그 설정은 시도마다
     * <b>상한 하나만</b> 돌려준다 — 설정 파일은 "첫 10초"라고 적혀 있는데 동작은
     * "언제나 1초"다. 상한이 작으면 그대로 폭주로 이어지고, 크더라도 적어 둔 것과
     * 도는 것이 다르면 <b>재연결이 왜 이 간격으로 도는지를 설정에서 읽을 수 없다.</b>
     */
    @AssertTrue(message = "pokeclip.chzzk.reconnect-first-delay가 reconnect-max-delay보다 크다")
    public boolean isReconnectDelayRangeOrdered() {
        return reconnectFirstDelay == null || reconnectMaxDelay == null
                || reconnectFirstDelay.compareTo(reconnectMaxDelay) <= 0;
    }
}
