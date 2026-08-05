package com.pokeclip.chat.collector.chzzk;

import com.pokeclip.chat.collector.StopReason;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

/**
 * 치지직 REST 둘. 세션 발급과 채팅 구독.
 *
 * <p><b>재시도하지 않는다.</b> 실패는 사유와 함께 던지고 끝낸다 — 재연결은 POK-86이다.
 */
public class ChzzkSessionClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient restClient;
    private final String baseUrl;
    private final String accessToken;

    public ChzzkSessionClient(RestClient restClient, String baseUrl, String accessToken) {
        this.restClient = restClient;
        this.baseUrl = baseUrl;
        this.accessToken = accessToken;
    }

    /** @return 소켓 연결용 URL. 형태는 태스크 1의 실측 로그로 확정한다. */
    public String createSession() {
        try {
            String body = restClient.get()
                    .uri(baseUrl + "/open/v1/sessions/auth")
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(String.class);
            return MAPPER.readTree(body).path("content").path("url").asString();
        } catch (RestClientResponseException e) {
            // 응답 본문을 메시지에 담지 않는다 — 토큰이 되비쳐 나올 수 있다.
            throw new SessionEstablishException(EstablishStage.AUTH,
                    StopReason.SESSION_AUTH_FAILED, "status=" + e.getStatusCode().value());
        } catch (Exception e) {
            throw new SessionEstablishException(EstablishStage.AUTH,
                    StopReason.SESSION_AUTH_FAILED, "cause=" + e.getClass().getSimpleName());
        }
    }

    /** sessionKey는 POST여도 쿼리 파라미터다. Body JSON은 미지원. */
    public void subscribeChat(String sessionKey) {
        try {
            restClient.post()
                    .uri(baseUrl + "/open/v1/sessions/events/subscribe/chat?sessionKey=" + sessionKey)
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new SessionEstablishException(EstablishStage.SUBSCRIBE,
                    StopReason.SUBSCRIBE_FAILED, "status=" + e.getStatusCode().value());
        } catch (Exception e) {
            throw new SessionEstablishException(EstablishStage.SUBSCRIBE,
                    StopReason.SUBSCRIBE_FAILED, "cause=" + e.getClass().getSimpleName());
        }
    }
}
