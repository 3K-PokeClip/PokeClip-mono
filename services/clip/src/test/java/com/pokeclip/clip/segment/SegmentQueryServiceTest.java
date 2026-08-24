package com.pokeclip.clip.segment;

import ch.qos.logback.classic.Level;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.delegation.DelegationResolveClient;
import com.pokeclip.clip.delegation.ResolveResult;
import com.pokeclip.clip.segment.SegmentErrors.AuthUnavailableException;
import com.pokeclip.clip.segment.SegmentErrors.InvalidRangeException;
import com.pokeclip.clip.segment.SegmentErrors.NotViewableException;
import com.pokeclip.clip.segment.SegmentErrors.VodExpiredException;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 「방송이 있나 → 이 사람이 볼 자격이 있나 → 기한이 안 지났나 → 조각을 읽어 창을 조립」.
 * <b>이 순서 자체가 계약이다</b> — 여기 갈래의 절반은 「무엇을 던지나」가 아니라
 * 「무엇을 <i>먼저</i> 던지나」를 잰다.
 *
 * <p>순서가 뒤집히면 정보가 샌다:
 * <ul>
 *   <li>만료를 자격보다 먼저 보면 남남에게도 410이 나가고, 410은 「있었는데 없어졌다」는
 *       뜻이라 <b>그 방송이 실재했다는 사실</b>이 샌다</li>
 *   <li>없는 방송(404)과 자격 없음(NONE)이 갈리면 남의 방송 번호를 넣어 보는 것만으로
 *       존재를 알 수 있다 — 그래서 예외 타입이 하나고, 실제 사유는 {@code reason}으로만 간다</li>
 * </ul>
 *
 * <p><b>자격 창구는 Mockito 가짜다.</b> 진짜 왕복은 {@code DelegationResolveClientTest}가
 * 가짜 auth 서버로 이미 재고, 여기서 재는 것은 「그 답을 어떻게 쓰나」뿐이다. 서비스를
 * 직접 {@code new} 하는 것도 그래서다 — 스프링 컨텍스트를 하나 더 만들지 않는다.
 * 다만 DB는 진짜다(만료 판정이 <b>DB 시계</b>를 쓰고, 조회가 실제 SQL이다).
 */
class SegmentQueryServiceTest extends IntegrationTestSupport {

    /** JWT {@code sub}. 스트리머 번호와 <b>다른 값</b>이어야 인자 순서가 재어진다. */
    private static final String 요청자_주체 = "42";
    private static final long 요청자_번호 = 42L;

    private static final String 스트리머_번호 = "777";
    private static final long 스트리머_번호_숫자 = 777L;

    /** 로그에 <b>안 실려야 하는</b> 값. 눈에 띄게 지어 다른 로그와 우연히 겹치지 않게 했다. */
    private static final String 숫자가_아닌_스트리머 = "streamer-NOT-A-NUMBER-9f3a";
    private static final String 숫자가_아닌_주체 = "subject-NOT-A-NUMBER-7c1b";

    private static final Duration 육십일 = Duration.ofDays(60);
    private static final Instant 시작_시각 = Instant.parse("2026-08-18T00:00:00Z");

    /** 조각의 벽시계 시각은 이 경로에 안 쓰인다 — NOT NULL을 채우려고 둔 값이다. */
    private static final OffsetDateTime 아무_UTC_시각 =
            OffsetDateTime.of(2026, 8, 24, 0, 0, 0, 0, ZoneOffset.UTC);

    private final JdbcTemplate jdbc;
    private final DelegationResolveClient 자격창구 = mock(DelegationResolveClient.class);
    private final SegmentQueryService service;

    SegmentQueryServiceTest(BroadcastRepository broadcasts, StreamSegmentReader reader, JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.service = new SegmentQueryService(broadcasts, reader, 자격창구);
    }

    @BeforeEach
    void 앞_테스트의_흔적을_지운다() {
        방송과_카드를_비운다(jdbc);
    }

    // ── ① 방송이 있나 ─────────────────────────────────────────────

    /**
     * 없는 방송은 <b>auth에 묻기 전에</b> 끊긴다. 물어볼 스트리머 번호 자체가 없기도 하지만,
     * 안 끊으면 남의 방송 번호를 훑는 것만으로 auth에 요청이 쌓인다.
     */
    @Test
    void 없는_방송은_NotViewable이다() {
        assertThatThrownBy(() -> service.previewWindow(요청자_주체, "s-없는방송", 0, 10_000))
                .isInstanceOfSatisfying(NotViewableException.class,
                        e -> assertThat(e.reason()).isEqualTo("broadcast_not_found"));

        verifyNoInteractions(자격창구);
    }

    // ── ② 자격이 있나 ─────────────────────────────────────────────

    /**
     * <b>방송은 실재한다.</b> 그런데도 위 갈래와 같은 예외 타입이 나가는 것이 이 시험의 전부다 —
     * 타입이 갈리면 응답이 갈리고, 그러면 존재가 샌다. 갈린 것은 {@code reason}뿐이고 그것은
     * 로그로만 간다.
     */
    @Test
    void NONE이면_NotViewable이다() {
        방송중인_방송을_넣는다("s-none", 스트리머_번호);
        given(자격창구.resolve(anyLong(), anyLong())).willReturn(ResolveResult.NONE);

        assertThatThrownBy(() -> service.previewWindow(요청자_주체, "s-none", 0, 10_000))
                .isInstanceOfSatisfying(NotViewableException.class,
                        e -> assertThat(e.reason()).isEqualTo("relation_none"));
    }

    @Test
    void OWNER는_통과한다() {
        방송중인_방송을_넣는다("s-owner", 스트리머_번호);
        조각을_넣는다("s-owner", 1, 4000, 4000, "uploaded", false);
        given(자격창구.resolve(anyLong(), anyLong())).willReturn(ResolveResult.OWNER);

        SegmentWindow 창 = service.previewWindow(요청자_주체, "s-owner", 5000, 8000);

        assertThat(창.segments()).extracting(StreamSegmentRow::seq).containsExactly(1L);
    }

    /**
     * 돈 내는 쪽(스트리머)과 매일 쓰는 쪽(전담 편집자)이 다르다 — <b>편집자가 못 열면 이 제품이
     * 안 돌아간다.</b> OWNER 갈래만 있으면 「OWNER만 통과」로 좁히는 회귀가 안 잡힌다.
     */
    @Test
    void EDITOR도_통과한다() {
        방송중인_방송을_넣는다("s-editor", 스트리머_번호);
        조각을_넣는다("s-editor", 1, 4000, 4000, "uploaded", false);
        given(자격창구.resolve(anyLong(), anyLong())).willReturn(ResolveResult.EDITOR);

        SegmentWindow 창 = service.previewWindow(요청자_주체, "s-editor", 5000, 8000);

        assertThat(창.segments()).extracting(StreamSegmentRow::seq).containsExactly(1L);
    }

    /**
     * <b>판정 불가는 거절이다.</b> 통과로 접는 순간(fail-open) auth가 죽어 있는 동안
     * 남남이 남의 방송을 본다 — 그리고 그 사고는 조용하다. 화면이 멀쩡히 돌기 때문이다.
     */
    @Test
    void UNAVAILABLE이면_AuthUnavailable이다() {
        방송중인_방송을_넣는다("s-unavailable", 스트리머_번호);
        조각을_넣는다("s-unavailable", 1, 4000, 4000, "uploaded", false);
        given(자격창구.resolve(anyLong(), anyLong())).willReturn(ResolveResult.UNAVAILABLE);

        assertThatThrownBy(() -> service.previewWindow(요청자_주체, "s-unavailable", 5000, 8000))
                .isInstanceOf(AuthUnavailableException.class);
    }

    /**
     * 태스크 4 구현자가 넘긴 주의를 <b>부르는 쪽에서</b> 잠근다. auth의 요청 칸이
     * {@code userId}·{@code streamerUserId} 둘인데, 여기서 <b>순서를 바꿔 넘기면</b>
     * 스트리머가 자기 방송을 못 보고 그 증상은 「권한 없음」으로 나온다 — 두 값이 서로 다른
     * 번호여야 이 시험이 무언가를 잰다.
     */
    @Test
    void auth에는_요청자와_스트리머_번호가_그_순서로_간다() {
        방송중인_방송을_넣는다("s-args", 스트리머_번호);
        given(자격창구.resolve(anyLong(), anyLong())).willReturn(ResolveResult.OWNER);

        service.previewWindow(요청자_주체, "s-args", 0, 10_000);

        verify(자격창구).resolve(요청자_번호, 스트리머_번호_숫자);
    }

    // ── ③ 기한이 안 지났나 — 자격 뒤다 ───────────────────────────────

    @Test
    void 기한이_지난_방송은_VodExpired다() {
        끝난_방송을_넣는다("s-expired", 스트리머_번호, Instant.now().minus(Duration.ofDays(1)));
        given(자격창구.resolve(anyLong(), anyLong())).willReturn(ResolveResult.OWNER);

        assertThatThrownBy(() -> service.previewWindow(요청자_주체, "s-expired", 0, 10_000))
                .isInstanceOf(VodExpiredException.class);
    }

    /**
     * <b>반대쪽이다.</b> 위 갈래만 있으면 판정을 「기한이 채워져 있나」로 줄이는 회귀
     * (즉 {@code < now()}를 지우는 것)가 안 잡힌다 — 라이브 방송은 기한이 NULL이라
     * 그 회귀에서도 멀쩡히 통과하기 때문이다. 끝났지만 <b>아직 안 지난</b> 방송이 그 창을 닫는다.
     */
    @Test
    void 기한이_아직_안_지난_방송은_통과한다() {
        끝난_방송을_넣는다("s-not-yet", 스트리머_번호, Instant.now().plus(Duration.ofDays(1)));
        조각을_넣는다("s-not-yet", 1, 4000, 4000, "uploaded", false);
        given(자격창구.resolve(anyLong(), anyLong())).willReturn(ResolveResult.OWNER);

        SegmentWindow 창 = service.previewWindow(요청자_주체, "s-not-yet", 5000, 8000);

        assertThat(창.segments()).extracting(StreamSegmentRow::seq).containsExactly(1L);
    }

    /**
     * 🔴 <b>순서 계약의 본체.</b> 남남이 만료된 방송을 물으면 404여야 한다 — 410이 나가면
     * 「있었는데 없어졌다」로 그 방송의 실재가 새고, 그러면 404 통합이 무의미해진다.
     * 만료 판정을 자격 확인 앞으로 옮기는 순간 이 갈래가 빨간불이다.
     */
    @Test
    void 자격이_없으면_만료여도_NotViewable이_먼저다() {
        끝난_방송을_넣는다("s-none-expired", 스트리머_번호, Instant.now().minus(Duration.ofDays(1)));
        given(자격창구.resolve(anyLong(), anyLong())).willReturn(ResolveResult.NONE);

        assertThatThrownBy(() -> service.previewWindow(요청자_주체, "s-none-expired", 0, 10_000))
                .isInstanceOfSatisfying(NotViewableException.class,
                        e -> assertThat(e.reason()).isEqualTo("relation_none"));
    }

    /**
     * 같은 순서 계약의 <b>다른 쪽 끝</b>이다. 위 갈래는 만료를 NONE 앞으로 옮기는 것만 잡는데,
     * 판정 불가(503)도 만료보다 먼저 나가야 한다 — auth가 죽은 동안 만료 여부를 알려 주는 것은
     * 「자격을 모르는 사람에게 답한다」는 같은 잘못이다.
     */
    @Test
    void 판정_불가면_만료여도_AuthUnavailable이_먼저다() {
        끝난_방송을_넣는다("s-unavail-expired", 스트리머_번호, Instant.now().minus(Duration.ofDays(1)));
        given(자격창구.resolve(anyLong(), anyLong())).willReturn(ResolveResult.UNAVAILABLE);

        assertThatThrownBy(() -> service.previewWindow(요청자_주체, "s-unavail-expired", 0, 10_000))
                .isInstanceOf(AuthUnavailableException.class);
    }

    // ── 식별자가 숫자가 아닐 때 — 조용한 장애라 로그가 유일한 발견 수단 ────────

    /**
     * 주인이 자기 방송을 못 보는데 화면에는 <b>「없는 방송」</b>이라고 나온다. 응답으로는
     * 영영 구분이 안 되므로 ERROR 로그가 유일한 발견 수단이다(PRD 성공 기준).
     *
     * <p>세 번째 단언이 핵심이다 — <b>값 자체는 안 찍는다.</b> 어떤 쓰레기가 왔는지가 아니라
     * 어느 방송이 아픈지가 진단이고, 이 값은 우리가 만든 것이 아니라 큐로 받은 것이다.
     */
    @Test
    void 비숫자_streamer_id는_NotViewable이고_ERROR가_남는다() {
        방송중인_방송을_넣는다("s-bad-streamer", 숫자가_아닌_스트리머);

        try (LogCaptor logs = new LogCaptor()) {
            assertThatThrownBy(() -> service.previewWindow(요청자_주체, "s-bad-streamer", 0, 10_000))
                    .isInstanceOfSatisfying(NotViewableException.class,
                            e -> assertThat(e.reason()).isEqualTo("streamer_id_not_numeric"));

            assertThat(logs.levelOf("clip.segment.identity_not_numeric")).isEqualTo(Level.ERROR);
            assertThat(logs.messages())
                    .anyMatch(m -> m.contains("reason=streamer_id_not_numeric")
                            && m.contains("streamId=s-bad-streamer"));
            assertThat(logs.messages()).as("값 자체는 안 찍는다")
                    .noneMatch(m -> m.contains(숫자가_아닌_스트리머));
        }

        verifyNoInteractions(자격창구);
    }

    /**
     * 우리가 발급·검증한 토큰이라 드문 갈래다. <b>갈래가 죽었는지가 아니라 살아 있는지를</b>
     * 그물이 지킨다 — 코드에 갈래가 있으면 잰다.
     */
    @Test
    void 비숫자_JWT_subject도_NotViewable이고_ERROR가_남는다() {
        방송중인_방송을_넣는다("s-bad-subject", 스트리머_번호);

        try (LogCaptor logs = new LogCaptor()) {
            assertThatThrownBy(() -> service.previewWindow(숫자가_아닌_주체, "s-bad-subject", 0, 10_000))
                    .isInstanceOfSatisfying(NotViewableException.class,
                            e -> assertThat(e.reason()).isEqualTo("subject_not_numeric"));

            assertThat(logs.levelOf("clip.segment.identity_not_numeric")).isEqualTo(Level.ERROR);
            assertThat(logs.messages())
                    .anyMatch(m -> m.contains("reason=subject_not_numeric")
                            && m.contains("streamId=s-bad-subject"));
            assertThat(logs.messages()).as("값 자체는 안 찍는다")
                    .noneMatch(m -> m.contains(숫자가_아닌_주체));
        }

        verifyNoInteractions(자격창구);
    }

    /**
     * ①(방송 조회)과 ⑤(만료 판정) 사이에는 <b>auth 왕복이 낀다</b>(최대 7초). 만료된 방송을
     * 지우는 배치가 붙는 날 그 창에서 행이 사라질 수 있고, 그때 조회는 0행을 받는다 —
     * <b>죽지 않는 것이 먼저다</b>({@code BroadcastRepository.isVodExpired} 주석).
     *
     * <p>가짜 자격 창구가 답하는 그 순간 행을 지워 <b>창을 실제로 재현한다.</b> 주석이 말하는
     * 가능성을 따져만 보지 않고 돌려 본 자리다.
     *
     * <p>이 갈래가 없으면 {@code Boolean.TRUE.equals(...)}를 primitive 언박싱으로 바꾸는
     * 회귀가 <b>영영 안 잡힌다</b> — 그 주입에서 나머지 열아홉이 전부 초록이었다(주입 15).
     */
    @Test
    void auth_왕복_중에_방송이_사라져도_죽지_않는다() {
        방송중인_방송을_넣는다("s-vanish", 스트리머_번호);
        조각을_넣는다("s-vanish", 1, 4000, 4000, "uploaded", false);
        given(자격창구.resolve(anyLong(), anyLong())).willAnswer(호출 -> {
            jdbc.update("DELETE FROM broadcasts WHERE stream_id = ?", "s-vanish");
            return ResolveResult.OWNER;
        });

        SegmentWindow 창 = service.previewWindow(요청자_주체, "s-vanish", 5000, 8000);

        assertThat(창.segments()).extracting(StreamSegmentRow::seq).containsExactly(1L);
    }

    // ── 구간 검증 — 컨트롤러가 아니라 여기다 ─────────────────────────

    /**
     * 🔴 <b>이 갈래가 POK-125(렌더 잡)를 막는 유일한 자리다.</b> 그 소비자는 컨트롤러를
     * 거치지 않고 이 메서드를 직접 부른다.
     *
     * <p>감사 1회차 실측: 조립기에 {@code assemble(조각들, 9000, 5000)}을 넣으면
     * <b>{@code complete=true}</b>가 나온다 — 뒤집힌 구간인데 「완전」이다. 조립기는 빈 결과만
     * 막았고 비지 않은 결과는 안 막았다.
     */
    @Test
    void 구간이_뒤집혔으면_InvalidRange다() {
        방송중인_방송을_넣는다("s-range", 스트리머_번호);
        given(자격창구.resolve(anyLong(), anyLong())).willReturn(ResolveResult.OWNER);

        assertThatThrownBy(() -> service.previewWindow(요청자_주체, "s-range", 9000, 5000))
                .isInstanceOfSatisfying(InvalidRangeException.class,
                        e -> assertThat(e.field()).isEqualTo("endMs"));
    }

    /** 길이 0 요청. {@code <}로 느슨하게 바꾸는 회귀를 잡는 유일한 갈래다. */
    @Test
    void 구간이_같으면_InvalidRange다() {
        방송중인_방송을_넣는다("s-degenerate", 스트리머_번호);
        given(자격창구.resolve(anyLong(), anyLong())).willReturn(ResolveResult.OWNER);

        assertThatThrownBy(() -> service.previewWindow(요청자_주체, "s-degenerate", 5000, 5000))
                .isInstanceOfSatisfying(InvalidRangeException.class,
                        e -> assertThat(e.field()).isEqualTo("endMs"));
    }

    @Test
    void 음수_시작이면_InvalidRange다() {
        방송중인_방송을_넣는다("s-negative", 스트리머_번호);
        given(자격창구.resolve(anyLong(), anyLong())).willReturn(ResolveResult.OWNER);

        assertThatThrownBy(() -> service.previewWindow(요청자_주체, "s-negative", -1, 5000))
                .isInstanceOfSatisfying(InvalidRangeException.class,
                        e -> assertThat(e.field()).isEqualTo("startMs"));
    }

    /** 상한이 없으면 8시간 방송 전체(약 7,200행)를 한 요청으로 당긴다. */
    @Test
    void 구간이_30분을_넘으면_InvalidRange다() {
        방송중인_방송을_넣는다("s-too-long", 스트리머_번호);
        given(자격창구.resolve(anyLong(), anyLong())).willReturn(ResolveResult.OWNER);

        assertThatThrownBy(() ->
                service.previewWindow(요청자_주체, "s-too-long", 0, SegmentQueryService.MAX_RANGE_MS + 1))
                .isInstanceOf(InvalidRangeException.class);
    }

    /**
     * 경계. {@code >}를 {@code >=}로 바꾸는 회귀를 잡는 <b>유일한</b> 갈래다
     * (계획 검증 m4). 조각을 하나 심어 두는 것은 「예외가 안 났다」만 재면 조회가 통째로
     * 망가져도 초록이기 때문이다.
     */
    @Test
    void 정확히_30분은_통과한다() {
        방송중인_방송을_넣는다("s-exactly", 스트리머_번호);
        조각을_넣는다("s-exactly", 1, 0, 4000, "uploaded", false);
        given(자격창구.resolve(anyLong(), anyLong())).willReturn(ResolveResult.OWNER);

        SegmentWindow 창 = service.previewWindow(요청자_주체, "s-exactly", 0, SegmentQueryService.MAX_RANGE_MS);

        assertThat(창.segments()).extracting(StreamSegmentRow::seq).containsExactly(1L);
    }

    /**
     * 🔴 <b>검증이 「있다」가 아니라 「먼저다」를 잰다.</b> 위 구간 갈래들은 전부 실재하는
     * 방송을 쓰므로, 검증을 방송 조회 <b>뒤로</b> 옮겨도 여섯이 다 초록이다. 없는 방송에
     * 틀린 구간을 넣었을 때 404가 아니라 400이 나오는 것이 그 순서를 못 박는다.
     *
     * <p>순서가 뒤집히면 형식 오류 하나가 <b>DB 조회 + auth 왕복(최대 7초)</b>을 태우고 나서야
     * 거절된다 — 그리고 그때 나가는 응답은 400이 아니라 404다.
     */
    @Test
    void 구간_검증이_방송_조회보다_먼저다() {
        assertThatThrownBy(() -> service.previewWindow(요청자_주체, "s-없는방송-게다가-구간도-틀림", 9000, 5000))
                .isInstanceOf(InvalidRangeException.class);

        verifyNoInteractions(자격창구);
    }

    // ── 통과했을 때 실제로 무엇이 실리나 ─────────────────────────────

    /**
     * 렌더 잡(POK-125) 몫이다 — <b>{@code s3Key}가 실려 있어야</b> 그 소비자가 조각을 내려받는다.
     * 사람용 응답에서 빼는 것은 태스크 6이고, 여기서 빠지면 그 소비자는 손쓸 방법이 없다.
     *
     * <p>record를 <b>통째로</b> 단언하는 이유는 여섯 칸이 한 번에 잠기기 때문이다. 특히
     * {@code discontinuity}는 판정에 안 쓰이고 <b>값이 응답까지 보존되는 것 자체가 요구사항</b>이라
     * (PRD 성공 기준 6번) 여기서만 잠긴다 — 감사 1회차 B1-a가 「계획 전체에 이 칸을 닫는
     * 갈래가 없다」고 지적한 자리다. 그래서 두 방향(false·true)을 다 심는다.
     */
    @Test
    void 통과하면_창이_조립돼_s3Key까지_실려_있다() {
        방송중인_방송을_넣는다("s-full", 스트리머_번호);
        조각을_넣는다("s-full", 1, 4000, 4000, "uploaded", false);   // [4000,8000)
        조각을_넣는다("s-full", 2, 8000, 4000, "uploaded", true);    // [8000,12000) 재연결 직후
        given(자격창구.resolve(anyLong(), anyLong())).willReturn(ResolveResult.OWNER);

        SegmentWindow 창 = service.previewWindow(요청자_주체, "s-full", 5000, 11_000);

        assertThat(창.segments()).containsExactly(
                new StreamSegmentRow(1L, 4000, 4000, "seg/1", "uploaded", false),
                new StreamSegmentRow(2L, 8000, 4000, "seg/2", "uploaded", true));
        assertThat(창.complete()).isTrue();
        assertThat(창.availableFromMs()).isEqualTo(4000);
        assertThat(창.availableUntilMs()).isEqualTo(12_000);
    }

    // ── 도우미 ──────────────────────────────────────────────────

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
        jdbc.update("""
                        INSERT INTO stream_segments
                            (stream_id, seq, start_pts_ms, start_wall_utc, duration_ms,
                             s3_key, upload_state, is_discontinuity)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                streamId, seq, startPtsMs, 아무_UTC_시각, durationMs,
                "seg/" + seq, state, discontinuity);
    }
}
