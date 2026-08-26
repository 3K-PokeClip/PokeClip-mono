package com.pokeclip.clip.jumpcard.stream;

import com.pokeclip.clip.delegation.BroadcastAccessGuard;
import com.pokeclip.clip.jumpcard.JumpCardErrors.TokenAlreadyExpiredException;
import com.pokeclip.clip.support.NotFoundFloor;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.time.Instant;

/** 계약 2B — 웹이 카드를 실시간으로 받는 문. */
@RestController
public class JumpCardStreamController {

    private static final Logger log = LoggerFactory.getLogger(JumpCardStreamController.class);

    /** 이 아래로 남은 토큰은 열지 않는다. SseEmitter가 받는 long ms로 자르면 0이 되기 때문이다. */
    private static final Duration MIN_LIFETIME = Duration.ofMillis(1);

    private final BroadcastAccessGuard guard;
    private final StreamOpener opener;
    private final StreamProperties properties;

    JumpCardStreamController(BroadcastAccessGuard guard, StreamOpener opener, StreamProperties properties) {
        this.guard = guard;
        this.opener = opener;
        this.properties = properties;
    }

    /**
     * <b>순서가 계약이다</b> — 기준 시각 → 토큰 수명 → 자격 판정 → (트랜잭션) 연결.
     *
     * <p>① <b>{@link NotFoundFloor#mark}가 맨 앞</b>이다. 이 문의 404는 갈래가 둘이고
     * (「없는 방송」은 명부 조회 하나, 「자격 없음」은 auth 왕복) 기준 시각이 <b>갈리기 전</b>에
     * 찍혀야 두 갈래가 같은 바닥에서 나간다. 갈림은 아래 {@code requireViewable} 안에서 시작한다.
     *
     * <p>② <b>토큰 수명이 자격 판정보다 앞</b>이다. 명백히 만료된 토큰을 <b>DB 조회와 auth
     * 왕복(최대 7초) 앞에서</b> 거르는 빠른 실패다 — 뒤로 옮기면 죽은 토큰이 auth를 두드린다.
     * 🔴 <b>이 값은 emitter에 안 걸린다.</b> 실제 시한은 자물쇠 <b>안에서</b> 다시 잰다
     * (아래 {@code timeoutFor} 참고).
     *
     * <p>③ <b>{@code requireViewable}이 방송 존재까지 판정한다.</b> 전에 여기 있던
     * {@code existsByStreamId}를 지운 이유가 그것이다 — 둘을 다 두면 「없는 방송」이 두 자리에서
     * 갈리고 응답이 달라질 수 있다.
     *
     * <p>④ <b>판정이 트랜잭션 밖이다.</b> auth 왕복 동안 커넥션을 쥐지 않으려는 것이고,
     * 그래서 트랜잭션은 {@link StreamOpener}가 연다 — 근거와 실측은 그 클래스에 있다.
     * {@code BroadcastListTransactionTest.통로도_auth_왕복_동안_커넥션을_안_쥔다}가 그 불변식을 잰다 —
     * 애너테이션이 아니라 <b>왕복 중 활성 커넥션 수</b>를 재므로 순서가 뒤집혀도 빨간불이 된다.
     */
    @GetMapping(value = "/api/clip/broadcasts/{streamId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> open(@PathVariable String streamId,
                                           @AuthenticationPrincipal Jwt jwt,
                                           @RequestHeader(value = "Last-Event-ID", required = false) String lastFromHeader,
                                           @RequestParam(value = "lastEventId", required = false) String lastFromQuery,
                                           HttpServletRequest request) {
        NotFoundFloor.mark(request);

        // 받아서 적기만 한다. 통로는 지난 카드를 안 보내므로(POK-174) 따라잡기는 목록 문이 맡는다 —
        // 마진 방식으로 바꾸는 날 쓰일 자리다(PRD 결정).
        String last = lastFromHeader != null ? lastFromHeader : lastFromQuery;
        if (last != null) {
            log.debug("jumpcard.stream.last_event_id streamId={} last={}", streamId, last);
        }

        timeoutFor(jwt);

        guard.requireViewable(jwt.getSubject(), streamId);

        // 상한 초과면 StreamLimitExceededException → 503.
        SseEmitter emitter = opener.open(streamId, jwt.getSubject(), () -> timeoutFor(jwt));

        return ResponseEntity.ok()
                // 앞단 프록시가 모아 보내면 "3초 내 도착"이 깨진다. 로컬엔 프록시가 없어 배포 후에만 난다.
                .header("X-Accel-Buffering", "no")
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .body(emitter);
    }

    /**
     * 연결 수명 = min(설정값, 토큰 {@code exp}까지). 만료 시점에 닫히고 브라우저가 새 토큰으로
     * 다시 붙는다. {@code exp}가 없는 토큰은 {@code JwtConfig}가 이미 401로 막았다
     * ({@code setAllowEmptyExpiryClaim(false)}).
     *
     * <p><b>두 번 부른다 — 입구에서 한 번, 자물쇠 안에서 한 번.</b> 값이 아니라 이 메서드를
     * {@code Supplier}로 넘기는 이유가 그것이다. 입구 것은 빠른 실패이고, emitter에 실제로
     * 걸리는 것은 자물쇠 안의 두 번째다. <b>한 번만 재면 그 사이(자물쇠 대기 + 스냅샷 조회)에
     * 만료된 토큰이 그대로 열린다</b> — PR #112 봇 지적 ④, 2026-08-23 재현: 자물쇠를 3초 쥐었더니
     * 만료 <b>2,508ms 뒤에</b> 200으로 열렸고 연결이 {@code exp}를 <b>3,398ms</b> 넘겨 살았다.
     *
     * <p>남은 수명이 1ms 미만이면 열지 않는다. 디코더의 clock skew 허용치(기본 60초) 안쪽
     * 토큰은 인증을 통과하는데, 그대로 열면 {@code SseEmitter}가 음수 시한을 받고 서블릿 규약상
     * {@code timeout <= 0}은 「시한 없음」이라 연결이 영영 산다 — 만료된 토큰일수록 오래 사는
     * 뒤집힌 결과가 된다(인가 2차 감사 실측). 하한을 두는 방식({@code max(untilExpiry, 최소값)})은
     * 만료 토큰으로 연 연결을 살려 주므로 쓰지 않는다.
     *
     * <p>🔴 기준이 「0 이하」가 아니라 {@code toMillis()}다. emitter 팩토리가 {@code Duration}을
     * long ms로 자르므로, {@code 0 < 남은수명 < 1ms}는 0도 음수도 아닌데 잘리면 0이 된다 —
     * 같은 「시한 없음」이다(PR #111 봇 지적 ④, 2026-08-23 재현: 실제로 시한 0짜리 emitter가 나왔다).
     * 진짜 토큰의 {@code exp}는 초 단위라(실측 nano=0) 이 창은 초 경계 직전 1ms 하나지만,
     * 닫혀 있지 않다. 자르는 쪽과 재는 쪽의 단위를 맞추는 것이 요점이다.
     *
     * <p>{@code untilExpiry.toMillis()}로 쓰지 않는다 — 아주 먼 {@code exp}에서 long을 넘겨
     * {@code ArithmeticException("long overflow")}이 되고, 401이어야 할 자리가 500이 된다
     * ({@code Instant.MAX}로 실측). {@code Duration}끼리 비교하면 그 자리가 없다.
     */
    private Duration timeoutFor(Jwt jwt) {
        Duration untilExpiry = Duration.between(Instant.now(), jwt.getExpiresAt());
        if (untilExpiry.compareTo(MIN_LIFETIME) < 0) {
            throw new TokenAlreadyExpiredException();
        }
        return untilExpiry.compareTo(properties.timeout()) < 0 ? untilExpiry : properties.timeout();
    }
}
