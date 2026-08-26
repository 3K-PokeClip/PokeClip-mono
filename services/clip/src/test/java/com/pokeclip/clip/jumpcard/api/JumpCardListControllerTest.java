package com.pokeclip.clip.jumpcard.api;

import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.NotFoundFloor;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.clip.support.TestTokens;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 방송 화면이 열릴 때 카드를 받아 가는 문. 재는 것은 넷이다 —
 * <b>자격 창구를 실제로 거치는가</b> · 상태 코드 · 이어받기 왕복 · <b>거절 둘이 시간으로 안 갈리는가</b>.
 *
 * <p><b>가짜 자격 창구를 Mockito로 갈아 끼우지 않는다.</b> 그렇게 하면 이 클래스가 「판정이 붙기
 * 전과 정확히 같은 것」을 재게 된다(스킬 문항 2). 진짜로 듣는 소켓({@link IntegrationTestSupport#AUTH})으로
 * 가고, <b>답을 안 걸어 둔 시험은 503을 받는다</b>.
 *
 * <p>🔴 <b>요청자 번호가 {@link TestIds#STREAMER}와 다르다.</b> 같으면 auth에 안 묻고 문자열만
 * 비교하는 구현에서도 초록이 된다.
 *
 * <p>카드 한 장의 <b>모양</b>이 통로로 오는 것과 같은지는 여기서 안 잰다 — 그것은 진짜 소켓이
 * 필요해서 {@code JumpCardListShapeTest}가 맡는다.
 */
@AutoConfigureMockMvc
class JumpCardListControllerTest extends IntegrationTestSupport {

    private static final String RESOLVE = "/internal/editor-delegations/resolve";

    /** JWT {@code sub}. 방송 픽스처의 스트리머 번호와 <b>다른 사람</b>이다. */
    private static final String 요청자 = "4178";

    private static final String 내_방송 = "s-cards";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Instant 시작_시각 = Instant.parse("2026-08-25T00:00:00Z");

    /** 404가 이보다 빨리 나가면 안 된다. 정본은 {@link NotFoundFloor#FLOOR} — 여기서 베끼지 않는다. */
    private static final long 바닥_ms = NotFoundFloor.FLOOR.toMillis();

    /**
     * 가짜 자격 창구에 일부러 심는 지연. <b>바닥의 절반</b>이라 바닥이 덮기로 한 범위 안이다 —
     * 바닥을 줄이는 날 이 값도 같이 줄어 「덮이는 범위 안」이라는 전제가 유지된다.
     */
    private static final Duration 느린_창구 = NotFoundFloor.FLOOR.dividedBy(2);

    /**
     * 차이를 잴 때 한 갈래를 몇 번 두드리나. <b>최솟값을 쓰므로 표본이 많을수록 참값에 붙는다</b> —
     * 지연 잡음은 한쪽(느린 쪽)으로만 붙기 때문이다.
     *
     * <p>🔴 <b>짐작이 아니라 실측으로 고른 값이다.</b> 세그먼트 문에서 같은 그물이 표본 3으로
     * 흔들렸고(20회에 3회 빨강), 표본 수를 바꿔 가며 15회씩 재서 차이의 최댓값이
     * <b>3 → 6.723 · 6 → 8.563 · 9 → 4.101 · 15 → 2.763</b>(ms, 문턱 6.0)이었다.
     * <b>6이 3보다 나빴다</b> — 조금 늘리는 것으로는 안 된다. 그래서 새 문에도 15로 시작한다
     * ({@code SegmentControllerTest.표본_수}와 같은 값이고 근거도 같다).
     */
    private static final int 표본_수 = 15;

    private final MockMvc mvc;
    private final JdbcTemplate jdbc;
    private final EntityManagerFactory emf;

    JumpCardListControllerTest(MockMvc mvc, JdbcTemplate jdbc, EntityManagerFactory emf) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.emf = emf;
    }

    @BeforeEach
    void 앞_테스트의_흔적을_지운다() {
        방송과_카드를_비운다(jdbc);
        방송을_넣는다(내_방송);
    }

    // ── 200과 그 모양 ────────────────────────────────────────────

    /**
     * 영상 앞에서 뒤로. 그리고 <b>auth를 정확히 한 번 물었다</b>는 것을 같이 잰다 —
     * 0이면 판정 경로를 안 탄 것이고, 그때 이 갈래는 아무것도 안 재면서 초록이 된다.
     */
    @Test
    void 카드가_방송_시간_순으로_나온다() throws Exception {
        볼_수_있다("OWNER");
        카드를_넣는다(내_방송, 3000, "auto");
        카드를_넣는다(내_방송, 1000, "auto");

        목록("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cards.length()").value(2))
                .andExpect(jsonPath("$.cards[0].streamTimestampMs").value(1000))
                .andExpect(jsonPath("$.cards[1].streamTimestampMs").value(3000))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));

        assertThat(AUTH.callCount()).as("자격 창구를 안 거쳤다 — 이 갈래는 판정을 안 재고 있다").isEqualTo(1);
        assertThat(AUTH.lastPath()).isEqualTo(RESOLVE);
    }

    /**
     * 돈 내는 쪽(스트리머)과 매일 쓰는 쪽(전담 편집자)이 다르다 — <b>편집자가 카드를 못 받으면
     * 이 제품이 빈 화면이다.</b> {@code OWNER}와 {@code EDITOR}를 가르지 않는다는 결정
     * (PRD)이 실제로 코드에 있는지를 재는 자리다.
     */
    @Test
    void 편집자도_받는다() throws Exception {
        볼_수_있다("EDITOR");
        카드를_넣는다(내_방송, 1000, "auto");

        목록("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cards.length()").value(1));
    }

    /** 자동으로 잡힌 것과 핫키로 잡힌 것이 화면에서 갈려야 한다(PRD 성공 기준). */
    @Test
    void 카드마다_출처가_실려_있다() throws Exception {
        볼_수_있다("OWNER");
        카드를_넣는다(내_방송, 1000, "auto");
        카드를_넣는다(내_방송, 2000, "hotkey");

        목록("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cards[0].source").value("auto"))
                .andExpect(jsonPath("$.cards[1].source").value("hotkey"));
    }

    /** 숨김은 표시 여부이지 삭제가 아니다 — 두 방향을 같은 갈래에서 재야 {@code includeHidden}이 갈린다. */
    @Test
    void 숨긴_카드는_기본으로_빠지고_달라고_하면_나온다() throws Exception {
        볼_수_있다("OWNER");
        카드를_넣는다(내_방송, 1000, "auto");
        숨긴다(카드를_넣는다(내_방송, 2000, "auto"));

        목록("?includeHidden=false")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cards.length()").value(1))
                .andExpect(jsonPath("$.cards[0].streamTimestampMs").value(1000));

        목록("?includeHidden=true")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cards.length()").value(2))
                .andExpect(jsonPath("$.cards[1].hidden").value(true));

        목록("")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cards.length()").value(1));
    }

    // ── 개수와 이어받기 ─────────────────────────────────────────

    /** 표시를 받아 그대로 되돌려 넣는 <b>왕복</b>을 잰다 — 감싸기와 풀기가 한 짝인지가 여기서 닫힌다. */
    @Test
    void 이어받으면_그_줄_다음부터_나온다() throws Exception {
        볼_수_있다("OWNER");
        카드를_넣는다(내_방송, 1000, "auto");
        카드를_넣는다(내_방송, 2000, "auto");
        카드를_넣는다(내_방송, 3000, "auto");

        String 첫장 = 본문(목록("?limit=2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cards.length()").value(2))
                .andExpect(jsonPath("$.cards[0].streamTimestampMs").value(1000)));
        String 다음_표시 = (String) 맵(첫장).get("nextCursor");
        assertThat(다음_표시).as("표시가 없으면 아래 요청이 첫 장을 또 받아 아무것도 안 잰다").isNotNull();

        목록("?limit=2&cursor=" + 다음_표시)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cards.length()").value(1))
                .andExpect(jsonPath("$.cards[0].streamTimestampMs").value(3000))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
    }

    /**
     * 🔴 <b>줄 수가 상한과 <i>정확히</i> 같은 경계.</b> 「다음 장이 있나」를 상한 하나를 더 받아
     * 판정하므로 여기서 {@code >}를 {@code >=}로 한 글자 바꾸면 <b>마지막 장에 다음 표시가 붙는다</b> —
     * 화면은 있지도 않은 다음 장을 한 번 더 받으러 간다.
     */
    @Test
    void 줄_수가_상한과_같으면_다음_표시가_없다() throws Exception {
        볼_수_있다("OWNER");
        카드를_넣는다(내_방송, 1000, "auto");
        카드를_넣는다(내_방송, 2000, "auto");

        목록("?limit=2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cards.length()").value(2))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
    }

    /**
     * 🔴 <b>방송 목록의 표시를 카드 문에 넣으면 거절이다.</b> 태그가 없으면 숫자 하나로 읽혀
     * 통과하고, 그러면 카드가 엉뚱한 자리부터 나온다. 깨진 문자열만 재면 그 갈래를 안 탄다.
     */
    @Test
    void 이어받기_표시가_깨졌으면_400이다() throws Exception {
        목록("?cursor=!!!not-base64!!!")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.field").value("cursor"));

        String 방송_표시 = 방송_목록의_다음_표시();
        목록("?cursor=" + 방송_표시)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("cursor"));

        assertThat(AUTH.lastPath()).as("형식 오류에 자격 창구를 두드리면 안 된다").isNotEqualTo(RESOLVE);
    }

    /**
     * m1 실측 — 서비스가 {@code IllegalArgumentException}을 던지면 전역 조언이 <b>일부러</b>
     * 안 잡으므로 {@code ServletException}으로 빠져 <b>500</b>이 된다. 400이어야 한다.
     */
    @Test
    void 개수가_0이거나_음수면_400이다() throws Exception {
        for (String 값 : new String[]{"0", "-1"}) {
            목록("?limit=" + 값)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_request"))
                    .andExpect(jsonPath("$.field").value("limit"));
        }

        assertThat(AUTH.callCount()).as("형식 오류에 auth 왕복을 태우면 안 된다").isZero();
    }

    /**
     * 🔴 <b>상한보다 많이 심어야 잰다.</b> 200장만 심으면 상한이 통째로 사라져도 200장이 나온다.
     * 카드 상한이 방송(100)보다 큰 것은 한 방송에 1,200장이 쌓인 실측이 있어서다(PRD).
     */
    @Test
    void 상한을_넘는_개수는_상한으로_깎인다() throws Exception {
        볼_수_있다("OWNER");
        for (int i = 1; i <= 201; i++) {
            카드를_넣는다(내_방송, i * 1000L, "auto");
        }

        목록("?limit=1000")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cards.length()").value(200))
                .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.notNullValue()));
    }

    /** 숫자가 아닌 값은 컨트롤러 메서드에 들어오기 전에 끝난다 — 그래도 봉투가 같아야 한다. */
    @Test
    void 숫자가_아닌_개수도_같은_400_봉투다() throws Exception {
        String 형식오류 = 본문(목록("?limit=abc").andExpect(status().isBadRequest()));
        String 값오류 = 본문(목록("?limit=0").andExpect(status().isBadRequest()));

        assertThat(형식오류).as("정본 봉투가 비어 있으면 아래 비교가 아무것도 안 잰다")
                .contains("invalid_request").contains("limit");
        assertThat(형식오류).isEqualTo(값오류);
    }

    // ── 거절 ────────────────────────────────────────────────────

    /**
     * 🔴 <b>본문을 서로 비교한다.</b> 둘을 각각 「{@code broadcast_not_found}가 맞다」로 재면
     * 한쪽이 바뀔 때 그 시험만 고쳐지고 <b>갈림 자체는 안 잡힌다</b>.
     *
     * <p>긍정 대조를 같은 갈래에 둔다 — 같은 경로·같은 방송에 자격 있는 토큰으로 200이 나오는 것을
     * 먼저 보고, 그래야 404가 「경로 오타」가 아니라 <b>판정</b>이라는 뜻이 된다.
     */
    @Test
    void 자격이_없으면_404이고_없는_방송과_본문이_같다() throws Exception {
        카드를_넣는다(내_방송, 1000, "auto");

        볼_수_있다("OWNER");
        목록("").andExpect(status().isOk()).andExpect(jsonPath("$.cards.length()").value(1));

        볼_수_없다();
        String 자격_없음 = 본문(목록("").andExpect(status().isNotFound()));
        String 없는_방송 = 본문(요청("s-없는방송", "").andExpect(status().isNotFound()));

        assertThat(자격_없음).as("정본 봉투가 비어 있으면 아래 동등 비교가 아무것도 안 잰다")
                .contains("broadcast_not_found");
        assertThat(자격_없음).isEqualTo(없는_방송);
        assertThat(자격_없음).as("카드가 한 장이라도 새면 존재가 통째로 드러난다").doesNotContain("cards");
    }

    /**
     * 🔴 <b>판정이 조회보다 앞이라는 것을 재는 유일한 갈래다.</b> 순서를 뒤집어도 응답은 똑같이
     * 404이고, 시간 차이는 바닥(25ms)이 덮는다 — 실제로 <b>뒤로 옮기는 결함 주입에 20건이 전부
     * 초록이었다</b>. 그래서 응답이 아니라 <b>DB에 질의가 나갔는지</b>를 잰다.
     *
     * <p>순서가 지켜야 하는 것은 「남의 방송 카드를 읽지 않는다」이다. 지금은 바닥이 시간을 덮지만
     * <b>바닥은 평상시만 막는다</b>(auth가 느려지면 다시 갈린다 — {@code NotFoundFloor} 주석).
     * 그때 남는 방어선이 이 순서다.
     *
     * <p><b>대조를 같은 갈래에 둔다</b> — 통과 갈래가 질의를 <b>더</b> 던지는 것을 함께 재므로,
     * 통계가 아무것도 안 세고 있으면(둘 다 0) 이 단언이 빨간불이다. 「0」은 「그 일이 안 일어났다」와
     * 「아무 일도 안 일어났다」가 같은 값이라 대조 없이는 뜻이 없다.
     *
     * <p>통계는 이 갈래 안에서만 켜고 {@code finally}에서 끈다 — 세션 팩토리가 컨텍스트 공유물이라
     * 켜 둔 채로 두면 남의 시험에 비용이 얹힌다.
     */
    @Test
    void 자격이_없으면_카드를_읽지도_않는다() throws Exception {
        카드를_넣는다(내_방송, 1000, "auto");
        Statistics 통계 = emf.unwrap(SessionFactory.class).getStatistics();
        통계.setStatisticsEnabled(true);
        try {
            볼_수_없다();
            통계.clear();
            목록("").andExpect(status().isNotFound());
            long 거절 = 통계.getPrepareStatementCount();

            볼_수_있다("OWNER");
            통계.clear();
            목록("").andExpect(status().isOk()).andExpect(jsonPath("$.cards.length()").value(1));
            long 통과 = 통계.getPrepareStatementCount();

            assertThat(거절).as("거절 갈래가 질의를 하나도 안 던졌다 — 측정기가 안 세고 있다").isPositive();
            assertThat(거절)
                    .as("자격 없는 요청이 통과와 같은 수의 질의를 던졌다 — 남의 카드를 읽고 나서 거절한 것이다")
                    .isLessThan(통과);
        } finally {
            통계.setStatisticsEnabled(false);
        }
    }

    @Test
    void 없는_방송이면_404다() throws Exception {
        볼_수_있다("OWNER");

        요청("s-없는방송", "")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("broadcast_not_found"));

        assertThat(AUTH.callCount()).as("없는 방송에 auth를 두드리면 NONE 카운터가 오염된다").isZero();
    }

    @Test
    void auth를_못_물으면_503이다() throws Exception {
        AUTH.respondWith(RESOLVE, 500, "");
        카드를_넣는다(내_방송, 1000, "auto");

        String 본문 = 본문(목록("")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("authorization_unavailable")));

        assertThat(본문).as("빈 목록으로 접으면 화면이 「카드가 없다」고 단정한다").doesNotContain("cards");
    }

    @Test
    void 토큰이_없으면_401이다() throws Exception {
        mvc.perform(get("/api/clip/broadcasts/" + 내_방송 + "/jump-cards"))
                .andExpect(status().isUnauthorized());

        assertThat(AUTH.callCount()).as("401인데 auth를 물었으면 체인이 컨트롤러 뒤에 선 것이다").isZero();
    }

    /**
     * 협상에 실패하면 상태 코드가 <b>500으로 둔갑한다</b> — POK-118이 이 자리에서 실제로 덴 사고다.
     */
    @Test
    void 오류_응답은_Accept가_JSON이_아니어도_JSON으로_나간다() throws Exception {
        볼_수_없다();

        mvc.perform(get("/api/clip/broadcasts/" + 내_방송 + "/jump-cards")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .header("Authorization", "Bearer " + TestTokens.access(요청자)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("broadcast_not_found"));
    }

    // ── 404 두 갈래의 시각 ──────────────────────────────────────

    /**
     * <b>하한을 잰다.</b> {@code isLessThan} 류의 상한은 바닥을 넣기 <b>전에도</b> 통과하므로
     * 아무것도 증명하지 않는다. 여기가 초록이려면 실제로 기다린 시간이 있어야 한다.
     *
     * <p><b>갈래마다 시험을 나눈 이유</b> — 한 메서드에 단언 둘을 두면 앞엣것이 빨간불일 때
     * 뒤엣것은 돌지도 않는다. 그러면 「한쪽에만 걸린」 회귀가 앞 단언 뒤에 숨는다.
     */
    @Test
    void 없는_방송의_404가_바닥_시간을_채운다() throws Exception {
        볼_수_있다("OWNER");

        double 없는_방송 = 가장_빠른_응답_ms(3, "s-없는방송", status().isNotFound());

        assertThat(없는_방송)
                .as("없는 방송이 바닥보다 빨리 나갔다 — 그 빠르기 자체가 「그 방송은 없다」는 신호다")
                .isGreaterThanOrEqualTo(바닥_ms);
    }

    /** 위 갈래의 짝. 이쪽은 auth 왕복을 지나서 오는 404다 — <b>둘 중 하나만 늦으면 여전히 갈린다.</b> */
    @Test
    void 자격_없음의_404도_같은_바닥_시간을_채운다() throws Exception {
        볼_수_없다();

        double 자격_없음 = 가장_빠른_응답_ms(3, 내_방송, status().isNotFound());

        assertThat(자격_없음)
                .as("자격 없음이 바닥보다 빨리 나갔다 — 두 갈래 중 한쪽에만 걸린 것이다")
                .isGreaterThanOrEqualTo(바닥_ms);
    }

    /**
     * 🔴 <b>기준 시각이 「갈리기 전」에 찍혔는지를 재는 유일한 갈래다.</b> 위 둘은 「기다리기는 한다」만
     * 잠근다 — 컨트롤러의 {@code mark} 한 줄을 지워도 둘 다 초록일 수 있다. 그런데 그러면 두 갈래는
     * <b>각자의 도착 시각에 바닥을 더한 값</b>이 되어 차이가 고스란히 남는다.
     *
     * <p><b>그래서 가짜 자격 창구를 일부러 느리게 만든다.</b> 평소 이 가짜는 즉시 답해서 재현할 차이가
     * 거의 없다. 심는 값은 <b>바닥의 절반</b>이라 바닥이 덮기로 한 범위 <i>안</i>이다.
     *
     * <p>단언은 <b>차이</b>에 건다. 절대 시각은 부하에 따라 통째로 밀리지만 두 갈래가 같이 밀리므로
     * 차이는 안 밀린다. 문턱은 심은 지연의 절반이다 — 회귀가 나면 차이가 심은 지연만큼(문턱의 2배) 벌어진다.
     */
    @Test
    void auth가_느려도_두_404가_같은_시각에_나간다() throws Exception {
        볼_수_없다();
        AUTH.holdFor(느린_창구);

        가장_빠른_응답_ms(1, "s-없는방송", status().isNotFound());  // 워밍업 — 첫 요청만 유독 느리다
        double 없는_방송 = 가장_빠른_응답_ms(표본_수, "s-없는방송", status().isNotFound());
        double 자격_없음 = 가장_빠른_응답_ms(표본_수, 내_방송, status().isNotFound());

        assertThat(AUTH.callCount()).as("느린 창구를 한 번도 안 지났다 — 잴 차이 자체가 없었다").isPositive();
        assertThat(Math.abs(자격_없음 - 없는_방송))
                .as("두 404의 시각이 심은 지연만큼 갈렸다 — 기준 시각이 갈림 뒤에 찍힌 것이다")
                .isLessThan(느린_창구.toMillis() / 2.0);
    }

    /**
     * 🔴 <b>바닥을 전역 조언에 걸면 판별기가 부르는 내부 문의 404까지 25ms 늦어진다</b>(계획 검증 m5).
     * 거기엔 감출 존재가 없고(서버 간 토큰) 판별기는 404를 재시도 상한으로 세므로 순수한 비용이다.
     * 그래서 바닥은 <b>기준이 찍힌 요청에만</b> 걸린다.
     *
     * <p><b>대조가 없으면 이 단언은 「바닥이 통째로 죽었다」에서도 초록이다.</b> 그래서 같은 갈래에서
     * 사람 문의 404를 함께 재고, <b>그쪽이 바닥 위</b>인 것을 먼저 확인한다 — 측정기가 살아 있다는 증거다.
     */
    @Test
    void 내부_문의_404는_바닥에_안_묶인다() throws Exception {
        볼_수_있다("OWNER");
        double 사람_문 = 가장_빠른_응답_ms(3, "s-없는방송", status().isNotFound());
        assertThat(사람_문).as("사람 문마저 안 늦는다 — 바닥이 통째로 안 걸린 것이라 아래 비교가 무의미하다")
                .isGreaterThanOrEqualTo(바닥_ms);

        double 내부_문 = 가장_빠른_내부_문_404_ms(5);

        assertThat(내부_문).as("내부 문의 404까지 바닥에 묶였다 — 판별기가 재시도마다 그만큼 손해다")
                .isLessThan(바닥_ms);
    }

    // ── 도우미 ──────────────────────────────────────────────────

    private void 볼_수_있다(String relation) {
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"%s\"}".formatted(relation));
    }

    private void 볼_수_없다() {
        AUTH.respondWith(RESOLVE, 200, "{\"relation\":\"NONE\"}");
    }

    private ResultActions 목록(String 질의) throws Exception {
        return 요청(내_방송, 질의);
    }

    private ResultActions 요청(String streamId, String 질의) throws Exception {
        return mvc.perform(get("/api/clip/broadcasts/" + streamId + "/jump-cards" + 질의)
                .header("Authorization", "Bearer " + TestTokens.access(요청자)));
    }

    /**
     * 같은 요청을 {@code 횟수}번 재서 <b>가장 빠른</b> 응답 시간(ms)을 준다.
     * 기대 상태를 매 회 확인하는 것은, 갈래가 바뀌어 엉뚱한 응답을 재는 일이 없게 하려는 것이다.
     */
    private double 가장_빠른_응답_ms(int 횟수, String streamId, ResultMatcher 기대) throws Exception {
        double 최소 = Double.MAX_VALUE;
        for (int i = 0; i < 횟수; i++) {
            long 시작 = System.nanoTime();
            요청(streamId, "").andExpect(기대);
            최소 = Math.min(최소, (System.nanoTime() - 시작) / 1_000_000.0);
        }
        return 최소;
    }

    /** 판별기가 쓰는 문. 없는 방송이면 같은 전역 조언이 404를 낸다 — 다른 것은 기준이 안 찍힌다는 것뿐이다. */
    private double 가장_빠른_내부_문_404_ms(int 횟수) throws Exception {
        String 본문 = """
                {"eventId":"evt-floor","source":"auto","streamTimestampMs":5000,
                 "window":{"startMs":4000,"endMs":6000},"score":50}""";
        double 최소 = Double.MAX_VALUE;
        for (int i = 0; i < 횟수; i++) {
            long 시작 = System.nanoTime();
            mvc.perform(post("/internal/broadcasts/s-없는방송/highlights")
                            .header("X-Internal-Token", "test-only-internal-token-32bytes-long!!")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(본문))
                    .andExpect(status().isNotFound());
            최소 = Math.min(최소, (System.nanoTime() - 시작) / 1_000_000.0);
        }
        return 최소;
    }

    /** 방송 목록 문에서 진짜 표시를 하나 받아 온다 — 손으로 만든 문자열은 그 문의 모양이 아닐 수 있다. */
    private String 방송_목록의_다음_표시() throws Exception {
        AUTH.respondWith("/internal/editor-delegations/accessible", 200,
                "{\"streamers\":[{\"streamerUserId\":%s,\"relation\":\"OWNER\"}]}".formatted(TestIds.STREAMER));
        방송을_넣는다("s-cursor-1");
        방송을_넣는다("s-cursor-2");

        String 본문 = 본문(mvc.perform(get("/api/clip/broadcasts?state=live&limit=1")
                        .header("Authorization", "Bearer " + TestTokens.access(요청자)))
                .andExpect(status().isOk()));
        String 표시 = (String) 맵(본문).get("nextCursor");
        assertThat(표시).as("표시가 없으면 아래 요청이 「빈 커서」를 재게 된다").isNotNull();
        return 표시;
    }

    private static String 본문(ResultActions 응답) throws Exception {
        return 응답.andReturn().getResponse().getContentAsString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> 맵(String 본문) {
        return MAPPER.readValue(본문, Map.class);
    }

    private void 숨긴다(long id) {
        jdbc.update("UPDATE jump_cards SET hidden_at = now(), hidden_by = '99' WHERE id = ?", id);
    }

    private long 카드를_넣는다(String streamId, long ts, String source) {
        return jdbc.queryForObject("""
                        INSERT INTO jump_cards
                            (stream_id, source, event_id, stream_timestamp_ms,
                             window_start_ms, window_end_ms, score, event_seq)
                        VALUES (?, ?, ?, ?, ?, ?, 50, 0)
                        RETURNING id""",
                Long.class, streamId, source, "evt-" + streamId + "-" + ts + "-" + source,
                ts, ts - 500, ts + 500);
    }

    private void 방송을_넣는다(String streamId) {
        jdbc.update("""
                        INSERT INTO broadcasts (stream_id, streamer_id, status, started_at, last_sequence)
                        VALUES (?, ?, 'live', ?, 1)""",
                streamId, TestIds.STREAMER, OffsetDateTime.ofInstant(시작_시각, ZoneOffset.UTC));
    }
}
