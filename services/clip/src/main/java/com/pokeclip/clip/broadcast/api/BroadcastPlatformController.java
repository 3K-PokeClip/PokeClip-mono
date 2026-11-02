package com.pokeclip.clip.broadcast.api;

import com.pokeclip.clip.broadcast.BroadcastPlatformStore;
import com.pokeclip.clip.broadcast.Platform;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * 수집기가 「이 방송이 이 플랫폼에서 켜져 있다」를 알리는 문(POK-397). {@code /internal/**}이라 {@code X-Internal-Token}으로만
 * 들어온다. 묶음 표({@link BroadcastPlatformStore})에 적을 뿐 편지가 정한 {@code broadcasts.platform}은 안 바꾼다.
 *
 * <p>답: 204 · 400 {@code invalid_platform}(형식 밖, 소문자 포함) · 400 {@code invalid_seen_at} · 404 {@code broadcast_not_found}
 * (방송 줄이 없다. 편지보다 알림이 먼저 왔거나 탈퇴로 지워졌다) · 401 토큰. 본문 {@code {"seenAt": ISO}}는 선택이고 없으면 지금이다.
 */
@RestController
public class BroadcastPlatformController {

    private final BroadcastPlatformStore store;
    private final Clock clock;

    BroadcastPlatformController(BroadcastPlatformStore store) {
        this.store = store;
        this.clock = Clock.systemUTC();
    }

    public record SeenBody(String seenAt) {
    }

    @PutMapping("/internal/broadcasts/{streamId}/platforms/{platform}")
    public ResponseEntity<Map<String, Object>> markSeen(@PathVariable String streamId, @PathVariable String platform,
                                                        @RequestBody(required = false) SeenBody body) {
        if (!Platform.isWellFormed(platform)) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_platform"));
        }
        Instant seenAt;
        if (body == null || body.seenAt() == null) {
            seenAt = clock.instant();
        } else {
            try {
                seenAt = Instant.parse(body.seenAt());
            } catch (DateTimeParseException e) {
                return ResponseEntity.badRequest().body(Map.of("error", "invalid_seen_at"));
            }
        }
        if (!store.markSeen(streamId, platform, seenAt)) {
            return ResponseEntity.status(404).body(Map.of("error", "broadcast_not_found"));
        }
        return ResponseEntity.noContent().build();
    }
}
