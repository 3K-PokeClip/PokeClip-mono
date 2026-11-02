package com.pokeclip.clip.purge.api;

import com.pokeclip.clip.purge.StreamerPurgeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * auth가 탈퇴를 알리는 문(POK-256). {@code /internal/**}이라 {@code X-Internal-Token}으로만 들어온다.
 *
 * <p>명부에 적고 202로 바로 답한다. 실제 지우기는 {@code StreamerPurgeSweeper}가 한다. 명부에 적힌 순간부터
 * 늦게 온 방송 편지는 버려진다. 같은 번호로 몇 번 불러도 결과가 같다(auth는 성공할 때까지 다시 부른다).
 *
 * <p>답: 202 · 400 회원 번호 모양이 틀렸다(숫자만, auth {@code users.id}) · 401 토큰.
 */
@RestController
public class StreamerPurgeController {

    private static final Logger log = LoggerFactory.getLogger(StreamerPurgeController.class);
    private static final Pattern USER_ID = Pattern.compile("[0-9]{1,19}");

    private final StreamerPurgeStore store;
    private final Clock clock;

    StreamerPurgeController(StreamerPurgeStore store) {
        this.store = store;
        this.clock = Clock.systemUTC();
    }

    @DeleteMapping("/internal/streamers/{streamerId}/data")
    public ResponseEntity<Map<String, Object>> purge(@PathVariable String streamerId) {
        if (!USER_ID.matcher(streamerId).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_streamer_id"));
        }
        store.request(streamerId, clock.instant());
        log.info("clip.purge.requested streamerId={}", streamerId);
        return ResponseEntity.accepted().build();
    }
}
