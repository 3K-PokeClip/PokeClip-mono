package com.pokeclip.clip.jumpcard.stream;

import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.broadcast.BroadcastStatus;
import com.pokeclip.clip.jumpcard.JumpCardErrors.BroadcastNotFoundException;
import com.pokeclip.clip.jumpcard.JumpCardErrors.TokenAlreadyExpiredException;
import com.pokeclip.clip.jumpcard.JumpCardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
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

    private final BroadcastRepository broadcasts;
    private final JumpCardService service;
    private final CardStreamRegistry registry;
    private final StreamProperties properties;

    JumpCardStreamController(BroadcastRepository broadcasts, JumpCardService service,
                             CardStreamRegistry registry, StreamProperties properties) {
        this.broadcasts = broadcasts;
        this.service = service;
        this.registry = registry;
        this.properties = properties;
    }

    /**
     * <b>{@code @Transactional}은 커넥션 <u>획득</u>을 자물쇠 밖으로 빼려고 붙었다.</b>
     *
     * <p>스냅샷 조회는 {@code openWithSnapshot}의 자물쇠 <b>안</b>에서 돈다(그래야 「읽은 뒤 ~
     * 명부에 오르기 전」 창이 안 열린다). 트랜잭션이 없으면 그 조회가 <b>자물쇠 안에서</b>
     * 커넥션을 새로 얻어야 하고, 풀이 비어 있으면 자물쇠를 쥔 채 {@code connection-timeout}
     * (운영 기본 <b>30초</b>)만큼 기다린다. 그동안 {@code publish}·{@code open}·
     * {@code broadcastEnded}가 전부 막힌다 — 실측 {@code publish} <b>3142ms</b> ·
     * {@code open} <b>3116ms</b>(풀 2·시한 3초).
     *
     * <p>🔴 <b>그것이 고갈을 스스로 만든다.</b> {@code afterCommit}은 커넥션 반납 <b>전</b>이라
     * ({@code activeConnections=1}·{@code resourceBound=true} 실측) 막힌 {@code publish}가 커넥션을
     * <b>쥔 채</b> 기다린다. 커넥션이 안 돌아오니 조회는 계속 굶는다. 외부 점유자 없이
     * {@code publish} 둘만으로 풀이 마른 채 시한까지 유지되는 것을 재현했다.
     *
     * <p>트랜잭션을 여기서 열면 커넥션은 <b>위의 {@code findByStreamId}</b>에서 확보되고
     * (자물쇠 밖이다) 자물쇠 안의 조회는 <b>그것을 재사용</b>한다.
     *
     * <p>🔴 <b>대가는 사라지지 않고 방향이 뒤집힌다.</b> 전에는 자물쇠를 쥔 채 커넥션을 기다렸고,
     * 이제는 커넥션을 쥔 채 자물쇠를 기다린다. <b>어느 쪽이 나은지가 이 결정의 전부라 재 봤다</b> —
     * 자물쇠 보유가 조회 한 번으로 끝나 기다림이 짧다: 같은 배치에서 발행 최악 막힘
     * <b>743~2022ms → 0~1ms</b>(고친 뒤는 전수 5회 + 단독 1회, 전부 0~1ms.
     * {@code OpenDoesNotBlockPublishTest}가 계속 잰다).
     *
     * <p><b>SSE가 오래 살아 있는 것과 무관하다.</b> 이 메서드는 {@code openWithSnapshot}이
     * 돌아오면 끝나고 트랜잭션도 거기서 닫힌다 — 전송은 {@code CardStreamExecutor}의 전용
     * 스레드가 한다. 커넥션을 쥐는 시간은 <b>연결 수명(최대 4시간)이 아니라 카드 0장 11ms ·
     * 300장 26ms</b>다(스냅샷 조회~트랜잭션 완료 실측). 연결이 살아 있는 동안
     * {@code activeConnections=0}인 것도 확인했다.
     */
    @Transactional(readOnly = true)
    @GetMapping(value = "/api/clip/broadcasts/{streamId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> open(@PathVariable String streamId,
                                           @AuthenticationPrincipal Jwt jwt,
                                           @RequestHeader(value = "Last-Event-ID", required = false) String lastFromHeader,
                                           @RequestParam(value = "lastEventId", required = false) String lastFromQuery) {
        // 없는 방송이면 연결을 열기 전에 404다. 여기서 <b>상태를 들고 가지 않는다</b> —
        // 이 값은 임계구역 밖이라 낡는다. 상태는 아래 Supplier가 락 안에서 다시 읽는다.
        if (broadcasts.findByStreamId(streamId).isEmpty()) {
            throw new BroadcastNotFoundException(streamId);
        }

        // 받아서 적기만 한다. 지금은 전체 스냅샷을 다시 보내므로 쓰지 않는다 —
        // 마진 방식으로 바꾸는 날 쓰일 자리다(PRD 결정).
        String last = lastFromHeader != null ? lastFromHeader : lastFromQuery;
        if (last != null) {
            log.debug("jumpcard.stream.last_event_id streamId={} last={}", streamId, last);
        }

        // 연결 수명 = min(설정값, 토큰 exp까지). 만료 시점에 닫히고 브라우저가 새 토큰으로 다시 붙는다.
        // exp가 없는 토큰은 JwtConfig가 이미 401로 막았다(setAllowEmptyExpiryClaim(false)).
        Duration untilExpiry = Duration.between(Instant.now(), jwt.getExpiresAt());

        // 남은 수명이 0 이하면 열지 않는다. 디코더의 clock skew 허용치(기본 60초) 안쪽
        // 토큰은 인증을 통과하는데, 그대로 열면 SseEmitter가 음수 시한을 받고
        // 서블릿 규약상 timeout <= 0은 「시한 없음」이라 연결이 영영 산다 —
        // 만료된 토큰일수록 오래 사는 뒤집힌 결과가 된다(인가 2차 감사 실측).
        // 하한을 두는 방식(max(untilExpiry, 최소값))은 만료 토큰으로 연 연결을 살려 주므로 쓰지 않는다.
        if (untilExpiry.isZero() || untilExpiry.isNegative()) {
            throw new TokenAlreadyExpiredException();
        }

        Duration timeout = untilExpiry.compareTo(properties.timeout()) < 0 ? untilExpiry : properties.timeout();

        // 🔴 스냅샷을 여기서 읽지 않고 <b>읽는 법</b>을 넘긴다. 값으로 읽으면 자물쇠 밖이 되고,
        // 「읽은 뒤 ~ 명부에 오르기 전」 창에 지나간 카드와 ended가 영구히 유실된다
        // (PR #109 봇 지적 ②, 재현: 창 실측 5.9~22.4ms). registry가 이것을 임계구역 안에서 부른다.
        //
        // 방송 상태도 그 안에서 <b>다시</b> 읽는다. 위에서 읽은 값은 창 밖이라, 창에서 방송이
        // 끝나면 LIVE로 낡는다 — 그러면 ended=false로 열려 그 연결이 종료를 영영 못 받는다.
        // 조회 하나(약 1.4ms)를 더 내고 그 갈래를 닫는다.
        //
        // 상한 초과면 StreamLimitExceededException → 503.
        SseEmitter emitter = registry.openWithSnapshot(streamId, jwt.getSubject(), timeout,
                () -> new CardStreamRegistry.InitialSnapshot(
                        service.snapshotsOf(streamId),
                        broadcasts.findByStreamId(streamId)
                                .map(b -> b.getStatus() == BroadcastStatus.ENDED)
                                .orElseThrow(() -> new BroadcastNotFoundException(streamId))));

        return ResponseEntity.ok()
                // 앞단 프록시가 모아 보내면 "3초 내 도착"이 깨진다. 로컬엔 프록시가 없어 배포 후에만 난다.
                .header("X-Accel-Buffering", "no")
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .body(emitter);
    }
}
