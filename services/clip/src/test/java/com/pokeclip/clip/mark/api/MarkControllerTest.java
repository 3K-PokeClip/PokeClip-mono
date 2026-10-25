package com.pokeclip.clip.mark.api;

import ch.qos.logback.classic.Level;
import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.delegation.AuthClientTokenLeakTest;
import com.pokeclip.clip.jumpcard.JumpCardErrors.HotkeyBroadcastGoneException;
import com.pokeclip.clip.jumpcard.JumpCardService;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.clip.support.TestTokens;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 계약4 4B 마킹 문의 응답 표 전부. 진짜 시큐리티 체인을 태우고 auth는 가짜({@code AUTH})다.
 * 거절 본문은 <b>바이트까지</b> 맞댄다: 전역 조언이 이 문의 거절을 가로채면 {@code {"error":…}}로 바뀌어 여기서 걸린다.
 *
 * <p>값은 계약4 시험 벡터만 쓴다(passphrase {@code EXAMPLE-4b-test-vector-not-a-key} → 파생 값 {@code 136ef57b…04315}).
 */
@AutoConfigureMockMvc
class MarkControllerTest extends IntegrationTestSupport {

    private static final String PATH = "/internal/stream-keys/resolve";
    private static final String VECTOR_PASSPHRASE = "EXAMPLE-4b-test-vector-not-a-key";
    private static final String DERIVED = "136ef57bb5b3eaa8c35485438c358ff1eb392d925714b76f1e2eb756d2a04315";
    private static final String TOKEN = "EXAMPLESTREAMKEYTOKEN00001";
    private static final String STREAMID = "#!::r=" + TOKEN + ",m=publish";
    private static final String VALID = "{\"valid\":true,\"userId\":7,\"passphrase\":\"" + VECTOR_PASSPHRASE + "\"}";
    private static final String 방송 = "S-mark";

    private static final String INVALID_REQUEST = "{\"reason\":\"invalid_request\"}";
    private static final String INVALID_STREAM_KEY = "{\"reason\":\"invalid_stream_key\"}";
    private static final String BROADCAST_NOT_FOUND = "{\"reason\":\"broadcast_not_found\"}";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MockMvc mvc;
    private final BroadcastRepository broadcasts;
    private final JdbcTemplate jdbc;

    @MockitoSpyBean
    private JumpCardService cards;

    MarkControllerTest(MockMvc mvc, BroadcastRepository broadcasts, JdbcTemplate jdbc) {
        this.mvc = mvc;
        this.broadcasts = broadcasts;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 정리() {
        방송과_카드를_비운다(jdbc);
        Instant now = Instant.now();
        broadcasts.save(Broadcast.startedNow(방송, TestIds.STREAMER, TOKEN, 1L, now.minusSeconds(600), null));
        조각(TOKEN, now.minusSeconds(570));
        AUTH.respondWith(PATH, 200, VALID);
    }

    // ── 받는다 ──────────────────────────────────────────────────

    @Test
    void 처음이면_201_같은_eventId면_200() throws Exception {
        String eventId = eventId();

        JsonNode first = json(mvc.perform(마킹(eventId)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("hotkey"))
                .andExpect(jsonPath("$.streamId").value(방송)).andReturn());
        JsonNode again = json(mvc.perform(마킹(eventId)).andExpect(status().isOk()).andReturn());

        assertThat(again.path("id").asLong()).isEqualTo(first.path("id").asLong());
    }

    @Test
    void 앞_카드_20초_안이면_200_그_카드() throws Exception {
        JsonNode first = json(mvc.perform(마킹(eventId())).andExpect(status().isCreated()).andReturn());
        JsonNode near = json(mvc.perform(마킹(eventId())).andExpect(status().isOk()).andReturn());

        assertThat(near.path("id").asLong()).isEqualTo(first.path("id").asLong());
    }

    /** 체인 밖이다: JWT 없이 닿는다. 사람 토큰을 Authorization에 실어도 그것은 파생 값이 아니라 401 사유 있는 본문이다. */
    @Test
    void JWT_없이_닿고_사람_토큰을_줘도_똑같다() throws Exception {
        mvc.perform(마킹(eventId())).andExpect(status().isCreated());

        String eventId = eventId();
        mvc.perform(post("/api/clip/marks").contentType(APPLICATION_JSON)
                        .header("Authorization", "Bearer " + TestTokens.access(TestIds.STREAMER))
                        .header("Idempotency-Key", eventId).content(본문(eventId)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(INVALID_STREAM_KEY));
    }

    // ── 400 ─────────────────────────────────────────────────────

    @ParameterizedTest(name = "본문={0}")
    @ValueSource(strings = {
            "",
            "{",
            "[]",
            "{\"eventId\":\"EID\",\"pressedAt\":1000,\"sentAt\":1000}",
            "{\"streamid\":\"   \",\"eventId\":\"EID\",\"pressedAt\":1000,\"sentAt\":1000}",
            "{\"streamid\":7,\"eventId\":\"EID\",\"pressedAt\":1000,\"sentAt\":1000}",
            "{\"streamid\":\"S\",\"eventId\":\"EID\",\"sentAt\":1000}",
            "{\"streamid\":\"S\",\"eventId\":\"EID\",\"pressedAt\":1000}",
            "{\"streamid\":\"S\",\"eventId\":\"EID\",\"pressedAt\":0,\"sentAt\":1000}",
            "{\"streamid\":\"S\",\"eventId\":\"EID\",\"pressedAt\":-5,\"sentAt\":1000}",
            "{\"streamid\":\"S\",\"eventId\":\"EID\",\"pressedAt\":1000.5,\"sentAt\":1000}",
            "{\"streamid\":\"S\",\"eventId\":\"EID\",\"pressedAt\":\"1000\",\"sentAt\":1000}",
            "{\"streamid\":\"S\",\"eventId\":\"EID\",\"pressedAt\":99999999999999999999,\"sentAt\":1000}"})
    void 필드가_빠지거나_형식이_틀리면_400_invalid_request_이고_auth를_안_부른다(String template) throws Exception {
        String eventId = eventId();
        String body = template.replace("EID", eventId).replace("\"S\"", "\"" + STREAMID.replace("\\", "") + "\"");

        mvc.perform(요청(eventId, "Bearer " + DERIVED, body))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(INVALID_REQUEST));
        assertThat(AUTH.callCount()).as("본문이 틀리면 키를 묻기 전에 끊는다").isZero();
    }

    /**
     * 감사 2회차 B2. 인증 전에 읽는 본문에 상한이 있다: 4KB 넘는 본문, 256자 넘는 streamid는 400이고 auth를 안 부른다.
     * 바로 아래 크기(4KB · 256자)는 상한 탓으로 거절되지 않는다(양성 대조: 키 확인까지 가서 201이다).
     */
    @Test
    void 본문이_4KB를_넘거나_streamid가_256자를_넘으면_400이고_auth를_안_부른다() throws Exception {
        String eventId = eventId();
        String big = 본문(eventId);
        big = big.substring(0, big.length() - 1) + ",\"pad\":\"" + "x".repeat(4 * 1024) + "\"}";
        mvc.perform(요청(eventId, "Bearer " + DERIVED, big))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(INVALID_REQUEST));

        String longStreamid = STREAMID + ",x=" + "y".repeat(256 - STREAMID.length() - 2);
        assertThat(longStreamid).hasSize(257);
        mvc.perform(요청(eventId, "Bearer " + DERIVED, 본문(eventId).replace(STREAMID, longStreamid)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(INVALID_REQUEST));
        assertThat(AUTH.callCount()).isZero();

        String atLimit = 본문(eventId);
        atLimit = atLimit.substring(0, atLimit.length() - 1) + ",\"pad\":\"" + "x".repeat(4 * 1024 - atLimit.length() - 9) + "\"}";
        assertThat(atLimit.getBytes(java.nio.charset.StandardCharsets.UTF_8)).hasSize(4 * 1024);
        mvc.perform(요청(eventId, "Bearer " + DERIVED, atLimit)).andExpect(status().isCreated());
    }

    /**
     * PR #338 codex P2. streamid에 {@code r=} 구성 요소가 둘 이상이면 400이고 auth를 안 부른다. 우리는 첫 {@code r=}로 방송을
     * 고르는데 auth는 마지막 {@code r=}로 키를 찾는다. 둘이 갈리면 한 키로 확인받고 다른 키의 방송을 고를 수 있다.
     */
    @ParameterizedTest(name = "streamid={0}")
    @ValueSource(strings = {
            "#!::r=EXAMPLESTREAMKEYTOKEN00001,m=publish,r=EXAMPLESTREAMKEYTOKEN00002",
            "#!::r=EXAMPLESTREAMKEYTOKEN00002,r=EXAMPLESTREAMKEYTOKEN00001,m=publish",
            "#!::r=EXAMPLESTREAMKEYTOKEN00001,r=EXAMPLESTREAMKEYTOKEN00001,m=publish"})
    void streamid에_r이_둘_이상이면_400이고_auth를_안_부른다(String streamid) throws Exception {
        String eventId = eventId();

        mvc.perform(요청(eventId, "Bearer " + DERIVED, 본문(eventId).replace(STREAMID, streamid)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(INVALID_REQUEST));
        assertThat(AUTH.callCount()).isZero();
    }

    /** UUID v4가 아니면 400. 짧은 꼴(UUID.fromString은 받는다)·버전 1·하이픈 없음. */
    @ParameterizedTest(name = "eventId={0}")
    @ValueSource(strings = {"1-1-1-1-1", "6ba7b810-9dad-11d1-80b4-00c04fd430c8", "6ba7b8109dad41d180b400c04fd430c8", "x"})
    void eventId가_UUID_v4가_아니면_400(String eventId) throws Exception {
        mvc.perform(요청(eventId, "Bearer " + DERIVED, 본문(eventId)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(INVALID_REQUEST));
    }

    @Test
    void 헤더_eventId와_본문이_다르거나_없으면_400() throws Exception {
        String eventId = eventId();
        mvc.perform(post("/api/clip/marks").contentType(APPLICATION_JSON)
                        .header("Authorization", "Bearer " + DERIVED)
                        .header("Idempotency-Key", eventId()).content(본문(eventId)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(INVALID_REQUEST));
        mvc.perform(post("/api/clip/marks").contentType(APPLICATION_JSON)
                        .header("Authorization", "Bearer " + DERIVED).content(본문(eventId)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(INVALID_REQUEST));
        assertThat(AUTH.callCount()).isZero();
    }

    @Test
    void resolve가_MALFORMED면_400() throws Exception {
        AUTH.respondWith(PATH, 200, "{\"valid\":false,\"reason\":\"MALFORMED\"}");

        mvc.perform(마킹(eventId()))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(INVALID_REQUEST));
    }

    /** 감사 1회차 A4. 남의 회원 카드의 eventId면 400이고 그 카드를 본문에 안 싣는다. */
    @Test
    void 남의_회원_카드_eventId면_400이고_그_카드를_안_싣는다() throws Exception {
        broadcasts.save(Broadcast.startedNow("S-other", "8", "OTHERKEY", 1L, Instant.now().minusSeconds(60), null));
        String eventId = eventId();
        jdbc.update("""
                INSERT INTO jump_cards (stream_id, source, event_id, stream_timestamp_ms, window_start_ms, window_end_ms, event_seq)
                VALUES ('S-other', 'hotkey', ?, 1000, 0, 21000, 0)""", eventId);

        mvc.perform(마킹(eventId))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(INVALID_REQUEST));
    }

    // ── 401 ─────────────────────────────────────────────────────

    @ParameterizedTest(name = "reason={0}")
    @ValueSource(strings = {"NOT_FOUND", "REVOKED"})
    void NOT_FOUND_REVOKED면_401_invalid_stream_key(String reason) throws Exception {
        AUTH.respondWith(PATH, 200, "{\"valid\":false,\"reason\":\"" + reason + "\"}");

        mvc.perform(마킹(eventId()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(INVALID_STREAM_KEY));
    }

    @Test
    void 파생값이_틀리면_401_invalid_stream_key() throws Exception {
        String eventId = eventId();
        String wrong = DERIVED.substring(0, 63) + "6";

        mvc.perform(요청(eventId, "Bearer " + wrong, 본문(eventId)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(INVALID_STREAM_KEY));
        assertThat(cardCount()).isZero();
    }

    @ParameterizedTest(name = "Authorization=[{0}]")
    @ValueSource(strings = {"", "Basic abc", "bearer " + DERIVED, DERIVED})
    void Bearer가_없으면_401이고_auth를_안_부른다(String authorization) throws Exception {
        String eventId = eventId();
        MockHttpServletRequestBuilder request = post("/api/clip/marks").contentType(APPLICATION_JSON)
                .header("Idempotency-Key", eventId).content(본문(eventId));
        if (!authorization.isEmpty()) {
            request.header("Authorization", authorization);
        }

        mvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(INVALID_STREAM_KEY));
        assertThat(AUTH.callCount()).isZero();
    }

    // ── 404 · 503 ───────────────────────────────────────────────

    @Test
    void 켜진_방송이_없으면_404_broadcast_not_found() throws Exception {
        jdbc.update("UPDATE broadcasts SET status = 'ended'");

        mvc.perform(마킹(eventId()))
                .andExpect(status().isNotFound())
                .andExpect(content().string(BROADCAST_NOT_FOUND));
    }

    /** 감사 1회차 A4. 고른 방송이 잠그는 사이 사라지면(서비스 경계에서 가짜로 던진다) 404 같은 본문이다: 500이 아니다. */
    @Test
    void 잠그는_사이_방송이_사라지면_404_broadcast_not_found() throws Exception {
        doThrow(new HotkeyBroadcastGoneException()).when(cards)
                .recordHotkey(anyString(), anyString(), anyString(), anyLong());

        mvc.perform(마킹(eventId()))
                .andExpect(status().isNotFound())
                .andExpect(content().string(BROADCAST_NOT_FOUND));
    }

    @Test
    void 기준점이_없으면_503_timeline_not_ready_Retry_After() throws Exception {
        jdbc.update("DELETE FROM public.stream_segments");

        mvc.perform(마킹(eventId()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(content().string("{\"reason\":\"timeline_not_ready\"}"));
    }

    /** 사유 없는 503이다: 플러그인은 다시 보낸다. 키가 틀렸다고 단정하지 않는다. */
    @ParameterizedTest(name = "status={0}")
    @ValueSource(ints = {400, 401, 500, 503})
    void auth가_200이_아니면_503_reason_없음(int status) throws Exception {
        AUTH.respondWith(PATH, status, VALID);

        mvc.perform(마킹(eventId()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(""));
        assertThat(cardCount()).isZero();
    }

    // ── 로그 ────────────────────────────────────────────────────

    /**
     * 계획 검증 E12. root TRACE에서 streamid·Authorization 값(파생 값)·passphrase가 어떤 로그에도 없다.
     * 갈래는 생성·같은 eventId·파생 값 틀림·auth 실패·본문 깨짐을 다 태운다.
     */
    @Test
    void 로그에_streamid_Authorization_파생값이_없다() throws Exception {
        // 🔴 방송 줄의 물리 키를 토큰과 다른 값으로 바꾼다. 오늘 물리 키는 스트림키 토큰과 같고(E3 전제), 그 칸은
        // root TRACE에서 Hibernate 추출 로그(org.hibernate.orm.jdbc.extract)가 방송을 읽을 때마다 찍는다: 이 문이 아니라
        // 방송 표를 읽는 모든 자리의 일이다(보고서 05). 여기서는 「요청에서 온 값이 새는가」만 잰다.
        jdbc.update("UPDATE broadcasts SET ingest_stream_id = 'PHYSICAL-KEY-NOT-TOKEN' WHERE stream_id = ?", 방송);
        jdbc.update("UPDATE public.stream_segments SET stream_id = 'PHYSICAL-KEY-NOT-TOKEN'");
        try (LogCaptor captor = new LogCaptor()) {
            AuthClientTokenLeakTest.setLevel(org.slf4j.Logger.ROOT_LOGGER_NAME, Level.TRACE);
            String eventId = eventId();

            mvc.perform(마킹(eventId)).andExpect(status().isCreated());
            mvc.perform(마킹(eventId)).andExpect(status().isOk());
            String other = eventId();
            mvc.perform(요청(other, "Bearer " + DERIVED.replace('1', '2'), 본문(other)))
                    .andExpect(status().isUnauthorized());
            AUTH.respondWith(PATH, 500, VALID);
            mvc.perform(마킹(eventId())).andExpect(status().isServiceUnavailable());
            String broken = eventId();
            mvc.perform(요청(broken, "Bearer " + DERIVED, "{\"streamid\":\"" + STREAMID + "\",\"eventId\":"))
                    .andExpect(status().isBadRequest());

            assertThat(captor.messages())
                    .as("양성 대조: 이 문의 로그가 실제로 나갔다")
                    .anyMatch(m -> m.startsWith("clip.mark."))
                    .anyMatch(m -> m.startsWith("jumpcard.hotkey_created"));
            AuthClientTokenLeakTest.assertNoSecretsIn(captor,
                    List.of(STREAMID, TOKEN, DERIVED, DERIVED.replace('1', '2'), VECTOR_PASSPHRASE));
        }
    }

    // ── 도우미 ──────────────────────────────────────────────────

    private static String eventId() {
        return UUID.randomUUID().toString();
    }

    private MockHttpServletRequestBuilder 마킹(String eventId) {
        return 요청(eventId, "Bearer " + DERIVED, 본문(eventId));
    }

    private static MockHttpServletRequestBuilder 요청(String eventId, String authorization, String body) {
        return post("/api/clip/marks").contentType(APPLICATION_JSON)
                .header("Authorization", authorization)
                .header("Idempotency-Key", eventId)
                .content(body);
    }

    /** 누른 때 = 보낸 때 = 지금(플러그인 시계 = 서버 시계). */
    private static String 본문(String eventId) {
        long now = System.currentTimeMillis();
        return "{\"streamid\":\"" + STREAMID + "\",\"eventId\":\"" + eventId
                + "\",\"pressedAt\":" + now + ",\"sentAt\":" + now + "}";
    }

    private void 조각(String ingestKey, Instant wall) {
        jdbc.update("""
                        INSERT INTO public.stream_segments (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms, s3_key, upload_state)
                        VALUES (?, 1, 0, ?, 4000, ?, 'uploaded')""",
                ingestKey, Timestamp.from(wall), "seg/" + ingestKey + "/1");
    }

    private long cardCount() {
        return jdbc.queryForObject("SELECT count(*) FROM jump_cards", Long.class);
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }
}
