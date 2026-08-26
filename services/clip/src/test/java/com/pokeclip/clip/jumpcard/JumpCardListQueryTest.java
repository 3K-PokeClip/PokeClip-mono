package com.pokeclip.clip.jumpcard;

import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.TestIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 카드 목록 한 장을 뽑는 조회. <b>재는 것은 넷이다</b> — 정렬 · 방송 거르기 · 숨김 거르기 ·
 * 이어받기 경계.
 *
 * <p>여기엔 자격 판정이 없다. 「이 사람이 이 방송을 볼 수 있나」는 {@code BroadcastAccessGuard}가
 * auth에 물어 정하고, 이 조회는 <b>받은 방송 이름으로만</b> 거른다. 두 자리를 갈라 두면 조회가
 * 「남의 방송 카드를 못 섞는다」를 auth 없이 잴 수 있다({@code BroadcastListQueryTest}와 같은 구조).
 *
 * <p>🔴 <b>정렬이 {@code event_seq}가 아니라 {@code stream_timestamp_ms}인 것을 재는 자리가
 * {@code 카드를_숨겨도_이어받기_순서가_안_바뀐다}다.</b> 순번은 트리거가 <b>바뀔 때마다</b> 올리므로
 * 카드를 숨기면 그 카드가 목록 맨 뒤로 밀린다 — 방송 시간은 쓰기 경로가 안 건드린다.
 */
class JumpCardListQueryTest extends IntegrationTestSupport {

    /** 남의 방송 카드가 섞이는지 보려고 두는 다른 방송. */
    private static final String 남의_방송 = "s-other";

    private static final String 내_방송 = "s-mine";

    private static final Instant 시작_시각 = Instant.parse("2026-08-25T00:00:00Z");

    private static final int 넉넉한_상한 = 20;

    private final JumpCardRepository cards;
    private final JdbcTemplate jdbc;

    JumpCardListQueryTest(JumpCardRepository cards, JdbcTemplate jdbc) {
        this.cards = cards;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 앞_테스트의_흔적을_지운다() {
        방송과_카드를_비운다(jdbc);
        방송을_넣는다(내_방송);
        방송을_넣는다(남의_방송);
    }

    // ── 정렬 ────────────────────────────────────────────────────

    /**
     * 방송 앞쪽이 위다. {@code containsExactly}라 순서가 뒤집히면 빨간불이다 —
     * {@code containsExactlyInAnyOrder}로 느슨하게 두면 {@code ORDER BY}가 통째로 사라져도 초록이다.
     *
     * <p>심는 순서를 방송 시간 순과 <b>다르게</b> 둔다. 같은 순서로 심으면 줄 번호({@code id})로
     * 정렬해도 같은 답이 나와 정렬 키가 무엇인지 못 가른다.
     */
    @Test
    void 방송_시간_오름차순으로_나온다() {
        카드를_넣는다(내_방송, 3000, "auto");
        카드를_넣는다(내_방송, 1000, "auto");
        카드를_넣는다(내_방송, 2000, "auto");

        assertThat(방송시간(첫장(넉넉한_상한))).containsExactly(1000L, 2000L, 3000L);
    }

    // ── 방송 거르기 ─────────────────────────────────────────────

    /**
     * 🔴 <b>내 카드가 나오는 것과 남의 카드가 안 나오는 것을 같은 갈래에서 잰다.</b>
     * 남의 카드만 심고 「비었다」를 재면 조회가 통째로 0행을 내도 초록이다.
     */
    @Test
    void 다른_방송의_카드는_안_나온다() {
        long 내것 = 카드를_넣는다(내_방송, 1000, "auto");
        카드를_넣는다(남의_방송, 2000, "auto");

        assertThat(줄번호(첫장(넉넉한_상한))).containsExactly(내것);
    }

    // ── 숨김 거르기 ─────────────────────────────────────────────

    /** 안 숨긴 카드를 <b>같이 심는다</b> — 안 심으면 「숨긴 것이 없다」가 빈 결과에서 자동으로 참이다. */
    @Test
    void 숨긴_카드는_기본으로_빠진다() {
        long 보이는_것 = 카드를_넣는다(내_방송, 1000, "auto");
        long 숨긴_것 = 카드를_넣는다(내_방송, 2000, "auto");
        숨긴다(숨긴_것);

        assertThat(줄번호(첫장(넉넉한_상한))).containsExactly(보이는_것);
    }

    /**
     * 숨김은 <b>표시 여부이지 삭제가 아니다</b> — 편집자가 「숨긴 것도 보기」를 켜면 되돌릴 수
     * 있어야 한다. 위 갈래의 짝이라 둘이 같이 있어야 {@code includeHidden}이 실제로 갈린다.
     */
    @Test
    void 달라고_하면_숨긴_카드도_나온다() {
        long 보이는_것 = 카드를_넣는다(내_방송, 1000, "auto");
        long 숨긴_것 = 카드를_넣는다(내_방송, 2000, "auto");
        숨긴다(숨긴_것);

        assertThat(줄번호(cards.findPage(내_방송, true, null, null, 넉넉한_상한)))
                .containsExactly(보이는_것, 숨긴_것);
    }

    // ── 이어받기 ────────────────────────────────────────────────

    /**
     * 🔴 <b>{@code id} tie-break를 재는 유일한 자리.</b> 자동으로 잡힌 것과 핫키로 잡힌 것이 같은
     * {@code stream_timestamp_ms}를 가질 수 있다 — {@code uq_jump_cards_window}가
     * {@code (stream_id, source, window_start_ms)}라 막지 않는다.
     *
     * <p>한 장에 하나만 담기게 잘라, 그 둘이 <b>첫 장의 끝과 다음 장의 처음</b>에 오도록 만든다.
     * 정렬·이어받기가 방송 시간 하나뿐이면 여기서 <b>같은 카드가 두 번 나오거나</b>(조건이 {@code >=})
     * <b>뒷줄이 통째로 빠진다</b>(조건이 {@code >}).
     */
    @Test
    void 같은_방송_시간을_가진_카드가_둘이어도_이어받기가_안_어긋난다() {
        long 자동 = 카드를_넣는다(내_방송, 1000, "auto");
        long 핫키 = 카드를_넣는다(내_방송, 1000, "hotkey");

        List<JumpCard> 첫장 = 첫장(1);
        assertThat(첫장).as("한 장에 다 들어갔다 — 이 시험은 이어받기를 안 재고 있다").hasSize(1);

        List<JumpCard> 둘째장 = 다음장(첫장.get(0), 1);
        List<Long> 합친것 = Stream.concat(첫장.stream(), 둘째장.stream()).map(JumpCard::getId).toList();

        // 중복도 누락도 한 번에 잡는다 — containsAll은 중복을 못 잡는다.
        assertThat(합친것).containsExactlyInAnyOrder(자동, 핫키);
    }

    /**
     * 🔴 <b>{@code event_seq}로 정렬·이어받기를 하면 여기서 어긋난다.</b> 숨김은 UPDATE라
     * {@code trg_jump_cards_touch}가 순번을 <b>새 값으로</b> 올린다 — 숨긴 카드가 목록 맨 뒤로 밀리고,
     * 이어받는 중이었다면 이미 지나온 자리로 되돌아온다.
     *
     * <p>{@code stream_timestamp_ms}는 쓰기 경로(점유·숨김)가 안 건드리므로 안 바뀐다.
     * <b>순번이 실제로 올라간 것을 먼저 확인한다</b> — 안 올라갔으면 이 갈래는 아무것도 안 재고 초록이다.
     */
    @Test
    void 카드를_숨겨도_이어받기_순서가_안_바뀐다() {
        long 첫째 = 카드를_넣는다(내_방송, 1000, "auto");
        long 둘째 = 카드를_넣는다(내_방송, 2000, "auto");
        long 셋째 = 카드를_넣는다(내_방송, 3000, "auto");

        long 숨기기_전_순번 = 순번(첫째);
        숨긴다(첫째);
        assertThat(순번(첫째)).as("순번이 안 올랐다 — 트리거가 안 돌았고 이 갈래는 아무것도 안 잰다")
                .isGreaterThan(숨기기_전_순번);

        assertThat(줄번호(cards.findPage(내_방송, true, null, null, 넉넉한_상한)))
                .containsExactly(첫째, 둘째, 셋째);
    }

    /**
     * 🔴 <b>첫 장을 받은 뒤에 심는다.</b> 미리 다 심어 두면 「보는 사이에 늘어난다」는 갈래를
     * 한 번도 안 탄다.
     *
     * <p>새 카드는 방송 시간이 가장 커서 <b>맨 뒤</b>에 붙는다 — 둘째 장에 그것이 섞여도
     * 이미 받은 장은 안 흔들린다. 그래서 재는 것은 「첫 장이 그대로인가」와 「중복·누락이 없나」다.
     */
    @Test
    void 이어받는_중에_새_카드가_생겨도_이미_받은_장이_안_흔들린다() {
        List<Long> 처음_스무개 = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            처음_스무개.add(카드를_넣는다(내_방송, i * 1000L, "auto"));
        }

        List<JumpCard> 첫장 = 첫장(10);
        // 한 장에 다 들어가면 이어받기를 한 번도 안 재고 아래가 전부 자동으로 참이 된다.
        assertThat(첫장).as("두 장으로 안 갈렸다 — 이 시험은 이어받기를 안 재고 있다").hasSize(10);

        long 나중에_생긴_것 = 카드를_넣는다(내_방송, 99_000, "auto");

        List<JumpCard> 둘째장 = 다음장(첫장.get(첫장.size() - 1), 10);
        List<Long> 합친것 = Stream.concat(첫장.stream(), 둘째장.stream()).map(JumpCard::getId).toList();

        assertThat(합친것).containsExactlyInAnyOrderElementsOf(처음_스무개);
        assertThat(합친것).as("맨 뒤에 붙은 새 카드가 둘째 장에 섞였다").doesNotContain(나중에_생긴_것);
    }

    // ── 알려진 함정 ─────────────────────────────────────────────

    /**
     * 🔴 <b>이 조회는 두 값이 함께 와야만 옳다.</b> {@code afterId}가 비면
     * {@code (stream_timestamp_ms = :afterTs AND id > NULL)}이 NULL로 평가돼 거짓이 되고,
     * <b>같은 방송 시간의 뒷줄이 조용히 빠진다</b>(계획 검증 실측).
     *
     * <p>그 사실을 여기 고정해 두는 이유는 <b>막는 자리가 여기가 아니기 때문</b>이다 —
     * 조회는 받은 값으로 성실히 답할 뿐이고, 「한 값만 든 카드 표시」를 거절하는 것은
     * {@code CursorCodec}의 칸 수 검사다({@code CursorCodecTest.카드_커서에_칸이_하나뿐이면_거절한다}).
     * 그 검사를 지우는 사람이 이 갈래를 보고 <b>무엇을 잃는지</b> 알 수 있어야 한다.
     *
     * <p>여기서 SQL을 「{@code afterId}가 없으면 방송 시간만 본다」로 고치지 마라 — 그러면
     * 같은 방송 시간의 <b>앞줄</b>이 두 번 나온다. 두 값이 함께 오는 것이 이 문의 계약이다.
     */
    @Test
    void afterId가_없으면_같은_방송_시간의_뒷줄이_빠진다() {
        카드를_넣는다(내_방송, 1000, "auto");
        카드를_넣는다(내_방송, 1000, "hotkey");
        long 뒤쪽 = 카드를_넣는다(내_방송, 2000, "auto");

        assertThat(줄번호(cards.findPage(내_방송, false, 1000L, null, 넉넉한_상한)))
                .as("두 값을 함께 주면 같은 방송 시간의 뒷줄도 나온다 — 그 갈래는 위 시험이 잰다")
                .containsExactly(뒤쪽);
    }

    // ── 도우미 ──────────────────────────────────────────────────

    private List<JumpCard> 첫장(int limit) {
        return cards.findPage(내_방송, false, null, null, limit);
    }

    /** 받은 줄의 <b>두 값</b>으로 이어받는다 — 카드 표시가 싣고 다니는 것이 정확히 이 둘이다. */
    private List<JumpCard> 다음장(JumpCard 마지막_줄, int limit) {
        return cards.findPage(내_방송, false, 마지막_줄.getStreamTimestampMs(), 마지막_줄.getId(), limit);
    }

    private static List<Long> 방송시간(List<JumpCard> rows) {
        return rows.stream().map(JumpCard::getStreamTimestampMs).toList();
    }

    private static List<Long> 줄번호(List<JumpCard> rows) {
        return rows.stream().map(JumpCard::getId).toList();
    }

    private long 순번(long id) {
        return jdbc.queryForObject("SELECT event_seq FROM jump_cards WHERE id = ?", Long.class, id);
    }

    /** 실제 문과 같은 SQL을 타야 트리거가 순번을 올린다 — 엔티티로 쓰면 그 경로를 안 지난다. */
    private void 숨긴다(long id) {
        jdbc.update("UPDATE jump_cards SET hidden_at = now(), hidden_by = '99' WHERE id = ?", id);
    }

    /**
     * 창은 방송 시간을 중심으로 잡는다 — {@code ck_jump_cards_ts_in_window}가
     * {@code window_start_ms <= stream_timestamp_ms <= window_end_ms}를 강제한다.
     * {@code source}를 받는 것은 {@code uq_jump_cards_window}가 {@code (방송, 출처, 창 시작)}이라
     * <b>같은 방송 시간을 두 번 심으려면 출처가 달라야</b> 하기 때문이다.
     */
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
