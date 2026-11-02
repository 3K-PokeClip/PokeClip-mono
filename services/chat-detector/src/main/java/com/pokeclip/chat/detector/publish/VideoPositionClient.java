package com.pokeclip.chat.detector.publish;

import com.pokeclip.chat.detector.config.CollectorClientProperties;
import com.pokeclip.chat.detector.config.InternalApiProperties;
import com.pokeclip.chat.detector.publish.VideoPosition.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 채팅 시각 하나를 그 방송 영상 안의 위치로 바꾼다. 계약 정본은 수집 서버의
 * {@code VideoPositionController}다(POK-92).
 *
 * <p><b>카드 한 장에 한 번만 부른다.</b> 채팅마다 부르지 않는다 — 수집 서버 문서가 정확히
 * 그 경우를 두고 "판별기가 채팅마다 부르는 시점에는 인덱스가 필요하다"고 경고했다.
 * 창 양 끝 위치는 보정값이 같으므로 부르는 쪽이 산수로 낸다.
 *
 * <p><b>예외를 던지지 않는다.</b> 못 물으면 {@link State#UNAVAILABLE}이다 — 「영영 없음」과
 * 「모름」을 부르는 쪽이 갈라야 하는데, 예외로 내보내면 그 구분이 호출부의 catch 모양에 달린다.
 */
@Component
public class VideoPositionClient {

    private static final Logger log = LoggerFactory.getLogger(VideoPositionClient.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient restClient;
    private final String baseUrl;
    private final String internalToken;

    /**
     * <b>{@code RestClient.create()}를 쓰지 않는다.</b> 그것은 자동 설정을 우회해
     * {@code spring.http.clients.*}의 시한이 어디에도 안 걸린다. 증상이 조용하다 — 평소엔
     * 응답이 빨라 아무 일도 없고, 창구가 연결만 받고 답을 안 하는 날에만 드러난다.
     * 이 저장소가 이미 세 번 데인 자리다.
     */
    public VideoPositionClient(RestClient.Builder restClientBuilder,
                               CollectorClientProperties properties,
                               InternalApiProperties internalApi) {
        this.restClient = restClientBuilder.build();
        this.baseUrl = properties.baseUrl();
        this.internalToken = internalApi.token();
    }

    public VideoPosition locate(String streamId, long messageTimeEpochMs) {
        try {
            String body = restClient.get()
                    // 창구가 형식 둘을 받지만 epoch ms로 보낸다 — 치지직이 주는 형식이고
                    // ISO는 쿼리에서 +가 공백으로 디코드돼 400이 되는 함정이 있다(창구 쪽 실측).
                    .uri(baseUrl + "/internal/streams/{streamId}/video-position?messageTime={t}",
                            streamId, messageTimeEpochMs)
                    .header("X-Internal-Token", internalToken)
                    .retrieve()
                    .body(String.class);
            return read(streamId, body);
        } catch (Exception e) {
            // 본문·예외 메시지를 안 찍는다 — RestClientResponseException의 메시지에는 응답
            // 본문이 딸려 오고, 그 안에 무엇이 들었는지는 수집 서버가 정한다.
            return unavailable(streamId, e.getClass().getSimpleName());
        }
    }

    private VideoPosition read(String streamId, String body) {
        JsonNode json = MAPPER.readTree(body);
        Long offset = json.hasNonNull("appliedOffsetMs") ? json.get("appliedOffsetMs").asLong() : null;
        return switch (json.path("state").asString("")) {
            // 위치가 없으면 CONVERTED라도 못 쓴다. 0으로 접지 않는다 —
            // 「0초 지점」이라는 그럴듯하게 틀린 답이 된다.
            case "converted" -> json.hasNonNull("positionMs")
                    ? new VideoPosition(State.CONVERTED, json.get("positionMs").asLong(), offset)
                    : unavailable(streamId, "converted_without_position");
            case "not_yet_indexed" -> new VideoPosition(State.NOT_YET_INDEXED, null, offset);
            case "no_footage" -> new VideoPosition(State.NO_FOOTAGE, null, offset);
            // 창구가 값을 늘리는 날 통과가 아니라 모름이 기본이다.
            default -> unavailable(streamId, "unknown_state");
        };
    }

    private VideoPosition unavailable(String streamId, String cause) {
        log.warn("detect.video_position_unavailable streamId={} cause={}", streamId, cause);
        return new VideoPosition(State.UNAVAILABLE, null, null);
    }
}
