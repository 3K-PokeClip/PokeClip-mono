package com.pokeclip.clip.collector;

import com.pokeclip.clip.config.InternalApiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

/**
 * 수집기(chat-collector)의 {@code /internal/streams/…} 창구를 부른다.
 * 계약 정본은 수집기의 {@code ChatWindowController}·{@code ChatChartController}·
 * {@code BroadcastInfoController}다.
 *
 * <p><b>본문을 해석하지 않는다.</b> 상태 코드와 글자를 그대로 들고 온다 — 채팅 목록·차트·방송
 * 정보 셋이 전부 화면이 그대로 그리는 값이고, clip이 다시 조립하면 <b>수집기가 칸을 늘릴 때마다
 * 여기도 고쳐야</b> 한다. 자격 판정은 이 클라이언트를 부르기 <b>전</b>에 끝나 있다.
 *
 * <p><b>4xx 중 400만 예외가 아니다.</b> 수집기의 400 본문은 {@code {"error":"inverted_window"}}처럼
 * 사유 낱말이 실려 있고 프론트가 그것을 읽는다. 503으로 접으면 「내가 잘못 물었다」가
 * 「서버가 아프다」로 둔갑해 화면이 같은 요청을 계속 다시 보낸다.
 *
 * <p>🔴 <b>그 밖의 4xx는 전부 접는다 — 같은 코드가 clip 계약에서 다른 뜻이기 때문이다.</b>
 * 수집기가 실제로 내는 4xx는 셋이고({@code QueryErrors}의 400 · {@code InternalTokenFilter}의 401 ·
 * 경로가 없을 때의 스프링 기본 404) 뒤 둘이 어긋난다 — clip에서 401은 「사용자 토큰 만료」,
 * 404는 「없는 방송·자격 없음」이다({@code JumpCardExceptionHandler}). 그대로 흘리면
 * <b>화면이 그럴듯하게 틀린다</b>: 401은 사용자가 못 고치는 재로그인 루프가 되고,
 * 404는 <b>롤링 배포 구간</b>(clip이 먼저 뜨고 수집기가 아직 옛 이미지)에서 <b>실재하는 자기 방송</b>에
 * 「없는 방송입니다」를 띄운다. 유실보다 나쁘다 — 아무도 안 본다.
 *
 * <p>접는 것이 <b>수집기 본문의 그대로 전달</b>도 같이 닫는다. 스프링 기본 404 본문에는
 * {@code path}가 실려(수집기 yml에 {@code server.error.*} 재정의가 0줄) 내부 경로가 브라우저로 나간다.
 *
 * <p><b>코드를 열거하지 않고 「400 말고는」으로 쓴다.</b> 401·404만 골라 접으면 수집기가 나중에
 * 내기 시작하는 코드가 조용히 통과한다. 403·410·429는 지금 수집기에 그 코드를 내는 자리가
 * 없지만(GET 하나뿐이라 405도 없다) 규칙은 같다.
 *
 * <p><b>되걸지 않는다</b> — {@link com.pokeclip.clip.config.HttpClientRetryConfig}가 clip의
 * 모든 요청 팩토리에서 자동 재시도를 껐다. 이 호출도 사람이 기다리는 요청 안에서 돈다.
 */
@Component
public class CollectorClient {

    private static final Logger log = LoggerFactory.getLogger(CollectorClient.class);

    /**
     * 수집기의 {@code InternalTokenFilter}가 보는 이름. 빠지거나 어긋나면 <b>401 + 빈 본문</b>이고,
     * 그것은 우리 설정 잘못이라 사용자가 못 고친다 — 그래서 아래에서 503으로 접는다.
     */
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private final RestClient restClient;
    private final boolean enabled;
    private final String internalToken;

    public CollectorClient(RestClient 수집기_전용_RestClient, CollectorClientProperties properties,
                           InternalApiProperties internalApi) {
        this.restClient = 수집기_전용_RestClient;
        this.enabled = properties.baseUrl() != null && !properties.baseUrl().isBlank();
        this.internalToken = internalApi.token();
    }

    /**
     * 주소가 비면 {@code false}이고 {@link #get}은 부르지 않고 바로 거절한다.
     * <b>부팅은 산다</b> — 이유는 {@link CollectorClientProperties} 머리에 있다.
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * @param query 넘길 쿼리. <b>부르는 쪽이 이미 걸러 놓은 것</b>이어야 한다 — 브라우저가 준 것을
     *              통째로 넘기면 프론트가 {@code channelId}(=보정값 열쇠)를 정하게 된다.
     *              거르는 자리는 {@code BroadcastChatController}의 허용 목록이다.
     * @throws CollectorErrors.CollectorUnavailableException 5xx·못 닿음·시한·꺼짐
     */
    public CollectorResponse get(String path, Map<String, String> query) {
        if (!enabled) {
            // 로그를 안 남긴다 — 이 상태는 매 요청마다 참이라 한 번 눌릴 때마다 줄이 쌓인다.
            // 「수집기 주소가 비었다」는 설정을 보면 알 수 있고, 화면에는 503이 나간다.
            throw new CollectorErrors.CollectorUnavailableException("disabled");
        }
        try {
            ResponseEntity<String> response = restClient.get()
                    .uri(builder -> {
                        // 🔴 경로 탈출을 막는 것은 이 조립이 아니라 <b>자격 판정</b>이다.
                        // DefaultUriBuilderFactory.path는 공백만 인코딩하고 `/`·`..`는 그대로
                        // 통과시킨다(실측: "../../actuator/health"가 글자 그대로 나간다).
                        // 실제로 막는 것은 부르기 전의 guard.requireViewable이다 — broadcasts
                        // 명부에 있는 방송 번호만 여기까지 온다. 그래서 판정을 이 호출보다
                        // 뒤로 옮기면 그 방어가 통째로 사라진다.
                        builder.path(path);
                        query.forEach(builder::queryParam);
                        return builder.build();
                    })
                    .header(INTERNAL_TOKEN_HEADER, internalToken)
                    .retrieve()
                    // 400만 예외로 안 바꾼다(핸들러를 비우는 것이 그 뜻이다). 나머지 4xx는
                    // 기본 핸들러가 던지고 아래 catch가 503으로 접는다 — 이유는 클래스 javadoc.
                    .onStatus(status -> status.value() == 400, (request, res) -> {
                    })
                    .toEntity(String.class);
            return new CollectorResponse(response.getStatusCode().value(), response.getBody());
        } catch (RestClientResponseException e) {
            // 5xx와 400 아닌 4xx가 여기로 온다(400만 위에서 삼켰다). 본문·예외 메시지를 안 찍는다 —
            // 그 메시지에는 응답 본문이 딸려 오고 안에 무엇이 들었는지는 수집기가 정한다.
            // cause에는 상태 코드만 남긴다 — 401과 404를 로그에서 갈라야 원인이 다르다.
            throw unavailable("status=" + e.getStatusCode().value());
        } catch (Exception e) {
            // 못 닿음·시한 초과.
            throw unavailable(e.getClass().getSimpleName());
        }
    }

    private CollectorErrors.CollectorUnavailableException unavailable(String cause) {
        // 경로는 안 싣는다 — 방송 번호가 들어 있고, 그것은 큐로 받은 값이라 개행이 섞이면
        // 로그 한 줄이 여러 줄로 쪼개져 없던 기록을 위조할 수 있다(clip의 다른 자리와 같은 규칙).
        log.warn("clip.collector.unavailable cause={}", cause);
        return new CollectorErrors.CollectorUnavailableException(cause);
    }
}
