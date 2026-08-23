package com.pokeclip.chat.collector.status;

import ch.qos.logback.classic.Level;
import com.pokeclip.chat.collector.ChatLogLeakTest;
import com.pokeclip.chat.collector.broadcast.EndedStreamStore;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 창구를 <b>밖에서</b> 친다. 등록부에 세션이 없는 프로세스라 답은 메모 표와 unknown뿐이다 —
 * 상태 전이는 {@code ChatCollectionStatusResolverTest}가 가짜 치지직으로 잰다(두 겹, health 검사와 같은 구조).
 * 여기는 직렬화(시각이 ISO 문자열인가·null이 null로 나가나)·필터·경로·health 분리를 본다.
 */
class ChatCollectionEndpointTest {

    /** 유출 검사의 바늘이기도 하다 — 리터럴로 흩어 두면 무엇을 찾는 값인지가 안 보인다. */
    static final String TOKEN = "test-internal-token";

    /** 들어오는 요청 바이트를 통째로 찍는 톰캣 로거의 부모. application.yml이 info로 박아 둔다. */
    static final String COYOTE = "org.apache.coyote";

    static HttpResponse<String> get(int port, String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) request.header("X-Internal-Token", token);
        return HttpClient.newHttpClient().send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = "pokeclip.link.internal-token=" + TOKEN)
    @ActiveProfiles("test")
    static class 토큰이_설정된_프로세스 extends IntegrationTestSupport {

        @LocalServerPort int port;
        @Autowired EndedStreamStore store;
        @Autowired JdbcTemplate jdbc;
        @Autowired Environment environment;

        @BeforeEach
        void 표를_비운다() {
            jdbc.update("DELETE FROM chat_ended_streams");
        }

        @Test
        void 모르는_방송도_200이고_unknown이다() throws Exception {
            HttpResponse<String> response = get(port, "/internal/streams/never/chat-collection", TOKEN);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body())
                    .contains("\"streamId\":\"never\"")
                    .contains("\"state\":\"unknown\"")
                    .contains("\"since\":null")
                    .contains("\"attempt\":null")
                    .contains("\"needsRelink\":false")
                    .contains("\"observedAt\":\"");
        }

        // 문항 4: state만 보면 본문에 JSON이 둘 실려도 참이다 — 상태 코드와 streamId를 같이 본다.
        @Test
        void 포기_메모가_있으면_stopped이고_시각은_ISO_문자열이다() throws Exception {
            store.rememberStopped("s1", "REVOKED", Instant.parse("2026-08-22T12:00:00Z"));
            HttpResponse<String> response = get(port, "/internal/streams/s1/chat-collection", TOKEN);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body())
                    .contains("\"streamId\":\"s1\"")
                    .contains("\"state\":\"stopped\"")
                    .contains("\"since\":\"2026-08-22T12:00:00Z\"")
                    .contains("\"needsRelink\":true")
                    .as("내부 사유 이름은 밖에 안 나간다").doesNotContain("REVOKED");
        }

        @Test
        void 토큰이_틀리면_401_본문_없음() throws Exception {
            HttpResponse<String> response = get(port, "/internal/streams/s1/chat-collection", "wrong");
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).isEmpty();
        }

        /**
         * <b>들어오는</b> 요청 헤더가 로그로 새는 자리. 이 서버는 <b>나가는</b> 길만 다섯 줄로 막아 뒀고
         * (jdbc·postgresql·awssdk·httpclient5·netty) 들어오는 HTTP는 안 막혀 있었다 —
         * POK-128 이전에는 컨트롤러가 0개라 요청에 비밀이 실릴 일이 없었다. 이 카드가 연 새 면적이다.
         *
         * <p>실측(2026-08-22 critic): root를 TRACE로 내리면 톰캣 {@code Http11InputBuffer}가 받은 바이트를
         * {@code "Received [GET /internal/... X-Internal-Token: <값> ...]"}로 통째로 찍는다
         * (root INFO 0건 · DEBUG 0건 · <b>TRACE 1건</b>). 새는 값이 {@code INTERNAL_API_TOKEN}이라
         * 채팅 본문 한 줄보다 파급이 크고, 사람이 TRACE를 켜는 순간은 정확히 이 창구를 디버깅할 때다.
         * 그래서 {@code application.yml}이 {@code org.apache.coyote}를 info로 박았고, 이 검사는
         * 운영자가 {@code LOGGING_LEVEL_ROOT=trace}로 내린 상황 그대로 재현해 <b>행동으로</b> 잰다.
         * yml 줄을 지우면 빨간불이다(주입 확인함).
         *
         * <p>탐지기는 {@link ChatLogLeakTest}의 것을 그대로 쓴다 — 포맷된 한 줄만 보면
         * {@code log.error("...", e)}로 새는 값을 못 잡는다(자기검사 셋이 거기 있다).
         *
         * <p>문항 2(자동으로 참이 되는 입력): 요청이 톰캣을 안 지나가면 부정 단언이 공짜로 통과한다 —
         * 상태 코드 200을 먼저 단언한다. 문항 4(통과시키는 잘못된 결과): 프레임워크가 바뀌어 이 로거가
         * 아예 조용해져도 부정 단언은 참이다 — 뒤의 양성 대조가 그것을 가른다.
         */
        @Test
        void 창구를_쳐도_내부_토큰이_root_TRACE에서_로그에_안_남는다() throws Exception {
            // 두 겹 — yml에 박혀 있나(Environment) + 부팅이 logback까지 박았나.
            // 기본값을 주지 않는다: 주면 yml에서 줄이 사라져도 초록이다.
            String level = environment.getProperty("logging.level." + COYOTE);
            assertThat(level).as("application.yml에 " + COYOTE + " 레벨이 박혀 있어야 root를 내려도 버틴다")
                    .isNotNull();
            assertThat(Level.toLevel(level, Level.TRACE).toInt())
                    .as(COYOTE + "가 이 레벨이면 아래 양성 대조가 재현하는 유출이 열린다: " + level)
                    .isGreaterThanOrEqualTo(Level.INFO.toInt());
            assertThat(ChatLogLeakTest.levelOf(COYOTE))
                    .as(COYOTE + " 프로퍼티가 Environment에만 있고 logback까지 안 닿았다").isNotNull();

            try (LogCaptor captor = new LogCaptor()) {
                // ① root TRACE — 구체 로거의 info가 이겨야 한다.
                Level rootBefore = ChatLogLeakTest.levelOf(Logger.ROOT_LOGGER_NAME);
                ChatLogLeakTest.setLevel(Logger.ROOT_LOGGER_NAME, Level.TRACE);
                try {
                    assertThat(get(port, "/internal/streams/leak-probe/chat-collection", TOKEN).statusCode())
                            .as("요청이 톰캣을 안 지나갔다면 아래 부정 단언은 아무것도 안 본 것이다")
                            .isEqualTo(200);
                } finally {
                    ChatLogLeakTest.setLevel(Logger.ROOT_LOGGER_NAME, rootBefore);
                }
                ChatLogLeakTest.assertNoSecretsIn(ChatLogLeakTest.renderAll(captor), List.of(TOKEN));

                // ② 양성 대조 — 이 로거를 직접 TRACE로 밀면 실제로 새야 한다. 안 새면 톰캣이 바뀐
                //    것이고, 그때는 위 단언이 아무것도 안 본 채 초록인 상태이니 yml 주석을 다시 볼 때다.
                Level before = ChatLogLeakTest.levelOf(COYOTE);
                ChatLogLeakTest.setLevel(COYOTE, Level.TRACE);
                try {
                    get(port, "/internal/streams/leak-probe/chat-collection", TOKEN);
                } finally {
                    ChatLogLeakTest.setLevel(COYOTE, before);
                }
                assertThat(captor.events())
                        .as(COYOTE + "를 TRACE로 밀어도 요청 헤더가 안 새면 yml에 박은 근거를 다시 볼 때다")
                        .anyMatch(e -> e.getLoggerName().startsWith(COYOTE)
                                && ChatLogLeakTest.renderFully(e).contains(TOKEN));
            }
        }

        @Test
        void health는_토큰_없이_그대로_200이다() throws Exception {
            HttpResponse<String> response = get(port, "/actuator/health", null);
            assertThat(response.statusCode()).as("창구와 경로가 분리돼 있다 — 카드 완료 조건 ③").isEqualTo(200);
        }
    }

    /**
     * 토큰 설정이 없는 프로세스(CI·팀원 로컬과 같은 모양). 창구는 잠겨 있어야 한다.
     * 빈 값을 <b>명시</b>한다 — 셸에 INTERNAL_API_TOKEN이 있는 기계에서는 yml 기본값이 그 값을 읽어 이 검사가 틀어진다(critic).
     */
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = "pokeclip.link.internal-token=")
    @ActiveProfiles("test")
    static class 토큰이_빈_프로세스 extends IntegrationTestSupport {

        @LocalServerPort int port;

        // 문항 5: InternalTokenFilter의 `locked ||`를 지우면 빈 헤더 쪽이 200으로 빨간불(태스크 4에서 확인함).
        @Test
        void 빈_헤더로도_401이다() throws Exception {
            assertThat(get(port, "/internal/streams/s1/chat-collection", "").statusCode()).isEqualTo(401);
            assertThat(get(port, "/internal/streams/s1/chat-collection", null).statusCode()).isEqualTo(401);
        }
    }
}
