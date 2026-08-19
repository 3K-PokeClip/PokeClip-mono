package com.pokeclip.chat.collector.link;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;

/**
 * 스트리머 한 명의 치지직 열쇠를 auth에서 받아온다. 계약 정본은 auth의
 * {@code ChzzkLinkResolveController}와 {@code services/README.md}「치지직 채널 연동」이다 —
 * <b>항상 HTTP 200</b>이고, 거절이면 {@code {valid:false, reason}}이 온다.
 *
 * <p><b>재시도하지 않는다.</b> 다시 물어볼 값어치가 있는지만 판정해서 돌려주고, 실제 재시도는
 * 편지를 남기는 쪽(SQS 가시성 시한)이 한다.
 */
public class ChzzkLinkClient {

    private static final Logger log = LoggerFactory.getLogger(ChzzkLinkClient.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String RESOLVE_PATH = "/internal/chzzk-link/resolve";

    /** auth의 {@code InternalTokenFilter}가 보는 이름. 빠지면 401이다. */
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    /**
     * <b>다시 물어도 답이 안 바뀌는 사유만</b> 여기 있다. 스트리머가 새로 동의해야 풀린다.
     *
     * <p>나머지는 전부 재시도 대상이다 — {@code REFRESH_UNAVAILABLE}(auth가 잠깐 갱신에
     * 실패), <b>auth에 못 닿거나 5xx라 사유가 아예 안 온 경우</b>, 그리고 auth가 나중에
     * 추가한 우리가 모르는 사유. 모르는 것을 「지운다」로 떨어뜨리면 그 방송은 채팅이
     * 통째로 안 걷히는데 아무 데도 안 남는다.
     */
    private static final Set<String> PERMANENT_REASONS = Set.of("NOT_LINKED", "UNLINKED", "BROKEN");

    private final RestClient restClient;
    private final String baseUrl;
    private final String internalToken;

    /**
     * <b>{@code RestClient.create()}를 쓰지 않는다.</b> 그것은 자동 설정을 우회해
     * {@code spring.http.clients.*}의 시한이 어디에도 안 걸리고, auth가 연결만 받고 답을
     * 안 하면 편지 처리가 무기한 매달린다 — 그동안 <b>다른 방송의 시작 편지도 안 처리된다.</b>
     * 이 서버가 이미 한 번 데인 자리다({@code CollectorRunner} 주석·{@code CLAUDE.md}).
     *
     * <p>설정 검증이 여기서 돈다. 이 클라이언트를 빈으로 올리는 부팅은 토큰이 비면 죽는다.
     */
    public ChzzkLinkClient(RestClient.Builder restClientBuilder, LinkProperties properties) {
        properties.validate();
        this.restClient = restClientBuilder.build();
        this.baseUrl = properties.authBaseUrl();
        this.internalToken = properties.internalToken();
    }

    public LinkResolution resolve(long userId) {
        try {
            String body = restClient.post()
                    .uri(baseUrl + RESOLVE_PATH)
                    .header(INTERNAL_TOKEN_HEADER, internalToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    // 회원 번호는 long이라 문자열로 이어도 JSON이 깨지지 않는다.
                    .body("{\"userId\":" + userId + "}")
                    .retrieve()
                    .body(String.class);
            return read(userId, body);
        } catch (RestClientResponseException e) {
            // 본문·예외 메시지를 안 찍는다 — RestClientResponseException의 메시지에는
            // 응답 본문이 딸려 오고, 그 안에 토큰이 있을 수 있다.
            return unavailable(userId, "status=" + e.getStatusCode().value());
        } catch (Exception e) {
            // 못 닿음·시한 초과·본문이 JSON이 아님이 여기로 온다. 사유가 없는 것은
            // 「연동이 없다」가 아니라 「모른다」다.
            return unavailable(userId, "cause=" + e.getClass().getSimpleName());
        }
    }

    /** {@code JacksonException}은 unchecked라 컴파일러가 안 잡는다 — 위 catch가 받는다. */
    private LinkResolution read(long userId, String body) {
        JsonNode json = MAPPER.readTree(body);
        if (!json.path("valid").asBoolean(false)) {
            String reason = json.path("reason").asString("");
            boolean retryable = !PERMANENT_REASONS.contains(reason);
            // WARN이 아닌 이유는 미연동 스트리머의 방송 시작이 정상 트래픽이기 때문이다(auth와 같다).
            log.info("chat.link.rejected userId={} reason={} retryable={}", userId, reason, retryable);
            return LinkResolution.refused(retryable);
        }
        String channelId = json.path("channelId").asString("");
        String accessToken = json.path("accessToken").asString("");
        Instant expiresAt = parseExpiry(json.path("expiresAt").asString(""));
        if (channelId.isBlank() || accessToken.isBlank() || expiresAt == null) {
            // <b>허락이라면서 필수 칸이 빠졌다 — 계약 위반이지 「잠깐 아픔」이 아니다.</b>
            // 그대로 통과시키면 빈 토큰으로 치지직에 붙으러 가고, 수립이 실패하면
            // LinkedSessionStarter가 RETRY_LATER를 돌려준다. auth는 매번 같은 응답을
            // 주므로 <b>그 FIFO 그룹이 영원히 막힌다.</b> 재시도로 안 낫는 것은 재시도
            // 대상에 두지 않는다 — 편지를 지우고 이 줄로 드러낸다.
            //
            // <b>값이 아니라 있고 없음만 싣는다.</b> accessToken은 이 응답의 전부이고
            // 이 로그는 운영에서 수집된다.
            log.warn("chat.link.incomplete userId={} channelIdMissing={} accessTokenMissing={} "
                            + "expiresAtUnreadable={}",
                    userId, channelId.isBlank(), accessToken.isBlank(), expiresAt == null);
            return LinkResolution.refused(false);
        }
        // 토큰·채널 ID는 안 찍는다. 남은 수명은 방송 중에 열쇠가 죽는 사고를 추적하는 값이다.
        log.info("chat.link.resolved userId={} expiresAt={}", userId, expiresAt);
        return LinkResolution.granted(channelId, accessToken, expiresAt);
    }

    /**
     * 못 읽으면 {@code null}이다. <b>예외로 내보내지 않는다</b> — {@link #resolve}의
     * {@code catch (Exception)}이 그것을 「auth에 못 닿았다」로 바꿔, <b>영영 안 낫는
     * 계약 위반을 재시도 대상으로</b> 만든다(로컬 리뷰 1라운드에서 재현).
     */
    private static Instant parseExpiry(String raw) {
        try {
            return raw.isBlank() ? null : Instant.parse(raw);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private LinkResolution unavailable(long userId, String detail) {
        // WARN이다 — 거절과 달리 이건 우리 쪽 장애이고, 편지가 큐에 쌓이는 동안 사람이 봐야 한다.
        log.warn("chat.link.unavailable userId={} {}", userId, detail);
        return LinkResolution.refused(true);
    }
}
