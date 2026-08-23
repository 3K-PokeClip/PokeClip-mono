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
 *       {@code scheduleAtFixedRate}. <b>양쪽 끝이 다 부팅을 죽인다</b> — 0이면
 *       {@code IllegalArgumentException}, 아주 크면 {@code toMillis()} 자체가
 *       {@code ArithmeticException}이다(둘 다 실기동 실측). 그래서 <b>이 값에만 하한과 상한을
 *       모두 건다</b>({@code MAX_MILLIS} 주석에 timeout과 다른 이유가 있다)</li>
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

    /**
     * {@code toMillis()}로 잘려 쓰이는 값의 <b>상한</b>. 이 위는 {@code toMillis()}가
     * {@code ArithmeticException("long overflow")}을 던진다({@code Math.multiplyExact}).
     *
     * <p>🔴 <b>하한만으로는 부족하다는 것이 실기동으로 드러났다</b>(2026-08-24, PR #113 봇 지적 ④):
     * {@code heartbeat: PT2562047788016H}로 부팅하면 {@code CardStreamRegistry.startHeartbeat}에서
     * 던져 <b>컨텍스트가 통째로 죽는다</b>. 게다가 오류 메시지가 {@code SqsIntakeRunner}를 먼저
     * 부르므로({@code EndedListener}를 당기다 걸린다) 운영자가 <b>엉뚱한 데서 원인을 찾는다.</b>
     *
     * <p><b>경계</b>: 초 값이 {@code Long.MAX_VALUE / 1000 = 9,223,372,036,854,775}를 넘을 때다.
     * {@code PT2562047788015H}는 통과하고 {@code PT2562047788016H}가 막힌다(둘 다 시험에 있다).
     * 봇이 예로 든 {@code PT100000000000H}는 <b>오버플로하지 않아 부팅이 멀쩡히 된다</b> —
     * 그 값으로 시험을 쓰면 아무것도 안 재는 시험이 된다.
     *
     * <p>🔴 <b>{@code timeout}에는 이 상한을 안 건다. 「거기도 {@code toMillis()}인데 왜」의 답이
     * 여기 있다</b> — {@code timeout}은 설정값이 그대로 잘리지 않는다.
     * {@code JumpCardStreamController.timeoutFor}가 {@code min(토큰 exp까지, 설정값)}을 돌려주고,
     * JWT의 {@code exp}는 {@code Date}(ms)로 표현되므로 <b>남은 수명이 ms 범위를 넘길 수 없다.</b>
     * 같은 거대 값을 {@code timeout}에 주고 실기동해 확인했다 — 부팅되고
     * ({@code Started ClipApplication in 2.603 seconds}) 연결도 200으로 열린다.
     * exp가 30만 년·{@code Date} 최대·{@code toMillis} 경계인 토큰 셋으로도 전부 200이었다.
     * <b>{@code heartbeat}에는 그런 {@code min()}이 없다 — 설정값이 곧바로 잘린다.</b>
     * 「timeout처럼 하면 되겠지」로 읽지 마라.
     */
    private static final Duration MAX_MILLIS = Duration.ofMillis(Long.MAX_VALUE);

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
        if (heartbeat == null || heartbeat.compareTo(MIN_MILLIS) < 0
                || heartbeat.compareTo(MAX_MILLIS) > 0) {
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
