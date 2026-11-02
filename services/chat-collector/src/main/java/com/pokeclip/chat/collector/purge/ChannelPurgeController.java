package com.pokeclip.chat.collector.purge;

import com.pokeclip.chat.collector.Platform;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * auth가 탈퇴한 스트리머의 채널을 알리는 문(POK-256). 플랫폼은 {@code ?platform=}이다(POK-397). {@code /internal/*}이라 {@code X-Internal-Token}
 * 필터({@code InternalApiConfiguration})를 지난다.
 *
 * <p>명부에 적고 202로 바로 답한다. 실제 지우기는 정리기가 한다. 같은 채널로 몇 번 불러도 결과가 같다.
 * 답: 202 · 400 채널 번호·시각·플랫폼 모양이 틀렸다 · 401 토큰.
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

    /**
     * @param since    탈퇴 시각(auth 장부의 줄을 만든 시각, ISO-8601). 지우는 범위의 기준이다. 없으면 받은 시각.
     * @param platform {@code CHZZK}·{@code SOOP}. 없거나 비면 치지직이다: 플랫폼 칸 전의 auth는 이 값을 안 보낸다. 대문자만 받는다
     *                 ({@link Platform#parse}). 모르는 값을 치지직으로 접으면 SOOP 탈퇴가 같은 아이디의 치지직 채팅을 지운다
     */
    @DeleteMapping("/internal/channels/{channelId}/chat-data")
    public ResponseEntity<Map<String, Object>> purge(@PathVariable String channelId,
                                                     @RequestParam(required = false) String since,
                                                     @RequestParam(required = false) String platform) {
        if (!CHANNEL_ID.matcher(channelId).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_channel_id"));
        }
        Optional<Platform> parsed = Platform.parse(platform);
        if (parsed.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_platform"));
        }
        Instant at;
        try {
            at = since == null ? clock.get() : Instant.parse(since);
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_since"));
        }
        store.request(parsed.get(), channelId, at);
        return ResponseEntity.accepted().build();
    }
}
