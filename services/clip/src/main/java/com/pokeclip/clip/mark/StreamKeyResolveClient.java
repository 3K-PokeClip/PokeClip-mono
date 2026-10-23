package com.pokeclip.clip.mark;

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

import java.util.Map;

/**
 * 수동 마킹이 보낸 스트림키(streamid 원문)를 auth의 {@code POST /internal/stream-keys/resolve}에 묻는다
 * (계약4). auth는 판정을 늘 200으로 주고 {@code {valid, userId, passphrase, reason}}을 NON_NULL로 싣는다.
 * 거절할지 말지는 이쪽이 정한다.
 *
 * <p>모양은 {@link com.pokeclip.clip.delegation.DelegationResolveClient}를 따른다: 주입받은 빌더
 * (시한이 걸린다), {@code X-Internal-Token}, <b>예외를 안 던지고</b> 못 물으면
 * {@link StreamKeyResolution.Kind#UNAVAILABLE}. 재시도하지 않는 것도 같다(그쪽 주석의
 * {@code HttpClientRetryConfig} 절이 이 클라이언트에도 걸린다: 같은 빌더라서).
 *
 * <p>🔴 <b>판정 창구와 다른 점: 응답 본문에 비밀(passphrase)이 실려 오고, 요청 본문(streamid)도 비밀이다.</b>
 * 그래서 어느 쪽 본문도, 받은 값도, 예외 메시지도 로그에 안 싣는다. 남기는 것은 원인 종류뿐이다.
 */
@Component
public class StreamKeyResolveClient {

    private static final Logger log = LoggerFactory.getLogger(StreamKeyResolveClient.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String RESOLVE_PATH = "/internal/stream-keys/resolve";

    /** auth의 공용 내부 체인이 보는 이름. 전용 헤더({@code X-Stream-Key-Resolve-Token})는 Media 몫이다. */
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private final RestClient restClient;
    private final String baseUrl;
    private final String internalToken;

    /** {@code RestClient.create()}를 안 쓰는 이유는 {@code DelegationResolveClient} 생성자 주석과 같다(시한). */
    public StreamKeyResolveClient(RestClient.Builder restClientBuilder,
                                  AuthClientProperties properties,
                                  InternalApiProperties internalApi) {
        properties.validate();
        this.restClient = restClientBuilder.build();
        this.baseUrl = properties.baseUrl();
        this.internalToken = internalApi.token();
    }

    public StreamKeyResolution resolve(String streamid) {
        try {
            String body = restClient.post()
                    .uri(baseUrl + RESOLVE_PATH)
                    .header(INTERNAL_TOKEN_HEADER, internalToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    // 이어 붙이지 않는다. streamid는 플러그인이 보낸 글자라 따옴표·역슬래시가 들어올 수 있고,
                    // 이어 붙이면 JSON이 깨지거나 칸이 끼어든다.
                    .body(MAPPER.writeValueAsString(Map.of("streamid", streamid)))
                    .retrieve()
                    .body(String.class);
            return read(body);
        } catch (RestClientResponseException e) {
            // 예외 메시지에 응답 본문이 딸려 온다: 거기 passphrase가 있을 수 있다. 상태 코드만 싣는다.
            return unavailable("status=" + e.getStatusCode().value());
        } catch (Exception e) {
            // 못 닿음·시한 초과·본문이 JSON이 아님. 메시지는 안 싣는다(파서 메시지는 본문 조각을 인용한다).
            return unavailable(e.getClass().getSimpleName());
        }
    }

    private StreamKeyResolution read(String body) {
        JsonNode json = MAPPER.readTree(body);
        JsonNode valid = json.path("valid");
        if (!valid.isBoolean()) {
            return unavailable("bad_valid");
        }
        if (valid.booleanValue()) {
            return readValid(json);
        }
        // 거절 응답에 다른 칸이 실려 와도 안 옮긴다: 거절 결과가 비밀을 들고 다니지 않게.
        return switch (json.path("reason").asString("")) {
            case "MALFORMED" -> StreamKeyResolution.malformed();
            case "NOT_FOUND", "REVOKED" -> StreamKeyResolution.rejected();
            // auth가 값을 늘리는 날 통과도 401도 아니라 「모른다」가 기본이다. 받은 값은 안 싣는다
            // (개행이 섞이면 로그 한 줄이 쪼개진다: DelegationResolveClient와 같은 이유).
            default -> unavailable("unknown_reason");
        };
    }

    /**
     * 계획 검증 E11. valid:true인데 칸이 비면 VALID로 못 내보낸다: 빈 passphrase로는 대조할 수 없고,
     * 회원 번호가 없거나 정수가 아니면 엉뚱한 방송을 찾는다. 키가 틀린 것도 아니라 「모른다」다.
     */
    private StreamKeyResolution readValid(JsonNode json) {
        // 이 정수 검사와 read()의 valid boolean 검사는 로그 원인용이다: 빼도 Jackson 3의 longValue()·booleanValue()가
        // 던져 바깥 catch에서 UNAVAILABLE이 된다(주입으로 쟀다). 결과가 아니라 cause 글자를 지킨다.
        JsonNode userId = json.path("userId");
        if (!userId.isIntegralNumber() || !userId.canConvertToLong()) {
            return unavailable("bad_user_id");
        }
        JsonNode passphrase = json.path("passphrase");
        if (!passphrase.isString() || passphrase.asString().isEmpty()) {
            return unavailable("bad_passphrase");
        }
        return StreamKeyResolution.valid(String.valueOf(userId.longValue()), passphrase.asString());
    }

    private StreamKeyResolution unavailable(String cause) {
        // WARN이다: 키가 틀린 사람이 아니라 우리 쪽 장애이고, 그동안 모든 마킹이 503이다.
        // 회원 번호도 안 싣는다: 이 갈래에서는 아직 누구인지 모른다.
        log.warn("clip.mark.resolve_unavailable cause={}", cause);
        return StreamKeyResolution.unavailable();
    }
}
