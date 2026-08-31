package com.pokeclip.chat.collector.broadcast.reattach;

import com.pokeclip.chat.collector.link.LinkProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

/**
 * clip에 「지금 방송 중인 것」을 묻는다. 계약 정본은 clip의 {@code LiveBroadcastsController}와
 * {@code services/README.md}「방송 중 목록 창구」다.
 *
 * <p><b>실패를 삼키지 않는다 — 예외로 내보낸다.</b> 빈 목록으로 접으면 「clip이 죽었다」와
 * 「방송이 하나도 없다」가 같아지고, 부르는 쪽이 그 둘을 못 가른다. 재시도는 부르는 쪽의
 * 주기가 한다.
 */
public class LiveBroadcastClient {

    private static final Logger log = LoggerFactory.getLogger(LiveBroadcastClient.class);

    private static final String LIVE_PATH = "/internal/broadcasts/live";

    /** clip의 {@code InternalTokenFilter}가 보는 이름. 빠지면 401이다. */
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private final RestClient restClient;
    private final String baseUrl;
    private final String internalToken;

    /**
     * <b>{@code RestClient.create()}를 쓰지 않는다.</b> 그것은 자동 설정을 우회해
     * {@code spring.http.clients.*}의 시한이 어디에도 안 걸리고, clip이 연결만 받고 답을
     * 안 하면 재부착 회차가 무기한 매달린다. 이 서버가 이미 한 번 데인 자리다
     * ({@code CollectorRunner} 주석·{@code CLAUDE.md}).
     *
     * <p>설정 검증 둘이 여기서 돈다.
     * <b>{@code link.validate()}가 {@code authBaseUrl}까지 보는 것이 맞다</b> — 재부착은
     * clip에 묻고 나서 <b>auth에 열쇠를 물어야</b> 붙일 수 있다({@code LinkedSessionStarter}를
     * 그대로 탄다). auth 주소가 없으면 재부착은 목록만 받고 하나도 못 붙는다 — 그 상태로
     * 뜨면 안 된다.
     */
    public LiveBroadcastClient(RestClient.Builder builder,
                               ReattachProperties reattach, LinkProperties link) {
        reattach.validate();
        link.validate();
        this.restClient = builder.build();
        this.baseUrl = reattach.clipBaseUrl();
        this.internalToken = link.internalToken();
    }

    /** @throws RuntimeException clip에 못 닿거나, 2xx가 아니거나, 본문을 못 읽으면 */
    public LiveBroadcasts list() {
        LiveBroadcasts result = restClient.get()
                .uri(baseUrl + LIVE_PATH)
                .header(INTERNAL_TOKEN_HEADER, internalToken)
                .retrieve()
                .body(LiveBroadcasts.class);
        if (result == null) {
            throw new IllegalStateException("clip 방송 중 목록 응답이 비었다");
        }
        if (result.truncated()) {
            // 상한에 닿는 것 자체가 「명부가 이상하다」는 신호다 — clip 쪽 별도 카드.
            log.warn("chat.reattach.live_list_truncated received={}", result.broadcasts().size());
        }
        return result;
    }
}
