package com.pokeclip.clip.delegation;

import com.pokeclip.clip.config.InternalApiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 「이 회원이 이 스트리머의 방송을 열 수 있나」를 auth에 묻는다. 계약 정본은 auth의
 * {@code DelegationResolveController}다 — <b>판정은 항상 HTTP 200</b>이고 {@code NONE}도 200이다.
 * 거절할지 말지는 이쪽이 정한다.
 *
 * <p><b>clip이 다른 서버를 HTTP로 부르는 첫 코드다.</b> 앞으로 방송 목록(POK-174)·영상
 * 출입증(POK-122)이 이 클라이언트를 그대로 재사용한다.
 *
 * <p><b>예외를 던지지 않는다.</b> 못 물으면 {@link ResolveResult#UNAVAILABLE}이다 —
 * 부르는 쪽이 「권한 없음」과 「모름」을 다른 응답으로 갈라야 하는데, 예외로 내보내면
 * 그 구분이 호출부의 catch 모양에 달리게 된다.
 *
 * <p><b>재시도하지 않는다.</b> 이 호출은 사람이 기다리는 요청 안에서 돈다 — 한 번 더 걸면
 * 시한이 두 배가 되고 톰캣 스레드를 그만큼 더 쥔다.
 */
@Component
public class DelegationResolveClient {

    private static final Logger log = LoggerFactory.getLogger(DelegationResolveClient.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String RESOLVE_PATH = "/internal/editor-delegations/resolve";

    private static final String ACCESSIBLE_PATH = "/internal/editor-delegations/accessible";

    /** auth의 {@code InternalTokenFilter}가 보는 이름. 빠지면 401이다. */
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private final RestClient restClient;
    private final String baseUrl;
    private final String internalToken;

    /**
     * <b>{@code RestClient.create()}를 쓰지 않는다.</b> 그것은 자동 설정을 우회해
     * {@code spring.http.clients.*}의 시한이 어디에도 안 걸리고, auth가 연결만 받고 답을
     * 안 하면 <b>미리보기 요청이 톰캣 스레드를 무기한 쥔다</b>. 증상이 조용하다 — 평소엔
     * 응답이 빨라 아무 일도 없다. 이 저장소가 이미 한 번 데인 자리다
     * ({@code chat-collector/CLAUDE.md} — 설정 파일은 완벽한데 시한이 어디에도 안 걸렸고
     * 검토 일곱 바퀴가 못 잡았다). {@code DelegationResolveClientTest.시한이_실제로_걸려_있다}가
     * 값이 아니라 행동으로 지킨다.
     *
     * <p>설정 검증이 여기서 돈다. 이 클라이언트는 조건 없이 빈이 되므로 <b>주소가 비면
     * 이 서버는 못 뜬다</b>(PRD 결정 — 자격 판정 없이 사람 문을 여는 것보다 안 뜨는 것이 낫다).
     */
    public DelegationResolveClient(RestClient.Builder restClientBuilder,
                                   AuthClientProperties properties,
                                   InternalApiProperties internalApi) {
        properties.validate();
        this.restClient = restClientBuilder.build();
        this.baseUrl = properties.baseUrl();
        this.internalToken = internalApi.token();
    }

    public ResolveResult resolve(long userId, long streamerUserId) {
        try {
            String body = restClient.post()
                    .uri(baseUrl + RESOLVE_PATH)
                    .header(INTERNAL_TOKEN_HEADER, internalToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    // 회원 번호는 long이라 문자열로 이어도 JSON이 깨지지 않는다.
                    // 칸 이름은 auth의 DelegationResolveRequest 그대로다 —
                    // streamerId가 아니라 streamerUserId이고, 틀리면 @Valid가 400을 준다.
                    .body("{\"userId\":" + userId + ",\"streamerUserId\":" + streamerUserId + "}")
                    .retrieve()
                    .body(String.class);
            return read(userId, body);
        } catch (RestClientResponseException e) {
            // 본문·예외 메시지를 안 찍는다 — RestClientResponseException의 메시지에는
            // 응답 본문이 딸려 오고, 그 안에 무엇이 들었는지는 auth가 정한다.
            return unavailable(userId, "status=" + e.getStatusCode().value());
        } catch (Exception e) {
            // 못 닿음·시한 초과·본문이 JSON이 아님이 여기로 온다.
            return unavailable(userId, e.getClass().getSimpleName());
        }
    }

    /** {@code JacksonException}은 unchecked라 컴파일러가 안 잡는다 — 위 catch가 받는다. */
    private ResolveResult read(long userId, String body) {
        JsonNode json = MAPPER.readTree(body);
        String relation = json.path("relation").asString("");
        return switch (relation) {
            case "OWNER" -> ResolveResult.OWNER;
            case "EDITOR" -> ResolveResult.EDITOR;
            case "NONE" -> ResolveResult.NONE;
            // auth가 값을 늘리는 날 <b>통과가 아니라 거절이 기본</b>이다. NONE으로 접지 않는
            // 이유는, 그 사이 진짜로 생긴 권한을 「없다」로 단정하지 않기 위해서다 —
            // 둘 다 문을 안 열지만 응답 코드와 로그가 다르다.
            //
            // 받은 값 자체는 안 싣는다. auth가 주는 값이지만 이 로그는 운영에서 수집되고,
            // 개행이 섞이면 한 줄이 여러 줄로 쪼개져 없던 기록을 위조할 수 있다
            // (clip/CLAUDE.md 「알려진 구멍」의 같은 모양). 무엇이 왔는지는 auth 쪽 열거형에서 본다.
            default -> unavailable(userId, "unknown_relation");
        };
    }

    /**
     * 이 회원이 볼 수 있는 스트리머 전부. 방송 목록 화면이 쓴다.
     *
     * <p><b>목록에 {@code NONE}은 안 나온다</b> — 없는 것이 곧 {@code NONE}이다.
     * 그리고 <b>없는 회원 번호에도 자기 자신이 {@code OWNER}로 한 줄 온다</b>(auth가 회원 표를
     * 안 읽는다). 그 번호로 방송을 찾으면 0건이라 목록에 안 나타나므로 그대로 둔다.
     *
     * <p>{@link #resolve}와 같이 <b>예외를 던지지 않는다.</b> 못 물으면 {@code available=false}다.
     */
    public AccessibleResult accessible(long userId) {
        try {
            String body = restClient.post()
                    .uri(baseUrl + ACCESSIBLE_PATH)
                    .header(INTERNAL_TOKEN_HEADER, internalToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    // 🔴 칸은 userId 하나다. streamerId 같은 옛 이름을 같이 실으면 auth가
                    // 400이 아니라 200을 주고(모르는 필드를 조용히 버린다) 오타가 안 드러난다.
                    .body("{\"userId\":" + userId + "}")
                    .retrieve()
                    .body(String.class);
            return readAccessible(userId, body);
        } catch (RestClientResponseException e) {
            return unavailableList(userId, "status=" + e.getStatusCode().value());
        } catch (Exception e) {
            return unavailableList(userId, e.getClass().getSimpleName());
        }
    }

    /**
     * 한 줄이라도 읽을 수 없으면 <b>목록 전체</b>를 거절한다. 그 줄만 버리면 볼 수 있는 방송이
     * 목록에서 조용히 사라지는데, 화면에는 그냥 없는 것으로 보인다 — 거절하면 503이라
     * 사람이 다시 누르고 로그가 남는다.
     */
    private AccessibleResult readAccessible(long userId, String body) {
        JsonNode streamers = MAPPER.readTree(body).path("streamers");
        // 🔴 칸 이름 하나만 믿으면 안 된다 — relation·streamerUserId는 안 믿으면서 이것만 믿고
        // 있었다(감사 1라운드). 칸이 없거나 배열이 아니면 순회가 통째로 비어 <b>「볼 방송이
        // 없다」는 참인 답</b>이 나가고, 화면은 그것을 단정한다. 로그도 안 남아 발견 수단이 없다.
        // 실측한 모양 여섯이 전부 그랬다: {} · null · "x" · 오타난 칸 · {} · 숫자.
        if (!streamers.isArray()) {
            return unavailableList(userId, "bad_streamers");
        }
        List<AccessibleResult.Entry> entries = new ArrayList<>();
        for (JsonNode node : streamers) {
            ResolveResult relation = switch (node.path("relation").asString("")) {
                case "OWNER" -> ResolveResult.OWNER;
                case "EDITOR" -> ResolveResult.EDITOR;
                // NONE도 여기서는 모르는 값이다 — 목록에 없는 것이 곧 NONE이라 실려 올 이유가 없고,
                // 실려 온다면 계약이 우리가 아는 것과 다르다는 뜻이다.
                default -> null;
            };
            if (relation == null) {
                return unavailableList(userId, "unknown_relation");
            }
            JsonNode id = node.path("streamerUserId");
            // 🔴 asLong()은 칸이 없거나 숫자가 아니면 조용히 0을 준다 — 그 0으로 방송을 찾으면
            // 0건이라 <b>틀린 목록이 「참인 답」으로 나간다</b>. 정수가 아닌 수도 막는다:
            // 7.9가 7로 잘리면 남의 목록이 나갈 수 있고, 회원 번호는 정수다.
            if (!id.isIntegralNumber() || !id.canConvertToLong()) {
                return unavailableList(userId, "bad_streamer_id");
            }
            entries.add(new AccessibleResult.Entry(id.longValue(), relation));
        }
        return AccessibleResult.of(entries);
    }

    private AccessibleResult unavailableList(long userId, String cause) {
        // resolve 쪽과 같은 이유로 WARN이고, 받은 값 자체는 안 싣는다 — auth가 주는 값이지만
        // 개행이 섞이면 로그 한 줄이 여러 줄로 쪼개져 없던 기록을 위조할 수 있다.
        log.warn("clip.delegation.accessible_unavailable userId={} cause={}", userId, cause);
        return AccessibleResult.unavailable();
    }

    private ResolveResult unavailable(long userId, String cause) {
        // WARN이다 — NONE과 달리 이건 우리 쪽 장애이고, 사람이 미리보기를 못 여는 동안
        // 누군가 봐야 한다. streamerUserId는 안 싣는다(누가 못 열었나만으로 추적된다).
        log.warn("clip.delegation.resolve_unavailable userId={} cause={}", userId, cause);
        return ResolveResult.UNAVAILABLE;
    }
}
