package com.pokeclip.chat.collector.sync;

import ch.qos.logback.classic.Level;
import com.pokeclip.chat.collector.ChatLogLeakTest;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import com.pokeclip.chat.collector.support.StreamSegmentsFixture;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 영상 위치 창구를 <b>밖에서</b> 친다. 변환 규칙 자체는 {@link VideoPositionCalculatorTest}가
 * 실 PG에서 전수로 재고, 여기는 <b>창구가 더하는 것</b>만 본다 — 시각 파라미터 두 형식 ·
 * 400 본문 · 직렬화(위치 없는 판정에서 {@code null}이 {@code null}로 나가나) · 필터 · 500 그대로.
 *
 * <p><b>방송 번호 접두는 {@code api-}다.</b> 조각 장부는 Flyway 밖이라 컨텍스트가 갈려도 표가
 * 하나이고 {@code ledger-}·{@code calc-}·{@code bind-}와 같은 컨테이너를 쓴다.
 */
class VideoPositionEndpointTest {

    /**
     * 유출 검사의 바늘이기도 하다. <b>{@code ChatCollectionEndpointTest}와 값을 맞춰 둔다</b>
     * (그쪽 상수는 package-private이라 참조할 수 없다). 원래 그 목적은 두 창구 검사가 같은
     * 스프링 컨텍스트를 공유하는 것이었는데, <b>지금은 공유하지 않는다</b> — 아래
     * {@link 토큰이_설정된_프로세스}가 보정값을 하나 더 박아 캐시 키가 갈렸다(2026-08-24).
     * 값을 그대로 맞춰 두는 것은 <b>두 창구가 같은 문을 쓴다는 것이 한눈에 보여야</b> 하고,
     * 프로퍼티를 도로 맞추면 언제든 다시 공유되기 때문이다. 대가는 컨텍스트 하나가 더 뜨는 것뿐이다.
     */
    static final String TOKEN = "test-internal-token";

    /** 들어오는 요청 바이트를 통째로 찍는 톰캣 로거의 부모. application.yml이 info로 박아 둔다. */
    static final String COYOTE = "org.apache.coyote";

    private static final Instant T0 = Instant.parse("2026-08-24T00:00:00Z");

    private static final String NORMAL = "api-normal";
    private static final String EMPTY = "api-empty";

    static HttpResponse<String> get(int port, String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) request.header("X-Internal-Token", token);
        return HttpClient.newHttpClient().send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * 조각 장부를 지운 채 창구를 한 번 치고 <b>반드시 되살린다</b> — 이 표는 Flyway 밖이라
     * 스프링 컨텍스트가 갈려도 하나이고, 안 되살리면 뒤에 도는 검사가 전부 깨진다.
     */
    static HttpResponse<String> 표를_지우고_친다(int port, String token, JdbcTemplate jdbc) throws Exception {
        try {
            StreamSegmentsFixture.dropTable(jdbc);
            return get(port, "/internal/streams/" + NORMAL + "/video-position?messageTime=" + T0, token);
        } finally {
            StreamSegmentsFixture.ensureTable(jdbc);
        }
    }

    /**
     * 500 본문이 <b>스프링 기본 네 필드({@code timestamp}·{@code status}·{@code error}·{@code path})
     * 말고는 아무것도 안 싣는지</b> 잰다.
     *
     * <p><b>「무엇이 없어야 하나」가 아니라 「무엇만 있어야 하나」다.</b> 예외 메시지가 실리는
     * 순간 SQL 전문과 DB 오류가 통째로 나가므로 아래 부정 단언은 <b>자동으로 참이 아니다</b> —
     * {@link 오류_본문에_예외_메시지를_실은_프로세스}가 그것을 실물로 보인다.
     */
    static void assertNoExceptionDetailIn(String body) {
        assertThat(body)
                .as("스프링 기본 500 본문 네 필드 말고는 아무것도 실리면 안 된다")
                .contains("\"status\":500")
                .doesNotContain("stream_segments")
                .doesNotContain("SELECT")
                .doesNotContain("bad SQL grammar")
                .doesNotContain("Exception");
    }

    /**
     * <b>보정값을 0으로 못박는다.</b> 이 검사가 재는 것은 창구의 모양(판정·본문·401·400·500)이지
     * 「기본 보정값이 얼마인가」가 아니다. 안 박으면 아래 기대값들이 {@code application.yml}의
     * 실측 기본값에 매달려, <b>운영 값을 다시 잴 때마다 이 파일이 같이 빨간불이 된다</b>
     * (2026-08-24에 0 → 3900으로 올리자 세 검사가 실제로 그랬다). 기본값을 실제로 재는 것은
     * {@link SyncOffsetBindingTest}이고 그쪽은 <b>0도 실측값도 아닌</b> 값을 일부러 쓴다.
     *
     * <p>0을 고른 이유는 조각 데이터와의 산수를 사람이 눈으로 따라갈 수 있어서다 —
     * {@code messageTime}이 곧 조각 안 위치이므로 아래 표의 벽시계 열을 그대로 읽으면 된다.
     */
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {"pokeclip.link.internal-token=" + TOKEN,
                    "pokeclip.sync.default-offset-ms=0"})
    @ActiveProfiles("test")
    static class 토큰이_설정된_프로세스 extends IntegrationTestSupport {

        @LocalServerPort int port;
        @Autowired JdbcTemplate jdbc;
        @Autowired Environment environment;

        /**
         * 이어진 조각 셋 + 12초 공백 뒤의 불연속 조각 하나. {@link VideoPositionCalculatorTest}의
         * 기준 데이터와 같은 모양이다 — 숫자가 1번 인덱서의 PTS 규칙과 맞물려 있어 임의로 못 바꾼다.
         *
         * <pre>
         * seq  pts     wall      duration  불연속
         *  1   0       T0        4000      X
         *  2   4000    T0+4s     4000      X
         *  3   8000    T0+8s     4000      X      ← 다음이 불연속이다
         *  5   20000   T0+20s    4000      O      ← 마지막
         * </pre>
         */
        @BeforeEach
        void 표를_세우고_내_방송을_넣는다() {
            StreamSegmentsFixture.ensureTable(jdbc);
            StreamSegmentsFixture.clear(jdbc, NORMAL);
            StreamSegmentsFixture.clear(jdbc, EMPTY);
            StreamSegmentsFixture.insert(jdbc, NORMAL, 1, 0, T0, 4000, false);
            StreamSegmentsFixture.insert(jdbc, NORMAL, 2, 4000, T0.plusMillis(4000), 4000, false);
            StreamSegmentsFixture.insert(jdbc, NORMAL, 3, 8000, T0.plusMillis(8000), 4000, false);
            StreamSegmentsFixture.insert(jdbc, NORMAL, 5, 20_000, T0.plusMillis(20_000), 4000, true);
        }

        private String 물어본다(String streamId, String query) throws Exception {
            HttpResponse<String> response =
                    get(port, "/internal/streams/" + streamId + "/video-position?" + query, TOKEN);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            return response.body();
        }

        @Test
        void 내부_토큰_없이는_401이다() throws Exception {
            HttpResponse<String> response = get(port,
                    "/internal/streams/" + NORMAL + "/video-position?messageTime=" + T0, null);
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).isEmpty();
        }

        @Test
        void 토큰이_틀리면_401이다() throws Exception {
            assertThat(get(port, "/internal/streams/" + NORMAL + "/video-position?messageTime=" + T0,
                    "wrong").statusCode()).isEqualTo(401);
        }

        @Test
        void 변환되면_위치와_조각_번호가_온다() throws Exception {
            assertThat(물어본다(NORMAL, "messageTime=" + T0.plusMillis(1000)))
                    .contains("\"streamId\":\"" + NORMAL + "\"")
                    .contains("\"state\":\"converted\"")
                    .contains("\"positionMs\":1000")
                    .contains("\"segmentSeq\":1")
                    .contains("\"appliedOffsetMs\":0");
        }

        /**
         * <b>치지직이 주는 형식이 이쪽이다</b>({@code messageTime}은 epoch ms) — 카드가 기준으로
         * 못박아 뒀는데 창구가 그것을 안 받으면 실측하는 사람이 매번 손으로 변환한다.
         * 같은 순간을 두 형식으로 물어 <b>답이 같은지</b>를 본다. 값 하나만 보면
         * 「둘 다 안 읽고 늘 같은 답」과 구분이 안 되므로, 조각 안쪽 위치까지 같이 본다.
         */
        @Test
        void epoch_ms로_줘도_같은_결과다() throws Exception {
            Instant at = T0.plusMillis(5500);
            String iso = 물어본다(NORMAL, "messageTime=" + at);
            String epoch = 물어본다(NORMAL, "messageTime=" + at.toEpochMilli());

            assertThat(epoch).isEqualTo(iso);
            assertThat(epoch).contains("\"positionMs\":5500").contains("\"segmentSeq\":2");
        }

        /**
         * {@code Instant.parse}는 오프셋 표기도 받고 <b>그때 결과가 옳다</b> — 400 메시지에
         * 「UTC」라고만 적으면 부르는 쪽이 되는 것을 안 된다고 읽는다. 그래서 되는 것을 잰다.
         *
         * <p><b>🔴 다만 {@code %2B}로 인코딩해야 한다.</b> 쿼리 스트링에서 {@code +}는 공백으로
         * 디코드되므로 사람이 curl에 그대로 치면 <b>400이다</b>. 인코딩한 쪽만 재면
         * <b>검사가 실물보다 관대해</b> 「오프셋 표기는 그냥 된다」는 오해가 남는다(감사 2 B3) —
         * 그래서 <b>안 되는 쪽도 같이 잰다</b>. 이 400은 결함이 아니라 규약이고, 우리가 아는
         * 한계에 그물을 씌우는 것이 이 줄의 목적이다.
         */
        @Test
        void 오프셋_표기는_인코딩하면_되고_안_하면_400이다() throws Exception {
            assertThat(물어본다(NORMAL, "messageTime=2026-08-24T09:00:01%2B09:00"))
                    .isEqualTo(물어본다(NORMAL, "messageTime=" + T0.plusMillis(1000)));

            HttpResponse<String> raw = get(port,
                    "/internal/streams/" + NORMAL + "/video-position?messageTime=2026-08-24T09:00:01+09:00",
                    TOKEN);
            assertThat(raw.statusCode())
                    .as("+ 가 공백으로 디코드돼 파싱이 깨진다 — 규약대로이고 문서가 이 간극을 말해야 한다")
                    .isEqualTo(400);
            assertThat(raw.body()).contains("\"error\":");
        }

        /** seq3 끝(T0+12s)과 seq5 시작(T0+20s) 사이. 위치·조각 번호가 <b>둘 다</b> null이어야 한다. */
        @Test
        void 공백_시각이면_no_footage가_오고_위치가_없다() throws Exception {
            assertThat(물어본다(NORMAL, "messageTime=" + T0.plusMillis(13_000)))
                    .contains("\"state\":\"no_footage\"")
                    .contains("\"positionMs\":null")
                    .contains("\"segmentSeq\":null");
        }

        @Test
        void 장부에_없으면_not_yet_indexed다() throws Exception {
            assertThat(물어본다(EMPTY, "messageTime=" + T0))
                    .contains("\"state\":\"not_yet_indexed\"")
                    .contains("\"positionMs\":null");
        }

        /**
         * 400 본문을 <b>우리가 정한다</b>. 스프링 기본 오류 본문은 파라미터 이름만 말하고
         * 「어떤 형식이 되는가」를 안 알려 준다 — 이 창구는 형식이 둘이라 그게 곧 진단이다.
         */
        @Test
        void 시각_형식이_틀리면_400이고_우리_본문이다() throws Exception {
            HttpResponse<String> response = get(port,
                    "/internal/streams/" + NORMAL + "/video-position?messageTime=어제쯤", TOKEN);
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body())
                    .contains("\"error\":")
                    .contains("epoch ms").contains("ISO-8601")
                    .as("Instant.parse는 오프셋 표기도 받는다 — UTC만 된다고 적으면 거짓이다")
                    .doesNotContain("UTC");
        }

        /**
         * <b>{@code long}에는 들어가는데 표에는 안 들어가는 구간이 있다.</b> 그 값을 그대로
         * 던지면 PostgreSQL이 거절해 500이 되는데, 이 창구의 500은 「표가 없다·DB가 죽었다」로
         * 계약된 신호다(clip이 「수집 서버 장애」로 읽는다) — 부르는 쪽 입력 오류를 그 신호에
         * 실으면 없는 장애를 쫓게 만든다.
         *
         * <p>실측(2026-08-24, 감사 2): 아래 셋이 전부 <b>500</b>이었다. 특히 첫째는
         * <b>epoch 단위를 나노초로 착각하면 정확히 밟는 구간</b>이다.
         *
         * <p><b>양성 대조 둘을 같이 본다.</b> 안 그러면 「전부 400을 내는」 구현도 초록이다 —
         * 연 9999는 우리가 허용하기로 한 범위 안이고, epoch 0은 하한 경계 그 자체다.
         */
        @Test
        void 다룰_수_없는_범위의_시각은_400이다() throws Exception {
            for (String raw : List.of("9223372036854775807",
                    "%2B292278994-08-17T07%3A12%3A55.807Z",
                    "-1000000000-01-01T00%3A00%3A00Z",
                    "1960-01-01T00%3A00%3A00Z")) {
                HttpResponse<String> response = get(port,
                        "/internal/streams/" + NORMAL + "/video-position?messageTime=" + raw, TOKEN);
                assertThat(response.statusCode()).as("입력 %s", raw).isEqualTo(400);
                assertThat(response.body()).as("입력 %s", raw).contains("\"error\":").contains("epoch ms");
            }

            assertThat(get(port, "/internal/streams/" + NORMAL + "/video-position?messageTime=4102444800000",
                    TOKEN).statusCode())
                    .as("연 2100은 우리가 받기로 한 범위 안이다 — 양성 대조")
                    .isEqualTo(200);
            assertThat(get(port, "/internal/streams/" + NORMAL + "/video-position?messageTime=0",
                    TOKEN).statusCode())
                    .as("epoch 0은 하한 경계 그 자체다 — 막으면 안 된다")
                    .isEqualTo(200);
        }

        /**
         * {@code required=false}로 받는 이유. 기본값({@code required=true})이면 스프링이
         * 자기 본문으로 400을 내고 위 형식 안내가 안 실린다 — 같은 실수인데 답이 둘로 갈린다.
         */
        @Test
        void 시각_파라미터가_없으면_400이고_우리_본문이다() throws Exception {
            HttpResponse<String> response =
                    get(port, "/internal/streams/" + NORMAL + "/video-position", TOKEN);
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).contains("\"error\":").contains("epoch ms");

            assertThat(get(port, "/internal/streams/" + NORMAL + "/video-position?messageTime=",
                    TOKEN).body())
                    .as("빈 값은 안 준 것과 같다 — 빈 문자열을 epoch ms로 읽으려 들면 안 된다")
                    .contains("\"error\":");
        }

        /**
         * <b>삼키지 않는다</b>(사용자 결정 F4). 표가 없는 것은 설정 장애이고 조각이 아직 안 들어온
         * 것은 정상 진행이다 — 500을 {@code not_yet_indexed}로 접으면 부르는 쪽이 <b>영원히 다시
         * 묻는다.</b> 그 상태가 실제로 500이 되는지를 아무도 확인하지 않은 주장으로 남기지 않으려고
         * 여기서 만들어 본다.
         *
         * <h2>무엇을 재고 무엇을 못 재나</h2>
         * <b>접속값 셋(비밀번호·계정·JDBC URL)이 없다는 단언은 여기서 자동으로 참이다</b> —
         * 표 없음의 근본 예외({@code PSQLException: relation … does not exist})에 그 셋이 원래
         * 안 들어 있다(감사 2 B4). 그래도 남기는 이유는 <b>예외 종류가 바뀌면 뜻이 생기기
         * 때문</b>이고(접속 실패 예외에는 URL이 실린다), 진짜 방어선을 재는 것은 그 아래
         * <b>「스프링 기본 네 필드뿐이다」</b> 쪽이다. 예외 메시지가 본문에 실리는 순간
         * SQL 전문이 통째로 나가므로 그 단언들은 자동으로 참이 아니다 —
         * {@link 오류_본문에_예외_메시지를_실은_프로세스}가 그것을 실물로 보인다.
         *
         * <p><b>표를 반드시 되살린다.</b> 안 그러면 뒤에 도는 검사가 전부 깨진다 —
         * 이 표는 Flyway 밖이라 컨텍스트가 갈려도 하나다.
         */
        @Test
        void 표가_없으면_500이고_본문이_스프링_기본_네_필드뿐이다() throws Exception {
            HttpResponse<String> response = 표를_지우고_친다(port, TOKEN, jdbc);

            assertThat(response.statusCode()).isEqualTo(500);
            ChatLogLeakTest.assertNoSecretsIn(response.body(),
                    List.of(POSTGRES.getPassword(), POSTGRES.getUsername(), POSTGRES.getJdbcUrl()));
            assertThat(response.body())
                    .as("판정 이름이 실리면 부르는 쪽이 장애를 정상 판정으로 읽는다")
                    .doesNotContain("not_yet_indexed").doesNotContain("no_footage");
            assertNoExceptionDetailIn(response.body());
        }

        /**
         * <b>{@code ChatCollectionEndpointTest}의 같은 이름 검사와 쌍둥이다</b> — 한쪽을 고치면
         * 나란히 놓는다. 창구가 둘이 됐으므로 <b>경로마다</b> 잰다: 방어선은
         * {@code application.yml}의 {@code org.apache.coyote: info} 한 줄이고 그것이 경로와
         * 무관하다는 것은 <b>지금은</b> 참이지만, 이 카드가 여는 새 경로에도 실제로 걸리는지를
         * 근거 없이 믿을 이유가 없다.
         *
         * <p>실측(2026-08-22): root를 TRACE로 내리면 톰캣 {@code Http11InputBuffer}가 받은 바이트를
         * 통째로 찍어 {@code X-Internal-Token}이 샌다(root INFO 0건 · DEBUG 0건 · <b>TRACE 1건</b>).
         */
        @Test
        void 창구를_쳐도_내부_토큰이_root_TRACE에서_안_남는다() throws Exception {
            String level = environment.getProperty("logging.level." + COYOTE);
            assertThat(level).as("application.yml에 " + COYOTE + " 레벨이 박혀 있어야 root를 내려도 버틴다")
                    .isNotNull();
            assertThat(Level.toLevel(level, Level.TRACE).toInt())
                    .as(COYOTE + "가 이 레벨이면 아래 양성 대조가 재현하는 유출이 열린다: " + level)
                    .isGreaterThanOrEqualTo(Level.INFO.toInt());

            String path = "/internal/streams/" + NORMAL + "/video-position?messageTime=" + T0;
            try (LogCaptor captor = new LogCaptor()) {
                Level rootBefore = ChatLogLeakTest.levelOf(Logger.ROOT_LOGGER_NAME);
                ChatLogLeakTest.setLevel(Logger.ROOT_LOGGER_NAME, Level.TRACE);
                try {
                    assertThat(get(port, path, TOKEN).statusCode())
                            .as("요청이 톰캣을 안 지나갔다면 아래 부정 단언은 아무것도 안 본 것이다")
                            .isEqualTo(200);
                } finally {
                    ChatLogLeakTest.setLevel(Logger.ROOT_LOGGER_NAME, rootBefore);
                }
                ChatLogLeakTest.assertNoSecretsIn(ChatLogLeakTest.renderAll(captor), List.of(TOKEN));

                // 양성 대조 — 이 로거를 직접 TRACE로 밀면 실제로 새야 한다. 안 새면 위 부정 단언이
                // 아무것도 안 본 채 초록인 상태이니 yml에 박은 근거를 다시 볼 때다.
                Level before = ChatLogLeakTest.levelOf(COYOTE);
                ChatLogLeakTest.setLevel(COYOTE, Level.TRACE);
                try {
                    get(port, path, TOKEN);
                } finally {
                    ChatLogLeakTest.setLevel(COYOTE, before);
                }
                assertThat(captor.events())
                        .as(COYOTE + "를 TRACE로 밀어도 요청 헤더가 안 새면 yml에 박은 근거를 다시 볼 때다")
                        .anyMatch(e -> e.getLoggerName().startsWith(COYOTE)
                                && ChatLogLeakTest.renderFully(e).contains(TOKEN));
            }
        }
    }

    /**
     * <b>위 {@code 표가_없으면_500이고_본문이_스프링_기본_네_필드뿐이다}의 양성 대조다.</b>
     * 그 검사의 부정 단언들이 무엇을 잡는지를 <b>실제로 유출을 열어</b> 보인다 —
     * 안 그러면 「아무것도 안 재는 초록」과 구분되지 않는다.
     *
     * <p><b>🔴 스위치 이름이 Boot 4.1에서 옮겨졌다: {@code spring.web.error.*}다.</b>
     * 예전 이름 {@code server.error.include-message}는 <b>바인딩되지 않고 오류도 없다</b> —
     * {@code Environment}에는 값이 들어가는데 응답은 안 바뀐다(2026-08-24 실측. 감사 2가
     * 그 이름으로 재현에 실패해 「켜면 열린다」를 못 세웠던 자리다). {@code ErrorProperties}가
     * {@code spring-boot-autoconfigure}로 옮겨가면서 접두가 바뀌었고, 옛 이름은
     * {@code spring-boot-web-server}의 메타데이터에만 남아 <b>더 헷갈린다.</b>
     * 이 서버의 {@code spring.http.clients.*} 단복수 함정과 같은 모양이다 —
     * <b>설정으로 거는 것은 값이 아니라 행동으로 잰다.</b>
     *
     * <p>우리 {@code application.yml}은 이 스위치를 어느 이름으로도 안 건드린다(기본값
     * {@code never}가 방어선이다). 그래서 여기서만 켜고, 켜면 실제로 열리는 것을 못박는다.
     */
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {"pokeclip.link.internal-token=" + TOKEN,
                    "spring.web.error.include-message=always"})
    @ActiveProfiles("test")
    static class 오류_본문에_예외_메시지를_실은_프로세스 extends IntegrationTestSupport {

        @LocalServerPort int port;
        @Autowired JdbcTemplate jdbc;

        @Test
        void 스위치를_켜면_SQL_전문이_통째로_샌다() throws Exception {
            HttpResponse<String> response = 표를_지우고_친다(port, TOKEN, jdbc);

            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(response.body())
                    .as("예외 메시지가 실리면 우리가 던진 SQL이 그대로 밖으로 나간다")
                    .contains("stream_segments")
                    .contains("SELECT");

            assertThatThrownBy(() -> assertNoExceptionDetailIn(response.body()))
                    .as("기본 프로세스의 그 부정 단언은 이 본문을 잡는다 — 자동으로 참이 아니다")
                    .isInstanceOf(AssertionError.class);
        }
    }

    /**
     * 토큰 설정이 없는 프로세스(CI·팀원 로컬과 같은 모양). 빈 값을 <b>명시</b>한다 —
     * 셸에 {@code INTERNAL_API_TOKEN}이 있는 기계에서는 yml 기본값이 그 값을 읽어 검사가 틀어진다.
     *
     * <p><b>조각 장부를 안 세운다.</b> 필터가 먼저 막아 요청이 컨트롤러에 닿지 않으므로 DB를
     * 쓸 일이 없다 — 세우면 「이 검사가 장부를 본다」는 오해를 준다. 필터를 지우는 주입에서는
     * 401 대신 500이 나오는데(표가 없는 상태라) 그것도 빨간불이라 그물은 성립한다.
     */
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = "pokeclip.link.internal-token=")
    @ActiveProfiles("test")
    static class 토큰이_빈_프로세스 extends IntegrationTestSupport {

        @LocalServerPort int port;

        /** {@code MessageDigest.isEqual("", "")}은 true다 — 필터의 {@code locked} 갈래가 없으면 열린다. */
        @Test
        void 빈_토큰_설정이면_빈_헤더로도_401이다() throws Exception {
            String path = "/internal/streams/" + NORMAL + "/video-position?messageTime=" + T0;
            assertThat(get(port, path, "").statusCode()).isEqualTo(401);
            assertThat(get(port, path, null).statusCode()).isEqualTo(401);
        }
    }
}
