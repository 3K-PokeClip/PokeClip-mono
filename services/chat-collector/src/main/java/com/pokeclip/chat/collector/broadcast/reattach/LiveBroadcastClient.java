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

    /** @throws RuntimeException clip에 못 닿거나, 2xx가 아니거나, 본문을 못 읽거나, 계약을 어기면 */
    public LiveBroadcasts list() {
        LiveBroadcasts result = restClient.get()
                .uri(baseUrl + LIVE_PATH)
                .header(INTERNAL_TOKEN_HEADER, internalToken)
                .retrieve()
                .body(LiveBroadcasts.class);
        if (result == null || result.broadcasts() == null) {
            // 🔴 <b>200인데 본문이 계약을 어겼다 — 「잠깐 아픔」이 아니다.</b> 쌍둥이
            // {@code link/ChzzkLinkClient}의 {@code chat.link.incomplete}와 같은 자리이고,
            // 이 클라이언트가 그것을 베끼면서 <b>이 방어만 안 베껴 왔다</b>(로컬 리뷰 라운드 2).
            //
            // <b>{@code broadcasts}가 빠지면 조용히 {@code null}이 된다</b> — LiveBroadcasts는
            // compact 생성자가 없는 record라 Jackson 3가 그 칸을 그냥 비운다(재현함).
            // {@code truncated}가 primitive라 그 칸이 빠지면 Jackson이 먼저 거절하는 것과 갈린다.
            //
            // <b>여기서 로그를 남기는 이유</b>: 부르는 쪽의 catch는 예외 <b>타입만</b> 찍는다
            // (주소·토큰이 메시지에 실릴 수 있어 일부러 그렇다). 그래서 메시지에 담으면
            // 아무 데도 안 나오고 {@code causeType=NullPointerException}만 남아
            // <b>clip의 계약 위반인지 우리 버그인지 구분이 안 된다.</b>
            //
            // <b>빈 목록으로 접지 않는다</b> — 클래스 javadoc 그대로다. compact 생성자로
            // 빈 리스트를 채우는 처방을 안 고른 것이 이 때문이다.
            log.warn("chat.reattach.live_list_incomplete bodyMissing={} broadcastsMissing={}",
                    result == null, result != null && result.broadcasts() == null);
            throw new IllegalStateException("clip 방송 중 목록 응답이 계약을 어겼다");
        }
        if (result.truncated()) {
            // 상한에 닿는 것 자체가 「명부가 이상하다」는 신호다 — clip 쪽 별도 카드.
            log.warn("chat.reattach.live_list_truncated received={}", result.broadcasts().size());
        }
        return result;
    }
}
