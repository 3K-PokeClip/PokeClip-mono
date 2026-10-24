package com.pokeclip.auth.youtube.retention.api;

import com.pokeclip.auth.youtube.YoutubeLinkService;
import com.pokeclip.auth.youtube.YoutubeOAuthClient;
import com.pokeclip.auth.youtube.YoutubeResolveResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * clip이 「이 회원이 올린 이 영상들이 유튜브에 아직 있나」를 묻는 문(POK-423, 30일 확인). {@code /internal/**} 체인이 토큰을 본다.
 * 영상 번호는 clip에 있고 토큰은 auth 밖으로 안 나가므로(resolve를 clip에 안 여는 이유와 같다) 여기서 그 회원 토큰으로 묻는다.
 *
 * <p>답:
 * <ul>
 *   <li>200 {@code {"linked":true,"channelId","existing":[…]}}: 그 토큰의 채널과, 물은 것 중 유튜브에 있는 것. 비공개 영상은 그 채널
 *       토큰으로만 보이므로 다른 채널 영상은 「없음」으로 나온다(clip이 채널을 견줘 안 지운다)</li>
 *   <li>200 {@code {"linked":false}}: 연동이 없거나 끊겼다(지우는 것은 해제 기한이 한다)</li>
 *   <li>503: 지금은 못 묻는다(갱신 일시 실패 · 구글 장애 · 할당량). clip은 다음 바퀴에 다시 묻는다</li>
 *   <li>400: 모양이 틀렸다(번호 1~50개, 영상 번호 모양)</li>
 * </ul>
 * 쿼터: 부를 때마다 videos.list 1유닛(+ 토큰 갱신이 필요하면 토큰 호출 하나).
 */
@RestController
public class YoutubeVideoCheckController {

    private static final Logger log = LoggerFactory.getLogger(YoutubeVideoCheckController.class);
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{6,32}");
    static final int MAX_IDS = 50;

    private final YoutubeLinkService links;
    private final YoutubeOAuthClient youtube;

    YoutubeVideoCheckController(YoutubeLinkService links, YoutubeOAuthClient youtube) {
        this.links = links;
        this.youtube = youtube;
    }

    public record CheckRequest(Long userId, List<String> videoIds) {
    }

    @PostMapping("/internal/youtube-videos/check")
    public ResponseEntity<Map<String, Object>> check(@RequestBody CheckRequest request) {
        if (request.userId() == null || request.videoIds() == null || request.videoIds().isEmpty()
                || request.videoIds().size() > MAX_IDS
                || !request.videoIds().stream().allMatch(id -> id != null && VIDEO_ID.matcher(id).matches())) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid_request"));
        }
        YoutubeResolveResult token = links.resolve(request.userId());
        if (!token.valid()) {
            if ("REFRESH_UNAVAILABLE".equals(token.reason())) {
                return unavailable(request.userId(), "REFRESH_UNAVAILABLE");
            }
            return ResponseEntity.ok(Map.of("linked", false));
        }
        Set<String> existing;
        try {
            existing = youtube.existingVideos(token.accessToken(), request.videoIds());
        } catch (RuntimeException e) {
            // 구글 장애 · 할당량 · 토큰 거절(그 사이 철회). 「없음」으로 답하면 clip이 멀쩡한 영상 번호를 지운다.
            return unavailable(request.userId(), e.getClass().getSimpleName());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("linked", true);
        body.put("channelId", token.channelId());
        body.put("existing", existing.stream().filter(request.videoIds()::contains).sorted().toList());
        log.info("auth.youtube.video_check userId={} asked={} existing={}",
                request.userId(), request.videoIds().size(), existing.size());
        return ResponseEntity.ok(body);
    }

    private static ResponseEntity<Map<String, Object>> unavailable(long userId, String cause) {
        log.warn("auth.youtube.video_check_unavailable userId={} cause={}", userId, cause);
        return ResponseEntity.status(503).body(Map.of("error", "youtube_unavailable"));
    }
}
