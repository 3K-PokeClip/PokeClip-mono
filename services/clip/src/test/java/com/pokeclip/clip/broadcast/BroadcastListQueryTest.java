package com.pokeclip.clip.broadcast;

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
 * 방송 목록 한 장을 뽑는 조회. <b>재는 것은 넷이다</b> — 정렬 · 상태 거르기 · 스트리머 거르기 ·
 * 이어받기 경계.
 *
 * <p>여기엔 자격 판정이 없다. 「누구의 번호를 넘길 것인가」는 {@code BroadcastListService}가
 * auth에 물어 정하고, 이 조회는 <b>받은 번호로만</b> 거른다. 두 자리를 갈라 두면 조회가
 * 「목록에 없는 스트리머를 못 섞는다」를 auth 없이 잴 수 있다.
 */
class BroadcastListQueryTest extends IntegrationTestSupport {

    /** 남의 방송이 섞이는지 보려고 두는 다른 사람. {@link TestIds#STREAMER}와 달라야 한다. */
    private static final String 남 = "8";

    private static final Instant 시작_시각 = Instant.parse("2026-08-25T00:00:00Z");

    private static final int 넉넉한_상한 = 20;

    private final BroadcastRepository broadcasts;
    private final JdbcTemplate jdbc;

    BroadcastListQueryTest(BroadcastRepository broadcasts, JdbcTemplate jdbc) {
        this.broadcasts = broadcasts;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 앞_테스트의_흔적을_지운다() {
        방송과_카드를_비운다(jdbc);
    }

    // ── 정렬 ────────────────────────────────────────────────────

    /**
     * 최신이 위다. {@code containsExactly}라 순서가 뒤집히면 빨간불이다 —
     * {@code containsExactlyInAnyOrder}로 느슨하게 두면 {@code ORDER BY}가 통째로 사라져도 초록이다.
     */
    @Test
    void 줄_번호_내림차순으로_나온다() {
        방송을_넣는다("s-1", TestIds.STREAMER, "live");
        방송을_넣는다("s-2", TestIds.STREAMER, "live");
        방송을_넣는다("s-3", TestIds.STREAMER, "live");

        assertThat(내_라이브(null, 넉넉한_상한)).containsExactly("s-3", "s-2", "s-1");
    }

    // ── 상태 거르기 ──────────────────────────────────────────────

    /** 라이브를 <b>같이 심는다</b> — 안 심으면 「끝난 것이 없다」가 빈 결과에서 자동으로 참이다. */
    @Test
    void live만_고르면_끝난_방송이_안_나온다() {
        방송을_넣는다("s-live", TestIds.STREAMER, "live");
        방송을_넣는다("s-ended", TestIds.STREAMER, "ended");

        assertThat(내_라이브(null, 넉넉한_상한)).containsExactly("s-live");
    }

    /**
     * {@code vod_ready}는 아직 쓰는 코드가 없지만 표 제약에 있는 값이다 —
     * 쓰기 시작하는 날 「지난 방송」에서 조용히 빠지면 편집자에게는 방송이 사라진 것으로 보인다.
     */
    @Test
    void past는_ended와_vod_ready_둘_다다() {
        방송을_넣는다("s-live", TestIds.STREAMER, "live");
        방송을_넣는다("s-ended", TestIds.STREAMER, "ended");
        방송을_넣는다("s-vod", TestIds.STREAMER, "vod_ready");

        assertThat(방송이름(조회(List.of(TestIds.STREAMER), BroadcastState.PAST, null, 넉넉한_상한)))
                .containsExactly("s-vod", "s-ended");
    }

    // ── 스트리머 거르기 ──────────────────────────────────────────

    /**
     * 🔴 <b>내 것이 나오는 것과 남의 것이 안 나오는 것을 같은 갈래에서 잰다.</b>
     * 남의 방송만 심고 「비었다」를 재면 조회가 통째로 0행을 내도 초록이다.
     */
    @Test
    void 목록에_없는_스트리머의_방송은_안_나온다() {
        방송을_넣는다("s-mine", TestIds.STREAMER, "live");
        방송을_넣는다("s-others", 남, "live");

        assertThat(내_라이브(null, 넉넉한_상한)).containsExactly("s-mine");
    }

    // ── 이어받기 ────────────────────────────────────────────────

    /**
     * 경계 줄이 <b>두 장에 안 걸친다</b>. {@code id < :afterId}를 {@code <=}로 되돌리면
     * 첫 장의 마지막 줄이 둘째 장에 다시 나와 이 갈래가 빨간불이 된다.
     */
    @Test
    void 이어받으면_그_줄_다음부터_나온다() {
        방송을_넣는다("s-1", TestIds.STREAMER, "live");
        방송을_넣는다("s-2", TestIds.STREAMER, "live");
        long 셋째 = 방송을_넣는다("s-3", TestIds.STREAMER, "live");
        방송을_넣는다("s-4", TestIds.STREAMER, "live");

        assertThat(내_라이브(null, 2)).containsExactly("s-4", "s-3");
        assertThat(내_라이브(셋째, 2)).containsExactly("s-2", "s-1");
    }

    /**
     * 🔴 <b>첫 장을 받은 뒤에 심는다.</b> 미리 다 심어 두면 「보는 사이에 늘어난다」는
     * 갈래를 한 번도 안 탄다.
     *
     * <p>새 방송은 줄 번호가 가장 커서 <b>첫 장 위</b>에 들어간다 — 이어받기 기준이
     * 줄 번호라 둘째 장에는 안 섞인다. 시작·종료 시각으로 이어받았다면 이 자리에서
     * 어긋난다(그 값들은 뒤늦은 알림이 갱신한다).
     */
    @Test
    void 이어받는_중에_새_방송이_생겨도_중복도_누락도_없다() {
        List<String> 처음_스무개 = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            String 이름 = "s-%02d".formatted(i);
            방송을_넣는다(이름, TestIds.STREAMER, "live");
            처음_스무개.add(이름);
        }

        List<Broadcast> 첫장 = 조회(List.of(TestIds.STREAMER), BroadcastState.LIVE, null, 10);
        // 한 장에 다 들어가면 이어받기를 한 번도 안 재고 아래가 전부 자동으로 참이 된다.
        assertThat(첫장).as("두 장으로 안 갈렸다 — 이 시험은 이어받기를 안 재고 있다").hasSize(10);

        방송을_넣는다("s-new", TestIds.STREAMER, "live");

        List<Broadcast> 둘째장 = 조회(List.of(TestIds.STREAMER), BroadcastState.LIVE,
                첫장.get(첫장.size() - 1).getId(), 10);
        List<String> 합친것 = Stream.concat(첫장.stream(), 둘째장.stream())
                .map(Broadcast::getStreamId).toList();

        // 중복도 누락도 한 번에 잡는다 — containsAll은 중복을 못 잡는다.
        assertThat(합친것).containsExactlyInAnyOrderElementsOf(처음_스무개);
        assertThat(합친것).doesNotContain("s-new");
    }

    // ── 값이 비어 있는 줄 ────────────────────────────────────────

    /**
     * 종료 선도착 placeholder는 시작 시각이 비어 있다(ADR-016). 🔴 <b>정렬이 줄 번호라
     * 이 줄이 빠지거나 뒤로 밀리지 않는다</b> — {@code ORDER BY started_at}이었다면
     * NULL이 어디로 가느냐에 따라 자리가 바뀐다.
     */
    @Test
    void 시작_시각이_비어_있어도_목록에_나온다() {
        방송을_넣는다("s-normal", TestIds.STREAMER, "ended", 시작_시각);
        방송을_넣는다("s-placeholder", TestIds.STREAMER, "ended", null);

        List<Broadcast> 결과 = 조회(List.of(TestIds.STREAMER), BroadcastState.PAST, null, 넉넉한_상한);

        assertThat(방송이름(결과)).containsExactly("s-placeholder", "s-normal");
        assertThat(결과.get(0).getStartedAt()).as("빈 채로 나와야 한다 — 지어내지 않는다").isNull();
    }

    // ── 알려진 한계 ─────────────────────────────────────────────

    /**
     * 🔴 <b>이것은 결함이 아니라 고정된 한계다.</b> auth는 스트리머 번호를 <b>숫자</b>로 주고
     * 이 칸은 {@code VARCHAR}라, 부르는 쪽이 {@code String.valueOf(7)} = {@code "7"}로 바꿔 넣는다.
     * 그 변환은 관대하지 않아 {@code "007"}이 든 줄을 못 찾는다. 세그먼트 조회는 반대 방향
     * ({@code Long.parseLong("007")} = 7)이라 <b>같은 방송을 열어 준다</b> — 두 문의 판정이 갈린다.
     *
     * <p>🔴 <b>이 시험을 초록으로 만들려고 조회를 고치지 마라.</b> 뿌리는 clip이 아니라
     * <b>안 닫힌 계약</b>이다 — 방송 알림이 싣고 오는 스트리머 번호가 우리 회원 번호와 같은
     * 값인지가 아직 안 정해졌고 1번의 답을 기다리는 중이다. 지금 한쪽으로 맞추면 계약이
     * 다르게 나오는 날 맞춘 쪽을 다시 뜯는다. <b>갚는 조건은 「그 값의 정본이 정해지면」</b>이고,
     * 그때 세 문(목록·세그먼트·판정기)을 한꺼번에 맞춘다.
     *
     * <p>한계로 둘 수 있는 것은 <b>어긋나는 방향이 안전한 쪽</b>이어서다 — 목록에 <b>안 나오고</b>
     * 직접 열면 <b>열린다</b>. 반대였다면(목록에 나오는데 열면 거절) 이번에 고쳐야 했다.
     */
    @Test
    void 선행_0이_붙은_스트리머_번호는_못_찾는다() {
        방송을_넣는다("s-plain", TestIds.STREAMER, "live");
        방송을_넣는다("s-padded", "0" + TestIds.STREAMER, "live");

        assertThat(내_라이브(null, 넉넉한_상한))
                .as("선행 0이 붙은 줄이 갑자기 나온다면 계약이 닫힌 것이다 — 그때는 세 문을 함께 본다")
                .containsExactly("s-plain");
    }

    // ── 도우미 ──────────────────────────────────────────────────

    private List<String> 내_라이브(Long afterId, int limit) {
        return 방송이름(조회(List.of(TestIds.STREAMER), BroadcastState.LIVE, afterId, limit));
    }

    private List<Broadcast> 조회(List<String> streamerIds, BroadcastState state, Long afterId, int limit) {
        return broadcasts.findPage(streamerIds, state.dbValues(), afterId, limit);
    }

    private static List<String> 방송이름(List<Broadcast> rows) {
        return rows.stream().map(Broadcast::getStreamId).toList();
    }

    private long 방송을_넣는다(String streamId, String streamerId, String status) {
        return 방송을_넣는다(streamId, streamerId, status, 시작_시각);
    }

    /** {@code RETURNING id}로 줄 번호를 받는다 — 이어받기 기준이 그 값이라 시험도 그것을 써야 한다. */
    private long 방송을_넣는다(String streamId, String streamerId, String status, Instant startedAt) {
        Long id = jdbc.queryForObject("""
                        INSERT INTO broadcasts
                            (stream_id, streamer_id, status, started_at, last_sequence)
                        VALUES (?, ?, ?, ?, 1)
                        RETURNING id""",
                Long.class, streamId, streamerId, status,
                startedAt == null ? null : OffsetDateTime.ofInstant(startedAt, ZoneOffset.UTC));
        return id;
    }
}
