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
            int status = e.getStatusCode().value();
            // 401·403만 영구 실패다. 5xx를 여기 묶으면 서버가 잠깐 아픈 것에 영구 정지하고,
            // 반대로 전부 일시로 보면 만료 토큰으로 영원히 재시도한다.
            StopReason reason = (status == 401 || status == 403)
                    ? StopReason.SESSION_AUTH_REJECTED
                    : StopReason.SESSION_AUTH_FAILED;
            throw new SessionEstablishException(EstablishStage.AUTH, reason, "status=" + status);
        } catch (Exception e) {
            throw new SessionEstablishException(EstablishStage.AUTH,
                    StopReason.SESSION_AUTH_FAILED, "cause=" + e.getClass().getSimpleName());
        }
    }

    /**
     * 종료할 때 구독을 반납한다. <b>실패해도 던지지 않는다</b> — 종료 경로에서
     * 예외가 나가면 뒤따르는 소켓 정리와 판정 라인이 통째로 건너뛰어진다.
     *
     * <p>이걸 안 보내면 세션 반납이 우리가 아니라 <b>서버가 죽은 전송을 알아채는
     * 때</b>에 달린다. 실측에서 같은 종료인데 10초와 4분 42초로 갈렸다.
     * 연결 상한이 Access Token당 3개라 짧은 간격으로 세 번 재시작하면 막히는데,
     * 막혔을 때 증상은 {@code connected}가 안 오는 것이라 핸드셰이크 실패와
     * 구분되지 않는다 — 태스크 1에서 이미 겪었다.
     *
     * @return 반납 요청이 200으로 끝났으면 true
     */
    public boolean unsubscribeChatQuietly(String sessionKey) {
        try {
            restClient.post()
                    .uri(baseUrl + "/open/v1/sessions/events/unsubscribe/chat?sessionKey=" + sessionKey)
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            return false;
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
