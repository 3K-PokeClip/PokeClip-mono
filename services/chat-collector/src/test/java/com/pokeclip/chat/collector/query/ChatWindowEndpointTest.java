package com.pokeclip.chat.collector.query;

import ch.qos.logback.classic.Level;
import com.pokeclip.chat.collector.ChatLogLeakTest;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 범위 목록 창구를 <b>밖에서</b> 친다. 조회 규칙 자체는 {@link ChatWindowQueryTest}가 실 PG에서
 * 재고, 여기는 <b>창구가 더하는 것</b>만 본다 — 400 사유 여덟 · 상한 잘라주기 · 401 · 500 ·
 * 직렬화(시각이 어느 축의 어떤 글자로 나가나).
 *
 * <p><b>방송 번호 접두는 {@code api-win-}이다</b>(문항 7).
 *
 * <p>보정값을 0으로 못박는다 — 이 검사가 재는 것은 창구의 모양이지 「기본 보정값이 얼마인가」가
 * 아니다. 안 박으면 {@code application.yml}의 실측값을 다시 잴 때마다 여기가 같이 빨간불이 된다
 * ({@code VideoPositionEndpointTest}의 같은 결정).
 */
class ChatWindowEndpointTest {

    /** {@code ChatCollectionEndpointTest}·{@code VideoPositionEndpointTest}와 값을 맞춰 둔다. */
    static final String TOKEN = "test-internal-token";

    /** 들어오는 요청 바이트를 통째로 찍는 톰캣 로거의 부모. application.yml이 info로 박아 둔다. */
    static final String COYOTE = "org.apache.coyote";

    /** 픽스처의 후원 순번. 표의 UNIQUE가 요구하는 유일값이면 되고,
     * 이 픽스처가 재는 것은 창구 조회이지 중복 규칙이 아니다
     * — 그쪽은 DonationPersisterTest가 잰다. */
    private static final java.util.concurrent.atomic.AtomicLong DONATION_SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    private static final String STREAM = "api-win-1";
    private static final Instant T0 = Instant.parse("2026-09-03T15:00:00Z");

    private static final AtomicInteger SEQ = new AtomicInteger(500_000);

    static HttpResponse<String> get(int port, String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (token != null) request.header("X-Internal-Token", token);
        return HttpClient.newHttpClient().send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    static String path(String query) {
        return "/internal/streams/" + STREAM + "/chat-messages?" + query;
    }

    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {"pokeclip.link.internal-token=" + TOKEN,
                    "pokeclip.sync.default-offset-ms=0"})
    @ActiveProfiles("test")
    static class 토큰이_설정된_프로세스 extends IntegrationTestSupport {

        @LocalServerPort int port;
        @Autowired JdbcTemplate jdbc;
        @Autowired Environment environment;

        @BeforeEach
        void 내_방송만_비운다() {
            jdbc.update("DELETE FROM chat_messages WHERE stream_id = ?", STREAM);
            jdbc.update("DELETE FROM chat_donations WHERE stream_id = ?", STREAM);
        }

        private HttpResponse<String> 물어본다(String query) throws Exception {
            return get(port, path(query), TOKEN);
        }

        /**
         * 커서가 <b>창구를 통해</b> 왕복하는지 본다. 조회 계층 검사와 달리 여기서는 커서가
         * <b>문자열로</b> 오간다 — 인코딩이 깨지면 여기서만 드러난다.
         */
        @Test
        void 범위와_커서로_잘라_준다() throws Exception {
            insertChat(T0.plusSeconds(1), "a");
            insertChat(T0.plusSeconds(2), "b");
            insertChat(T0.plusSeconds(3), "c");

            HttpResponse<String> first = 물어본다("from=" + T0 + "&to=" + T0.plusSeconds(10) + "&limit=2");
            assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
            assertThat(first.body())
                    .contains("\"kind\":\"chat\"")
                    .contains("\"timeBasis\":\"message\"")
                    .as("표 축의 원본 시각이 ISO-8601로 나간다")
                    .contains("\"time\":\"2026-09-03T15:00:01Z\"")
                    .contains("\"appliedOffsetMs\":0")
                    .contains("\"text\":\"a\"").contains("\"text\":\"b\"")
                    .doesNotContain("\"text\":\"c\"");

            String cursor = cursorOf(first.body());
            assertThat(cursor).isNotNull();

            HttpResponse<String> second =
                    물어본다("from=" + T0 + "&to=" + T0.plusSeconds(10) + "&limit=2&cursor=" + cursor);
            assertThat(second.statusCode()).as(second.body()).isEqualTo(200);
            assertThat(second.body())
                    .contains("\"text\":\"c\"")
                    .doesNotContain("\"text\":\"a\"")
                    .contains("\"nextCursor\":null");
        }

        /**
         * 400 본문은 <b>사유 낱말 하나</b>다. 받은 값을 되비추면 반사된 값이 로그와 화면으로 흐른다
         * (영상 위치 창구의 같은 결정).
         */
        @Test
        void from_to가_없으면_400이고_받은_값을_되비추지_않는다() throws Exception {
            HttpResponse<String> none = 물어본다("limit=10");
            assertThat(none.statusCode()).isEqualTo(400);
            assertThat(none.body()).contains("\"error\":\"missing\"");

            HttpResponse<String> unreadable = 물어본다("from=어제쯤&to=" + T0.plusSeconds(10));
            assertThat(unreadable.statusCode()).isEqualTo(400);
            assertThat(unreadable.body()).contains("\"error\":\"unreadable\"")
                    .as("받은 값을 되비추면 안 된다").doesNotContain("어제");

            HttpResponse<String> range = 물어본다("from=9223372036854775807&to=9223372036854775807");
            assertThat(range.statusCode()).isEqualTo(400);
            assertThat(range.body()).contains("\"error\":\"out_of_range\"")
                    .doesNotContain("9223372036854775807");
        }

        @Test
        void 뒤집힌_범위와_한_시간_초과는_400이다() throws Exception {
            assertThat(물어본다("from=" + T0.plusSeconds(10) + "&to=" + T0).body())
                    .contains("\"error\":\"inverted\"");
            assertThat(물어본다("from=" + T0 + "&to=" + T0).body())
                    .as("빈 범위는 「0건」과 「잘못 물었다」가 구분이 안 된다")
                    .contains("\"error\":\"inverted\"");
            assertThat(물어본다("from=" + T0 + "&to=" + T0.plusSeconds(3601)).body())
                    .contains("\"error\":\"too_wide\"");
            assertThat(물어본다("from=" + T0 + "&to=" + T0.plusSeconds(3600)).statusCode())
                    .as("정확히 상한은 통과한다 — 양성 대조")
                    .isEqualTo(200);
        }

        /**
         * 방향이 반대인 둘을 한 검사에 둔다. {@code limit=0}은 <b>400</b>이고(「0건을 달라」는
         * 실수 말고 뜻이 없다) 상한 초과는 <b>잘라 준다</b>(많이 달라는 것은 오류가 아니다).
         */
        @Test
        void limit_0과_상한_초과는_각각_400과_잘라주기다() throws Exception {
            String window = "from=" + T0 + "&to=" + T0.plusSeconds(10);
            assertThat(물어본다(window + "&limit=0").body()).contains("\"error\":\"limit\"");
            assertThat(물어본다(window + "&limit=-1").body()).contains("\"error\":\"limit\"");
            assertThat(물어본다(window + "&limit=abc").body()).contains("\"error\":\"limit\"");

            for (int i = 0; i < 3; i++) insertChat(T0.plusSeconds(1).plusMillis(i), "m" + i);
            HttpResponse<String> huge = 물어본다(window + "&limit=9999");
            assertThat(huge.statusCode()).as(huge.body()).isEqualTo(200);
            assertThat(huge.body()).contains("\"text\":\"m2\"");
        }

        /**
         * 🔴 <b>잘라주기에 그물이 0이었다</b>(POK-234 감사 라운드 3 C-1). 위 검사는 3건을 심고
         * {@code limit=9999}로 「m2가 있다」만 봐서 {@code Math.min(requested, pageMax())}를
         * 지워도 참이었다 — 상한과 무관하게 통과하는 단언이다.
         *
         * <p><b>운영 상한을 그대로 쓴다.</b> 검사용으로 낮춘 컨텍스트를 만들면 재는 것이
         * 사본이 되고, 그러면 {@code application.yml}의 500이 실제로 걸리는지는 여전히
         * 아무도 안 본다. 그래서 상한보다 <b>한 건 더</b> 심어 깎이는 것을 센다.
         */
        @Test
        void 상한을_넘겨_물으면_page_max까지만_준다() throws Exception {
            int max = Integer.parseInt(environment.getProperty("pokeclip.query.page-max"));
            for (int i = 0; i <= max; i++) {
                insertChat(T0.plusSeconds(1).plusMillis(i), "m" + i);
            }

            HttpResponse<String> huge =
                    물어본다("from=" + T0 + "&to=" + T0.plusSeconds(10) + "&limit=9999");
            assertThat(huge.statusCode()).as(huge.body()).isEqualTo(200);
            assertThat(huge.body().split("\\{\"kind\":", -1).length - 1)
                    .as("상한이 안 걸리면 심은 " + (max + 1) + "건이 통째로 나간다")
                    .isEqualTo(max);
            assertThat(huge.body())
                    .as("깎였으면 다음 장이 남는다 — 깎고 「끝」이라고 하면 한 건이 사라진다")
                    .doesNotContain("\"nextCursor\":null");
        }

        /**
         * 🔴 <b>응답 JSON 칸 이름은 clip·프론트와의 계약이다</b>(POK-234 감사 라운드 3 C-3).
         * 여섯이 record 필드 이름 하나에만 걸려 있어서 이름을 바꿔도 아무도 안 잡았다.
         * 여기서 <b>글자로</b> 못박는다 — 특히 {@code donationType}은 {@code type}으로
         * 줄이자는 제안이 실제로 있었다.
         */
        @Test
        void 응답_칸_이름_여섯은_글자로_못박는다() throws Exception {
            insertChat(T0.plusSeconds(1), "채팅본문");
            insertDonation(T0.plusSeconds(2), "후원본문");

            String body = 물어본다("from=" + T0 + "&to=" + T0.plusSeconds(10)).body();

            assertThat(body)
                    .containsPattern("\"id\":\\d+")
                    .contains("\"nickname\":\"후원자\"")
                    .contains("\"senderChannelId\":\"api-win-donator\"")
                    .contains("\"role\":\"common_user\"")
                    .contains("\"donationType\":\"CHAT\"")
                    .contains("\"amount\":1000");
        }

        @Test
        void 모르는_kinds는_400이고_아는_것은_거른다() throws Exception {
            insertChat(T0.plusSeconds(1), "채팅");
            insertDonation(T0.plusSeconds(1), "후원");
            String window = "from=" + T0 + "&to=" + T0.plusSeconds(10);

            assertThat(물어본다(window + "&kinds=chat,subscription").body())
                    .contains("\"error\":\"kinds\"");
            assertThat(물어본다(window + "&kinds=donation").body())
                    .contains("\"text\":\"후원\"").doesNotContain("\"text\":\"채팅\"");
            assertThat(물어본다(window).body())
                    .as("안 주면 둘 다다")
                    .contains("\"text\":\"후원\"").contains("\"text\":\"채팅\"");
        }

        /** 남이 지어낸 커서를 조용히 첫 장으로 접으면 페이징이 어긋난 것이 정상처럼 보인다. */
        @Test
        void 남의_커서는_400이다() throws Exception {
            String window = "from=" + T0 + "&to=" + T0.plusSeconds(10);
            assertThat(물어본다(window + "&cursor=" + ChatWindowCursor.encode(1, "c", 1)).statusCode())
                    .as("우리 커서는 통과한다 — 양성 대조").isEqualTo(200);

            HttpResponse<String> bad = 물어본다(window + "&cursor=YjoxOmM6MQ");   // base64url("b:1:c:1")
            assertThat(bad.statusCode()).isEqualTo(400);
            assertThat(bad.body()).contains("\"error\":\"cursor\"");
        }

        @Test
        void 토큰이_없거나_틀리면_401이다() throws Exception {
            String query = "from=" + T0 + "&to=" + T0.plusSeconds(10);
            HttpResponse<String> none = get(port, path(query), null);
            assertThat(none.statusCode()).isEqualTo(401);
            assertThat(none.body()).isEmpty();
            assertThat(get(port, path(query), "wrong").statusCode()).isEqualTo(401);
        }

        /**
         * <b>{@code ChatCollectionEndpointTest}·{@code VideoPositionEndpointTest}의 같은 이름
         * 검사와 쌍둥이다.</b> 방어선은 {@code application.yml}의 {@code org.apache.coyote: info}
         * 한 줄이고 경로와 무관하다는 것이 <b>지금은</b> 참이지만, 이 카드가 여는 새 경로에도
         * 실제로 걸리는지를 근거 없이 믿을 이유가 없다.
         */
        @Test
        void 창구를_쳐도_내부_토큰이_root_TRACE에서_안_남는다() throws Exception {
            String level = environment.getProperty("logging.level." + COYOTE);
            assertThat(level).isNotNull();
            assertThat(Level.toLevel(level, Level.TRACE).toInt()).isGreaterThanOrEqualTo(Level.INFO.toInt());

            String p = path("from=" + T0 + "&to=" + T0.plusSeconds(10));
            try (LogCaptor captor = new LogCaptor()) {
                Level rootBefore = ChatLogLeakTest.levelOf(Logger.ROOT_LOGGER_NAME);
                ChatLogLeakTest.setLevel(Logger.ROOT_LOGGER_NAME, Level.TRACE);
                try {
                    assertThat(get(port, p, TOKEN).statusCode())
                            .as("요청이 톰캣을 안 지나갔다면 아래 부정 단언은 아무것도 안 본 것이다")
                            .isEqualTo(200);
                } finally {
                    ChatLogLeakTest.setLevel(Logger.ROOT_LOGGER_NAME, rootBefore);
                }
                ChatLogLeakTest.assertNoSecretsIn(ChatLogLeakTest.renderAll(captor), List.of(TOKEN));

                Level before = ChatLogLeakTest.levelOf(COYOTE);
                ChatLogLeakTest.setLevel(COYOTE, Level.TRACE);
                try {
                    get(port, p, TOKEN);
                } finally {
                    ChatLogLeakTest.setLevel(COYOTE, before);
                }
                assertThat(captor.events())
                        .as(COYOTE + "를 TRACE로 밀어도 요청 헤더가 안 새면 yml에 박은 근거를 다시 볼 때다")
                        .anyMatch(e -> e.getLoggerName().startsWith(COYOTE)
                                && ChatLogLeakTest.renderFully(e).contains(TOKEN));
            }
        }

        /** 닉네임·본문이 어느 레벨에서도 로그에 안 나간다 — 창구가 새 유출 면적이다. */
        @Test
        void 창구를_쳐도_닉네임과_본문이_안_남는다() throws Exception {
            insertChat(T0.plusSeconds(1), "비밀본문ZZ");
            try (LogCaptor captor = new LogCaptor()) {
                Level rootBefore = ChatLogLeakTest.levelOf(Logger.ROOT_LOGGER_NAME);
                ChatLogLeakTest.setLevel(Logger.ROOT_LOGGER_NAME, Level.TRACE);
                try {
                    assertThat(물어본다("from=" + T0 + "&to=" + T0.plusSeconds(10)).body())
                            .as("답에는 실려야 한다 — 안 실리면 아래 부정 단언이 아무것도 안 본 것이다")
                            .contains("비밀본문ZZ");
                } finally {
                    ChatLogLeakTest.setLevel(Logger.ROOT_LOGGER_NAME, rootBefore);
                }
                ChatLogLeakTest.assertNoSecretsIn(ChatLogLeakTest.renderAll(captor), List.of("비밀본문ZZ"));
            }
        }

        private void insertChat(Instant messageTime, String content) {
            int n = SEQ.incrementAndGet();
            jdbc.update("""
                    INSERT INTO chat_messages
                      (channel_id, sender_channel_id, content, message_time, received_at,
                       content_sha256, stream_id, nickname, user_role)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    "api-win-ch", "api-win-sender", content,
                    Timestamp.from(messageTime), Timestamp.from(messageTime),
                    String.format("%064d", n), STREAM, "닉" + n, "common_user");
        }

        private void insertDonation(Instant receivedAt, String text) {
            jdbc.update("""
                    INSERT INTO chat_donations
                      (stream_id, channel_id, donator_channel_id, donator_nickname,
                       donation_type, pay_amount, donation_text, received_at, received_seq)
                    -- 순번은 매번 유일한 값이면 된다. 이 픽스처가 재는 것은 창구 조회이지
                    -- 중복 규칙이 아니다 — 그쪽은 DonationPersisterTest가 잰다.
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    STREAM, "api-win-ch", "api-win-donator", "후원자", "CHAT", 1000L, text,
                    Timestamp.from(receivedAt), DONATION_SEQ.incrementAndGet());
        }

        /** {@code "nextCursor":"…"}만 뽑는다. 검사가 JSON 라이브러리를 끌어오지 않으려는 것이다. */
        private static String cursorOf(String body) {
            int at = body.indexOf("\"nextCursor\":\"");
            if (at < 0) return null;
            int from = at + "\"nextCursor\":\"".length();
            return body.substring(from, body.indexOf('"', from));
        }
    }

    /**
     * 🔴 <b>표를 못 찾는 컨텍스트다. 공유 표를 DROP하지 않는다</b>(계획 검증 F11) —
     * {@code chat_donations}·{@code chat_messages}는 Flyway가 만든 <b>공유 Testcontainers
     * 컨테이너</b>의 표라, 되살릴 때 색인·identity 시퀀스까지 똑같이 안 되돌리면 뒤에 도는 검사가
     * 조용히 갈린다(문항 7).
     *
     * <p>대신 <b>{@code search_path}를 없는 스키마로 돌린다.</b> DDL을 하나도 안 건드리고,
     * 이 컨텍스트 안에서만 참이며, 질의는 실제로 {@code relation "chat_messages" does not exist}로
     * 죽는다 — <b>500 본문에 SQL 전문이 실릴 수 있는 진짜 상황</b>이다. Flyway는 꺼야 한다
     * (없는 스키마로 마이그레이션하려다 컨텍스트가 안 뜬다).
     *
     * <p>이 창구에서 <b>500은 「DB가 죽었다」로 계약된 신호다</b> — 삼켜서 빈 목록을 주면
     * clip이 「그 구간에 채팅이 없었다」로 읽고, 되감기 화면이 조용히 빈다.
     */
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {"pokeclip.link.internal-token=" + TOKEN,
                    "spring.flyway.enabled=false",
                    "spring.datasource.hikari.data-source-properties.currentSchema=pokeclip_no_such_schema"})
    @ActiveProfiles("test")
    static class 표를_못_찾는_프로세스 extends IntegrationTestSupport {

        @LocalServerPort int port;

        @Test
        void 표가_없으면_500이고_본문에_SQL이_없다() throws Exception {
            HttpResponse<String> response =
                    get(port, path("from=" + T0 + "&to=" + T0.plusSeconds(10)), TOKEN);

            assertThat(response.statusCode()).isEqualTo(500);
            ChatLogLeakTest.assertNoSecretsIn(response.body(),
                    List.of(POSTGRES.getPassword(), POSTGRES.getUsername(), POSTGRES.getJdbcUrl()));
            assertThat(response.body())
                    .as("스프링 기본 500 본문 네 필드 말고는 아무것도 실리면 안 된다")
                    .contains("\"status\":500")
                    .doesNotContain("chat_messages")
                    .doesNotContain("SELECT")
                    .doesNotContain("bad SQL grammar")
                    .doesNotContain("Exception");
            assertThat(response.body())
                    .as("빈 목록으로 삼키면 clip이 「채팅이 없었다」로 읽는다")
                    .doesNotContain("\"items\"");
        }
    }
}
