package com.pokeclip.clip.broadcast.api;

import ch.qos.logback.classic.Level;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.clip.support.TestTokens;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 편집자가 홈 화면을 열 때 처음 부르는 문. 재는 것은 넷이다 —
 * <b>자격 창구를 실제로 거치는가</b> · 상태 코드 · 응답 한 줄의 모양 · 이어받기 왕복.
 *
 * <p><b>가짜 자격 창구를 Mockito로 갈아 끼우지 않는다.</b> 그렇게 하면 이 클래스가
 * 「판정이 붙기 전과 정확히 같은 것」을 재게 된다(스킬 문항 2). 진짜로 듣는 소켓
 * ({@link IntegrationTestSupport#AUTH})으로 가고, <b>답을 안 걸어 둔 시험은 503을 받는다</b> —
 * 그래서 「auth를 안 물어도 되는 갈래」와 「물어야 하는 갈래」가 저절로 갈린다.
 *
 * <p>🔴 <b>요청자 번호가 {@link TestIds#STREAMER}와 다르다.</b> 같으면 auth에 안 묻고
 * 문자열만 비교하는 구현에서도 초록이 된다.
 */
@AutoConfigureMockMvc
class BroadcastListControllerTest extends IntegrationTestSupport {

    private static final String ACCESSIBLE = "/internal/editor-delegations/accessible";

    /** JWT {@code sub}. 방송 픽스처의 스트리머 번호와 <b>다른 사람</b>이다. */
    private static final String 요청자 = "4174";

    /** 내가 편집자로 맡은 남의 스트리머. */
    private static final String 남의_스트리머 = "3";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Instant 시작_시각 = Instant.parse("2026-08-25T00:00:00Z");
    private static final Duration 육십일 = Duration.ofDays(60);

    private final MockMvc mvc;
    private final JdbcTemplate jdbc;

    BroadcastListControllerTest(MockMvc mvc, JdbcTemplate jdbc) {
        this.mvc = mvc;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 앞_테스트의_흔적을_지운다() {
        방송과_카드를_비운다(jdbc);
    }

    // ── 200과 그 모양 ────────────────────────────────────────────

    /**
     * 최신이 위다. 그리고 <b>auth를 정확히 한 번 물었다</b>는 것을 같이 잰다 —
     * 0이면 판정 경로를 안 탄 것이고, 그때 이 갈래는 아무것도 안 재면서 초록이 된다.
     */
    @Test
    void 내_방송이_최신순으로_나온다() throws Exception {
        볼_수_있는_스트리머(줄(TestIds.STREAMER, "OWNER"));
        방송을_넣는다("s-1", TestIds.STREAMER, "live");
        방송을_넣는다("s-2", TestIds.STREAMER, "live");

        목록("?state=live")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.broadcasts.length()").value(2))
                .andExpect(jsonPath("$.broadcasts[0].streamId").value("s-2"))
                .andExpect(jsonPath("$.broadcasts[1].streamId").value("s-1"));

        assertThat(AUTH.callCount()).as("자격 창구를 안 거쳤다 — 이 갈래는 판정을 안 재고 있다").isEqualTo(1);
        assertThat(AUTH.lastPath()).isEqualTo(ACCESSIBLE);
    }

    /**
     * 돈 내는 쪽(스트리머)과 매일 쓰는 쪽(전담 편집자)이 다르다 — <b>맡은 방송이 안 나오면
     * 편집자에게는 이 제품이 빈 화면이다.</b> 관계가 줄마다 실려야 화면이 「내 방송」과
     * 「맡은 방송」을 가른다(PRD 성공 기준).
     */
    @Test
    void 편집자로_맡은_방송도_같이_나오고_관계가_실린다() throws Exception {
        볼_수_있는_스트리머(줄(TestIds.STREAMER, "OWNER"), 줄(남의_스트리머, "EDITOR"));
        방송을_넣는다("s-mine", TestIds.STREAMER, "live");
        방송을_넣는다("s-delegated", 남의_스트리머, "live");

        목록("?state=live")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.broadcasts.length()").value(2))
                .andExpect(jsonPath("$.broadcasts[0].streamId").value("s-delegated"))
                .andExpect(jsonPath("$.broadcasts[0].relation").value("EDITOR"))
                .andExpect(jsonPath("$.broadcasts[1].streamId").value("s-mine"))
                .andExpect(jsonPath("$.broadcasts[1].relation").value("OWNER"));
    }

    /**
     * 🔴 <b>남의 방송을 실제로 심어 두고 잰다.</b> 안 심으면 「비었다」가 조회가 통째로
     * 망가져도 참이다. auth가 본인만 돌려주는 상황을 만들어, 거르는 쪽이 <b>목록 창구의 답</b>임을
     * 못 박는다.
     */
    @Test
    void 관계없는_사람은_빈_목록을_받는다() throws Exception {
        볼_수_있는_스트리머(줄(요청자, "OWNER"));
        방송을_넣는다("s-others", TestIds.STREAMER, "live");

        목록("?state=live")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.broadcasts.length()").value(0))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
    }

    /**
     * auth가 빈 목록을 주는 경우. <b>이 갈래는 「빈 목록이면 조회를 건너뛴다」는 방어가
     * 있으나 없으나 초록이다</b> — 빈 {@code IN} 목록도 예외 없이 0행이기 때문이다(계획 검증 실측).
     * 여기서 재는 것은 <b>죽지 않는다는 것</b>뿐이고, 건너뛰기 자체는 성능·의도의 문제다.
     * 주입으로 확인했고 진행 기록에 그렇게 적었다.
     */
    @Test
    void auth가_빈_목록을_주면_200에_빈_목록이다() throws Exception {
        AUTH.respondWith(ACCESSIBLE, 200, "{\"streamers\":[]}");
        방송을_넣는다("s-others", TestIds.STREAMER, "live");

        목록("?state=live")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.broadcasts.length()").value(0));
    }

    // ── state — 🔴 계약이 소문자다 ────────────────────────────────

    /**
     * 🔴 <b>열거형을 그대로 바인딩하면 {@code state=live}가 400이다</b>(계획 검증 실측:
     * {@code MethodArgumentTypeMismatchException}). PRD·README·2번에게 줄 계약이 전부
     * 소문자인데, 시험을 {@code state=LIVE}로 쓰면 <b>초록인 채 계약이 대문자로 바뀐다.</b>
     * 그래서 대문자가 거절되는 것까지 같은 갈래에서 잰다.
     */
    @Test
    void state는_소문자_live와_past를_받는다() throws Exception {
        볼_수_있는_스트리머(줄(TestIds.STREAMER, "OWNER"));
        방송을_넣는다("s-live", TestIds.STREAMER, "live");
        방송을_넣는다("s-ended", TestIds.STREAMER, "ended");

        목록("?state=live")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.broadcasts.length()").value(1))
                .andExpect(jsonPath("$.broadcasts[0].streamId").value("s-live"))
                .andExpect(jsonPath("$.broadcasts[0].status").value("live"));

        목록("?state=past")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.broadcasts.length()").value(1))
                .andExpect(jsonPath("$.broadcasts[0].streamId").value("s-ended"))
                .andExpect(jsonPath("$.broadcasts[0].status").value("ended"));

        목록("?state=LIVE")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.field").value("state"));
    }

    @Test
    void state가_없으면_400이다() throws Exception {
        목록("")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.field").value("state"));

        assertThat(AUTH.callCount()).as("형식 오류에 auth 왕복을 태우면 안 된다").isZero();
    }

    @Test
    void state가_이상하면_400이다() throws Exception {
        목록("?state=archived")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.field").value("state"));
    }

    // ── 개수 ────────────────────────────────────────────────────

    /**
     * 🔴 <b>상한보다 많이 심어야 잰다.</b> 100장만 심으면 상한이 통째로 사라져도 100장이 나온다.
     */
    @Test
    void 상한을_넘는_개수는_상한으로_깎인다() throws Exception {
        볼_수_있는_스트리머(줄(TestIds.STREAMER, "OWNER"));
        for (int i = 1; i <= 101; i++) {
            방송을_넣는다("s-%03d".formatted(i), TestIds.STREAMER, "live");
        }

        목록("?state=live&limit=1000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.broadcasts.length()").value(100))
                .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.notNullValue()));
    }

    /**
     * m1 실측 — 서비스가 {@code IllegalArgumentException}을 던지면 전역 조언이 <b>일부러</b>
     * 안 잡으므로 {@code ServletException}으로 빠져 <b>500</b>이 된다. 400이어야 한다.
     */
    @Test
    void 개수가_0이거나_음수면_400이다() throws Exception {
        for (String 값 : new String[]{"0", "-1"}) {
            목록("?state=live&limit=" + 값)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_request"))
                    .andExpect(jsonPath("$.field").value("limit"));
        }

        assertThat(AUTH.callCount()).as("형식 오류에 auth 왕복을 태우면 안 된다").isZero();
    }

    /** 숫자가 아닌 값은 컨트롤러 메서드에 들어오기 전에 끝난다 — 그래도 봉투가 같아야 한다. */
    @Test
    void 숫자가_아닌_개수도_같은_400_봉투다() throws Exception {
        String 형식오류 = 본문(목록("?state=live&limit=abc").andExpect(status().isBadRequest()));
        String 값오류 = 본문(목록("?state=live&limit=0").andExpect(status().isBadRequest()));

        assertThat(형식오류).as("정본 봉투가 비어 있으면 아래 비교가 아무것도 안 잰다")
                .contains("invalid_request").contains("limit");
        assertThat(형식오류).isEqualTo(값오류);
    }

    // ── 이어받기 ────────────────────────────────────────────────

    /** 표시를 받아 그대로 되돌려 넣는 <b>왕복</b>을 잰다 — 감싸기와 풀기가 한 짝인지가 여기서 닫힌다. */
    @Test
    void 이어받으면_그_줄_다음부터_나온다() throws Exception {
        볼_수_있는_스트리머(줄(TestIds.STREAMER, "OWNER"));
        방송을_넣는다("s-1", TestIds.STREAMER, "live");
        방송을_넣는다("s-2", TestIds.STREAMER, "live");
        방송을_넣는다("s-3", TestIds.STREAMER, "live");

        String 첫장 = 본문(목록("?state=live&limit=2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.broadcasts[0].streamId").value("s-3"))
                .andExpect(jsonPath("$.broadcasts[1].streamId").value("s-2")));
        String 다음_표시 = (String) 맵(첫장).get("nextCursor");
        assertThat(다음_표시).as("표시가 없으면 아래 요청이 첫 장을 또 받아 아무것도 안 잰다").isNotNull();

        목록("?state=live&limit=2&cursor=" + 다음_표시)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.broadcasts.length()").value(1))
                .andExpect(jsonPath("$.broadcasts[0].streamId").value("s-1"))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
    }

    @Test
    void 마지막_장에는_다음_표시가_없다() throws Exception {
        볼_수_있는_스트리머(줄(TestIds.STREAMER, "OWNER"));
        방송을_넣는다("s-1", TestIds.STREAMER, "live");
        방송을_넣는다("s-2", TestIds.STREAMER, "live");

        목록("?state=live&limit=20")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.broadcasts.length()").value(2))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
    }

    /**
     * 🔴 <b>줄 수가 상한과 <i>정확히</i> 같은 경계.</b> 「다음 장이 있나」를 상한 하나를 더
     * 받아 판정하므로 여기서 {@code >}를 {@code >=}로 한 글자 바꾸면 <b>마지막 장에
     * 다음 표시가 붙는다</b> — 화면은 있지도 않은 다음 장을 한 번 더 받으러 간다.
     *
     * <p>이 갈래를 넣기 전에는 그 결함을 <b>19건이 전부 통과했다</b>(주입 s4). 위
     * {@code 마지막_장에는_다음_표시가_없다}는 2장을 20장 상한으로 받아 경계에서 멀다.
     */
    @Test
    void 줄_수가_상한과_같으면_다음_표시가_없다() throws Exception {
        볼_수_있는_스트리머(줄(TestIds.STREAMER, "OWNER"));
        방송을_넣는다("s-1", TestIds.STREAMER, "live");
        방송을_넣는다("s-2", TestIds.STREAMER, "live");

        목록("?state=live&limit=2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.broadcasts.length()").value(2))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
    }

    @Test
    void 이어받기_표시가_깨졌으면_400이다() throws Exception {
        목록("?state=live&cursor=!!!not-base64!!!")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.field").value("cursor"));

        assertThat(AUTH.callCount()).as("형식 오류에 auth 왕복을 태우면 안 된다").isZero();
    }

    /**
     * 🔴 <b>「칸이 비어 있다」와 「칸을 안 줬다」를 같게 본다.</b> 스프링은 {@code ?cursor=}를
     * {@code null}이 아니라 <b>{@code ""}</b>로 넘긴다({@code defaultValue}가 있어야 대체한다) —
     * 고치기 전에는 그 빈 문자열이 표시를 푸는 자리로 그대로 가서 <b>첫 장이 400</b>이었다.
     * 웹이 {@code cursor=${표시 ?? ''}} 같은 흔한 모양으로 쓰면 홈 화면이 안 열린다.
     *
     * <p><b>{@code limit}은 이미 이렇게 군다</b> — {@code Integer}라 변환기가 빈 문자열을
     * {@code null}로 바꿔 기본값을 탄다. 같은 응답의 두 칸이 다르게 굴던 것을 맞춘 것이다.
     *
     * <p>🔴 이것은 {@code ListLimit}의 「0을 조용히 봐 주지 않는다」와 <b>충돌하지 않는다</b> —
     * {@code 0}은 <b>값을 준 것</b>이고 빈 문자열은 <b>값이 없는 것</b>이다. 그래서
     * {@code ?limit=0}은 지금도 400이다(바로 위 갈래가 그것을 잰다).
     */
    @Test
    void 빈_이어받기_표시는_안_준_것과_같다() throws Exception {
        볼_수_있는_스트리머(줄(TestIds.STREAMER, "OWNER"));
        방송을_넣는다("s-1", TestIds.STREAMER, "live");
        방송을_넣는다("s-2", TestIds.STREAMER, "live");

        // 앞엣것은 웹이 `cursor=${표시 ?? ''}`로 보내는 모양 그대로다.
        // 뒤엣것은 공백만 있는 값 — 🔴 URL에 `%20`으로 쓰면 MockMvc가 한 번 더 감싸 리터럴
        // "%20"이 되어(빈 값이 아니라 진짜 깨진 표시다) 400이 맞다. 그래서 칸 값으로 싣는다.
        for (ResultActions 응답 : List.of(
                목록("?state=live&limit=2&cursor="),
                mvc.perform(get("/api/clip/broadcasts")
                        .param("state", "live").param("limit", "2").param("cursor", " ")
                        .header("Authorization", "Bearer " + TestTokens.access(요청자))))) {
            응답.andExpect(status().isOk())
                    .andExpect(jsonPath("$.broadcasts.length()").value(2))
                    .andExpect(jsonPath("$.broadcasts[0].streamId").value("s-2"))
                    .andExpect(jsonPath("$.broadcasts[1].streamId").value("s-1"));
        }
    }

    // ── 거절 ────────────────────────────────────────────────────

    /**
     * 🔴 <b>빈 목록이 아니다.</b> 빈 목록을 주면 화면이 「방송이 없다」고 단정하고, auth가
     * 살아난 뒤에도 편집자는 다시 시도하지 않는다. 그래서 본문에 {@code broadcasts}라는
     * 칸이 <b>아예 없어야</b> 한다 — {@code jsonPath().doesNotExist()}는 값이 null인 키도
     * 통과하므로 본문 문자열로 잰다.
     */
    @Test
    void auth를_못_물으면_503이고_빈_목록이_아니다() throws Exception {
        AUTH.respondWith(ACCESSIBLE, 500, "");
        방송을_넣는다("s-1", TestIds.STREAMER, "live");

        String 본문 = 본문(목록("?state=live")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("authorization_unavailable")));

        assertThat(본문).doesNotContain("broadcasts").doesNotContain("nextCursor");
    }

    @Test
    void 토큰이_없으면_401이다() throws Exception {
        mvc.perform(get("/api/clip/broadcasts?state=live"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 우리가 발급·검증한 토큰인데 {@code sub}가 숫자가 아니면 auth에 물어볼 수가 없다.
     * <b>「볼 수 없다」로 접고 ERROR를 남긴다</b> — {@code BroadcastAccessGuard}·
     * {@code SegmentQueryService}와 같은 판정이다(쌍둥이 셋이 같은 모양이어야 한다).
     *
     * <p>ERROR인 이유는 이것이 <b>조용한 장애</b>이기 때문이다 — 응답으로는 영영 구분이 안 된다.
     * <b>값 자체는 안 찍는다</b>(로그 위조 차단).
     */
    @Test
    void 주체가_숫자가_아니면_404이고_ERROR가_남는다() throws Exception {
        String 숫자가_아닌_주체 = "subject-NOT-A-NUMBER-4174";

        try (LogCaptor 로그 = new LogCaptor()) {
            mvc.perform(get("/api/clip/broadcasts?state=live")
                            .header("Authorization", "Bearer " + TestTokens.access(숫자가_아닌_주체)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("broadcast_not_found"));

            assertThat(로그.levelOf("clip.list.identity_not_numeric")).isEqualTo(Level.ERROR);
            assertThat(로그.messages()).anyMatch(m -> m.contains("reason=subject_not_numeric"));
            assertThat(로그.messages()).as("값 자체는 안 찍는다").noneMatch(m -> m.contains(숫자가_아닌_주체));
        }

        assertThat(AUTH.callCount()).as("숫자로 못 바꾼 번호로 auth를 두드리면 안 된다").isZero();
    }

    // ── 응답 한 줄의 모양 ────────────────────────────────────────

    /**
     * 🔴 <b>감추거나 지어내지 않는다</b>(PRD 성공 기준). 종료 선도착 placeholder는 시작 시각이
     * 비어 있고, 화면은 그것을 「시작 시각 미상」으로 그린다.
     *
     * <p>{@code doesNotExist()}로 재면 안 된다 — <b>값이 null인 키도 통과</b>해서 키가 아예
     * 빠진 것을 못 잡는다(POK-121 실측). 그래서 본문을 맵으로 읽어 <b>키가 있는 것</b>과
     * <b>값이 null인 것</b>을 따로 단언한다.
     */
    @Test
    void 시작_시각이_빈_방송도_나오고_그_칸이_빈_채로_나간다() throws Exception {
        볼_수_있는_스트리머(줄(TestIds.STREAMER, "OWNER"));
        방송을_넣는다("s-placeholder", TestIds.STREAMER, "ended", null, 시작_시각, 시작_시각.plus(육십일));

        String 본문 = 본문(목록("?state=past")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.broadcasts.length()").value(1))
                .andExpect(jsonPath("$.broadcasts[0].startedAt").value(nullValue())));

        @SuppressWarnings("unchecked")
        Map<String, Object> 한_줄 = ((java.util.List<Map<String, Object>>) 맵(본문).get("broadcasts")).get(0);
        assertThat(한_줄).containsKey("startedAt");
        assertThat(한_줄.get("startedAt")).isNull();
    }

    /**
     * 영상은 못 봐도 방송 기록은 남는다 — 빼면 편집자에게는 방송이 사라진 것으로 보인다.
     * 화면이 「보관 만료」를 그릴 재료(보관 기한)가 응답에 실려 있어야 한다.
     */
    @Test
    void 보관_기한이_지난_방송도_나오고_기한이_실려_있다() throws Exception {
        볼_수_있는_스트리머(줄(TestIds.STREAMER, "OWNER"));
        Instant 지난_기한 = Instant.parse("2026-01-01T00:00:00Z");
        방송을_넣는다("s-expired", TestIds.STREAMER, "ended", 시작_시각, 지난_기한.minus(육십일), 지난_기한);

        목록("?state=past")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.broadcasts.length()").value(1))
                .andExpect(jsonPath("$.broadcasts[0].streamId").value("s-expired"))
                .andExpect(jsonPath("$.broadcasts[0].vodExpiresAt").value(지난_기한.toString()))
                .andExpect(jsonPath("$.broadcasts[0].endedAt").value(지난_기한.minus(육십일).toString()));
    }

    /**
     * 협상에 실패하면 상태 코드가 <b>500으로 둔갑한다</b> — POK-118이 이 자리에서 실제로 덴
     * 사고다. 세 갈래를 한 번에 보는 것은 {@code json} 하나가 셋의 공통 출구여서다.
     */
    @Test
    void 오류_응답은_Accept가_JSON이_아니어도_JSON으로_나간다() throws Exception {
        오류_협상("?state=archived").andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("invalid_request"));
        오류_협상("?state=live&cursor=!!!").andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.field").value("cursor"));

        AUTH.respondWith(ACCESSIBLE, 500, "");
        오류_협상("?state=live").andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("authorization_unavailable"));
    }

    // ── 도우미 ──────────────────────────────────────────────────

    private void 볼_수_있는_스트리머(String... 줄들) {
        AUTH.respondWith(ACCESSIBLE, 200, "{\"streamers\":[" + String.join(",", 줄들) + "]}");
    }

    private static String 줄(String streamerUserId, String relation) {
        return "{\"streamerUserId\":%s,\"relation\":\"%s\"}".formatted(streamerUserId, relation);
    }

    private ResultActions 목록(String 질의) throws Exception {
        return mvc.perform(get("/api/clip/broadcasts" + 질의)
                .header("Authorization", "Bearer " + TestTokens.access(요청자)));
    }

    /** JSON을 <b>안</b> 받겠다는 Accept를 실어 보낸다(브라우저 EventSource가 보내는 값). */
    private ResultActions 오류_협상(String 질의) throws Exception {
        return mvc.perform(get("/api/clip/broadcasts" + 질의)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .header("Authorization", "Bearer " + TestTokens.access(요청자)));
    }

    private static String 본문(ResultActions 응답) throws Exception {
        return 응답.andReturn().getResponse().getContentAsString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> 맵(String 본문) {
        return MAPPER.readValue(본문, Map.class);
    }

    private void 방송을_넣는다(String streamId, String streamerId, String status) {
        방송을_넣는다(streamId, streamerId, status, 시작_시각, null, null);
    }

    private void 방송을_넣는다(String streamId, String streamerId, String status,
                        Instant startedAt, Instant endedAt, Instant vodExpiresAt) {
        jdbc.update("""
                        INSERT INTO broadcasts
                            (stream_id, streamer_id, status, started_at, ended_at, last_sequence, vod_expires_at)
                        VALUES (?, ?, ?, ?, ?, 1, ?)""",
                streamId, streamerId, status,
                startedAt == null ? null : OffsetDateTime.ofInstant(startedAt, ZoneOffset.UTC),
                endedAt == null ? null : OffsetDateTime.ofInstant(endedAt, ZoneOffset.UTC),
                vodExpiresAt == null ? null : OffsetDateTime.ofInstant(vodExpiresAt, ZoneOffset.UTC));
    }
}
