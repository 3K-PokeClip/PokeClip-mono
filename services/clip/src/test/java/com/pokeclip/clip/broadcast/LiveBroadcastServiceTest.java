package com.pokeclip.clip.broadcast;

import ch.qos.logback.classic.Level;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「한 번에 상한만큼만 주고, 잘렸으면 그 사실을 싣는다」(POK-218 태스크 3).
 *
 * <p><b>경계 셋을 다 잰다</b> — 상한 미만 · <b>정확히 상한</b> · 상한 초과. 가운데를 빼면
 * {@code >}와 {@code >=}가 안 갈리고, 그러면 방송이 정확히 500개인 정상 상황에서 창구가
 * <b>매번 「명부가 이상하다」고 운다</b>. 우는 신호가 평상시에 울면 아무도 안 본다.
 *
 * <p>🔴 <b>개수와 불리언만 재면 이 카드의 핵심을 통째로 놓친다.</b> 계획의 시험 넷을 글자
 * 그대로 두고 {@code subList(0, MAX_ROWS)}를 {@code subList(1, MAX_ROWS + 1)}로 바꾸면
 * (= 최근 것을 버리고 오래된 것을 남긴다) <b>넷 다 초록</b>이었다(계획 검증 실측). 그것이
 * PRD가 「이 카드가 고치려는 바로 그 실패」라 부른 모양이다 — 종료를 놓쳐 쌓인 옛 줄이
 * 자리를 다 먹고 진짜 방송이 잘린다. 그래서 {@link #잘리면_최근_시작한_것들이_남는다()}가
 * <b>남는 쪽과 잘린 쪽을 이름으로 짚는다</b>.
 *
 * <p>기대값을 {@link LiveBroadcastService#MAX_ROWS}에서 끌어온다 — 상한을 숫자로 베끼면
 * 상한이 바뀌는 날 씨앗만 그대로 남아 <b>경계를 안 넘는 시험</b>이 된다(그러면 잘림 코드를
 * 지워도 초록이다). <b>값의 정본은 커밋되는 {@code services/README.md}</b>이고,
 * {@code BroadcastListService.DEFAULT_LIMIT}이 같은 자리를 같은 방식으로 다룬다.
 *
 * <p>씨앗을 {@code jdbc}로 한 문장에 심는다 — 501줄을 줄마다 왕복하면 시험이 분 단위가 된다.
 */
class LiveBroadcastServiceTest extends IntegrationTestSupport {

    private static final String 로그_이름 = "clip.live_broadcasts.truncated";

    /** 상한과 아무 관계 없는 「적은 수」. 여기 상한 근처 값을 적으면 경계 시험과 헷갈린다. */
    private static final int 몇_줄_안_되는_수 = 3;

    private final LiveBroadcastService service;
    private final JdbcTemplate jdbc;

    LiveBroadcastServiceTest(LiveBroadcastService service, JdbcTemplate jdbc) {
        this.service = service;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 앞_시험의_흔적을_지운다() {
        방송과_카드를_비운다(jdbc);
    }

    /**
     * 🔴 501줄을 남기고 끝나면 <b>다음 클래스가 그 줄 위에서 돈다</b>. 그 실패는 단독
     * 실행에서는 안 보이고 모듈 전체에서만 터진다(POK-118 선례).
     */
    @AfterEach
    void 대량_씨앗을_남기지_않는다() {
        방송과_카드를_비운다(jdbc);
    }

    // ── 경계 셋 ─────────────────────────────────────────────────

    @Test
    void 상한_이하면_잘림이_거짓이다() {
        방송을_심는다(몇_줄_안_되는_수);

        LiveBroadcastPage page = service.list();

        assertThat(page.rows()).hasSize(몇_줄_안_되는_수);
        assertThat(page.truncated()).isFalse();
    }

    /**
     * 🔴 <b>이 갈래 하나가 {@code >}와 {@code >=}를 가른다.</b> 501줄과 3줄만 재면 둘 다
     * 통과하고, 그러면 방송이 정확히 상한만큼인 날 창구가 잘리지도 않았는데 잘렸다고 답한다.
     */
    @Test
    void 상한과_같으면_잘림이_거짓이다() {
        방송을_심는다(LiveBroadcastService.MAX_ROWS);

        LiveBroadcastPage page = service.list();

        assertThat(page.rows()).hasSize(LiveBroadcastService.MAX_ROWS);
        assertThat(page.truncated())
                .as("정확히 상한이면 잘린 것이 아니다 — 여기서 참이면 정상 상황이 매번 운다")
                .isFalse();
    }

    @Test
    void 상한을_넘으면_상한만큼만_주고_잘림이_참이다() {
        방송을_심는다(LiveBroadcastService.MAX_ROWS + 1);

        LiveBroadcastPage page = service.list();

        assertThat(page.rows()).hasSize(LiveBroadcastService.MAX_ROWS);
        assertThat(page.truncated()).isTrue();
    }

    // ── 무엇이 남는가 (PRD 성공 기준 10) ─────────────────────────

    /**
     * 🔴 <b>이 시험의 존재 이유는 「500개가 왔다」와 「그 500개가 최근 것이다」가 다르다는
     * 것이다.</b> 앞의 셋은 개수와 불리언만 보므로 잘리는 쪽이 뒤집혀도 전부 초록이다.
     *
     * <p>씨앗은 {@code s-1}이 가장 최근이고 번호가 클수록 오래됐다. 그래서 남아야 할 줄은
     * {@code s-1}(맨 앞) 부터 {@code s-500}(맨 뒤)까지이고, <b>{@code s-501}은 없어야 한다</b>.
     * 양 끝과 잘린 줄을 <b>이름으로</b> 짚는 이유 — 순서가 한 칸 밀리는 결함은 개수를
     * 안 바꾸므로 {@code hasSize}로는 영영 안 보인다.
     */
    @Test
    void 잘리면_최근_시작한_것들이_남는다() {
        방송을_심는다(LiveBroadcastService.MAX_ROWS + 1);
        String 가장_최근 = "s-1";
        String 마지막으로_남는_줄 = "s-" + LiveBroadcastService.MAX_ROWS;
        String 잘려야_하는_가장_오래된_줄 = "s-" + (LiveBroadcastService.MAX_ROWS + 1);

        List<String> 이름 = 방송이름(service.list().rows());

        assertThat(이름.get(0))
                .as("가장 최근 줄이 잘려 나가면 이 창구가 고치려는 바로 그 실패다")
                .isEqualTo(가장_최근);
        assertThat(이름.get(이름.size() - 1)).isEqualTo(마지막으로_남는_줄);
        assertThat(이름)
                .as("잘린 쪽을 이름으로 짚는다 — 개수만 재면 오래된 500개가 와도 통과한다")
                .doesNotContain(잘려야_하는_가장_오래된_줄);
    }

    // ── 잘림 로그 ───────────────────────────────────────────────

    /**
     * WARN인 이유는 이것이 울면 사람이 명부를 봐야 하기 때문이다. <b>안 잘렸을 때 안 우는
     * 것도 같이 잰다</b> — 늘 우는 신호는 아무도 안 보므로, 「운다」만 재면 절반만 잰 것이다.
     */
    @Test
    void 잘리면_로그가_남고_안_잘리면_안_남는다() {
        방송을_심는다(LiveBroadcastService.MAX_ROWS + 1);
        try (LogCaptor logs = new LogCaptor()) {
            service.list();

            assertThat(logs.levelOf(로그_이름)).isEqualTo(Level.WARN);
            assertThat(logs.messages())
                    .as("숫자가 없으면 무엇에 닿았는지 로그만 보고는 모른다")
                    .anyMatch(m -> m.startsWith(로그_이름)
                            && m.contains("limit=" + LiveBroadcastService.MAX_ROWS));
        }

        방송과_카드를_비운다(jdbc);
        방송을_심는다(몇_줄_안_되는_수);
        try (LogCaptor logs = new LogCaptor()) {
            service.list();

            assertThat(logs.levelOf(로그_이름))
                    .as("안 잘렸는데 울면 평상시에 우는 신호가 되고, 그러면 아무도 안 본다")
                    .isNull();
        }
    }

    // ── 도우미 ──────────────────────────────────────────────────

    /**
     * {@code s-1}이 가장 최근이고 번호가 클수록 오래됐다. 시작 시각을 {@code g}만큼 벌려
     * <b>전부 다르게</b> 둔다 — 같으면 정렬이 안정적이지 않아 무엇이 잘리는지가 흔들린다.
     */
    private void 방송을_심는다(int count) {
        jdbc.update("""
                        INSERT INTO broadcasts (stream_id, streamer_id, status, started_at, last_sequence)
                        SELECT 's-' || g, ?, 'live',
                               TIMESTAMPTZ '2026-08-31 00:00:00+00' - (g || ' seconds')::interval, 1
                          FROM generate_series(1, ?) g""",
                TestIds.STREAMER, count);
    }

    private static List<String> 방송이름(List<LiveBroadcastRow> rows) {
        return rows.stream().map(LiveBroadcastRow::getStreamId).toList();
    }
}
