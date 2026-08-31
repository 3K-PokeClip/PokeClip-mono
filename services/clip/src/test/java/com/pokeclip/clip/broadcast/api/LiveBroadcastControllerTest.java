package com.pokeclip.clip.broadcast.api;

import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.clip.support.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 수집기가 재시작한 뒤 「지금 어느 방송에 붙어야 하나」를 묻는 문(POK-218).
 * MockMvc지만 <b>실제 시큐리티 체인을 태운다</b> — 내부 토큰이 없으면 컨트롤러에 닿지도
 * 못하는 것까지 여기서 닫힌다({@code HighlightIntakeControllerTest}와 같은 자리).
 *
 * <p>🔴 <b>이 창구는 auth를 한 번도 안 부른다.</b> 「안 부른다」는 단언은
 * <b>아무것도 안 하면 자동으로 참</b>이라, 재는 방법을 셋으로 못 박았다 —
 * {@code callCount()}를 <b>호출 전후로</b> 재고 · 같은 클래스 안에 <b>auth를 부르는 문</b>을
 * 대조로 두고 · 판정기를 {@code @MockitoBean}으로 갈아 끼우지 않는다(그러면 운영에서
 * 부르는지를 하나도 안 재면서 초록이 된다).
 *
 * <p>가짜 auth는 답을 안 걸어 두면 <b>503</b>을 준다. 만약 이 창구가 auth를 부르면서 그 503을
 * 삼키고 있으면 <b>200이 나가면서도 부른 것</b>이고, 상태 코드로는 그것을 못 가른다 —
 * {@code callCount()}가 가른다.
 */
@AutoConfigureMockMvc
class LiveBroadcastControllerTest extends IntegrationTestSupport {

    private static final String 창구 = "/internal/broadcasts/live";

    /** application-test.yml의 pokeclip.internal-api.token과 같은 값. */
    private static final String INTERNAL = "test-only-internal-token-32bytes-long!!";

    /** auth를 <b>부르는</b> 문. 대조가 여기 있어야 「이 환경은 원래 아무도 안 부른다」와 갈린다. */
    private static final String 사람_목록_문 = "/api/clip/broadcasts?state=live";

    private static final String ACCESSIBLE = "/internal/editor-delegations/accessible";

    /** 사람 문에 낼 JWT {@code sub}. 방송 픽스처의 스트리머 번호와 다른 사람이다. */
    private static final String 요청자 = "4174";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Instant 시작_시각 = Instant.parse("2026-08-31T09:00:00Z");

    private final MockMvc mvc;
    private final JdbcTemplate jdbc;

    LiveBroadcastControllerTest(MockMvc mvc, JdbcTemplate jdbc) {
        this.mvc = mvc;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 앞_시험의_흔적을_지운다() {
        방송과_카드를_비운다(jdbc);
    }

    /**
     * 🔴 상한 시험이 501줄을 남기고 끝나면 <b>다음 클래스가 그 줄 위에서 돈다</b>.
     * 그 실패는 단독 실행에서는 안 보이고 모듈 전체에서만 터진다(POK-118 선례).
     */
    @AfterEach
    void 대량_씨앗을_남기지_않는다() {
        방송과_카드를_비운다(jdbc);
    }

    // ── 200과 그 모양 ────────────────────────────────────────────

    /**
     * 칸 셋이 다 있고 값이 맞는지. <b>키 집합을 통째로 못 박는다</b> — 「셋이 있다」만 재면
     * 엔티티를 그대로 실어 {@code track_manifest}까지 나가도 초록이다. 방송 상태도 안 싣는다
     * (이 창구는 방송 중인 것만 주므로 항상 같은 값이다).
     *
     * <p>끝난 방송을 <b>실제로 심어 둔다</b> — 안 심으면 「방송 중인 것만 온다」가 거르는
     * 코드를 지워도 참이다.
     */
    @Test
    void 내부_토큰으로_부르면_방송_중인_줄이_온다() throws Exception {
        방송을_넣는다("s-old", TestIds.STREAMER, "live", 시작_시각.minusSeconds(60));
        방송을_넣는다("s-new", TestIds.STREAMER, "live", 시작_시각);
        방송을_넣는다("s-ended", TestIds.STREAMER, "ended", 시작_시각.minusSeconds(30));

        String 본문 = 본문(부른다()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.broadcasts.length()").value(2))
                .andExpect(jsonPath("$.truncated").value(false))
                .andExpect(jsonPath("$.broadcasts[0].streamId").value("s-new"))
                .andExpect(jsonPath("$.broadcasts[0].streamerId").value(TestIds.STREAMER))
                .andExpect(jsonPath("$.broadcasts[0].startedAt").value(시작_시각.toString()))
                .andExpect(jsonPath("$.broadcasts[1].streamId").value("s-old")));

        assertThat(맵(본문).keySet())
                .as("응답 칸이 둘뿐이다 — 늘어나면 POK-219가 읽는 계약이 조용히 바뀐다")
                .containsExactlyInAnyOrder("broadcasts", "truncated");
        assertThat(한_줄(본문, 0).keySet())
                .as("한 줄은 칸 셋뿐이다 — 엔티티를 그대로 실으면 안 쓰는 값까지 나간다")
                .containsExactlyInAnyOrder("streamId", "streamerId", "startedAt");
    }

    /**
     * 🔴 <b>404가 아니다.</b> 그리고 <b>끝난 방송을 심어 두고 잰다</b> — 안 심으면 「비었다」가
     * 표가 비어서 참인지 걸러서 참인지 구분이 안 된다.
     */
    @Test
    void 방송이_없으면_빈_목록_200이다() throws Exception {
        방송을_넣는다("s-ended", TestIds.STREAMER, "ended", 시작_시각);

        String 본문 = 본문(부른다()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.broadcasts.length()").value(0))
                .andExpect(jsonPath("$.truncated").value(false)));

        assertThat(맵(본문))
                .as("칸 이름이 틀려 파서가 못 읽은 것과 진짜 빈 목록을 가른다")
                .containsKey("broadcasts");
    }

    /**
     * 🔴 <b>선행 0이 든 번호를 심는다.</b> 숫자로 직렬화되면 {@code "007"}이 {@code 7}이 되어
     * <b>원본을 잃는다</b> — 명부의 그 칸이 문자열이고 수집기가 {@code StreamerId.parse}로 읽는다.
     * 여기서 숫자로 바꾸면 못 읽는 줄을 clip이 빼야 하고, 그러면 그 방송은 영영 안 걷힌다.
     *
     * <p>값만 비교하면 안 된다 — {@code jsonPath().value("007")}은 형이 갈려도 메시지가
     * 헷갈린다. <b>본문을 맵으로 읽어 형까지 짚는다.</b>
     */
    @Test
    void 스트리머_번호가_문자열로_나간다() throws Exception {
        방송을_넣는다("s-leading-zero", "007", "live", 시작_시각);

        String 본문 = 본문(부른다().andExpect(status().isOk())
                .andExpect(jsonPath("$.broadcasts.length()").value(1)));

        assertThat(한_줄(본문, 0).get("streamerId"))
                .as("숫자로 실으면 007이 7이 되어 원본을 잃는다")
                .isInstanceOf(String.class)
                .isEqualTo("007");
    }

    /**
     * 🔴 <b>칸을 지우지 않고 {@code null}로 싣는다.</b> 이 줄은 <b>운영 경로로는 도달 불가</b>다
     * ({@code LiveStartedAtNeverNullTest}가 재현으로 고정했고, 막는 것은 러너의 봉투 검증
     * 한 줄뿐이다). 그래도 계약을 못 박는 이유 — 그 검증이 사라지는 날 수집기가 받는 것이
     * 「칸이 없는 줄」이면 파싱이 통째로 깨지고, 「{@code null}인 칸」이면 그 줄만 판단하면 된다.
     *
     * <p>{@code jsonPath().doesNotExist()}로는 못 잰다 — <b>값이 {@code null}인 키도 통과</b>한다
     * (POK-121 실측). 그래서 맵으로 읽어 <b>키가 있는 것</b>과 <b>값이 null인 것</b>을 따로 잰다.
     */
    @Test
    void 시작_시각이_빈_줄도_그_칸이_남은_채로_나간다() throws Exception {
        방송을_넣는다("s-null", "007", "live", null);

        String 본문 = 본문(부른다().andExpect(status().isOk())
                .andExpect(jsonPath("$.broadcasts.length()").value(1)));

        Map<String, Object> 줄 = 한_줄(본문, 0);
        assertThat(줄).containsKey("startedAt");
        assertThat(줄.get("startedAt")).isNull();
    }

    /**
     * 🔴 <b>상한을 넘겨야 {@code truncated}를 잰다.</b> 몇 줄만 심고 「거짓이다」만 재면
     * 이 칸을 <b>상수 {@code false}로 박아도 초록</b>이다.
     *
     * <p>상한 값(500)의 정본은 {@code LiveBroadcastService.MAX_ROWS}인데 <b>패키지 전용이라
     * 여기서 못 읽는다.</b> 그래서 숫자를 적었다 — 상한이 바뀌면 이 시험은 <b>빨간불</b>이 되지
     * 조용히 무의미해지지 않는다(줄 수가 안 맞아 단언이 깨진다). 값의 정본은 커밋되는
     * {@code services/README.md}이고 경계 판정 자체는 {@code LiveBroadcastServiceTest}가 잰다.
     */
    @Test
    void 상한을_넘으면_상한만큼만_나가고_truncated가_참이다() throws Exception {
        방송을_많이_넣는다(501);

        부른다()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.broadcasts.length()").value(500))
                .andExpect(jsonPath("$.truncated")
                        .value(true));
    }

    // ── 거절은 401 하나뿐이다 ────────────────────────────────────

    /**
     * 🔴 <b>긍정 갈래를 짝으로 둔다.</b> {@code /internal/**}는 없는 경로도 401이라,
     * 경로에 오타가 있어도 이 단언은 통과한다 — <b>같은 경로에 내부 토큰을 내면 200</b>이어야
     * 401이 「토큰 때문」이라는 뜻을 갖는다({@code SecurityChainTest}가 같은 이유로 404 짝을 둔다).
     */
    @Test
    void 토큰이_없으면_401이다() throws Exception {
        mvc.perform(get(창구)).andExpect(status().isUnauthorized());

        같은_경로가_내부_토큰이면_200이다();
    }

    /**
     * 사람 토큰으로는 못 연다. 체인이 갈려 있어 {@code /internal/**}는 JwtDecoder를 아예 안 탄다 —
     * 이것이 무너지면 <b>로그인한 아무나 방송 중인 방송 전부를 본다</b>.
     */
    @Test
    void 사람_토큰을_내면_401이다() throws Exception {
        mvc.perform(get(창구).header("Authorization", "Bearer " + TestTokens.access(TestIds.STREAMER)))
                .andExpect(status().isUnauthorized());

        같은_경로가_내부_토큰이면_200이다();
    }

    // ── 🔴 auth 무접촉 ──────────────────────────────────────────

    /**
     * 🔴 이 카드에서 가장 새기 쉬운 자리다. 재는 것이 셋이다 —
     *
     * <ol>
     *   <li><b>전후 차이</b>. 호출 뒤에만 재면 0이 「이 요청이 안 불렀다」인지 「원래부터 0」인지
     *       구분이 안 된다</li>
     *   <li><b>대조</b>. auth를 부르는 문을 같은 클래스에서 한 번 불러 그 값이 <b>실제로 는다</b>는
     *       것을 보인다. 없으면 「이 환경에서는 원래 아무도 auth를 안 부른다」와 안 갈린다</li>
     *   <li><b>진짜 소켓</b>. 판정기를 가짜 빈으로 갈아 끼우지 않는다 —
     *       {@link IntegrationTestSupport#AUTH}는 실제로 듣는 서버다</li>
     * </ol>
     *
     * <p>대조 호출의 응답은 503이다(가짜 auth의 초기 상태). <b>그것으로 충분하다</b> —
     * 재는 것은 응답이 아니라 「요청이 auth에 도달했다」이고, {@code lastPath()}가 그것을 짚는다.
     */
    @Test
    void auth를_한_번도_부르지_않는다() throws Exception {
        방송을_넣는다("s-1", TestIds.STREAMER, "live", 시작_시각);

        int 부르기_전 = AUTH.callCount();
        부른다().andExpect(status().isOk())
                .andExpect(jsonPath("$.broadcasts.length()").value(1));
        int 부른_뒤 = AUTH.callCount();

        assertThat(부른_뒤 - 부르기_전)
                .as("이 창구는 없는 회원 번호로 auth를 두드리면 안 된다")
                .isZero();

        // 대조 — 이 문은 auth를 부른다. 안 늘면 위의 0은 아무것도 안 재고 있다는 뜻이다.
        int 대조_전 = AUTH.callCount();
        mvc.perform(get(사람_목록_문)
                .header("Authorization", "Bearer " + TestTokens.access(요청자)));

        assertThat(AUTH.callCount() - 대조_전)
                .as("대조가 안 늘면 이 환경에서는 원래 아무도 auth를 안 부르는 것이고, 위 단언은 무의미하다")
                .isEqualTo(1);
        assertThat(AUTH.lastPath()).isEqualTo(ACCESSIBLE);
    }

    // ── 도우미 ──────────────────────────────────────────────────

    private ResultActions 부른다() throws Exception {
        return mvc.perform(get(창구).header("X-Internal-Token", INTERNAL));
    }

    private void 같은_경로가_내부_토큰이면_200이다() throws Exception {
        부른다().andExpect(status().isOk());
    }

    private static String 본문(ResultActions 응답) throws Exception {
        return 응답.andReturn().getResponse().getContentAsString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> 맵(String 본문) {
        return MAPPER.readValue(본문, Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> 한_줄(String 본문, int 번째) {
        return ((List<Map<String, Object>>) 맵(본문).get("broadcasts")).get(번째);
    }

    private void 방송을_넣는다(String streamId, String streamerId, String status, Instant startedAt) {
        jdbc.update("""
                        INSERT INTO broadcasts (stream_id, streamer_id, status, started_at, last_sequence)
                        VALUES (?, ?, ?, ?, 1)""",
                streamId, streamerId, status,
                startedAt == null ? null : OffsetDateTime.ofInstant(startedAt, ZoneOffset.UTC));
    }

    /** 한 문장에 심는다 — 501줄을 줄마다 왕복하면 시험이 분 단위가 된다. */
    private void 방송을_많이_넣는다(int count) {
        jdbc.update("""
                        INSERT INTO broadcasts (stream_id, streamer_id, status, started_at, last_sequence)
                        SELECT 's-' || g, ?, 'live',
                               TIMESTAMPTZ '2026-08-31 00:00:00+00' - (g || ' seconds')::interval, 1
                          FROM generate_series(1, ?) g""",
                TestIds.STREAMER, count);
    }
}
