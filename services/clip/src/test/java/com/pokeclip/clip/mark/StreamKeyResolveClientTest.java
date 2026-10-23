package com.pokeclip.clip.mark;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import com.pokeclip.clip.config.InternalApiProperties;
import com.pokeclip.clip.delegation.AuthClientProperties;
import com.pokeclip.clip.delegation.AuthClientTokenLeakTest;
import com.pokeclip.clip.mark.StreamKeyResolution.Kind;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * auth의 {@code POST /internal/stream-keys/resolve}를 부르는 쪽(계약4). 응답은 언제나 200이고
 * {@code {valid, userId, passphrase, reason}}이다(NON_NULL). 거절할지 말지는 clip이 정한다.
 *
 * <p><b>진짜 컨텍스트의 {@code RestClient.Builder}로 태운다</b>: 운영과 같은 Apache HC5 스택이다.
 * 이 클라이언트는 <b>응답 본문에 비밀(passphrase)이 실려 온다</b>는 점이 판정 창구와 다르다.
 * 그 스택의 로거가 본문을 찍으면 그대로 새므로 유출 검사를 JDK 스택으로 재면 표적이 빗나간다.
 *
 * <p>UNAVAILABLE을 기대하는 갈래가 많아 「늘 UNAVAILABLE」인 구현에 대부분 초록이다. 그것을 막는
 * 양성 대조가 {@link #valid면_회원번호와_passphrase를_돌려준다()}와 거절 갈래 둘이다.
 */
class StreamKeyResolveClientTest extends IntegrationTestSupport {

    private static final String PATH = "/internal/stream-keys/resolve";

    private static final String INTERNAL_TOKEN = "internal-token-for-mark-test";

    /** 계약4 시험 벡터. 실제 키처럼 보이는 값을 쓰지 않는다. */
    private static final String VECTOR_PASSPHRASE = "EXAMPLE-4b-test-vector-not-a-key";

    /** 플러그인이 보내는 streamid 원문 모양. 토큰 자리는 시험용 글자다. */
    private static final String STREAMID = "#!::r=EXAMPLEstreamidtokentest01,m=publish";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient.Builder restClientBuilder;

    StreamKeyResolveClientTest(RestClient.Builder restClientBuilder) {
        this.restClientBuilder = restClientBuilder;
    }

    @Test
    void valid면_회원번호와_passphrase를_돌려준다() {
        AUTH.respondWith(PATH, 200,
                "{\"valid\":true,\"userId\":7,\"passphrase\":\"" + VECTOR_PASSPHRASE + "\"}");

        StreamKeyResolution result = client().resolve(STREAMID);

        assertThat(result.kind()).isEqualTo(Kind.VALID);
        // broadcasts.streamer_id가 문자열이라 여기서 바꿔 둔다. 숫자 7과 "7"이 갈리면 방송을 못 찾는다.
        assertThat(result.userId()).isEqualTo("7");
        assertThat(result.passphrase()).isEqualTo(VECTOR_PASSPHRASE);
    }

    /** 회원 번호가 int 범위를 넘어도 잘리지 않는다. */
    @Test
    void 큰_회원번호도_그대로_문자열이_된다() {
        AUTH.respondWith(PATH, 200,
                "{\"valid\":true,\"userId\":9007199254740993,\"passphrase\":\"" + VECTOR_PASSPHRASE + "\"}");

        assertThat(client().resolve(STREAMID).userId()).isEqualTo("9007199254740993");
    }

    @Test
    void MALFORMED는_MALFORMED다() {
        AUTH.respondWith(PATH, 200, "{\"valid\":false,\"reason\":\"MALFORMED\"}");

        StreamKeyResolution result = client().resolve(STREAMID);

        assertThat(result.kind()).isEqualTo(Kind.MALFORMED);
        assertThat(result.userId()).isNull();
        assertThat(result.passphrase()).isNull();
    }

    /** 없는 키와 폐기한 키는 같은 응답(401)이라 하나로 접는다. 둘을 가르면 키의 존재가 샌다. */
    @ParameterizedTest(name = "reason={0}")
    @ValueSource(strings = {"NOT_FOUND", "REVOKED"})
    void NOT_FOUND와_REVOKED는_REJECTED다(String reason) {
        AUTH.respondWith(PATH, 200, "{\"valid\":false,\"reason\":\"" + reason + "\"}");

        StreamKeyResolution result = client().resolve(STREAMID);

        assertThat(result.kind()).isEqualTo(Kind.REJECTED);
        assertThat(result.passphrase()).isNull();
    }

    /**
     * 거절이어도 passphrase가 실려 오면 버린다. 거절 결과가 비밀을 들고 다니면 뒤에서 누가 그 값으로
     * 대조할 수 있다.
     */
    @Test
    void 거절_응답에_실린_passphrase는_버린다() {
        AUTH.respondWith(PATH, 200,
                "{\"valid\":false,\"reason\":\"REVOKED\",\"userId\":7,\"passphrase\":\"" + VECTOR_PASSPHRASE + "\"}");

        StreamKeyResolution result = client().resolve(STREAMID);

        assertThat(result.kind()).isEqualTo(Kind.REJECTED);
        assertThat(result.userId()).isNull();
        assertThat(result.passphrase()).isNull();
    }

    /**
     * auth가 200이 아니면 「키가 틀렸다」가 아니라 「모른다」다(503, 플러그인이 다시 보낸다).
     * 400은 auth의 {@code @NotBlank}가 빈 streamid를 막은 것이고, 401은 내부 토큰이 갈린 것이다.
     */
    @ParameterizedTest(name = "status={0}")
    @ValueSource(ints = {400, 401, 404, 500, 503})
    void 비200은_UNAVAILABLE이고_던지지_않는다(int status) {
        AUTH.respondWith(PATH, status,
                "{\"valid\":true,\"userId\":7,\"passphrase\":\"" + VECTOR_PASSPHRASE + "\"}");

        StreamKeyResolution result = client().resolve(STREAMID);

        assertThat(result.kind()).isEqualTo(Kind.UNAVAILABLE);
        assertThat(result.passphrase()).isNull();
    }

    @Test
    void 연결이_거부되면_UNAVAILABLE이고_던지지_않는다() {
        assertThat(clientFor("http://127.0.0.1:1").resolve(STREAMID).kind()).isEqualTo(Kind.UNAVAILABLE);
    }

    /**
     * 계획 검증 E11. valid:true인데 회원 번호나 passphrase가 없으면 VALID로 내보낼 수 없다 :
     * 빈 passphrase로 대조하면 아무 키나 통과하거나(빈 키 HMAC) 예외가 나고, 회원 번호가 없으면
     * 엉뚱한 방송을 찾는다. 키가 틀린 것도 아니라서 「모른다」로 접는다.
     */
    @ParameterizedTest(name = "본문={0}")
    @ValueSource(strings = {
            "{\"valid\":true,\"passphrase\":\"EXAMPLE-4b-test-vector-not-a-key\"}",
            "{\"valid\":true,\"userId\":null,\"passphrase\":\"EXAMPLE-4b-test-vector-not-a-key\"}",
            "{\"valid\":true,\"userId\":\"7\",\"passphrase\":\"EXAMPLE-4b-test-vector-not-a-key\"}",
            "{\"valid\":true,\"userId\":7.5,\"passphrase\":\"EXAMPLE-4b-test-vector-not-a-key\"}",
            "{\"valid\":true,\"userId\":7}",
            "{\"valid\":true,\"userId\":7,\"passphrase\":null}",
            "{\"valid\":true,\"userId\":7,\"passphrase\":\"\"}",
            "{\"valid\":true,\"userId\":7,\"passphrase\":123}"})
    void valid인데_회원번호나_passphrase가_이상하면_UNAVAILABLE이다(String body) {
        AUTH.respondWith(PATH, 200, body);

        assertThat(client().resolve(STREAMID).kind()).isEqualTo(Kind.UNAVAILABLE);
    }

    /** auth가 reason을 늘리는 날 통과가 아니라 「모른다」가 기본이다. valid 칸이 이상한 것도 같다. */
    @ParameterizedTest(name = "본문={0}")
    @ValueSource(strings = {
            "{\"valid\":false,\"reason\":\"SUSPENDED\"}",
            "{\"valid\":false,\"reason\":\"malformed\"}",
            "{\"valid\":false}",
            "{\"valid\":\"true\",\"userId\":7,\"passphrase\":\"EXAMPLE-4b-test-vector-not-a-key\"}",
            "{\"userId\":7,\"passphrase\":\"EXAMPLE-4b-test-vector-not-a-key\"}",
            "{}",
            "<html>Bad Gateway</html>"})
    void 모르는_응답이면_UNAVAILABLE이다(String body) {
        AUTH.respondWith(PATH, 200, body);

        assertThat(client().resolve(STREAMID).kind()).isEqualTo(Kind.UNAVAILABLE);
    }

    /**
     * 요청이 실제로 나갔는지, 계약대로 생겼는지는 받는 쪽에서 센다. 위 갈래들은 auth를 아예 안
     * 부르는 구현에도 일부 초록이다.
     *
     * <p>streamid는 원문 그대로 가야 한다: auth가 그 글자로 키를 찾는다. 따옴표·역슬래시가 섞여도
     * JSON이 깨지지 않아야 한다(이어 붙이면 깨지거나 칸이 끼어든다).
     */
    @Test
    void 내부_토큰과_streamid를_그대로_보낸다() {
        String tricky = STREAMID + "\"},\"x\":\"\\";
        AUTH.respondWith(PATH, 200, "{\"valid\":false,\"reason\":\"NOT_FOUND\"}");

        client().resolve(tricky);

        assertThat(AUTH.callCount())
                .as("0이면 서버에 닿기 전에 끝난 것, 2 이상이면 재시도")
                .isEqualTo(1);
        assertThat(AUTH.lastPath()).isEqualTo(PATH);
        assertThat(AUTH.lastToken()).isEqualTo(INTERNAL_TOKEN);
        JsonNode sent = MAPPER.readTree(AUTH.lastBody());
        assertThat(sent.path("streamid").asString()).isEqualTo(tricky);
        assertThat(sent.propertyNames()).containsExactly("streamid");
    }

    /** 결과가 로그·예외 메시지에 통째로 찍혀도 passphrase가 안 나와야 한다. */
    @Test
    void 결과의_toString에_passphrase가_없다() {
        AUTH.respondWith(PATH, 200,
                "{\"valid\":true,\"userId\":7,\"passphrase\":\"" + VECTOR_PASSPHRASE + "\"}");

        StreamKeyResolution result = client().resolve(STREAMID);

        assertThat(result.passphrase())
                .as("양성 대조: passphrase를 실제로 들고 있어야 toString 검사가 뜻이 있다")
                .isEqualTo(VECTOR_PASSPHRASE);
        assertThat(result.toString())
                .doesNotContain(VECTOR_PASSPHRASE)
                .contains("VALID");
    }

    /**
     * 🔴 <b>root를 TRACE로 올리고 잰다</b>(계획 검증 E12). INFO에서 재면 DEBUG·TRACE로 새는 구현이
     * 늘 초록이다. streamid(요청 본문)와 passphrase(응답 본문)·내부 토큰이 어떤 로그에도 없어야 한다.
     * 갈래는 성공·거절·비200·못 닿음·이상한 본문을 다 태운다.
     */
    @Test
    void 로그에_streamid와_passphrase와_내부_토큰이_없다() {
        try (LogCaptor captor = new LogCaptor()) {
            AuthClientTokenLeakTest.setLevel(org.slf4j.Logger.ROOT_LOGGER_NAME, Level.TRACE);
            // application.yml이 org.springframework.web·org.apache.hc.client5.http를 info로 박아 둔
            // 것은 운영 설정 그대로 둔다: 사람이 root를 TRACE로 켜는 날의 모양을 재는 것이다.
            // 그 둘 밖(JSON 매퍼·HTTP 코어·우리 패키지)은 전부 TRACE다.
            assertThat(((LoggerContext) LoggerFactory.getILoggerFactory())
                    .getLogger(StreamKeyResolveClient.class).getEffectiveLevel())
                    .as("TRACE가 실제로 걸렸는가: 안 걸렸으면 아래 「안 샜다」는 공허하다")
                    .isEqualTo(Level.TRACE);

            AUTH.respondWith(PATH, 200,
                    "{\"valid\":true,\"userId\":7,\"passphrase\":\"" + VECTOR_PASSPHRASE + "\"}");
            assertThat(client().resolve(STREAMID).kind())
                    .as("호출이 성립하지 않았으면 샐 것도 없다")
                    .isEqualTo(Kind.VALID);

            AUTH.respondWith(PATH, 200, "{\"valid\":false,\"reason\":\"REVOKED\"}");
            client().resolve(STREAMID);

            AUTH.respondWith(PATH, 500,
                    "{\"valid\":true,\"userId\":7,\"passphrase\":\"" + VECTOR_PASSPHRASE + "\"}");
            client().resolve(STREAMID);

            AUTH.respondWith(PATH, 200, "{\"valid\":true,\"userId\":7,\"passphrase\":\"\"}");
            client().resolve(STREAMID);

            AUTH.respondWith(PATH, 200, "<html>" + VECTOR_PASSPHRASE + "</html>");
            client().resolve(STREAMID);

            clientFor("http://127.0.0.1:1").resolve(STREAMID);

            // 양성 대조: 「모른다」 갈래 넷이 실제로 로그를 남겼다.
            assertThat(captor.messages())
                    .filteredOn(m -> m.startsWith("clip.mark."))
                    .as("비200·빈 passphrase·JSON 아님·못 닿음 넷만 남는다: 성공·거절은 안 찍는다")
                    .hasSize(4)
                    .allMatch(m -> m.startsWith("clip.mark.resolve_unavailable cause="));

            AuthClientTokenLeakTest.assertNoSecretsIn(captor,
                    List.of(STREAMID, "EXAMPLEstreamidtokentest01", VECTOR_PASSPHRASE, INTERNAL_TOKEN));
        }
    }

    private StreamKeyResolveClient client() {
        return clientFor(AUTH.baseUrl());
    }

    private StreamKeyResolveClient clientFor(String baseUrl) {
        return new StreamKeyResolveClient(
                restClientBuilder, new AuthClientProperties(baseUrl), new InternalApiProperties(INTERNAL_TOKEN));
    }
}
