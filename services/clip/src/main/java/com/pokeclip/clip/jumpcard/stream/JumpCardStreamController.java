package com.pokeclip.clip.jumpcard.stream;

import com.pokeclip.clip.broadcast.Broadcast;
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

    @GetMapping(value = "/api/clip/broadcasts/{streamId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> open(@PathVariable String streamId,
                                           @AuthenticationPrincipal Jwt jwt,
                                           @RequestHeader(value = "Last-Event-ID", required = false) String lastFromHeader,
                                           @RequestParam(value = "lastEventId", required = false) String lastFromQuery) {
        Broadcast broadcast = broadcasts.findByStreamId(streamId)
                .orElseThrow(() -> new BroadcastNotFoundException(streamId));

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

        // 상한 초과면 StreamLimitExceededException → 503
        SseEmitter emitter = registry.open(streamId, jwt.getSubject(), timeout);
        registry.sendInitial(emitter, service.snapshotsOf(streamId),
                broadcast.getStatus() == BroadcastStatus.ENDED);

        return ResponseEntity.ok()
                // 앞단 프록시가 모아 보내면 "3초 내 도착"이 깨진다. 로컬엔 프록시가 없어 배포 후에만 난다.
                .header("X-Accel-Buffering", "no")
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .body(emitter);
    }
}
