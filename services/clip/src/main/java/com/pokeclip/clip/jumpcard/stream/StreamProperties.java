package com.pokeclip.clip.jumpcard.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 실시간 통로 설정. <b>전부 기본값이 있어 yml에 한 줄도 없어도 부팅한다</b> — 비밀이 아니라
 * 숫자 설정이다.
 *
 * <p><b>{@code sendTimeout}이 없다.</b> 막힌 {@code send}를 밖에서 끊는 장치는 작동하지 않는다
 * (2026-08-23 실측) — {@code send()}와 {@code completeWithError()}가 같은 {@code writeLock}을 써서
 * 끊으러 간 스레드가 같이 멈춘다. 막힌 연결은 스트라이프 격리로 가두고 서버의 write timeout이 푼다.
 *
 * <p><b>이 클래스는 잘못된 값을 던지지 않고 덮는다.</b> {@code JumpCardProperties}·
 * {@code IntakeProperties}는 던진다 — 불일치가 아니라 클래스마다 이미 서 있던 정책을 따른
 * 결과다. 통일은 별도 카드다.
 *
 * <p>🔴 <b>{@code Duration} 설정을 새로 추가하는 사람이 읽을 것.</b> 이 서버에는 설정을
 * {@code Duration}으로 받아 놓고 <b>정수로 잘라서</b> 쓰는 자리가 셋 있고, 검증을 자르기
 * <b>전</b> 값에만 걸면 셋 다 뚫린다. 세 자리가 <b>서로 다른 방향으로</b> 망가지므로
 * 「하나 고쳤으니 됐다」가 성립하지 않는다(PR #111에서 실제로 둘만 닫고 하나를 놓쳤다):
 * <ul>
 *   <li>{@code timeout} → {@code toMillis()} → {@code SseEmitter}. 0이면 「시한 없음」이라
 *       <b>연결이 안 죽는다</b>. 증상이 없다</li>
 *   <li>{@code heartbeat} → {@code toMillis()} → {@code CardStreamRegistry.startHeartbeat}의
 *       {@code scheduleAtFixedRate}. 0이면 {@code IllegalArgumentException}이라
 *       <b>부팅이 죽는다</b>(실측)</li>
 *   <li>{@code JumpCardProperties.claimTtl} → {@code toSeconds()} → 점유 SQL.
 *       0이면 <b>모든 점유가 즉시 탈취 가능</b>해진다</li>
 * </ul>
 * <b>자르는 쪽과 재는 쪽의 단위를 맞춘다</b> — 여기 둘은 1ms 하한, {@code claimTtl}은
 * 초 단위로 떨어질 것을 요구한다.
 */
@ConfigurationProperties(prefix = "pokeclip.jump-card.stream")
public record StreamProperties(Duration heartbeat, Duration timeout, int stripes, int queueCapacity,
                               int maxPerUser, int maxPerStream, int maxTotal) {

    private static final Logger log = LoggerFactory.getLogger(StreamProperties.class);

    private static final Duration DEFAULT_TIMEOUT = Duration.ofHours(4);

    private static final Duration DEFAULT_HEARTBEAT = Duration.ofSeconds(20);

    /**
     * {@code toMillis()}로 잘려 쓰이는 값의 하한. <b>1ms 미만은 잘리면 0이 되고, 0이 되는
     * 순간 두 소비자가 서로 다르게 망가진다</b> — {@code timeout}은 연결이 안 죽고
     * {@code heartbeat}는 부팅이 죽는다(클래스 주석의 세 자리 참고). 그래서 하한이 1ms다.
     *
     * <p>비교를 {@code compareTo}로 한다 — {@code toMillis()}로 재면 아주 큰 값에서 long을
     * 넘겨({@code ArithmeticException}) 바인딩이 엉뚱한 이유로 죽는다.
     */
    private static final Duration MIN_MILLIS = Duration.ofMillis(1);

    public StreamProperties {
        // 🔴 heartbeat도 toMillis()로 잘려 scheduleAtFixedRate에 들어간다
        // (CardStreamRegistry.startHeartbeat). PT0.0005S는 0도 음수도 아니라 「0 이하」 가드를
        // 지나는데, 잘리면 period=0이 되어 IllegalArgumentException으로 <b>부팅이 죽는다</b>(실측).
        // 이 클래스는 「덮는다」고 선언해 놓고 실제로는 다른 클래스에서 터지고 있었다 —
        // 운영자가 원인을 엉뚱한 데서 찾는다. 아래 timeout과 같은 하한을 쓴다.
        //
        // WARN을 안 남기는 것은 의도다. 증상이 없는 것만 알린다(아래 timeout 주석) —
        // heartbeat가 틀리면 프록시가 조용한 연결을 끊어 드러나고, 1ms 미만이면 애초에
        // 부팅이 죽어 더 시끄럽다.
        if (heartbeat == null || heartbeat.compareTo(MIN_MILLIS) < 0) {
            heartbeat = DEFAULT_HEARTBEAT;
        }
        // 🔴 「0 이하」로는 부족하다. SseEmitter가 받는 것은 long ms이고 toMillis()가 자른다 —
        // PT0.0005S는 0도 음수도 아닌데 잘리면 0이 되어 똑같이 「시한 없음」이 된다.
        // 2026-08-23 재현(PR #111 봇 지적 ④): 이 값으로 부팅해 진짜 HTTP로 연결하니 20초 뒤에도
        // 안 닫혔고 하트비트를 20개 받았다(설정 시한의 4만 배). PT2S를 주면 2995ms에 닫힌다.
        // 자르기 전 값만 보던 것이 원인이고, 위 heartbeat·JumpCardProperties의 toSeconds()와
        // 뿌리가 같다(클래스 주석의 세 자리).
        if (timeout == null || timeout.compareTo(MIN_MILLIS) < 0) {
            // 🔴 이 하나만 로그를 남긴다. 대칭을 깬 것이 아니라 결과의 종류가 다르다 —
            // stripes·큐·상한 셋이 틀리면 느려지거나 거부가 늘어 운영자가 겪어서 알고,
            // heartbeat가 틀리면 프록시가 연결을 끊거나 부팅이 죽어 역시 드러난다. timeout만
            // 인증 경계가 아무 증상 없이 무너진다(만료 직전 토큰으로 연 연결이 영원히 산다).
            // 증상이 없는 것만 알린다 — 나머지까지 찍으면 진짜 신호가 묻힌다.
            if (timeout != null) {
                log.warn("pokeclip.jump-card.stream.timeout={} 는 1ms 미만이라 기본값 {} 로 덮었다. "
                        + "1ms 미만은 SseEmitter에서 「시한 없음」이 되어 인증 연결이 무한히 산다.",
                        timeout, DEFAULT_TIMEOUT);
            }
            timeout = DEFAULT_TIMEOUT;
        }
        if (stripes <= 0) {
            stripes = 4;
        }
        if (queueCapacity <= 0) {
            queueCapacity = 1000;
        }
        if (maxPerUser <= 0) {
            maxPerUser = 4;
        }
        if (maxPerStream <= 0) {
            maxPerStream = 50;
        }
        if (maxTotal <= 0) {
            maxTotal = 500;
        }
    }
}
