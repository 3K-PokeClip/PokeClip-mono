package com.pokeclip.clip.segment.api;

import ch.qos.logback.classic.Level;
import com.pokeclip.clip.delegation.AuthClientTokenLeakTest;
import com.pokeclip.clip.delegation.DelegationResolveClient;
import com.pokeclip.clip.delegation.ResolveResult;
import com.pokeclip.clip.segment.SegmentQueryService;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.TestTokens;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 편집기(웹)가 실제로 부르는 문 하나. 이 클래스가 재는 것은 <b>서비스가 이미 정한 판정</b>이
 * 아니라 그것을 HTTP로 옮기는 세 가지다 — <b>상태 코드</b> · <b>응답에서 무엇이 빠지나</b> ·
 * <b>거절 본문이 서로 구분되지 않나</b>.
 *
 * <p>판정 자체(순서·구간 검증)는 {@code SegmentQueryServiceTest}가 잰다. 여기 400 갈래가
 * 그것과 겹쳐 보이는 것은 <b>컨트롤러가 그 예외를 400으로 옮기는지</b>를 재기 때문이다 —
 * 검증을 컨트롤러로 옮기라는 뜻이 아니다(POK-125는 컨트롤러를 안 거친다).
 *
 * <p><b>자격 창구만 가짜다.</b> DB는 진짜다(만료가 DB 시계이고 조회가 실제 SQL이다) —
 * {@code SegmentQueryServiceTest}와 같은 이유. 진짜 auth 왕복은
 * {@code DelegationResolveClientTest}가 가짜 서버로 잰다.
 */
@AutoConfigureMockMvc
class SegmentControllerTest extends IntegrationTestSupport {

    /** JWT {@code sub}. 스트리머 번호와 <b>다른 값</b>이어야 자격 판정이 본인 통과로 새지 않는다. */
    private static final String 요청자_주체 = "42";

    private static final String 스트리머_번호 = "777";

    private static final Duration 육십일 = Duration.ofDays(60);
    private static final Instant 시작_시각 = Instant.parse("2026-08-18T00:00:00Z");

    /** 404가 이보다 빨리 나가면 안 된다. 정본은 {@link NotFoundFloor#FLOOR} — 여기서 베끼지 않는다. */
    private static final long 바닥_ms = NotFoundFloor.FLOOR.toMillis();

    /**
     * 가짜 자격 창구에 일부러 심는 지연. <b>바닥의 절반</b>이라 바닥이 덮기로 한 범위 안이다 —
     * 바닥을 줄이는 날 이 값도 같이 줄어, 「덮이는 범위 안」이라는 전제가 유지된다.
     */
    private static final Duration 느린_창구 = NotFoundFloor.FLOOR.dividedBy(2);

    /**
     * 차이를 잴 때 한 갈래를 몇 번 두드리나. <b>최솟값을 쓰므로 표본이 많을수록 참값에 붙는다</b> —
     * 지연 잡음은 한쪽(느린 쪽)으로만 붙기 때문이다.
     *
     * <p>🔴 <b>3이었고 그것이 흔들렸다</b>(세 세션이 CPU를 나눠 쓰는 중 20회에 3회 빨강).
     * 표본 수를 바꿔 가며 15회씩 재서 골랐다 — 차이의 최댓값이
     * <b>3 → 6.723 · 6 → 8.563 · 9 → 4.101 · 15 → 2.763</b>(ms, 문턱 6.0)였다.
     * 15는 문턱까지 <b>2배 넘는 여유</b>가 있고, 그러고도 부분 회귀(7ms쯤)는 문턱 위로 나온다.
     *
     * <p><b>문턱을 올리거나 {@link NotFoundFloor#FLOOR}를 키우는 길은 안 골랐다.</b> 바닥을 키우면
     * 문턱도 같이 커지지만(문턱이 {@code FLOOR}의 1/4에 묶여 있다) <b>모든 404가 그만큼 느려진다</b> —
     * 감출 것이 없는 응답에까지 무는 비용이다. 표본 수는 운영 비용이 0이다.
     * 무엇보다 <b>회귀의 서명이 표본에 하나도 없었다</b> — 회귀라면 차이가 심은 지연만큼(12ms)
     * 벌어져야 하는데 12ms 근처가 0개였고 분포가 0.02~8.6에 연속이었다. <b>잡음이 문턱을 넘은
     * 것이지 신호가 아니다.</b>
     */
    private static final int 표본_수 = 15;

    /** 조각의 벽시계 시각은 이 경로에 안 쓰인다 — NOT NULL을 채우려고 둔 값이다. */
    private static final OffsetDateTime 아무_UTC_시각 =
            OffsetDateTime.of(2026, 8, 24, 0, 0, 0, 0, ZoneOffset.UTC);

    /**
     * 가짜로 <b>갈아끼운다</b>(감싸지 않는다). 실물은 {@code application-test.yml}이 가리키는
     * 아무도 안 듣는 포트로 나가 항상 {@code UNAVAILABLE}이 되므로, 감싸면 모든 갈래가 503이다.
     *
     * <p>⚠ 스텁이 없는 호출은 {@code null}을 돌려주고, 서비스는 {@code null}을
     * <b>거절이 아닌 것</b>으로 읽어 통과시킨다. 그래서 {@code @BeforeEach}에서 기본값(OWNER)을
     * 반드시 건다 — 안 걸면 자격 검사가 사라진 채로 초록이 된다.
     */
    @MockitoBean
    private DelegationResolveClient 자격창구;

    private final MockMvc mvc;
    private final JdbcTemplate jdbc;

    SegmentControllerTest(MockMvc mvc, JdbcTemplate jdbc) {
        this.mvc = mvc;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 앞_테스트의_흔적을_지운다() {
        방송과_카드를_비운다(jdbc);
        given(자격창구.resolve(anyLong(), anyLong())).willReturn(ResolveResult.OWNER);
    }

    // ── 200과 그 모양 ────────────────────────────────────────────

    /**
     * 화면이 실제로 받는 모양 전부를 한 번에 잠근다. {@code discontinuity}는 판정에 안 쓰이고
     * <b>값이 응답까지 보존되는 것 자체가 요구사항</b>이라(PRD 성공 기준 6) 여기서 두 방향을 다 심는다 —
     * 매핑에서 그 칸을 빠뜨리거나 상수로 접으면 이 갈래가 빨간불이다.
     */
    @Test
    void 조각이_실린_200과_그_모양() throws Exception {
        방송중인_방송을_넣는다("s-ok", 스트리머_번호);
        조각을_넣는다("s-ok", 1, 4000, 4000, "uploaded", false);   // [4000,8000)
        조각을_넣는다("s-ok", 2, 8000, 4000, "uploaded", true);    // [8000,12000) 재연결 직후

        요청("s-ok", 5000, 11_000)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complete").value(true))
                .andExpect(jsonPath("$.availableFromMs").value(4000))
                .andExpect(jsonPath("$.availableUntilMs").value(12_000))
                .andExpect(jsonPath("$.segments.length()").value(2))
                .andExpect(jsonPath("$.segments[0].seq").value(1))
                .andExpect(jsonPath("$.segments[0].startPtsMs").value(4000))
                .andExpect(jsonPath("$.segments[0].durationMs").value(4000))
                .andExpect(jsonPath("$.segments[0].discontinuity").value(false))
                .andExpect(jsonPath("$.segments[1].seq").value(2))
                .andExpect(jsonPath("$.segments[1].discontinuity").value(true));
    }

    /**
     * 🔴 <b>이 record가 존재하는 이유 그 자체다</b>(PRD 결정: 출입증(POK-122) 전에는 키를 줘도
     * 화면이 못 쓰고 버킷 구조만 샌다).
     *
     * <p><b>조각이 둘 실린 200을 먼저 단언한다.</b> 빈 목록·오류 응답에서는 「s3Key가 없다」가
     * 자동으로 참이 되어 아무것도 안 재기 때문이다. 칸 이름(<i>s3Key</i>·<i>s3_key</i>)과
     * <b>값의 모양</b>(<i>seg/</i>)을 같이 본다 — 이름만 보면 Jackson 이름 전략이 바뀌는 날
     * 값이 그대로 실린 채 초록이 된다.
     */
    @Test
    void 조각이_여러_개_실린_본문에_s3Key가_없다() throws Exception {
        방송중인_방송을_넣는다("s-nokey", 스트리머_번호);
        조각을_넣는다("s-nokey", 1, 4000, 4000, "uploaded", false);
        조각을_넣는다("s-nokey", 2, 8000, 4000, "uploaded", false);

        String 본문 = 요청("s-nokey", 5000, 11_000)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments.length()").value(2))
                .andReturn().getResponse().getContentAsString();

        assertThat(본문).as("실제로 조각이 실린 본문이어야 아래 부정 단언이 무언가를 본다")
                .contains("\"seq\":1").contains("\"seq\":2");
        assertThat(본문).doesNotContain("s3Key").doesNotContain("s3_key").doesNotContain("seg/");
    }

    /**
     * 🔴 <b>{@code s3Key}는 본문뿐 아니라 로그에서도 빠져야 한다.</b> 위 갈래는 <b>응답</b>만
     * 잠근다 — 유출 검사({@code SegmentApiTokenLeakTest})는 404 경로라 조각이 하나도 안 실리고,
     * 거기서 「s3Key가 없다」는 <b>자동으로 참</b>이다(감사 2회차 E1).
     *
     * <p>그래서 여기서는 <b>조각이 둘 실린 200</b>을 root=TRACE로 태운다. 새는 값은 버킷 구조이고,
     * 출입증(POK-122) 전에는 그것만으로 원본에 닿는 길이 열린다.
     *
     * <p>키는 <b>매 실행 무작위</b>다 — 고정 문자열은 다른 로그와 우연히 겹쳐 통과할 수 있다.
     * 탐지기는 {@code AuthClientTokenLeakTest}의 것 하나를 그대로 쓴다(복사하면 갈리고,
     * 느슨해진 쪽이 조용히 초록이 된다 — POK-118의 {@code SseReader} 사고).
     */
    @Test
    void 조각이_실린_200에서_s3Key가_로그에도_안_남는다() throws Exception {
        String 바늘 = "seg/LEAK-s3-" + UUID.randomUUID() + ".ts";
        방송중인_방송을_넣는다("s-s3log", 스트리머_번호);
        조각을_넣는다("s-s3log", 1, 4000, 4000, "uploaded", false, 바늘);
        조각을_넣는다("s-s3log", 2, 8000, 4000, "uploaded", false, 바늘 + ".2");

        try (LogCaptor 로그 = new LogCaptor()) {
            Level 원래_root = AuthClientTokenLeakTest.levelOf(org.slf4j.Logger.ROOT_LOGGER_NAME);
            AuthClientTokenLeakTest.setLevel(org.slf4j.Logger.ROOT_LOGGER_NAME, Level.TRACE);
            try {
                // 조각이 실린 것을 먼저 못 박는다 — 빈 200이면 아래 부정 단언이 자동으로 참이다.
                요청("s-s3log", 5000, 11_000)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.segments.length()").value(2));
            } finally {
                AuthClientTokenLeakTest.setLevel(org.slf4j.Logger.ROOT_LOGGER_NAME, 원래_root);
            }

            // 양성 대조 — 조각을 읽어 온 그 경로가 실제로 로그에 잡혔다. 이게 없으면
            // 아래 부정 단언은 「아무것도 안 담긴 haystack」에 자동으로 통과한다.
            assertThat(로그.events())
                    .as("JDBC 조회가 TRACE로 안 잡혔다 — s3Key가 지나가는 그 경로를 안 본 것이다")
                    .anyMatch(e -> e.getLoggerName().startsWith("org.springframework.jdbc"));

            AuthClientTokenLeakTest.assertNoSecretsIn(로그, List.of(바늘));
        }
    }

    /**
     * 돈 내는 쪽(스트리머)과 매일 쓰는 쪽(전담 편집자)이 다르다 — <b>편집자가 못 열면 이 제품이
     * 안 돌아간다.</b> 서비스 계층에 같은 갈래가 있지만 HTTP 계층에는 없었다(계획 검증 m3).
     */
    @Test
    void EDITOR도_200이다() throws Exception {
        방송중인_방송을_넣는다("s-editor", 스트리머_번호);
        조각을_넣는다("s-editor", 1, 4000, 4000, "uploaded", false);
        given(자격창구.resolve(anyLong(), anyLong())).willReturn(ResolveResult.EDITOR);

        요청("s-editor", 5000, 8000)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments.length()").value(1))
                .andExpect(jsonPath("$.segments[0].seq").value(1));
    }

    // ── 인증 ────────────────────────────────────────────────────

    /**
     * 🔴 <b>「누가 물었나」가 토큰에서 온다는 것을 재는 유일한 갈래다.</b> 가짜 자격 창구는 어떤
     * 인자에도 OWNER를 돌려주므로, 컨트롤러가 {@code jwt.getSubject()} 대신 상수나
     * <b>쿼리 파라미터</b>를 넘기도록 바뀌어도 나머지 열일곱이 전부 초록이다 — 주입으로 확인함(주입 13).
     *
     * <p>그 회귀의 결과는 <b>로그인한 아무나가 남의 방송을 여는 것</b>이고, 화면은 멀쩡히 돌기
     * 때문에 조용하다. 서비스 계층의 같은 이름 갈래는 서비스에 <i>이미 건네진</i> 값의 순서를 재고,
     * 여기서 재는 것은 <b>그 값이 어디서 오는가</b>다.
     */
    @Test
    void 토큰의_주체가_그대로_자격_판정에_간다() throws Exception {
        방송중인_방송을_넣는다("s-subject", 스트리머_번호);
        조각을_넣는다("s-subject", 1, 4000, 4000, "uploaded", false);

        요청("s-subject", 5000, 8000).andExpect(status().isOk());

        verify(자격창구).resolve(Long.parseLong(요청자_주체), Long.parseLong(스트리머_번호));
    }

    @Test
    void 토큰이_없으면_401이다() throws Exception {
        방송중인_방송을_넣는다("s-401", 스트리머_번호);
        조각을_넣는다("s-401", 1, 4000, 4000, "uploaded", false);

        mvc.perform(get("/api/clip/broadcasts/s-401/segments")
                        .param("startMs", "5000").param("endMs", "8000"))
                .andExpect(status().isUnauthorized());
    }

    // ── 거절: 404 두 갈래가 구분되면 안 된다 ─────────────────────────

    @Test
    void 없는_방송은_404다() throws Exception {
        요청("s-없는방송", 5000, 8000)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("broadcast_not_found"));
    }

    /**
     * 🔴 <b>PRD 성공 기준의 본체.</b> 각각 404인 것만 보면 본문이 갈려도 초록이라
     * <b>두 응답 문자열을 직접 비교</b>한다 — 한 글자라도 다르면 남의 방송 번호를 넣어 보는
     * 것만으로 그 방송이 실재하는지 알 수 있다.
     */
    @Test
    void NONE이면_404이고_본문이_없는_방송과_같다() throws Exception {
        방송중인_방송을_넣는다("s-none", 스트리머_번호);
        given(자격창구.resolve(anyLong(), anyLong())).willReturn(ResolveResult.NONE);

        String 자격없음 = 요청("s-none", 5000, 8000)
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        String 없는방송 = 요청("s-없는방송", 5000, 8000)
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();

        assertThat(없는방송).as("빈 본문끼리 같은 것은 아무 뜻이 없다").contains("broadcast_not_found");
        assertThat(자격없음).isEqualTo(없는방송);
    }

    /**
     * 위 갈래가 「본문이 같다」를 잠그면 이 갈래는 <b>그래서 무엇으로 진단하나</b>를 잠근다.
     * 사유는 응답에서 영영 안 보이므로 이 INFO 한 줄이 유일한 구분 수단이다 — 지우면
     * 주인이 자기 방송을 못 보는 사고와 남남의 정상 거절이 로그에서도 같아진다.
     */
    @Test
    void 거절_사유는_404_본문이_아니라_로그로만_간다() throws Exception {
        방송중인_방송을_넣는다("s-reason", 스트리머_번호);
        given(자격창구.resolve(anyLong(), anyLong())).willReturn(ResolveResult.NONE);

        try (LogCaptor logs = new LogCaptor()) {
            String 본문 = 요청("s-reason", 5000, 8000)
                    .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();

            assertThat(logs.levelOf("clip.segment.not_viewable")).isEqualTo(Level.INFO);
            assertThat(logs.messages()).anyMatch(m -> m.contains("reason=relation_none"));
            assertThat(본문).as("사유가 본문에 실리면 404 통합이 무의미해진다")
                    .doesNotContain("relation_none");
        }
    }

    /**
     * 🔴 <b>본문이 같아도 시간이 갈리면 실재가 샌다.</b> 「없는 방송」은 명부 조회 하나로 끝나고
     * 「실재·자격 없음」은 auth 왕복을 태운다 — 실기동 1,240회에서 중앙값 <b>1.488ms 대 4.422ms</b>,
     * 한 번만 재도 99.5%가 갈렸고 <b>느린 쪽을 빠른 쪽으로 오독한 경우는 0건</b>이었다.
     * 즉 「느리면 실재한다」가 한 번도 안 틀렸다. {@link NotFoundFloor}가 두 갈래를 같은 바닥 뒤로 민다.
     *
     * <p><b>하한을 잰다.</b> {@code isLessThan} 류의 상한은 지연을 넣기 <b>전에도</b> 통과하므로
     * 아무것도 증명하지 않는다. 여기가 초록이려면 실제로 기다린 시간이 있어야 한다 —
     * {@code awaitFloor}를 빈 몸통으로 되돌리면 빨간불이다(주입 1로 확인함).
     *
     * <p><b>이 갈래가 재는 것은 「기다리기는 한다」까지다.</b> 두 응답이 <b>서로</b> 같은 시각에
     * 나가는지는 아래 {@code auth가_느려도_두_404가_같은_시각에_나간다}가 잰다 — 그쪽은 차이를
     * 만들어 내려고 가짜 자격 창구에 지연을 심는다(평소 이 가짜는 즉시 답해서 잴 차이가 없다).
     *
     * <p><b>갈래마다 시험을 나눈 이유</b> — 한 메서드에 단언 둘을 두면 앞엣것이 빨간불일 때
     * 뒤엣것은 <b>돌지도 않는다</b>. 그러면 「한쪽에만 지연이 걸린」 회귀가 앞 단언 뒤에 숨는다.
     */
    @Test
    void 없는_방송의_404가_바닥_시간을_채운다() throws Exception {
        double 없는_방송 = 가장_빠른_응답_ms(3, "s-없는방송", status().isNotFound());

        assertThat(없는_방송)
                .as("없는 방송이 바닥보다 빨리 나갔다 — 그 빠르기 자체가 「그 방송은 없다」는 신호다")
                .isGreaterThanOrEqualTo(바닥_ms);
    }

    /** 위 갈래의 짝. 이쪽은 auth 왕복을 지나서 오는 404다 — <b>둘 중 하나만 늦으면 여전히 갈린다.</b> */
    @Test
    void 자격_없음의_404도_같은_바닥_시간을_채운다() throws Exception {
        방송중인_방송을_넣는다("s-floor-none", 스트리머_번호);
        given(자격창구.resolve(anyLong(), anyLong())).willReturn(ResolveResult.NONE);

        double 자격_없음 = 가장_빠른_응답_ms(3, "s-floor-none", status().isNotFound());

        assertThat(자격_없음)
                .as("자격 없음이 바닥보다 빨리 나갔다 — 두 갈래 중 한쪽에만 걸린 것이다")
                .isGreaterThanOrEqualTo(바닥_ms);
    }

    /**
     * 🔴 <b>기준 시각이 「갈리기 전」에 찍혔는지를 재는 유일한 갈래다.</b> 위 둘은 「기다리기는 한다」만
     * 잠근다 — {@code NotFoundFloor.mark} 한 줄을 지워도 둘 다 초록이다(핸들러가 기준 없이 바닥
     * 전체를 자므로). 그런데 그러면 두 갈래는 <b>각자의 도착 시각에 바닥을 더한 값</b>이 되어
     * 차이가 고스란히 남는다 — 이 카드가 고치려던 그 구멍이 그대로 돌아온다.
     *
     * <p><b>그래서 가짜 자격 창구를 일부러 느리게 만든다.</b> 평소 이 가짜는 즉시 답해서 재현할
     * 차이가 아예 없다(실물의 3ms는 진짜 HTTP 왕복이다). 심는 값은 <b>바닥의 절반</b>이다 —
     * 바닥이 덮기로 한 범위 <i>안</i>이므로, 기준이 제대로 찍혔다면 두 갈래는 여전히 같은 시각에 나가야 한다.
     *
     * <p>단언은 <b>차이</b>에 건다. 절대 시각은 부하에 따라 통째로 밀리지만 두 갈래가 같이 밀리므로
     * 차이는 안 밀린다. 문턱은 심은 지연의 절반이다 — 회귀가 나면 차이가 심은 지연만큼(문턱의 2배)
     * 벌어진다.
     *
     * <p>🔴 <b>「정상이면 0.7ms 수준이라 여유가 있다」고 적어 뒀던 것은 한가한 기계의 값이었다.</b>
     * 세션 셋이 CPU를 나눠 쓰는 중에는 표본 3으로 잰 차이가 <b>6.7ms까지</b> 튀어 문턱(6.0)을
     * 넘었다. 고친 것은 문턱이 아니라 <b>표본 수</b>다 — 근거는 {@link #표본_수}에 있다.
     */
    @Test
    void auth가_느려도_두_404가_같은_시각에_나간다() throws Exception {
        방송중인_방송을_넣는다("s-floor-slow", 스트리머_번호);
        given(자격창구.resolve(anyLong(), anyLong())).willAnswer(호출 -> {
            Thread.sleep(느린_창구.toMillis());
            return ResolveResult.NONE;
        });

        가장_빠른_응답_ms(1, "s-없는방송", status().isNotFound());  // 워밍업 — 첫 요청만 유독 느리다
        double 없는_방송 = 가장_빠른_응답_ms(표본_수, "s-없는방송", status().isNotFound());
        double 자격_없음 = 가장_빠른_응답_ms(표본_수, "s-floor-slow", status().isNotFound());

        assertThat(Math.abs(자격_없음 - 없는_방송))
                .as("두 404의 시각이 심은 지연만큼 갈렸다 — 기준 시각이 갈림 뒤에 찍힌 것이다")
                .isLessThan(느린_창구.toMillis() / 2.0);
    }

    /**
     * 늦추는 범위가 <b>이 문의 404 하나</b>로 한정됐다는 증거. 200까지 묶이면 편집기가 조각을
     * 받을 때마다 그만큼 기다리고, 그것은 감출 것이 없는 응답에 무는 순수한 비용이다.
     *
     * <p><b>최소값을 쓴다.</b> 같은 머신에서 세션이 여럿 도는 동안 한 번의 측정은 언제든 튄다.
     * 반대로 200이 바닥에 묶였다면 <b>모든</b> 측정이 바닥 위여서 최소값도 바닥 위다 —
     * 부하에 강하면서 재는 것은 그대로다.
     */
    @Test
    void 정상_응답은_그_바닥에_안_묶인다() throws Exception {
        방송중인_방송을_넣는다("s-floor-ok", 스트리머_번호);
        조각을_넣는다("s-floor-ok", 1, 4000, 4000, "uploaded", false);

        가장_빠른_응답_ms(2, "s-floor-ok", status().isOk());  // 워밍업 — 첫 요청에 JIT·커넥션이 실린다
        double 정상 = 가장_빠른_응답_ms(5, "s-floor-ok", status().isOk());

        assertThat(정상).as("200까지 404의 바닥에 묶였다 — 늦추는 범위가 새어 나갔다")
                .isLessThan(바닥_ms);
    }

    /**
     * 🔴 <b>바닥 값 자체를 지키는 자리.</b> 위 셋은 기대값을 {@link NotFoundFloor#FLOOR}에서 가져온다
     * (베껴 두면 두 값이 갈리고 느슨해진 쪽이 조용히 초록이 된다 — POK-118의 {@code SseReader} 사고).
     * 그 대가로 <b>그 상수를 0으로 낮추면 위 셋이 자동으로 참이 된다.</b>
     *
     * <p>그래서 값의 근거를 여기 못 박는다 — 감춰야 할 느린 갈래의 실측이 p99 10.242ms ·
     * <b>최대 21.160ms</b>였다(1,000회). 바닥이 그 아래로 내려가면 평상시 변동이 바닥 밖으로 삐져나온다.
     */
    @Test
    void 바닥은_느린_갈래의_실측_최대보다_크다() {
        assertThat(NotFoundFloor.FLOOR)
                .as("바닥이 실측 꼬리(21.160ms)를 못 덮는다 — 그만큼은 여전히 시간으로 구분된다")
                .isGreaterThan(Duration.ofNanos(21_160_000));
    }

    @Test
    void 기한_지난_방송은_410이다() throws Exception {
        끝난_방송을_넣는다("s-expired", 스트리머_번호, Instant.now().minus(Duration.ofDays(1)));
        조각을_넣는다("s-expired", 1, 4000, 4000, "uploaded", false);

        요청("s-expired", 5000, 8000)
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error").value("vod_expired"));
    }

    @Test
    void UNAVAILABLE이면_503이다() throws Exception {
        방송중인_방송을_넣는다("s-unavailable", 스트리머_번호);
        조각을_넣는다("s-unavailable", 1, 4000, 4000, "uploaded", false);
        given(자격창구.resolve(anyLong(), anyLong())).willReturn(ResolveResult.UNAVAILABLE);

        요청("s-unavailable", 5000, 8000)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("authorization_unavailable"));
    }

    // ── 거절: 형식 오류는 어느 칸인지 말해 준다 ──────────────────────

    @Test
    void startMs가_음수면_400이다() throws Exception {
        방송중인_방송을_넣는다("s-negative", 스트리머_번호);

        요청("s-negative", -1, 5000)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.field").value("startMs"));
    }

    @Test
    void endMs가_startMs_이하면_400이다() throws Exception {
        방송중인_방송을_넣는다("s-degenerate", 스트리머_번호);

        요청("s-degenerate", 5000, 5000)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.field").value("endMs"));
    }

    @Test
    void 구간이_30분을_넘으면_400이다() throws Exception {
        방송중인_방송을_넣는다("s-too-long", 스트리머_번호);

        요청("s-too-long", 0, SegmentQueryService.MAX_RANGE_MS + 1)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    /**
     * 🔴 <b>같은 400인데 봉투가 둘이면 안 된다.</b> 숫자가 아닌 값은 컨트롤러 메서드에 들어오기
     * 전에 변환이 실패해 {@code MethodArgumentTypeMismatchException}으로 끝나므로,
     * 핸들러가 그 타입을 안 다루면 스프링 기본 {@code /error} 봉투
     * ({@code {"timestamp":…,"status":400,"error":"Bad Request","path":…}})로 나간다 —
     * 웹은 같은 400에 <b>모양이 다른 본문 둘</b>을 받는다(감사 2회차 C2·E3).
     */
    @Test
    void 숫자가_아닌_startMs도_같은_400_봉투다() throws Exception {
        방송중인_방송을_넣는다("s-nan", 스트리머_번호);

        String 변환실패 = 원본_요청("s-nan", "abc", "5000")
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();

        assertThat(변환실패).isEqualTo(우리_400_봉투("s-nan"));
    }

    /**
     * 같은 뿌리의 다른 갈래 — 파라미터가 아예 없으면
     * {@code MissingServletRequestParameterException}이다. 위 갈래만 고치면 이쪽이 그대로
     * 기본 봉투로 남는다(POK-118의 「같은 뿌리인데 한 자리만 고침」).
     */
    @Test
    void 파라미터가_빠져도_같은_400_봉투다() throws Exception {
        방송중인_방송을_넣는다("s-missing", 스트리머_번호);

        String 누락 = mvc.perform(get("/api/clip/broadcasts/s-missing/segments")
                        .param("endMs", "5000")
                        .header("Authorization", "Bearer " + TestTokens.access(요청자_주체)))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();

        assertThat(누락).isEqualTo(우리_400_봉투("s-missing"));
    }

    /**
     * 경계. {@code >}를 {@code >=}로 바꾸는 회귀를 잡는 <b>유일한</b> 갈래다(계획 검증 m4).
     * 조각을 심어 두는 것은 「400이 아니다」만 재면 조회가 통째로 망가져도 초록이기 때문이다.
     */
    @Test
    void 정확히_30분은_통과한다() throws Exception {
        방송중인_방송을_넣는다("s-exactly", 스트리머_번호);
        조각을_넣는다("s-exactly", 1, 0, 4000, "uploaded", false);

        요청("s-exactly", 0, SegmentQueryService.MAX_RANGE_MS)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.segments.length()").value(1))
                .andExpect(jsonPath("$.segments[0].seq").value(1));
    }

    // ── 200인데 비어 있거나 잘린 창 — 화면이 실제로 만나는 모양 ────────

    @Test
    void 겹치는_조각이_없으면_200에_빈_목록이고_from과_until이_startMs다() throws Exception {
        방송중인_방송을_넣는다("s-empty", 스트리머_번호);

        요청("s-empty", 5000, 8000)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complete").value(false))
                .andExpect(jsonPath("$.segments.length()").value(0))
                .andExpect(jsonPath("$.availableFromMs").value(5000))
                .andExpect(jsonPath("$.availableUntilMs").value(5000));
    }

    /**
     * 요청 머리가 아직 안 올라온 상태. 화면은 <b>200을 받고도 요청한 앞부분을 못 튼다</b> —
     * {@code complete=false}와 {@code availableFromMs}가 그 사실을 전하는 유일한 수단이다.
     */
    @Test
    void 요청_머리가_비면_뒤_조각이_실리고_complete가_거짓이다() throws Exception {
        방송중인_방송을_넣는다("s-headless", 스트리머_번호);
        조각을_넣는다("s-headless", 1, 4000, 4000, "uploaded", false);
        조각을_넣는다("s-headless", 2, 8000, 4000, "uploaded", false);

        요청("s-headless", 1000, 11_000)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complete").value(false))
                .andExpect(jsonPath("$.availableFromMs").value(4000))
                .andExpect(jsonPath("$.availableUntilMs").value(12_000))
                .andExpect(jsonPath("$.segments.length()").value(2));
    }

    /** 가운데가 빈 목록을 그대로 주면 화면이 이어 붙여 영상이 튄다 — 거기서 자른다. */
    @Test
    void 중간이_빈_경우_목록이_거기서_잘린다() throws Exception {
        방송중인_방송을_넣는다("s-gap", 스트리머_번호);
        조각을_넣는다("s-gap", 1, 4000, 4000, "uploaded", false);
        조각을_넣는다("s-gap", 2, 8000, 4000, "pending", false);
        조각을_넣는다("s-gap", 3, 12_000, 4000, "uploaded", false);

        요청("s-gap", 5000, 15_000)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complete").value(false))
                .andExpect(jsonPath("$.segments.length()").value(1))
                .andExpect(jsonPath("$.segments[0].seq").value(1))
                .andExpect(jsonPath("$.availableUntilMs").value(8000));
    }

    // ── 오류 응답의 Content-Type ──────────────────────────────────

    /**
     * 🔴 <b>{@code SegmentExceptionHandler.json}의 {@code contentType(APPLICATION_JSON)}을 지키는
     * 유일한 갈래다.</b> 그 줄을 지워도 나머지 전부가 초록이었다(감사 2회차 J12 실측 313/0) —
     * 평범한 {@code Accept: *​/*} 요청은 협상이 어차피 JSON으로 끝나기 때문이다.
     *
     * <p>그래서 <b>협상이 실패하는 Accept를 일부러 보낸다.</b> Content-Type이 미리 박혀 있으면
     * 스프링은 협상을 건너뛰지만, 없으면 {@code HttpMediaTypeNotAcceptableException}이 나고
     * 그 예외는 조언 안에서 삼켜져 <b>원래 예외가 그대로 500으로 나간다</b> —
     * POK-118이 이 자리에서 실제로 덴 사고다(404·503이 500으로 둔갑).
     * 지금 이 문에는 {@code produces}가 없어 저절로는 안 열리지만, 붙는 날 그물이 없었다.
     *
     * <p>네 갈래를 한 번에 보는 것은 <b>{@code json} 하나가 넷의 공통 출구</b>여서다 —
     * 한 자리만 고쳐지는 회귀가 아니라 그 출구가 통째로 사라지는 회귀를 잡는다.
     */
    @Test
    void 오류_응답은_Accept가_JSON이_아니어도_JSON으로_나간다() throws Exception {
        방송중인_방송을_넣는다("s-ct-400", 스트리머_번호);
        끝난_방송을_넣는다("s-ct-410", 스트리머_번호, Instant.now().minus(Duration.ofDays(1)));
        방송중인_방송을_넣는다("s-ct-503", 스트리머_번호);

        오류_협상("s-ct-없는방송", 5000, 8000).andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("broadcast_not_found"));
        오류_협상("s-ct-400", -1, 8000).andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("invalid_request"));
        오류_협상("s-ct-410", 5000, 8000).andExpect(status().isGone())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("vod_expired"));

        given(자격창구.resolve(anyLong(), anyLong())).willReturn(ResolveResult.UNAVAILABLE);
        오류_협상("s-ct-503", 5000, 8000).andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("authorization_unavailable"));
    }

    // ── 핸들러의 적용 범위 ────────────────────────────────────────

    /**
     * 🔴 <b>행동으로는 못 재는 자리라 구조로 잰다.</b> 지금 이 서버에는
     * {@code JumpCardExceptionHandler}가 <b>범위를 안 좁힌 채</b> 전역으로 떠 있고, 그쪽도
     * {@code broadcast_not_found}라는 <b>같은 코드 문자열</b>을 쓴다. 두 조언이 다루는 예외
     * 타입이 지금은 겹치지 않아서 <b>이 줄을 지워도 오늘은 모든 갈래가 초록이다</b> —
     * 확인함(주입 12).
     *
     * <p>🔴 <b>다만 이 좁힘이 「내가 이긴다」를 보장하지는 않는다.</b> 감사 2회차가 일부러
     * 겹치게 만들어 재 보니 <b>전역 조언이 이겼다</b>(404 → 403, 시험 3건 빨강).
     * {@code assignableTypes}가 막는 것은 <b>반대 방향</b>뿐이다 — 이 조언이 점프카드
     * 컨트롤러의 예외를 가로채는 것.
     *
     * <p>그래서 이 갈래가 지키는 것은 오늘의 응답이 아니라 <b>내일의 순서 경쟁</b>이다.
     * 어느 한쪽이 공통 예외(bean validation 따위)를 하나 더 다루는 순간 두 조언이 같은 예외를
     * 두고 경쟁하고, 그때 이 줄이 없으면 <b>이 조언이 남의 문까지 잡는 쪽</b>이 열린다.
     */
    @Test
    void 이_핸들러는_세그먼트_컨트롤러에만_걸린다() {
        RestControllerAdvice advice = SegmentExceptionHandler.class.getAnnotation(RestControllerAdvice.class);

        assertThat(advice).isNotNull();
        assertThat(advice.assignableTypes()).containsExactly(SegmentController.class);
    }

    // ── 도우미 ──────────────────────────────────────────────────

    private ResultActions 요청(String streamId, long startMs, long endMs) throws Exception {
        return 원본_요청(streamId, String.valueOf(startMs), String.valueOf(endMs));
    }

    /**
     * 같은 요청을 {@code 횟수}번 재서 <b>가장 빠른</b> 응답 시간(ms)을 준다.
     * 기대 상태를 매 회 확인하는 것은, 갈래가 바뀌어 엉뚱한 응답을 재는 일이 없게 하려는 것이다.
     */
    private double 가장_빠른_응답_ms(int 횟수, String streamId, ResultMatcher 기대) throws Exception {
        double 최소 = Double.MAX_VALUE;
        for (int i = 0; i < 횟수; i++) {
            long 시작 = System.nanoTime();
            요청(streamId, 5000, 8000).andExpect(기대);
            최소 = Math.min(최소, (System.nanoTime() - 시작) / 1_000_000.0);
        }
        return 최소;
    }

    /**
     * 이 문이 내는 400의 <b>정본 모양</b>. {@code startMs=-1}은 컨트롤러 메서드까지 들어가
     * {@code InvalidRangeException}으로 끝나는 갈래라, 이미 잠겨 있는 봉투다
     * ({@code startMs가_음수면_400이다}).
     *
     * <p><b>비었으면 비교가 아무 뜻이 없으므로</b> 여기서 한 번 확인하고 돌려준다.
     */
    private String 우리_400_봉투(String streamId) throws Exception {
        String 본문 = 요청(streamId, -1, 5000)
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(본문).as("정본 봉투가 비어 있으면 아래 동등 비교가 아무것도 안 잰다")
                .contains("invalid_request").contains("startMs");
        return 본문;
    }

    /**
     * JSON을 <b>안</b> 받겠다는 Accept를 실어 보낸다. {@code text/event-stream}인 것은
     * POK-118이 덴 실물 조건이 그것이어서다(브라우저 EventSource가 보내는 값).
     */
    private ResultActions 오류_협상(String streamId, long startMs, long endMs) throws Exception {
        return mvc.perform(get("/api/clip/broadcasts/" + streamId + "/segments")
                .param("startMs", String.valueOf(startMs))
                .param("endMs", String.valueOf(endMs))
                .accept(MediaType.TEXT_EVENT_STREAM)
                .header("Authorization", "Bearer " + TestTokens.access(요청자_주체)));
    }

    /** 형식이 깨진 값을 그대로 실어 보내는 갈래용 — {@code long}을 거치면 그 상황을 못 만든다. */
    private ResultActions 원본_요청(String streamId, String startMs, String endMs) throws Exception {
        return mvc.perform(get("/api/clip/broadcasts/" + streamId + "/segments")
                .param("startMs", startMs)
                .param("endMs", endMs)
                .header("Authorization", "Bearer " + TestTokens.access(요청자_주체)));
    }

    /** 기한이 NULL이다 — 「아직 안 끝나 기한이 없다」(V203 주석). */
    private void 방송중인_방송을_넣는다(String streamId, String streamerId) {
        방송을_넣는다(streamId, streamerId, "live", null, null);
    }

    private void 끝난_방송을_넣는다(String streamId, String streamerId, Instant 기한) {
        방송을_넣는다(streamId, streamerId, "ended", 기한.minus(육십일), 기한);
    }

    private void 방송을_넣는다(String streamId, String streamerId, String status,
                        Instant endedAt, Instant vodExpiresAt) {
        jdbc.update("""
                        INSERT INTO broadcasts
                            (stream_id, streamer_id, status, started_at, ended_at, last_sequence, vod_expires_at)
                        VALUES (?, ?, ?, ?, ?, 1, ?)""",
                streamId, streamerId, status,
                OffsetDateTime.ofInstant(시작_시각, ZoneOffset.UTC),
                endedAt == null ? null : OffsetDateTime.ofInstant(endedAt, ZoneOffset.UTC),
                vodExpiresAt == null ? null : OffsetDateTime.ofInstant(vodExpiresAt, ZoneOffset.UTC));
    }

    private void 조각을_넣는다(String streamId, long seq, long startPtsMs, int durationMs,
                        String state, boolean discontinuity) {
        조각을_넣는다(streamId, seq, startPtsMs, durationMs, state, discontinuity, "seg/" + seq);
    }

    /** 유출 갈래용 — 키를 무작위 바늘로 심는다. */
    private void 조각을_넣는다(String streamId, long seq, long startPtsMs, int durationMs,
                        String state, boolean discontinuity, String s3Key) {
        jdbc.update("""
                        INSERT INTO stream_segments
                            (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms,
                             s3_key, upload_state, is_discontinuity)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                streamId, seq, startPtsMs, 아무_UTC_시각, durationMs,
                s3Key, state, discontinuity);
    }
}
