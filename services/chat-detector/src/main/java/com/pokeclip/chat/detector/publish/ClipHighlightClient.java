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
 *
 * <p>🔴 <b>4xx 를 한 덩어리로 보지 않는다.</b> 404는 「clip 이 아직 그 방송을 모른다」이고
 * <b>다시 보내면 답이 바뀐다</b> — 나머지 4xx 와 처분이 반대다. 자세한 것은
 * {@link PublishResult#BROADCAST_NOT_FOUND}.
 */
@Component
public class ClipHighlightClient {

    private static final Logger log = LoggerFactory.getLogger(ClipHighlightClient.class);

    public enum PublishResult {
        /** 새 카드가 만들어졌다 */
        CREATED,
        /** clip이 이미 갖고 있다. 성공이다 */
        ALREADY_EXISTS,
        /**
         * 🔴 <b>clip 이 그 방송을 아직 모른다.</b> 4xx 지만 <b>다시 보내면 답이 바뀐다</b> —
         * 방송 시작 알림을 수집기와 clip 이 <b>각자 다른 큐</b>에서 받으므로, clip 이 늦으면
         * 채팅은 이미 쌓이는데 clip 에는 방송 행이 없다.
         *
         * <p><b>clip 이 재시도를 전제로 설계한 자리다</b> — {@code JumpCardService.record} 가
         * 「FK 위반은 500이 되고, 판별기는 404를 받아야 재시도 상한을 센다」라고 적어 뒀다
         * (봇 리뷰 1판에서 codex 가 짚었고 그 코드로 확인했다).
         */
        BROADCAST_NOT_FOUND,
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
                // 🔴 404만 갈라낸다. 그 엔드포인트에서 clip 이 내는 404는 「그 방송을 모른다」
                // 하나뿐이고(계약 표), 그것은 몇 초~몇 분 뒤면 답이 바뀐다. 본문을 읽어
                // 확인하지 않는 이유는 아래 4xx 와 같다 — 무엇이 들었는지는 clip 이 정한다.
                //
                // 여기서 즉시 재시도하지 않는다. 몇 밀리초 만에 다시 보내 봐야 clip 이 그 사이
                // 방송을 만들 리 없다. 바퀴를 넘겨 다시 시도하는 것은 부르는 쪽 몫이다.
                if (status.value() == 404) {
                    log.info("detect.publish_broadcast_missing streamId={} eventId={}",
                            card.streamId(), card.eventId());
                    return PublishResult.BROADCAST_NOT_FOUND;
                }
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
