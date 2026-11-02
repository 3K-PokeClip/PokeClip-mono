package com.pokeclip.clip.upload.retention;

import com.pokeclip.clip.config.InternalApiProperties;
import com.pokeclip.clip.delegation.AuthClientProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「이 영상들이 유튜브에 아직 있나」를 auth에 묻는다(POK-423, {@code POST /internal/youtube-videos/check}). 토큰은 auth 밖으로
 * 안 나간다({@code YoutubeLinkStatusClient}와 같은 원칙): 영상 번호를 넘기고 auth가 그 회원 토큰으로 유튜브에 묻는다.
 *
 * <p>예외를 안 던진다. 못 닿음 · 시한 · 모르는 모양이면 {@code null}이고 부른 쪽은 아무것도 안 바꾼다(다음 순회가 다시 묻는다).
 * 시한은 전역({@code spring.http.clients.*})이다: 사람이 기다리는 요청이 아니다.
 */
@Component
public class YoutubeVideoCheckClient {

    private static final Logger log = LoggerFactory.getLogger(YoutubeVideoCheckClient.class);
    private static final String PATH = "/internal/youtube-videos/check";
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private final RestClient restClient;
    private final String baseUrl;
    private final String internalToken;
    private final ObjectMapper mapper;

    YoutubeVideoCheckClient(RestClient.Builder builder, AuthClientProperties auth, InternalApiProperties internalApi,
                            ObjectMapper mapper) {
        this.restClient = builder.build();
        this.baseUrl = auth.baseUrl();
        this.internalToken = internalApi.token();
        this.mapper = mapper;
    }

    /**
     * @param linked    살아 있는 연동이 있나. 없으면 나머지 둘은 비어 있다
     * @param channelId 그 연동의 채널(물어본 토큰의 채널)
     * @param existing  유튜브에 있는 영상 번호 → 그 영상의 주인 채널(공개 영상은 다른 채널 토큰으로도 보여 주인을 따로 받는다)
     */
    public record Answer(boolean linked, String channelId, Map<String, String> existing) {
    }

    /** @return 모르면 {@code null} */
    public Answer check(long userId, List<String> videoIds) {
        try {
            String body = restClient.post()
                    .uri(baseUrl + PATH)
                    .header(INTERNAL_TOKEN_HEADER, internalToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(mapper.writeValueAsString(Map.of("userId", userId, "videoIds", videoIds)))
                    .retrieve()
                    .body(String.class);
            JsonNode json = body == null ? null : mapper.readTree(body);
            JsonNode linked = json == null ? null : json.get("linked");
            if (linked == null || !linked.isBoolean()) {
                return unknown(userId, "bad_body");
            }
            if (!linked.booleanValue()) {
                return new Answer(false, null, Map.of());
            }
            JsonNode channel = json.get("channelId");
            JsonNode videos = json.get("videos");
            if (channel == null || !channel.isString() || videos == null || !videos.isArray()) {
                return unknown(userId, "bad_body");
            }
            Map<String, String> found = new HashMap<>();
            for (JsonNode video : videos) {
                JsonNode id = video.get("id");
                JsonNode owner = video.get("channelId");
                if (id == null || !id.isString() || owner == null || !owner.isString()) {
                    return unknown(userId, "bad_body");
                }
                found.put(id.stringValue(), owner.stringValue());
            }
            return new Answer(true, channel.stringValue(), found);
        } catch (RestClientResponseException e) {
            return unknown(userId, "status=" + e.getStatusCode().value());
        } catch (RuntimeException e) {
            return unknown(userId, e.getClass().getSimpleName());
        }
    }

    private static Answer unknown(long userId, String cause) {
        log.warn("clip.youtube_video.check_skipped userId={} cause={}", userId, cause);
        return null;
    }
}
