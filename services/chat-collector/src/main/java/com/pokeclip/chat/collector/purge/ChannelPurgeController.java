package com.pokeclip.chat.collector.purge;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * auth가 탈퇴한 스트리머의 치지직 채널을 알리는 문(POK-256). {@code /internal/*}이라 {@code X-Internal-Token}
 * 필터({@code InternalApiConfiguration})를 지난다.
 *
 * <p>명부에 적고 202로 바로 답한다. 실제 지우기는 정리기가 한다. 같은 채널로 몇 번 불러도 결과가 같다.
 * 답: 202 · 400 채널 번호 모양이 틀렸다 · 401 토큰.
 */
@RestController
public class ChannelPurgeController {

    /** 치지직 채널 번호는 32자 16진수다. 넉넉히 잡되 경로 문자는 막는다. */
    private static final Pattern CHANNEL_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final ChatPurgeStore store;
    private final Supplier<Instant> clock;

    public ChannelPurgeController(ChatPurgeStore store) {
        this.store = store;
        this.clock = Instant::now;
    }

    @DeleteMapping("/internal/channels/{channelId}/chat-data")
    public ResponseEntity<Map<String, Object>> purge(@PathVariable String channelId) {
        if (!CHANNEL_ID.matcher(channelId).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_channel_id"));
        }
        store.request(channelId, clock.get());
        return ResponseEntity.accepted().build();
    }
}
