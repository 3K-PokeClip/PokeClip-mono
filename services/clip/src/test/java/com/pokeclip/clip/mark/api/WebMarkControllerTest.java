package com.pokeclip.clip.mark.api;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.jumpcard.JumpCardErrors.HotkeyBroadcastGoneException;
import com.pokeclip.clip.jumpcard.JumpCardService;
import com.pokeclip.clip.mark.WebMarkService;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.NotFoundFloor;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.clip.support.TestTokens;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 웹 수동 마킹 문(POK-392) {@code POST /api/clip/broadcasts/{streamId}/marks}. 진짜 시큐리티 체인(JWT)과 진짜 자격 판정을 태우고
 * auth는 가짜 소켓({@code AUTH})이다. 받은 시각은 서비스의 {@code now()}를 {@link #RECEIVED}로 고정해 카드 ms를 정확히 잰다.
 *
 * <p>기준점(ADR-079) = 첫 조각의 벽시계 − 그 조각의 {@code start_pts_ms}. pts 0인 조각 하나를 {@link #T0}에 심어 기준점이 정확히
 * {@link #T0}이다.
 *
 * <p>🔴 <b>요청자({@link #편집자})와 방송 주인({@link #주인})이 다르다.</b> 같으면 주인 번호 대신 요청자 번호를 저장에 넘기는 결함이 초록이다.
 */
@AutoConfigureMockMvc
class WebMarkControllerTest extends IntegrationTestSupport {

    private static final String RESOLVE = "/internal/editor-delegations/resolve";
    private static final String 주인 = TestIds.STREAMER;
    private static final String 편집자 = "4178";

    private static final String 라이브 = "S-web-live";
    private static final String 라이브_키 = "WEBLIVEKEY";
    private static final String 지난_방송 = "S-web-vod";
    private static final String 지난_방송_키 = "WEBVODKEY";

    /** 기준점. */
    private static final Instant T0 = Instant.parse("2026-10-23T00:00:00Z");
    /** 받은 시각(서버 시계): 기준점 10분 뒤. */
    private static final Instant RECEIVED = T0.plusSeconds(600);
    /** 지난 방송의 종료 시각: 기준점 1시간 뒤. 위치 상한은 그 + 10분이다. */
    private static final Instant ENDED = T0.plusSeconds(3600);
    /** 브라우저 시계의 보낸 시각: 서버 시계와 아무 관계가 없는 값이다(시계 차를 일부러 크게). */
    private static final long SENT_AT = 1_000_000_000_000L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MockMvc mvc;
    private final BroadcastRepository broadcasts;
    private final JdbcTemplate jdbc;

    @MockitoSpyBean
    private WebMarkService webMarks;

    @MockitoSpyBean
    private JumpCardService cards;

    WebMarkControllerTest(MockMvc mvc, BroadcastRepository broadcasts, JdbcTemplate jdbc) {
        this.mvc = mvc;
        this.broadcasts = broadcasts;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 정리() {
        방송과_카드를_비운다(jdbc);
        broadcasts.save(Broadcast.startedNow(라이브, 주인, 라이브_키, 1L, T0.minusSeconds(30), null));
        조각(라이브_키, T0);
        broadcasts.save(Broadcast.startedNow(지난_방송, 주인, 지난_방송_키, 1L, T0.minusSeconds(30), null));
        jdbc.update("UPDATE broadcasts SET status = 'ended', ended_at = ? WHERE stream_id = ?",
                Timestamp.from(ENDED), 지난_방송);
        조각(지난_방송_키, T0);
        doReturn(RECEIVED).when(webMarks).now();
        볼_수_있다("EDITOR");
    }

    // ── 라이브: 누른 때·보낸 때 ───────────────────────────────────

    /** 받기 3초 전에 눌렀다: 카드 ms = (받은 시각 − 3초) − 기준점 = 597,000. 브라우저 시계와 서버 시계의 차는 빠진다. */
    @Test
    void 라이브는_누른_시각을_서버_시계로_옮겨_201() throws Exception {
        mvc.perform(마킹(라이브, 라이브_본문(eventId(), SENT_AT - 3_000, SENT_AT)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("hotkey"))
                .andExpect(jsonPath("$.streamId").value(라이브))
                .andExpect(jsonPath("$.streamTimestampMs").value(597_000))
                .andExpect(jsonPath("$.window.startMs").value(557_000))
                .andExpect(jsonPath("$.window.endMs").value(617_000));

        assertThat(AUTH.callCount()).as("자격 창구를 안 거쳤다: 판정을 안 재고 있다").isEqualTo(1);
        assertThat(AUTH.lastPath()).isEqualTo(RESOLVE);
    }

    /** 누른 때가 받은 시각보다 미래면 받은 시각이다(브라우저 시계가 앞서 있거나 sentAt이 틀렸다). */
    @Test
    void 라이브에서_누른_때가_미래면_받은_시각() throws Exception {
        mvc.perform(마킹(라이브, 라이브_본문(eventId(), SENT_AT + 5_000, SENT_AT)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.streamTimestampMs").value(600_000));
    }

    @Test
    void 같은_eventId면_200_같은_카드() throws Exception {
        String eventId = eventId();
        JsonNode first = json(mvc.perform(마킹(라이브, 라이브_본문(eventId, SENT_AT, SENT_AT)))
                .andExpect(status().isCreated()));
        JsonNode again = json(mvc.perform(마킹(라이브, 라이브_본문(eventId, SENT_AT, SENT_AT)))
                .andExpect(status().isOk()));

        assertThat(again.path("id").asLong()).isEqualTo(first.path("id").asLong());
        assertThat(cardCount()).isEqualTo(1);
    }

    /** 앞 수동 카드와 19,999ms 차는 그 카드 200, 20,000ms 차는 새 카드 201(POK-391 규칙 그대로). */
    @Test
    void 앞_카드_20초_안이면_200_그_카드() throws Exception {
        JsonNode first = json(mvc.perform(마킹(라이브, 라이브_본문(eventId(), SENT_AT, SENT_AT)))
                .andExpect(status().isCreated()));
        JsonNode near = json(mvc.perform(마킹(라이브, 라이브_본문(eventId(), SENT_AT - 19_999, SENT_AT)))
                .andExpect(status().isOk()));
        mvc.perform(마킹(라이브, 라이브_본문(eventId(), SENT_AT - 20_000, SENT_AT))).andExpect(status().isCreated());

        assertThat(near.path("id").asLong()).isEqualTo(first.path("id").asLong());
    }

    /**
     * OBS 문(POK-391)으로 만든 카드와 웹 문이 20초 판정을 같이 쓴다(출처가 둘 다 {@code hotkey}). 두 문 다 진짜 시계로 돈다:
     * OBS 카드가 지금 − 기준점에 서고 웹 마킹도 거의 같은 순간이라 그 카드를 200으로 받는다.
     */
    @Test
    void OBS_카드와_20초_판정을_같이_쓴다() throws Exception {
        doCallRealMethod().when(webMarks).now();
        Instant now = Instant.now();
        jdbc.update("UPDATE broadcasts SET started_at = ? WHERE stream_id = ?", Timestamp.from(now.minusSeconds(630)), 라이브);
        jdbc.update("UPDATE public.stream_segments SET start_wall_utc = ? WHERE stream_id = ?", Timestamp.from(now.minusSeconds(600)), 라이브_키);
        jdbc.update("DELETE FROM public.stream_segments WHERE stream_id = ?", 지난_방송_키);
        jdbc.update("DELETE FROM broadcasts WHERE stream_id = ?", 지난_방송);
        AUTH.respondWith("/internal/stream-keys/resolve", 200,
                "{\"valid\":true,\"userId\":7,\"passphrase\":\"EXAMPLE-4b-test-vector-not-a-key\"}");
        String obsEvent = eventId();
        long pressed = System.currentTimeMillis();
        JsonNode obs = json(mvc.perform(post("/api/clip/marks").contentType(APPLICATION_JSON)
                        .header("Authorization", "Bearer 136ef57bb5b3eaa8c35485438c358ff1eb392d925714b76f1e2eb756d2a04315")
                        .header("Idempotency-Key", obsEvent)
                        .content("{\"streamid\":\"#!::r=" + 라이브_키 + ",m=publish\",\"eventId\":\"" + obsEvent
                                + "\",\"pressedAt\":" + pressed + ",\"sentAt\":" + pressed + "}"))
                .andExpect(status().isCreated()));

        long now2 = System.currentTimeMillis();
        JsonNode web = json(mvc.perform(마킹(라이브, 라이브_본문(eventId(), now2, now2))).andExpect(status().isOk()));

        assertThat(web.path("id").asLong()).isEqualTo(obs.path("id").asLong());
        assertThat(cardCount()).isEqualTo(1);
    }

    // ── 지난 방송: 재생 위치 ────────────────────────────────────

    /** 위치가 곧 카드 ms다(브라우저가 반올림해 정수로 보낸다: 91.39초 → 91,390). */
    @Test
    void 지난_방송은_재생_위치_그대로_201() throws Exception {
        mvc.perform(마킹(지난_방송, 위치_본문(eventId(), "91390")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.streamId").value(지난_방송))
                .andExpect(jsonPath("$.streamTimestampMs").value(91_390))
                .andExpect(jsonPath("$.window.startMs").value(51_390))
                .andExpect(jsonPath("$.window.endMs").value(111_390));
    }

    /** 녹화가 기준점보다 앞에서 시작하면 위치가 음수다: 10분까지는 0으로 접고, 그 아래는 시각이 틀린 것이라 400. */
    @Test
    void 위치가_음수면_10분까지는_0으로_접고_그_아래는_400() throws Exception {
        mvc.perform(마킹(지난_방송, 위치_본문(eventId(), "-600001")))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(invalid("positionMs")));
        assertThat(cardCount()).isZero();

        mvc.perform(마킹(지난_방송, 위치_본문(eventId(), "-600000")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.streamTimestampMs").value(0));
    }

    /** 상한 = (종료 − 기준점) + 10분 = 4,200,000. 녹화가 종료 편지 뒤까지 이어질 수 있어 10분 여유를 둔다. */
    @Test
    void 위치가_방송_길이_10분_여유를_넘으면_400() throws Exception {
        mvc.perform(마킹(지난_방송, 위치_본문(eventId(), "4200001")))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(invalid("positionMs")));
        assertThat(cardCount()).isZero();

        mvc.perform(마킹(지난_방송, 위치_본문(eventId(), "4200000")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.streamTimestampMs").value(4_200_000));
    }

    /** 종료 시각을 모르는 지난 방송은 48시간이 상한이다. */
    @Test
    void 종료_시각이_없으면_상한은_48시간() throws Exception {
        jdbc.update("UPDATE broadcasts SET ended_at = NULL WHERE stream_id = ?", 지난_방송);
        long max = Duration.ofHours(48).toMillis();

        mvc.perform(마킹(지난_방송, 위치_본문(eventId(), String.valueOf(max + 1))))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(invalid("positionMs")));
        mvc.perform(마킹(지난_방송, 위치_본문(eventId(), String.valueOf(max))))
                .andExpect(status().isCreated());
    }

    // ── 400: 갈래 불일치 · 형식 ─────────────────────────────────

    @Test
    void 라이브에_위치를_보내면_400() throws Exception {
        mvc.perform(마킹(라이브, 위치_본문(eventId(), "1000")))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(invalid("positionMs")));
        assertThat(cardCount()).isZero();
    }

    @Test
    void 지난_방송에_누른_때를_보내면_400() throws Exception {
        mvc.perform(마킹(지난_방송, 라이브_본문(eventId(), SENT_AT, SENT_AT)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(invalid("pressedAt")));
        assertThat(cardCount()).isZero();
    }

    /** 형식 오류는 자격을 묻기 전에 끊는다: auth 왕복(최대 7초)을 태우고 400을 내지 않게. */
    @ParameterizedTest(name = "본문={0} → field={1}")
    @CsvSource(delimiter = '|', value = {
            "''|body",
            "{|body",
            "[]|body",
            "{\"eventId\":\"EID\"}|body",
            "{\"eventId\":\"EID\",\"pressedAt\":1000,\"sentAt\":1000,\"positionMs\":5}|body",
            "{\"eventId\":\"1-1-1-1-1\",\"positionMs\":5}|eventId",
            "{\"eventId\":\"6ba7b810-9dad-11d1-80b4-00c04fd430c8\",\"positionMs\":5}|eventId",
            "{\"positionMs\":5}|eventId",
            "{\"eventId\":\"EID\",\"pressedAt\":1000}|sentAt",
            "{\"eventId\":\"EID\",\"sentAt\":1000}|pressedAt",
            "{\"eventId\":\"EID\",\"pressedAt\":0,\"sentAt\":1000}|pressedAt",
            "{\"eventId\":\"EID\",\"pressedAt\":1000.5,\"sentAt\":1000}|pressedAt",
            "{\"eventId\":\"EID\",\"pressedAt\":\"1000\",\"sentAt\":1000}|pressedAt",
            "{\"eventId\":\"EID\",\"pressedAt\":1000,\"sentAt\":-1}|sentAt",
            "{\"eventId\":\"EID\",\"positionMs\":1000.5}|positionMs",
            "{\"eventId\":\"EID\",\"positionMs\":\"1000\"}|positionMs",
            "{\"eventId\":\"EID\",\"positionMs\":99999999999999999999}|positionMs"})
    void 형식이_틀리면_400_field이고_auth를_안_부른다(String template, String field) throws Exception {
        String body = template.replace("EID", eventId());

        mvc.perform(마킹(라이브, body))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(invalid(field)));
        assertThat(AUTH.callCount()).isZero();
    }

    @Test
    void 본문이_4KB를_넘으면_400_body() throws Exception {
        String body = 위치_본문(eventId(), "1000");
        body = body.substring(0, body.length() - 1) + ",\"pad\":\"" + "x".repeat(4 * 1024) + "\"}";

        mvc.perform(마킹(지난_방송, body))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(invalid("body")));
        assertThat(AUTH.callCount()).isZero();
    }

    /** 남의 방송 카드의 eventId(전역 유일)면 400이고 그 카드를 안 싣는다. */
    @Test
    void 남의_방송_카드_eventId면_400_eventId() throws Exception {
        broadcasts.save(Broadcast.startedNow("S-other", "8", "OTHERKEY", 1L, T0, null));
        String eventId = eventId();
        jdbc.update("""
                INSERT INTO jump_cards (stream_id, source, event_id, stream_timestamp_ms, window_start_ms, window_end_ms, event_seq)
                VALUES ('S-other', 'hotkey', ?, 1000, 0, 21000, 0)""", eventId);

        mvc.perform(마킹(라이브, 라이브_본문(eventId, SENT_AT, SENT_AT)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(invalid("eventId")));
    }

    // ── 권한 ───────────────────────────────────────────────────

    /**
     * 편집자도 카드를 만든다(10-22 「권한 같음」). 카드는 <b>주인의 방송</b>에 서고 로그에는 요청자와 주인이 둘 다 남는다.
     * 저장에 요청자 번호를 넘기면 방송 주인 대조에서 404다.
     */
    @Test
    void 편집자도_201이고_로그에_요청자와_주인이_남는다() throws Exception {
        try (LogCaptor captor = new LogCaptor()) {
            mvc.perform(마킹(라이브, 라이브_본문(eventId(), SENT_AT, SENT_AT)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.streamId").value(라이브));
            mvc.perform(마킹(라이브, 라이브_본문(eventId(), SENT_AT, SENT_AT))).andExpect(status().isOk());

            assertThat(captor.messages())
                    .anyMatch(m -> m.startsWith("clip.webmark.created") && m.contains("by=" + 편집자) && m.contains("owner=" + 주인))
                    .anyMatch(m -> m.startsWith("clip.webmark.suppressed") && m.contains("by=" + 편집자) && m.contains("owner=" + 주인));
        }
    }

    @Test
    void 주인도_201() throws Exception {
        볼_수_있다("OWNER");

        mvc.perform(마킹(주인, 라이브, 라이브_본문(eventId(), SENT_AT, SENT_AT))).andExpect(status().isCreated());
    }

    /** 자격 없음과 없는 방송은 본문이 같다(남의 방송 번호로 실재를 못 알게). 판정이 저장보다 앞이라 카드도 없다. */
    @Test
    void 자격이_없으면_404이고_없는_방송과_본문이_같고_카드가_안_생긴다() throws Exception {
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"NONE\"}");

        String 자격_없음 = 본문(mvc.perform(마킹(라이브, 라이브_본문(eventId(), SENT_AT, SENT_AT)))
                .andExpect(status().isNotFound()));
        String 없는_방송 = 본문(mvc.perform(마킹("S-없는방송", 라이브_본문(eventId(), SENT_AT, SENT_AT)))
                .andExpect(status().isNotFound()));

        assertThat(자격_없음).isEqualTo("{\"error\":\"broadcast_not_found\"}").isEqualTo(없는_방송);
        assertThat(cardCount()).isZero();
    }

    @Test
    void auth를_못_물으면_503_authorization_unavailable() throws Exception {
        AUTH.respondWith(RESOLVE, 500, "");

        mvc.perform(마킹(라이브, 라이브_본문(eventId(), SENT_AT, SENT_AT)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string("{\"error\":\"authorization_unavailable\"}"));
        assertThat(cardCount()).isZero();
    }

    @Test
    void 토큰이_없으면_401() throws Exception {
        mvc.perform(post("/api/clip/broadcasts/" + 라이브 + "/marks").contentType(APPLICATION_JSON)
                        .content(라이브_본문(eventId(), SENT_AT, SENT_AT)))
                .andExpect(status().isUnauthorized());
        assertThat(cardCount()).isZero();
    }

    // ── 시간: 404 바닥 · 받은 시각 (감사 1회차 m1·m2) ─────────────

    /**
     * 「없는 방송」은 명부 조회로 끝나고 「자격 없음」은 auth 왕복을 탄다. 두 404가 시간으로 갈리면 본문을 같게 한 뜻이 없다
     * ({@code NotFoundFloor}). 전역 처리기는 기준이 찍힌 요청에만 바닥을 걸어, 이 문이 {@code mark}를 안 찍으면 조용히 안 늦는다:
     * 그것을 잡는 것이 이 시험이다. 자격 창구에 바닥 절반의 지연을 심고 두 404의 가장 빠른 응답 시간 차가 그 절반보다 작은지 본다
     * ({@code JumpCardListControllerTest.auth가_느려도_두_404가_같은_시각에_나간다}와 같은 모양·표본 수).
     */
    @Test
    void auth가_느려도_두_404가_같은_시각에_나간다() throws Exception {
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"NONE\"}");
        Duration 느린_창구 = NotFoundFloor.FLOOR.dividedBy(2);
        AUTH.holdFor(느린_창구);

        가장_빠른_404_ms(1, "S-없는방송");  // 워밍업: 첫 요청만 유독 느리다
        double 없는_방송 = 가장_빠른_404_ms(15, "S-없는방송");
        double 자격_없음 = 가장_빠른_404_ms(15, 라이브);

        assertThat(AUTH.callCount()).as("느린 창구를 한 번도 안 지났다: 잴 차이 자체가 없었다").isPositive();
        assertThat(없는_방송).as("없는 방송이 바닥보다 빨리 나갔다").isGreaterThanOrEqualTo(NotFoundFloor.FLOOR.toMillis());
        assertThat(Math.abs(자격_없음 - 없는_방송))
                .as("두 404의 시각이 심은 지연만큼 갈렸다: 기준 시각이 갈림 뒤에 찍혔거나 안 찍혔다")
                .isLessThan(느린_창구.toMillis() / 2.0);
    }

    /**
     * 받은 시각은 문에 들어오자마자 읽는다(계획 검증 m1). 자격 판정(auth 왕복) 뒤에 읽으면 그 시간만큼 누른 시각이 뒤로 밀린다.
     * 진짜 시계로 돌리고 auth에 1초를 심는다.
     *
     * <p>🔴 <b>벽시계 간격이 아니라 순서와 값을 잰다</b>(POK-448). 전에는 「카드 ms − (보낸 때 − 기준점)이 0~500ms」를 봐서, 보낸 때와
     * 서버가 받은 시각을 읽는 사이 JVM이 0.5초 넘게 멈추면(CI 전체 실행에서 4.5초 멈춤 실측, POK-426) 결함 없이 깨질 수 있었다.
     * 지금은 서버가 실제로 읽은 받은 시각을 붙잡아 ① 그것이 auth에 첫 요청이 닿기 <b>전</b>이고 ② 카드 ms가 정확히 그 시각 − 기준점인지
     * 본다. 받은 시각을 판정 뒤에 다시 읽으면 ②가, 판정 뒤에서만 읽으면 ①이 빨갛다.
     *
     * <p>🔴 기준점은 ms로 잘라 심는다(POK-448). {@code Instant.now()}의 나노초를 그대로 심으면 PostgreSQL이 마이크로초로 <b>반올림</b>해
     * 저장해서, 소수가 ms 경계 바로 아래(…0.9995ms 이상)면 서버가 읽는 기준점이 다음 ms가 된다. 받은 시각이 보낸 시각과 같은 ms에
     * 찍힌 회차에서 차이가 −1로 떨어져 CI에서 간헐로 깨졌다(PR #376, 결정적 재현: 기준점 + 0.9997ms · 받은 시각 = 보낸 시각이면 −1).
     */
    @Test
    void 받은_시각은_자격_판정_전에_읽는다() throws Exception {
        AtomicReference<Instant> 받은_시각 = new AtomicReference<>();
        doAnswer(invocation -> {
            Instant read = (Instant) invocation.callRealMethod();
            받은_시각.compareAndSet(null, read);
            return read;
        }).when(webMarks).now();
        Instant origin = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(600);
        jdbc.update("UPDATE broadcasts SET started_at = ? WHERE stream_id = ?", Timestamp.from(origin.minusSeconds(30)), 라이브);
        jdbc.update("UPDATE public.stream_segments SET start_wall_utc = ? WHERE stream_id = ?", Timestamp.from(origin), 라이브_키);
        AUTH.holdFor(Duration.ofSeconds(1));

        long sent = System.currentTimeMillis();
        JsonNode card = json(mvc.perform(마킹(라이브, 라이브_본문(eventId(), sent, sent))).andExpect(status().isCreated()));

        assertThat(받은_시각.get()).as("문이 받은 시각을 안 읽었다").isNotNull();
        assertThat(AUTH.firstRequestAt()).as("자격 판정이 auth를 안 탔다: 순서를 잴 수 없다").isNotNull();
        assertThat(받은_시각.get())
                .as("받은 시각을 자격 판정(auth 왕복) 뒤에 읽었다")
                .isBefore(AUTH.firstRequestAt());
        assertThat(card.path("streamTimestampMs").asLong())
                .as("카드 ms가 문에서 읽은 받은 시각 − 기준점이 아니다: 판정 뒤에 다시 읽었다")
                .isEqualTo(받은_시각.get().toEpochMilli() - origin.toEpochMilli());
    }

    // ── 404 · 503 ───────────────────────────────────────────────

    @Test
    void 기준점이_없으면_503_timeline_not_ready_Retry_After() throws Exception {
        jdbc.update("DELETE FROM public.stream_segments");

        mvc.perform(마킹(라이브, 라이브_본문(eventId(), SENT_AT, SENT_AT)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(content().string("{\"error\":\"timeline_not_ready\"}"));
        mvc.perform(마킹(지난_방송, 위치_본문(eventId(), "1000")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(content().string("{\"error\":\"timeline_not_ready\"}"));
        assertThat(cardCount()).isZero();
    }

    /** 잠그는 사이 방송이 사라지면(서비스 경계에서 가짜로 던진다) 없는 방송과 같은 404다: 500이 아니다. */
    @Test
    void 잠그는_사이_방송이_사라지면_404_broadcast_not_found() throws Exception {
        doThrow(new HotkeyBroadcastGoneException()).when(cards)
                .recordHotkey(anyString(), anyString(), anyString(), anyLong());

        mvc.perform(마킹(라이브, 라이브_본문(eventId(), SENT_AT, SENT_AT)))
                .andExpect(status().isNotFound())
                .andExpect(content().string("{\"error\":\"broadcast_not_found\"}"));
    }

    // ── 도우미 ──────────────────────────────────────────────────

    /** 같은 404를 {@code 횟수}번 재서 가장 빠른 응답 시간(ms). 매 회 404인지 본다: 갈래가 바뀌어 엉뚱한 응답을 재지 않게 */
    private double 가장_빠른_404_ms(int 횟수, String streamId) throws Exception {
        double 최소 = Double.MAX_VALUE;
        for (int i = 0; i < 횟수; i++) {
            long 시작 = System.nanoTime();
            mvc.perform(마킹(streamId, 라이브_본문(eventId(), SENT_AT, SENT_AT))).andExpect(status().isNotFound());
            최소 = Math.min(최소, (System.nanoTime() - 시작) / 1_000_000.0);
        }
        return 최소;
    }

    private static String eventId() {
        return UUID.randomUUID().toString();
    }

    private void 볼_수_있다(String relation) {
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"%s\"}".formatted(relation));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder 마킹(String streamId, String body) {
        return 마킹(편집자, streamId, body);
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder 마킹(
            String requester, String streamId, String body) {
        return post("/api/clip/broadcasts/" + streamId + "/marks").contentType(APPLICATION_JSON)
                .header("Authorization", "Bearer " + TestTokens.access(requester))
                .content(body);
    }

    private static String 라이브_본문(String eventId, long pressedAt, long sentAt) {
        return "{\"eventId\":\"" + eventId + "\",\"pressedAt\":" + pressedAt + ",\"sentAt\":" + sentAt + "}";
    }

    private static String 위치_본문(String eventId, String positionMs) {
        return "{\"eventId\":\"" + eventId + "\",\"positionMs\":" + positionMs + "}";
    }

    private static String invalid(String field) {
        return "{\"error\":\"invalid_request\",\"field\":\"" + field + "\"}";
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

    private static String 본문(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString();
    }

    private static JsonNode json(ResultActions actions) throws Exception {
        MvcResult result = actions.andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }
}
