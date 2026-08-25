package com.pokeclip.chat.detector.publish;

import com.pokeclip.chat.detector.config.ClipClientProperties;
import com.pokeclip.chat.detector.config.InternalApiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 계약 2A — 판별한 지점을 clip에 카드로 넣는다.
 *
 * <p><b>201과 200이 둘 다 성공이다.</b> 201은 새로 만든 것, 200은 clip이 이미 갖고 있는 것.
 * 200을 실패로 읽으면 정상 중복이 재시도 대상이 된다.
 */
@Component
public class ClipHighlightClient {

    private static final Logger log = LoggerFactory.getLogger(ClipHighlightClient.class);

    public enum PublishResult {
        /** 새 카드가 만들어졌다 */
        CREATED,
        /** clip이 이미 갖고 있다. 성공이다 */
        ALREADY_EXISTS,
        /** 요청이 잘못됐거나 인증이 거부됐다. <b>다시 보내도 같다</b> */
        REJECTED,
        /** clip 쪽 사정이다. 정해진 횟수를 다 쓰고도 못 넣었다 */
        FAILED
    }

    private final RestClient restClient;
    private final String baseUrl;
    private final int maxAttempts;
    private final String internalToken;

    public ClipHighlightClient(RestClient.Builder restClientBuilder,
                               ClipClientProperties properties,
                               InternalApiProperties internalApi) {
        this.restClient = restClientBuilder.build();
        this.baseUrl = properties.baseUrl();
        this.maxAttempts = properties.maxAttempts();
        this.internalToken = internalApi.token();
    }

    public PublishResult publish(HighlightCard card) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ResponseEntity<Void> response = restClient.post()
                        .uri(baseUrl + "/internal/broadcasts/{streamId}/highlights", card.streamId())
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(card.toJson())
                        .retrieve()
                        .toBodilessEntity();
                return response.getStatusCode().value() == 201
                        ? PublishResult.CREATED
                        : PublishResult.ALREADY_EXISTS;
            } catch (RestClientResponseException e) {
                HttpStatusCode status = e.getStatusCode();
                // 🔴 4xx는 재시도로 안 풀린다. 같은 본문·같은 헤더라 영영 같은 답이 온다.
                // 여기서 멈추지 않으면 못 들어갈 카드에 시도 횟수를 다 쓴다.
                if (status.is4xxClientError()) {
                    // 응답 본문은 안 찍는다 — 무엇이 들었는지는 clip이 정한다.
                    log.warn("detect.publish_rejected streamId={} eventId={} status={}",
                            card.streamId(), card.eventId(), status.value());
                    return PublishResult.REJECTED;
                }
                log.warn("detect.publish_retrying streamId={} eventId={} status={} attempt={}/{}",
                        card.streamId(), card.eventId(), status.value(), attempt, maxAttempts);
            } catch (Exception e) {
                log.warn("detect.publish_retrying streamId={} eventId={} causeType={} attempt={}/{}",
                        card.streamId(), card.eventId(), e.getClass().getSimpleName(), attempt, maxAttempts);
            }
        }
        log.warn("detect.publish_failed streamId={} eventId={} attempts={}",
                card.streamId(), card.eventId(), maxAttempts);
        return PublishResult.FAILED;
    }
}
