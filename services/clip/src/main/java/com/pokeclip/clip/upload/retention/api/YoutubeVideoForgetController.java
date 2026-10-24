package com.pokeclip.clip.upload.retention.api;

import com.pokeclip.clip.upload.retention.YoutubeVideoStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * auth가 「이 회원의 이 채널 영상 번호를 지워라」를 알리는 문(POK-423). 연동 해제 7일 · 철회 · 갱신 불가 30일이 지났고, 그 회원의 살아
 * 있는 연동이 그 채널이 아닐 때 온다. {@code /internal/**}이라 {@code X-Internal-Token}으로만 들어온다.
 *
 * <p>바로 지우고 200 {@code {"forgotten": n}}으로 답한다(줄이 적어 탈퇴처럼 명부를 둘 까닭이 없다). auth는 2xx를 받을 때까지 다시
 * 부르고, 몇 번 불러도 결과가 같다. 400 모양이 틀렸다 · 401 토큰. 채널 번호는 로그에 안 찍는다.
 */
@RestController
public class YoutubeVideoForgetController {

    private static final Logger log = LoggerFactory.getLogger(YoutubeVideoForgetController.class);
    private static final Pattern USER_ID = Pattern.compile("[0-9]{1,19}");
    private static final Pattern CHANNEL_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final YoutubeVideoStore store;
    private final Clock clock;

    YoutubeVideoForgetController(YoutubeVideoStore store) {
        this.store = store;
        this.clock = Clock.systemUTC();
    }

    public record ForgetBody(String owner, String channelId) {
    }

    @PostMapping("/internal/youtube-videos/forget")
    public ResponseEntity<Map<String, Object>> forget(@RequestBody ForgetBody body) {
        if (body.owner() == null || !USER_ID.matcher(body.owner()).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_owner"));
        }
        if (body.channelId() == null || !CHANNEL_ID.matcher(body.channelId()).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_channel_id"));
        }
        int forgotten = store.forget(body.owner(), body.channelId(), clock.instant());
        log.info("clip.youtube_video.forgotten owner={} count={}", body.owner(), forgotten);
        return ResponseEntity.ok(Map.of("forgotten", forgotten));
    }
}
