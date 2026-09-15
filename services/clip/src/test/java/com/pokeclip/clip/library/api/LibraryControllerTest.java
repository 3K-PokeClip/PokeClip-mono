package com.pokeclip.clip.library.api;

import com.pokeclip.clip.paging.CursorCodec;
import com.pokeclip.clip.render.RenderFixtures;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.NotFoundFloor;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.clip.support.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 보관함 문 둘(POK-243). 재는 것은 다섯 — <b>남의 스트리머 편집본이 안 나오는가</b>(auth {@code accessible}를 실제로 거치는가) ·
 * 상태가 편집본과 최신 영상에서 <b>규칙대로 파생되는가</b>(판을 고치면 옛 영상이 있어도 편집 중) · 거르기·이어받기가 맞는가 ·
 * 상세의 거절 둘(없다·남의 것)이 <b>같은 본문·같은 바닥 시간</b>인가 · 목록 줄과 상세의 공통 칸이 <b>한 모양</b>인가.
 *
 * <p>🔴 요청자 번호는 {@link TestIds#STREAMER}와 다르다 — 같으면 auth에 안 묻는 구현에서도 초록이 된다.
 */
@AutoConfigureMockMvc
class LibraryControllerTest extends IntegrationTestSupport {

    private static final String ACCESSIBLE = "/internal/editor-delegations/accessible";
    private static final String RESOLVE = "/internal/editor-delegations/resolve";
    private static final String 요청자 = "4181";
    private static final String 내_방송 = "s-lib";
    private static final String 내_다른_방송 = "s-lib-2";
    private static final String 남의_방송 = "s-lib-other";
    private static final String 남의_스트리머 = "8";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long 바닥_ms = NotFoundFloor.FLOOR.toMillis();

    private final MockMvc mvc;
    private final JdbcTemplate jdbc;

    LibraryControllerTest(MockMvc mvc, JdbcTemplate jdbc) {
        this.mvc = mvc;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 앞_테스트의_흔적을_지운다() {
        방송과_카드를_비운다(jdbc);
        RenderFixtures.방송을_넣는다(jdbc, 내_방송);
        RenderFixtures.방송을_넣는다(jdbc, 내_다른_방송);
        jdbc.update("INSERT INTO broadcasts (stream_id, streamer_id, status, last_sequence) VALUES (?, ?, 'ended', 1)",
                남의_방송, 남의_스트리머);
    }

    /** 다른 시험 클래스가 broadcasts를 직접 지운다 — 내 자식 줄을 내가 치운다(RenderRequestControllerTest와 같은 이유). */
    @AfterEach
    void 내_흔적을_지운다() {
        jdbc.update("DELETE FROM render_job_events");
        jdbc.update("DELETE FROM render_jobs");
        jdbc.update("DELETE FROM clips");
        jdbc.update("DELETE FROM recipes");
    }

    // ── 목록 ─────────────────────────────────────────────────────

    /** 내 스트리머의 방송 둘에 걸친 편집본이 최근 만든 순으로 오고, 남의 스트리머 편집본은 <b>있어도 안 나온다</b>. */
    @Test
    void 목록은_볼_수_있는_스트리머의_편집본만_최근순으로_준다() throws Exception {
        long 첫째 = 편집본(내_방송);
        long 둘째 = 편집본(내_다른_방송);
        편집본(남의_방송);
        볼_수_있는_스트리머(줄(TestIds.STREAMER, "EDITOR"));

        목록("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].recipeId").value(둘째))
                .andExpect(jsonPath("$.items[0].streamId").value(내_다른_방송))
                .andExpect(jsonPath("$.items[0].status").value("editing"))
                .andExpect(jsonPath("$.items[0].latestClip").value(nullValue()))
                .andExpect(jsonPath("$.items[0].creatorId").value("4180"))
                .andExpect(jsonPath("$.items[0].recipeVersion").value(1))
                .andExpect(jsonPath("$.items[0].cut.inAtMs").value(RenderFixtures.CUT_IN))
                .andExpect(jsonPath("$.items[0].cut.outAtMs").value(RenderFixtures.CUT_OUT))
                .andExpect(jsonPath("$.items[0].broadcast.status").value("live"))
                .andExpect(jsonPath("$.items[0].broadcast.startedAt").isNotEmpty())
                .andExpect(jsonPath("$.items[0].broadcast.vodExpiresAt").value(nullValue()))
                .andExpect(jsonPath("$.items[1].recipeId").value(첫째))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));

        assertThat(AUTH.callCount()).as("자격 창구를 안 거쳤다 — 이 갈래는 판정을 안 재고 있다").isEqualTo(1);
        assertThat(AUTH.lastPath()).isEqualTo(ACCESSIBLE);
    }

    /**
     * 상태 파생 규칙 전부 — 영상 없음·주문됨·만드는 중·완성·실패, 그리고 <b>완성한 뒤 편집본을 또 고친 것</b>은 옛 영상이
     * 그대로 실리면서 편집 중이다. 파생을 SQL이 하므로 자바 쪽 표로는 못 재고 표에 심어서 잰다.
     */
    @Test
    void 상태는_편집본과_최신_영상에서_파생된다() throws Exception {
        long 영상없음 = 편집본(내_방송);
        long 주문됨 = 편집본_과_영상(내_방송, "queued");
        long 만드는중 = 편집본_과_영상(내_방송, "rendering");
        long 완성 = 편집본_과_영상(내_방송, "rendered");
        long 실패 = 편집본_과_영상(내_방송, "failed");
        long 고친것 = 편집본_과_영상(내_방송, "rendered");
        jdbc.update("UPDATE recipes SET recipe_version = 2 WHERE id = ?", 고친것);
        볼_수_있는_스트리머(줄(TestIds.STREAMER, "OWNER"));

        JsonNode items = MAPPER.readTree(본문(목록("").andExpect(status().isOk()))).get("items");
        assertThat(상태(items, 영상없음)).isEqualTo("editing");
        assertThat(상태(items, 주문됨)).isEqualTo("rendering");
        assertThat(상태(items, 만드는중)).isEqualTo("rendering");
        assertThat(상태(items, 완성)).isEqualTo("rendered");
        assertThat(상태(items, 실패)).isEqualTo("failed");
        assertThat(상태(items, 고친것)).isEqualTo("editing");

        JsonNode 고친줄 = 줄을_찾는다(items, 고친것);
        assertThat(고친줄.get("recipeVersion").asInt()).isEqualTo(2);
        assertThat(고친줄.get("latestClip").get("recipeVersion").asInt()).as("옛 영상은 그대로 실린다").isEqualTo(1);
        assertThat(고친줄.get("latestClip").get("status").asString()).isEqualTo("rendered");

        JsonNode 주문줄 = 줄을_찾는다(items, 주문됨);
        assertThat(주문줄.get("latestClip").get("progress").get("jobId").asString()).as("주문 정보가 봉투에 있다").isNotEmpty();
        assertThat(주문줄.get("latestClip").get("progress").get("percent").asInt()).isZero();
    }

    /** 같은 편집본에 영상이 여럿이면 <b>가장 최근 것</b>이 상태를 정한다 — 실패 뒤 다시 주문해 완성했으면 완성이다. */
    @Test
    void 영상이_여럿이면_가장_최근_것이_상태를_정한다() throws Exception {
        long 편집본 = 편집본_과_영상(내_방송, "failed");
        영상(내_방송, 편집본, "rendered");
        볼_수_있는_스트리머(줄(TestIds.STREAMER, "OWNER"));

        목록("")
                .andExpect(jsonPath("$.items[0].status").value("rendered"))
                .andExpect(jsonPath("$.items[0].latestClip.status").value("rendered"));
    }

    /**
     * v1 주문이 v2 주문보다 <b>뒤에</b> 끼어들어 번호가 큰 영상이 옛 판인 경우 — 주문은 편집본을 읽고 나서 트랜잭션을
     * 열므로 실제로 난다. 「번호가 가장 큰 영상」을 고르면 v2가 만드는 중인데 편집 중으로 나간다(PR #189 codex).
     */
    @Test
    void 번호가_큰_옛_판_영상이_있어도_지금_판_영상이_상태를_정한다() throws Exception {
        long 편집본 = 편집본(내_방송);
        jdbc.update("UPDATE recipes SET recipe_version = 2 WHERE id = ?", 편집본);
        long v2 = 영상_판(내_방송, 편집본, 2, "rendering");
        long 늦은_v1 = 영상_판(내_방송, 편집본, 1, "rendered");
        assertThat(늦은_v1).isGreaterThan(v2);
        볼_수_있는_스트리머(줄(TestIds.STREAMER, "OWNER"));

        목록("")
                .andExpect(jsonPath("$.items[0].status").value("rendering"))
                .andExpect(jsonPath("$.items[0].latestClip.id").value(v2))
                .andExpect(jsonPath("$.items[0].latestClip.recipeVersion").value(2));
        목록("?status=rendering").andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void 상태로_거른다() throws Exception {
        편집본(내_방송);
        long 완성 = 편집본_과_영상(내_방송, "rendered");
        long 실패 = 편집본_과_영상(내_방송, "failed");
        볼_수_있는_스트리머(줄(TestIds.STREAMER, "OWNER"));

        목록("?status=rendered")
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].recipeId").value(완성));
        목록("?status=failed")
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].recipeId").value(실패));
        목록("?status=editing").andExpect(jsonPath("$.items.length()").value(1));
        목록("?status=rendering").andExpect(jsonPath("$.items.length()").value(0));
        // 빈 값은 「안 줬다」다 — cursor와 같은 규칙.
        목록("?status=").andExpect(jsonPath("$.items.length()").value(3));
    }

    /** 업로드 상태는 POK-220 뒤의 값이다 — 지금은 모르는 값이라 400이고, 대문자도 400이다. 형식 오류에 auth 왕복을 안 태운다. */
    @Test
    void 모르는_상태는_400이다() throws Exception {
        목록("?status=uploaded").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.field").value("status"));
        목록("?status=RENDERED").andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("status"));
        목록("?limit=0").andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("limit"));
        목록("?cursor=!!!").andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("cursor"));
        // 방송 목록의 표시를 여기 넣으면 종류가 달라 400이다 — 숫자 하나로 읽혀 엉뚱한 자리부터 나오면 안 된다.
        목록("?cursor=" + CursorCodec.encode(CursorCodec.Kind.BROADCAST, 1))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("cursor"));
        assertThat(AUTH.callCount()).as("형식 오류에 auth 왕복을 태우면 안 된다").isZero();
    }

    /** 세 장으로 나눠 받아도 빠지거나 겹치는 줄이 없고, 마지막 장의 표시는 비어 있다. 거르기와 이어받기가 같이 걸린다. */
    @Test
    void 이어받기로_전부_받는다() throws Exception {
        List<Long> 기대 = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            기대.add(0, 편집본_과_영상(내_방송, "rendered"));
            편집본(내_방송); // 거르기에 걸려 안 나올 줄 — 이어받기 계산이 거른 줄을 세지 않아야 한다
        }
        볼_수_있는_스트리머(줄(TestIds.STREAMER, "OWNER"));

        List<Long> 받은 = new ArrayList<>();
        String cursor = "";
        int 장 = 0;
        do {
            JsonNode page = MAPPER.readTree(본문(목록("?status=rendered&limit=2&cursor=" + cursor).andExpect(status().isOk())));
            for (JsonNode item : page.get("items")) {
                받은.add(item.get("recipeId").asLong());
            }
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asString();
            // 이어받기가 앞으로 안 나가면 표시가 영영 안 비어 이 루프가 안 끝난다 — 빨간불로 끝낸다.
            assertThat(++장).as("이어받기가 같은 자리를 맴돈다").isLessThanOrEqualTo(5);
        } while (cursor != null);

        assertThat(받은).isEqualTo(기대);
        assertThat(장).isEqualTo(3);
    }

    @Test
    void auth에_못_물으면_503이고_빈_명부면_빈_목록이다() throws Exception {
        편집본(내_방송);
        AUTH.respondWith(ACCESSIBLE, 500, "");
        목록("").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("authorization_unavailable"));

        볼_수_있는_스트리머();
        목록("").andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
    }

    // ── 상세 ─────────────────────────────────────────────────────

    /** 상세는 목록 줄의 칸 전부에 편집본 본문(계약6)이 얹힌 것이다 — 공통 칸은 JSON 트리로 <b>같아야</b> 한다. */
    @Test
    void 상세는_목록_줄과_같은_칸에_편집본_본문을_얹는다() throws Exception {
        long 편집본 = 편집본_과_영상(내_방송, "rendered");
        볼_수_있는_스트리머(줄(TestIds.STREAMER, "OWNER"));
        JsonNode 줄 = MAPPER.readTree(본문(목록(""))).get("items").get(0);

        볼_수_있다("OWNER");
        JsonNode 상세 = MAPPER.readTree(본문(하나(편집본).andExpect(status().isOk())
                .andExpect(jsonPath("$.recipe.schemaVersion").value(1))
                .andExpect(jsonPath("$.recipe.outputs[0].outputId").value("o1"))
                .andExpect(jsonPath("$.recipe.audio.tracks[0].trackId").value(1))
                .andExpect(jsonPath("$.recipe.subtitles.mode").value("BURN_AND_CC"))
                .andExpect(jsonPath("$.latestClip.status").value("rendered"))));
        assertThat(AUTH.lastPath()).as("상세는 방송 하나의 자격을 묻는다").isEqualTo(RESOLVE);

        ObjectNode 상세의_공통칸 = ((ObjectNode) 상세).deepCopy();
        상세의_공통칸.remove("recipe");
        assertThat(상세의_공통칸).isEqualTo(줄);
    }

    /** 없는 번호와 남의 방송 편집본이 <b>같은 본문</b>이고 둘 다 25ms 바닥을 탄다 — 시간으로 존재를 못 가른다. */
    @Test
    void 상세의_거절_둘은_같은_본문_같은_바닥이다() throws Exception {
        long 남의것 = 편집본(남의_방송);
        볼_수_없다();

        long 시작 = System.nanoTime();
        String 남의_응답 = 본문(하나(남의것).andExpect(status().isNotFound()));
        long 남의_소요 = (System.nanoTime() - 시작) / 1_000_000;

        시작 = System.nanoTime();
        String 없는_응답 = 본문(하나(999_999).andExpect(status().isNotFound()));
        long 없는_소요 = (System.nanoTime() - 시작) / 1_000_000;

        assertThat(남의_응답).isEqualTo(없는_응답).isEqualTo("{\"error\":\"recipe_not_found\"}");
        assertThat(남의_소요).isGreaterThanOrEqualTo(바닥_ms);
        assertThat(없는_소요).isGreaterThanOrEqualTo(바닥_ms);
    }

    @Test
    void 상세도_auth에_못_물으면_503이다() throws Exception {
        long 편집본 = 편집본(내_방송);
        AUTH.respondWith(RESOLVE, 500, "");
        하나(편집본).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("authorization_unavailable"));
    }

    // ── 도우미 ──────────────────────────────────────────────────

    private long 편집본(String streamId) {
        return RenderFixtures.편집본을_넣는다(jdbc, streamId, RenderFixtures.CUT_IN, RenderFixtures.CUT_OUT);
    }

    private long 편집본_과_영상(String streamId, String clipStatus) {
        long recipeId = 편집본(streamId);
        영상(streamId, recipeId, clipStatus);
        return recipeId;
    }

    /** 주문 한 벌(영상 + 주문 기록)을 심고 영상 상태만 바꾼다. */
    private void 영상(String streamId, long recipeId, String clipStatus) {
        영상_판(streamId, recipeId, 1, clipStatus);
    }

    /** @return 영상 번호 */
    private long 영상_판(String streamId, long recipeId, int recipeVersion, String clipStatus) {
        long[] clipId = new long[1];
        RenderFixtures.주문을_넣는다(jdbc, streamId, recipeId, clipId);
        jdbc.update("UPDATE clips SET status = ?, recipe_version = ? WHERE id = ?", clipStatus, recipeVersion, clipId[0]);
        return clipId[0];
    }

    private static String 상태(JsonNode items, long recipeId) {
        return 줄을_찾는다(items, recipeId).get("status").asString();
    }

    private static JsonNode 줄을_찾는다(JsonNode items, long recipeId) {
        for (JsonNode item : items) {
            if (item.get("recipeId").asLong() == recipeId) {
                return item;
            }
        }
        throw new AssertionError("목록에 없다: " + recipeId);
    }

    private void 볼_수_있는_스트리머(String... 줄들) {
        AUTH.respondWith(ACCESSIBLE, 200, "{\"streamers\":[" + String.join(",", 줄들) + "]}");
    }

    private static String 줄(String streamerUserId, String relation) {
        return "{\"streamerUserId\":%s,\"relation\":\"%s\"}".formatted(streamerUserId, relation);
    }

    private void 볼_수_있다(String relation) {
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"%s\"}".formatted(relation));
    }

    private void 볼_수_없다() {
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"NONE\"}");
    }

    private ResultActions 목록(String 질의) throws Exception {
        return mvc.perform(get("/api/clip/library" + 질의).header("Authorization", "Bearer " + TestTokens.access(요청자)));
    }

    private ResultActions 하나(long recipeId) throws Exception {
        return mvc.perform(get("/api/clip/library/" + recipeId).header("Authorization", "Bearer " + TestTokens.access(요청자)));
    }

    private static String 본문(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString();
    }
}
