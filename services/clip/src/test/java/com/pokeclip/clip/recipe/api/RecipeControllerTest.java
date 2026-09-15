package com.pokeclip.clip.recipe.api;

import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.NotFoundFloor;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.clip.support.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 편집 기록 문 넷(POK-124). 재는 것은 다섯이다 — <b>보낸 모양이 그대로 돌아오는가</b>(칸 이름 계약6) ·
 * 고치면 판이 오르고 <b>동시에 고쳐도 판이 안 겹치는가</b> · 계약6 2층 규칙이 <b>하나씩 실제로 거절되는가</b> ·
 * 자격 창구를 <b>실제로</b> 거치는가 · 거절 둘이 <b>시간으로 안 갈리는가</b>.
 *
 * <p>가짜 자격 창구는 Mockito가 아니라 진짜 소켓({@link IntegrationTestSupport#AUTH})이고 답을 안 걸면 503이다.
 * 🔴 요청자 번호는 {@link TestIds#STREAMER}와 다르다 — 같으면 auth에 안 묻는 구현에서도 초록이 된다.
 */
@AutoConfigureMockMvc
class RecipeControllerTest extends IntegrationTestSupport {

    private static final String RESOLVE = "/internal/editor-delegations/resolve";

    /** JWT {@code sub}. 방송 픽스처의 스트리머와 <b>다른 사람</b>이다. */
    private static final String 요청자 = "4179";

    private static final String 내_방송 = "s-recipe";
    /** 같은 스트리머의 다른 방송. 자격은 있지만 <b>경로가 다르다</b> — 번호를 섞어 쓰는 갈래를 잰다. */
    private static final String 다른_방송 = "s-recipe-2";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Instant 시작_시각 = Instant.parse("2026-09-15T00:00:00Z");

    /** 정본은 {@link NotFoundFloor#FLOOR} — 여기서 베끼지 않는다. */
    private static final long 바닥_ms = NotFoundFloor.FLOOR.toMillis();

    private final MockMvc mvc;
    private final JdbcTemplate jdbc;

    RecipeControllerTest(MockMvc mvc, JdbcTemplate jdbc) {
        this.mvc = mvc;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 앞_테스트의_흔적을_지운다() {
        방송과_카드를_비운다(jdbc);
        방송을_넣는다(내_방송);
        방송을_넣는다(다른_방송);
    }

    /**
     * 🔴 <b>내 레시피를 내가 치운다.</b> 다른 시험 클래스 열다섯이 공용 도우미 대신 {@code broadcasts}를 직접
     * 지우는데, 레시피가 남아 있으면 그 삭제가 FK로 죽는다 — <b>단독은 초록이고 모듈 전체에서만, 그것도 실행 순서에
     * 따라</b> 터진다(2026-09-15 실측: {@code BroadcastEventProcessorTest} 5건). 남의 시험을 고치는 대신 흔적을
     * 안 남긴다. {@code jump_cards}는 그 시험들이 이미 알아서 지우지만 {@code recipes}는 모른다.
     */
    @AfterEach
    void 내_흔적을_지운다() {
        jdbc.update("DELETE FROM recipes");
    }

    // ── 저장하고 다시 읽기 ────────────────────────────────────────

    /**
     * 카드의 완료 조건 첫 줄 — 「보낸 기록이 저장되고 다시 조회된다」. <b>보낸 JSON과 돌아온 {@code recipe}가
     * 트리로 같다</b>고 단언하므로 칸 이름 하나가 바뀌면(계약6 위반) 여기서 빨간불이다.
     * auth를 <b>정확히 한 번</b> 물었는지도 같이 잰다 — 0이면 판정 경로를 안 탄 것이다.
     */
    @Test
    void 저장하면_201이고_다시_읽으면_보낸_모양_그대로다() throws Exception {
        볼_수_있다("OWNER");
        JsonNode 보낸것 = 정상_레시피();

        String 응답 = 본문(저장(내_방송, 보낸것)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.streamId").value(내_방송))
                .andExpect(jsonPath("$.creatorId").value(요청자))
                .andExpect(jsonPath("$.recipeVersion").value(1))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists()));
        long id = MAPPER.readTree(응답).get("id").asLong();
        assertThat(MAPPER.readTree(응답).get("recipe")).as("돌아온 recipe가 보낸 것과 다르다").isEqualTo(보낸것);
        assertThat(AUTH.callCount()).as("자격 창구를 안 거쳤다").isEqualTo(1);

        String 다시 = 본문(하나(내_방송, id).andExpect(status().isOk()));
        assertThat(MAPPER.readTree(다시).get("recipe")).isEqualTo(보낸것);
        assertThat(MAPPER.readTree(다시).get("id").asLong()).isEqualTo(id);
    }

    @Test
    void 목록은_만든_순서다() throws Exception {
        볼_수_있다("OWNER");
        long 첫째 = 저장된_번호(내_방송, 정상_레시피());
        long 둘째 = 저장된_번호(내_방송, 정상_레시피());
        저장된_번호(다른_방송, 정상_레시피(다른_방송));

        목록(내_방송)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipes.length()").value(2))
                .andExpect(jsonPath("$.recipes[0].id").value(첫째))
                .andExpect(jsonPath("$.recipes[1].id").value(둘째));
    }

    /** 계약6 2절 — {@code cut: null}이 곧 템플릿이다. 표에서는 두 칸이 다 비고, 돌아올 때도 {@code null}이다. */
    @Test
    void 템플릿은_cut이_null이다() throws Exception {
        볼_수_있다("OWNER");
        ObjectNode 템플릿 = 정상_레시피();
        템플릿.putNull("cut");

        long id = 저장된_번호(내_방송, 템플릿);

        하나(내_방송, id).andExpect(jsonPath("$.recipe.cut").value(nullValue()));
        assertThat(jdbc.queryForObject("SELECT cut_in_at_ms IS NULL AND cut_out_at_ms IS NULL FROM recipes WHERE id = ?",
                Boolean.class, id)).isTrue();
    }

    /** 자막을 빼면 「자막 없음」이다 — 빈 배열로 바꿔 채우지 않는다(번인도 srt도 없다는 뜻이 사라진다). */
    @Test
    void 자막을_빼면_null로_돌아온다() throws Exception {
        볼_수_있다("OWNER");
        ObjectNode 없음 = 정상_레시피();
        없음.remove("subtitles");

        long id = 저장된_번호(내_방송, 없음);

        하나(내_방송, id).andExpect(jsonPath("$.recipe.subtitles").value(nullValue()));
    }

    /** 돈 내는 쪽과 매일 쓰는 쪽이 다르다 — 편집자가 저장 못 하면 이 문은 쓸모가 없다. */
    @Test
    void 편집자도_저장한다() throws Exception {
        볼_수_있다("EDITOR");
        저장(내_방송, 정상_레시피()).andExpect(status().isCreated());
    }

    // ── 고치기 ───────────────────────────────────────────────────

    @Test
    void 고치면_판이_오르고_내용이_바뀌고_만든_사람은_그대로다() throws Exception {
        볼_수_있다("OWNER");
        long id = 저장된_번호(내_방송, 정상_레시피());
        ObjectNode 고친것 = 정상_레시피();
        ((ObjectNode) 고친것.get("audio").get("tracks").get(0)).put("gain", 0.5);

        String 응답 = 본문(고치기(내_방송, id, 고친것)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.recipeVersion").value(2))
                .andExpect(jsonPath("$.creatorId").value(요청자)));
        assertThat(MAPPER.readTree(응답).get("recipe")).isEqualTo(고친것);

        하나(내_방송, id).andExpect(jsonPath("$.recipeVersion").value(2))
                .andExpect(jsonPath("$.recipe.audio.tracks[0].gain").value(0.5));
    }

    /**
     * 🔴 <b>락이 실제로 판을 지키는지</b>를 잰다. 락을 빼면 두 요청이 같은 판을 읽고 둘 다 +1을 써서 판 하나가
     * 사라진다(둘 다 2). 렌더 주문이 판 번호로 레시피를 가리키므로 번호가 겹치면 다른 내용이 같은 좌표를 갖는다.
     * 스무 요청을 한 문에서 같이 출발시키고 <b>돌아온 판 번호가 전부 다르고</b> 마지막이 21인 것을 본다.
     */
    @Test
    void 동시에_고쳐도_판_번호가_안_겹친다() throws Exception {
        볼_수_있다("OWNER");
        long id = 저장된_번호(내_방송, 정상_레시피());
        int 요청_수 = 20;
        CountDownLatch 출발 = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(요청_수);
        try {
            List<Future<Integer>> 결과 = new ArrayList<>();
            for (int i = 0; i < 요청_수; i++) {
                double gain = 0.1 + i * 0.05;
                Callable<Integer> 한_번 = () -> {
                    출발.await();
                    ObjectNode 고친것 = 정상_레시피();
                    ((ObjectNode) 고친것.get("audio").get("tracks").get(0)).put("gain", gain);
                    String 응답 = 본문(고치기(내_방송, id, 고친것).andExpect(status().isOk()));
                    return MAPPER.readTree(응답).get("recipeVersion").asInt();
                };
                결과.add(pool.submit(한_번));
            }
            출발.countDown();
            List<Integer> 판들 = new ArrayList<>();
            for (Future<Integer> f : 결과) {
                판들.add(f.get());
            }
            assertThat(Set.copyOf(판들)).as("판 번호가 겹쳤다 — 락이 안 걸린 것이다").hasSize(요청_수);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT recipe_version FROM recipes WHERE id = ?", Integer.class, id))
                .isEqualTo(1 + 요청_수);
    }

    // ── 404 ──────────────────────────────────────────────────────

    /** 자격 없음과 없는 방송은 <b>본문이 같다</b>(PRD). 문 넷 중 저장·목록 둘로 잰다 — 나머지는 같은 서비스 줄이다. */
    @Test
    void 자격_없음과_없는_방송은_같은_404다() throws Exception {
        볼_수_없다();
        String 자격_없음_저장 = 본문(저장(내_방송, 정상_레시피()).andExpect(status().isNotFound()));
        String 자격_없음_목록 = 본문(목록(내_방송).andExpect(status().isNotFound()));

        볼_수_있다("OWNER");
        String 없는_방송_저장 = 본문(저장("s-없는방송", 정상_레시피("s-없는방송")).andExpect(status().isNotFound()));
        String 없는_방송_목록 = 본문(목록("s-없는방송").andExpect(status().isNotFound()));

        assertThat(자격_없음_저장).isEqualTo(없는_방송_저장).contains("broadcast_not_found");
        assertThat(자격_없음_목록).isEqualTo(없는_방송_목록).contains("broadcast_not_found");
    }

    /**
     * 🔴 <b>규칙 검증은 자격 판정 뒤다.</b> 앞에 두면 없는 방송에 대고 본문을 고쳐 가며 400/404를 갈라 볼 수 있어
     * 「없는 방송과 자격 없음이 같다」가 깨진다. 잘못된 본문을 자격 없는 사람이 보내면 <b>404</b>여야 한다.
     */
    @Test
    void 규칙_위반_본문도_자격이_없으면_404다() throws Exception {
        볼_수_없다();
        ObjectNode 틀린것 = 정상_레시피();
        틀린것.put("schemaVersion", 2);

        저장(내_방송, 틀린것).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("broadcast_not_found"));
    }

    /**
     * 다른 방송의 번호를 내 방송 경로에 넣으면 <b>{@code recipe_not_found}</b>다 — 「있는데 남의 것」을 따로
     * 말하면 번호를 훑어 남의 방송 레시피 수를 셀 수 있다. 고치기도 같고, <b>고쳐지지도 않는다</b>.
     */
    @Test
    void 다른_방송의_번호는_내_방송_경로에서_404다() throws Exception {
        볼_수_있다("OWNER");
        long 남의것 = 저장된_번호(다른_방송, 정상_레시피(다른_방송));

        하나(내_방송, 남의것).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("recipe_not_found"));
        고치기(내_방송, 남의것, 정상_레시피()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("recipe_not_found"));
        하나(내_방송, 999_999L).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("recipe_not_found"));

        assertThat(jdbc.queryForObject("SELECT recipe_version FROM recipes WHERE id = ?", Integer.class, 남의것))
                .as("남의 방송 경로로 고쳐졌다").isEqualTo(1);
    }

    @Test
    void auth를_못_물으면_503이다() throws Exception {
        // 답을 안 건다 — 503.
        저장(내_방송, 정상_레시피()).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("authorization_unavailable"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recipes", Integer.class)).isZero();
    }

    @Test
    void 토큰이_없으면_401이다() throws Exception {
        mvc.perform(post("/api/clip/broadcasts/" + 내_방송 + "/recipes")
                        .contentType(MediaType.APPLICATION_JSON).content(정상_레시피().toString()))
                .andExpect(status().isUnauthorized());
    }

    // ── 바닥 시간 ─────────────────────────────────────────────────

    /** 없는 방송의 404가 바닥보다 빠르면 그 빠르기가 「없다」는 신호다. 목록 문으로 잰다. */
    @Test
    void 없는_방송의_404가_바닥_시간을_채운다() throws Exception {
        볼_수_있다("OWNER");
        assertThat(가장_빠른_응답_ms(3, () -> 목록("s-없는방송"), status().isNotFound()))
                .isGreaterThanOrEqualTo(바닥_ms);
    }

    @Test
    void 자격_없음의_404도_같은_바닥_시간을_채운다() throws Exception {
        볼_수_없다();
        assertThat(가장_빠른_응답_ms(3, () -> 목록(내_방송), status().isNotFound()))
                .isGreaterThanOrEqualTo(바닥_ms);
    }

    /** 없는 번호의 404도 같은 바닥이다 — 「내 방송에 없는 번호」와 「남의 방송에 있는 번호」가 시간으로 갈리면 안 된다. */
    @Test
    void 없는_번호의_404도_바닥_시간을_채운다() throws Exception {
        볼_수_있다("OWNER");
        assertThat(가장_빠른_응답_ms(3, () -> 하나(내_방송, 999_999L), status().isNotFound()))
                .isGreaterThanOrEqualTo(바닥_ms);
    }

    // ── 400: 계약6 2층 규칙 하나씩 ────────────────────────────────

    /**
     * 규칙마다 <b>정상 본문에서 그 하나만</b> 바꾼다 — 그래야 빨간불이 그 규칙 탓이다. 기대하는 {@code field}는
     * 계약6의 덩어리 이름(파서 거절은 칸 경로)이고, 표에 없는 규칙은 아무도 안 재고 있는 것이다.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("규칙_위반들")
    void 계약6_규칙에_어긋나면_400이고_어느_칸인지_말한다(String 설명, Consumer<ObjectNode> 망가뜨리기, String 기대_칸)
            throws Exception {
        볼_수_있다("OWNER");
        ObjectNode 본문 = 정상_레시피();
        망가뜨리기.accept(본문);

        저장(내_방송, 본문).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.field").value(기대_칸));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recipes", Integer.class)).as("거절했는데 저장됐다").isZero();
    }

    static Stream<Arguments> 규칙_위반들() {
        return Stream.of(
                // 파서 — 모르는 칸·형 불일치. 칸 경로를 싣는다.
                위반("모르는 칸(최상위)", r -> r.put("title", "제목은 레시피가 아니다"), "title"),
                위반("모르는 칸(중첩)", r -> 출력(r, 0).put("scale", 1.0), "outputs[0].scale"),
                위반("문자열 trackId", r -> 트랙(r, 0).put("trackId", "1"), "audio.tracks[0].trackId"),
                위반("소수 trackId", r -> 트랙(r, 0).put("trackId", 1.5), "audio.tracks[0].trackId"),
                // schemaVersion · streamId
                위반("schemaVersion 2", r -> r.put("schemaVersion", 2), "schemaVersion"),
                위반("schemaVersion 없음", r -> r.remove("schemaVersion"), "schemaVersion"),
                위반("streamId가 경로와 다름", r -> r.put("streamId", 다른_방송), "streamId"),
                위반("streamId 없음", r -> r.remove("streamId"), "streamId"),
                // cut
                위반("cut 뒤집힘", r -> 컷(r).put("outAtMs", 0), "cut"),
                // 뺄셈이 넘쳐 길이가 정확히 5,000으로 접히는 값 — 순서 검사가 없으면 통과해 DB CHECK에서 500이다(1판 claude).
                위반("cut 뺄셈 넘침", r -> { 컷(r).put("inAtMs", Long.MAX_VALUE); 컷(r).put("outAtMs", Long.MIN_VALUE + 4_999); }, "cut"),
                위반("cut 4.999초", r -> 컷(r).put("outAtMs", 1_000_000L + 4_999), "cut"),
                위반("cut 180.001초", r -> 컷(r).put("outAtMs", 1_000_000L + 180_001), "cut"),
                위반("cut 한 칸만", r -> 컷(r).remove("outAtMs"), "cut"),
                // outputs
                위반("outputs 빈 배열", r -> ((ArrayNode) r.get("outputs")).removeAll(), "outputs"),
                위반("outputs 없음", r -> r.remove("outputs"), "outputs"),
                위반("outputId 대문자", r -> 출력(r, 0).put("outputId", "O1"), "outputs"),
                위반("outputId 33자", r -> 출력(r, 0).put("outputId", "a".repeat(33)), "outputs"),
                위반("outputId 중복", r -> 출력(r, 1).put("outputId", "o1"), "outputs"),
                위반("aspect 모름", r -> 출력(r, 0).put("aspect", "WIDE_16_9"), "outputs"),
                위반("aspect 중복", r -> 출력(r, 1).put("aspect", "VERT_9_16"), "outputs"),
                위반("crop x+w>1", r -> 크롭(r, 0).put("x", 0.7), "outputs"),
                위반("crop y+h>1", r -> 크롭(r, 0).put("y", 0.01), "outputs"),
                위반("crop w<0.05", r -> { 크롭(r, 0).put("w", 0.04); }, "outputs"),
                위반("crop x=1", r -> { 크롭(r, 0).put("x", 1.0); 크롭(r, 0).put("w", 0.05); }, "outputs"),
                위반("crop 칸 없음", r -> 크롭(r, 0).remove("h"), "outputs"),
                // audio
                위반("tracks 빈 배열", r -> ((ArrayNode) r.get("audio").get("tracks")).removeAll(), "audio"),
                위반("audio 없음", r -> r.remove("audio"), "audio"),
                위반("trackId 6", r -> 트랙(r, 0).put("trackId", 6), "audio"),
                위반("trackId 중복", r -> 트랙(r, 1).put("trackId", 1), "audio"),
                위반("0과 1~5 동시", r -> 트랙(r, 1).put("trackId", 0), "audio"),
                위반("gain 2.1", r -> 트랙(r, 0).put("gain", 2.1), "audio"),
                위반("gain 음수", r -> 트랙(r, 0).put("gain", -0.1), "audio"),
                위반("gain 없음", r -> 트랙(r, 0).remove("gain"), "audio"),
                // subtitles
                위반("mode 모름", r -> 자막(r).put("mode", "CC_AND_BURN"), "subtitles"),
                위반("segments 없음", r -> 자막(r).remove("segments"), "subtitles"),
                위반("segment start>=end", r -> 구간(r, 0).put("endAtMs", 1_001_200L), "subtitles"),
                위반("segments 순서 역", r -> 구간(r, 1).put("startAtMs", 1_000_100L), "subtitles"),
                위반("segments 겹침", r -> 구간(r, 1).put("startAtMs", 1_003_000L), "subtitles"),
                위반("segment text 없음", r -> 구간(r, 0).remove("text"), "subtitles"));
    }

    @Test
    void 본문이_JSON이_아니면_400이고_칸은_body다() throws Exception {
        볼_수_있다("OWNER");
        mvc.perform(post("/api/clip/broadcasts/" + 내_방송 + "/recipes")
                        .header("Authorization", "Bearer " + TestTokens.access(요청자))
                        .contentType(MediaType.APPLICATION_JSON).content("{이건 JSON이 아니다"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.field").value("body"));
    }

    /** 고치기도 같은 규칙을 탄다. 하나만 재고 나머지는 같은 검증기다 — 거절되면 판도 안 오른다. */
    @Test
    void 고치기도_규칙에_어긋나면_400이고_판은_안_오른다() throws Exception {
        볼_수_있다("OWNER");
        long id = 저장된_번호(내_방송, 정상_레시피());
        ObjectNode 틀린것 = 정상_레시피();
        트랙(틀린것, 0).put("gain", 3.0);

        고치기(내_방송, id, 틀린것).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("audio"));

        하나(내_방송, id).andExpect(jsonPath("$.recipeVersion").value(1));
    }

    // ── 도우미 ──────────────────────────────────────────────────

    private static Arguments 위반(String 설명, Consumer<ObjectNode> 망가뜨리기, String 기대_칸) {
        return Arguments.of(설명, 망가뜨리기, 기대_칸);
    }

    private static ObjectNode 컷(ObjectNode r) {
        return (ObjectNode) r.get("cut");
    }

    private static ObjectNode 출력(ObjectNode r, int i) {
        return (ObjectNode) r.get("outputs").get(i);
    }

    private static ObjectNode 크롭(ObjectNode r, int i) {
        return (ObjectNode) 출력(r, i).get("crop");
    }

    private static ObjectNode 트랙(ObjectNode r, int i) {
        return (ObjectNode) r.get("audio").get("tracks").get(i);
    }

    private static ObjectNode 자막(ObjectNode r) {
        return (ObjectNode) r.get("subtitles");
    }

    private static ObjectNode 구간(ObjectNode r, int i) {
        return (ObjectNode) 자막(r).get("segments").get(i);
    }

    /** 계약6 1절 예시를 이 시험의 방송 번호로. 45초 컷 · 출력 둘 · 트랙 둘 · 자막 둘(안 겹침, 오름차순). */
    private static ObjectNode 정상_레시피() {
        return 정상_레시피(내_방송);
    }

    private static ObjectNode 정상_레시피(String streamId) {
        return (ObjectNode) MAPPER.readTree("""
                {
                  "schemaVersion": 1,
                  "streamId": "%s",
                  "cut": { "inAtMs": 1000000, "outAtMs": 1045000 },
                  "outputs": [
                    { "outputId": "o1", "aspect": "VERT_9_16",  "crop": { "x": 0.21,  "y": 0.0, "w": 0.316,  "h": 1.0 } },
                    { "outputId": "o2", "aspect": "SQUARE_1_1", "crop": { "x": 0.219, "y": 0.0, "w": 0.5625, "h": 1.0 } }
                  ],
                  "audio": { "tracks": [ { "trackId": 1, "gain": 1.0 }, { "trackId": 3, "gain": 0.6 } ] },
                  "subtitles": {
                    "mode": "BURN_AND_CC",
                    "segments": [
                      { "startAtMs": 1001200, "endAtMs": 1003400, "text": "첫 자막" },
                      { "startAtMs": 1003400, "endAtMs": 1005000, "text": "둘째 자막" }
                    ]
                  }
                }""".formatted(streamId));
    }

    private void 볼_수_있다(String relation) {
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"%s\"}".formatted(relation));
    }

    private void 볼_수_없다() {
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"NONE\"}");
    }

    private ResultActions 저장(String streamId, JsonNode 본문) throws Exception {
        return mvc.perform(post("/api/clip/broadcasts/" + streamId + "/recipes")
                .header("Authorization", "Bearer " + TestTokens.access(요청자))
                .contentType(MediaType.APPLICATION_JSON).content(본문.toString()));
    }

    private long 저장된_번호(String streamId, JsonNode 본문) throws Exception {
        return MAPPER.readTree(본문(저장(streamId, 본문).andExpect(status().isCreated()))).get("id").asLong();
    }

    private ResultActions 목록(String streamId) throws Exception {
        return mvc.perform(get("/api/clip/broadcasts/" + streamId + "/recipes")
                .header("Authorization", "Bearer " + TestTokens.access(요청자)));
    }

    private ResultActions 하나(String streamId, long id) throws Exception {
        return mvc.perform(get("/api/clip/broadcasts/" + streamId + "/recipes/" + id)
                .header("Authorization", "Bearer " + TestTokens.access(요청자)));
    }

    private ResultActions 고치기(String streamId, long id, JsonNode 본문) throws Exception {
        return mvc.perform(put("/api/clip/broadcasts/" + streamId + "/recipes/" + id)
                .header("Authorization", "Bearer " + TestTokens.access(요청자))
                .contentType(MediaType.APPLICATION_JSON).content(본문.toString()));
    }

    private static String 본문(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString();
    }

    private interface 요청 {
        ResultActions 보낸다() throws Exception;
    }

    /** 같은 요청을 {@code 횟수}번 재서 가장 빠른 응답(ms). 기대 상태를 매 회 확인한다 — 엉뚱한 응답을 재지 않게. */
    private double 가장_빠른_응답_ms(int 횟수, 요청 요청, ResultMatcher 기대) throws Exception {
        double 최소 = Double.MAX_VALUE;
        for (int i = 0; i < 횟수; i++) {
            long 시작 = System.nanoTime();
            요청.보낸다().andExpect(기대);
            최소 = Math.min(최소, (System.nanoTime() - 시작) / 1_000_000.0);
        }
        return 최소;
    }

    private void 방송을_넣는다(String streamId) {
        jdbc.update("""
                        INSERT INTO broadcasts (stream_id, streamer_id, status, started_at, last_sequence)
                        VALUES (?, ?, 'live', ?, 1)""",
                streamId, TestIds.STREAMER, OffsetDateTime.ofInstant(시작_시각, ZoneOffset.UTC));
    }
}
