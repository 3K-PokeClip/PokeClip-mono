package com.pokeclip.clip.broadcast;

import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.TestIds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「지금 방송 중인 줄」 조회(POK-218 태스크 2). <b>재는 것은 다섯이다</b> — 상태 거르기 ·
 * 정렬 · 상한 · 리터럴과 열거형의 결속 · 빈 시각의 자리.
 *
 * <p>여기엔 자격 판정이 없다. 이 창구는 수집기(기계)가 부르고 <b>auth에 아무것도 안 묻는다</b> —
 * 그것을 재는 자리는 태스크 4의 창구 시험이다. 조회는 무조건 「방송 중인 것 전부」를 준다.
 *
 * <p>씨앗을 {@code jdbc}로 직접 심는다. 처리기를 태우면 봉투 검증이 끼어 <b>시각이 빈 줄을
 * 만들 수가 없고</b>, 그러면 {@code NULLS LAST}를 재는 갈래가 통째로 사라진다.
 */
class LiveBroadcastQueryTest extends IntegrationTestSupport {

    private static final Instant 기준_시각 = Instant.parse("2026-08-31T00:00:00Z");

    /**
     * 「심은 줄보다 큰 아무 수」다. <b>운영 상한(태스크 3)과 아무 관계가 없다</b> — 여기에
     * 500을 적으면 그 상한과 가짜 쌍둥이가 되어, 상한이 바뀌는 날 이 값도 고쳐야 하는 것처럼 보인다.
     */
    private static final int 넉넉한_상한 = 20;

    private final BroadcastRepository broadcasts;
    private final JdbcTemplate jdbc;

    LiveBroadcastQueryTest(BroadcastRepository broadcasts, JdbcTemplate jdbc) {
        this.broadcasts = broadcasts;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 앞_시험의_흔적을_지운다() {
        방송과_카드를_비운다(jdbc);
    }

    /**
     * 🔴 {@code @BeforeEach}만으로는 부족하다. 색인 시험이 2만 줄을 남기고 끝나면 <b>다음
     * 클래스가 그 줄 위에서 돈다</b> — 스스로 안 비우는 클래스는 조용히 틀린 답을 보고,
     * 그 실패는 단독 실행에서는 안 보이고 모듈 전체에서만 터진다(POK-118 선례).
     */
    @AfterEach
    void 대량_씨앗을_남기지_않는다() {
        방송과_카드를_비운다(jdbc);
    }

    // ── 상태 거르기 ──────────────────────────────────────────────

    /**
     * 🔴 <b>끝난 방송을 실제로 심는다</b> — 안 심으면 거르는 코드를 지워도 안 나오므로
     * 이 시험이 아무것도 안 잰다(문항 3).
     *
     * <p>{@code ended} 둘의 모양이 다른 것은 일부러다. 하나는 <b>종료 선도착 placeholder</b>
     * ({@code status='ended'}이고 {@code started_at IS NULL}, {@link Broadcast#endedPlaceholder})인데,
     * <b>상태 거르기가 무너졌을 때 가장 나쁜 줄이 정확히 이것</b>이다 — 시각이 비어 있어
     * {@code NULLS LAST}까지 함께 무너지면 목록의 맨 앞을 먹는다. 평범한 {@code ended}만
     * 심으면 그 조합을 이름대로 안 잰다.
     */
    @Test
    void 방송_중인_줄만_나온다() {
        방송을_넣는다("s-live-1", "live", 기준_시각);
        방송을_넣는다("s-live-2", "live", 기준_시각.plusSeconds(60));
        방송을_넣는다("s-ended", "ended", 기준_시각.minusSeconds(600));
        방송을_넣는다("s-ended-placeholder", "ended", null);
        방송을_넣는다("s-vod", "vod_ready", 기준_시각.minusSeconds(900));

        assertThat(방송이름(broadcasts.findLive(넉넉한_상한)))
                .containsExactly("s-live-2", "s-live-1");
    }

    // ── 정렬 ────────────────────────────────────────────────────

    /**
     * 최근 시작한 것이 위다. <b>일부러 뒤섞어 심는다</b> — 심은 순서가 기대 순서와 같으면
     * {@code ORDER BY}가 통째로 사라져도 초록일 수 있다(문항 3).
     *
     * <p>{@code containsExactly}라 순서가 뒤집히면 빨간불이다.
     */
    @Test
    void 최근_시작한_순서로_나온다() {
        방송을_넣는다("s-중간", "live", 기준_시각.minusSeconds(60));
        방송을_넣는다("s-가장_오래된", "live", 기준_시각.minusSeconds(600));
        방송을_넣는다("s-가장_최근", "live", 기준_시각);

        assertThat(방송이름(broadcasts.findLive(넉넉한_상한)))
                .containsExactly("s-가장_최근", "s-중간", "s-가장_오래된");
    }

    // ── 상한 ────────────────────────────────────────────────────

    /**
     * <b>상한보다 많이 심는다</b> — 적게 심으면 {@code LIMIT}을 지워도 초록이다(문항 3).
     *
     * <p>개수만 재지 않는다. 「셋이 왔다」와 「그 셋이 최근 것이다」는 다르므로,
     * <b>잘린 쪽에 있어야 할 줄을 이름으로 짚는다</b>(문항 4).
     */
    @Test
    void 상한만큼만_나온다() {
        for (int i = 0; i < 5; i++) {
            방송을_넣는다("s-" + i, "live", 기준_시각.minusSeconds(i * 60L));
        }

        List<String> 결과 = 방송이름(broadcasts.findLive(3));

        assertThat(결과).containsExactly("s-0", "s-1", "s-2");
        assertThat(결과)
                .as("개수만 재면 오래된 셋이 와도 통과한다 — 잘린 쪽을 이름으로 짚는다")
                .doesNotContain("s-3", "s-4");
    }

    // ── 리터럴과 열거형의 결속 ───────────────────────────────────

    /**
     * 🔴 <b>이 시험은 SQL 문자열을 직접 읽는다.</b> {@code dbValue()}가 {@code "live"}인지만
     * 보는 형태였다면 <b>쿼리 리터럴을 {@code 'LIVE'}로 바꿔도 초록</b>이라 아무것도 안 지킨다
     * (계획 검증 지적).
     *
     * <p>둘이 갈리면 조회가 <b>조용히 0행</b>이 된다 — 예외도 로그도 없이 「방송 중인 방송이
     * 없다」로 보인다. 리터럴을 쓰는 대가가 정확히 이것이고, 여기가 그 대가를 갚는 자리다.
     */
    @Test
    void 상태_문자열이_열거형과_갈리지_않는다() throws NoSuchMethodException {
        String sql = BroadcastRepository.class
                .getMethod("findLive", int.class)
                .getAnnotation(Query.class)
                .value();

        assertThat(sql)
                .as("쿼리에 박힌 리터럴과 열거형이 갈리면 조회가 조용히 0행이 된다")
                .contains("status = '" + BroadcastStatus.LIVE.dbValue() + "'");
    }

    // ── 빈 시각의 자리 ───────────────────────────────────────────

    /**
     * 🔴 <b>운영 경로로는 이런 줄이 도달 불가다</b> — {@code LiveStartedAtNeverNullTest}가
     * 사슬 넷으로 재현해 고정했다. 그런데도 {@code NULLS LAST}를 넣는 이유는 <b>그 사슬의
     * 방어가 러너의 봉투 검증 한 줄뿐</b>이기 때문이다.
     *
     * <p>없으면 무엇이 되는가 — PostgreSQL은 {@code DESC}에서 {@code NULLS FIRST}가 기본이라
     * 시각이 빈 줄이 <b>맨 앞</b>에 온다. 상한이 500인데 그런 줄이 500개면 <b>진짜 방송이
     * 하나도 안 나가고</b>, 수집기는 조용히 아무 데도 안 붙는다. 넣는 비용은 0이다.
     *
     * <p>씨앗을 {@code jdbc}로 심는 것이 이 시험의 전제다 — 러너를 태우면 그 봉투가 버려져
     * 이런 줄을 <b>만들 수가 없다</b>.
     */
    @Test
    void 시각이_빈_줄은_맨_뒤로_간다() {
        방송을_넣는다("s-빈_시각", "live", null);
        방송을_넣는다("s-오래된", "live", 기준_시각.minusSeconds(600));
        방송을_넣는다("s-최근", "live", 기준_시각);

        assertThat(방송이름(broadcasts.findLive(넉넉한_상한)))
                .containsExactly("s-최근", "s-오래된", "s-빈_시각");
    }

    // ── 색인 ────────────────────────────────────────────────────

    /**
     * 🔴 <b>시간이 아니라 계획 문자열과 버퍼 수로 단언한다.</b> 시간·배율은 빠른 쪽이 잡음
     * 구간이라 흔들린다(POK-174 실측).
     *
     * <p>단언 셋이 각각 다른 것을 막는다.
     * <ul>
     *   <li><b>색인 이름</b> — 부분 색인을 아예 안 타는 것</li>
     *   <li><b>{@code Sort} 부재</b> — 색인을 타고도 정렬을 다시 하는 것. {@code NULLS LAST}가
     *       쿼리와 색인 중 <b>한쪽에만</b> 있으면 정확히 이 모양이 되고, 이름만 보는 단언은
     *       그것을 통과시킨다(계획 검증 지적)</li>
     *   <li><b>버퍼 천장</b> — 색인 이름이 나오면서도 표를 통째로 읽는 것
     *       (예: 비트맵 스캔 뒤 힙 접근). 이름과 {@code Sort}만으로는 안 걸린다</li>
     * </ul>
     *
     * <p><b>천장 50은 측정값이 아니라 경계다.</b> 실측은 색인이 있을 때 <b>5</b>, 지웠을 때
     * <b>250</b>(Seq Scan + Sort, {@code Rows Removed by Filter: 20000})이라 그 사이 어디든
     * 갈린다. 색인을 지우고 대조한 기록은 {@code _workspace/04_implementer_task2.md}에 있다 —
     * 시험 안에서 지웠다 되살리면 중간에 실패했을 때 <b>같은 JVM의 뒤 시험이 색인 없이 돈다</b>.
     *
     * <p>표를 키우는 것이 전제다 — 20줄짜리 표에서는 순차 스캔이 실제로 더 빨라 플래너가
     * 색인을 무시하고, 그러면 이 시험이 색인이 <b>있어도</b> 빨간불이 된다(문항 5).
     */
    @Test
    void 색인을_탄다() {
        대량으로_심는다(200, 20_000);
        jdbc.execute("ANALYZE broadcasts");

        String 계획 = 실행계획();

        assertThat(계획)
                .as("부분 색인을 안 타면 표 전체를 훑는다")
                .contains("Index Scan using idx_broadcasts_live_started_at");
        assertThat(계획)
                .as("색인을 타고도 Sort가 붙으면 NULLS LAST가 한쪽에만 있는 것이다")
                .doesNotContain("Sort");
        assertThat(버퍼_최대값(계획))
                .as("색인 이름이 나오면서도 표를 통째로 읽는 계획을 막는다 (실측: 색인 5 · 없으면 250)\n%s",
                        계획)
                .isLessThan(50);
    }

    // ── 도우미 ──────────────────────────────────────────────────

    /**
     * 실행 단계의 계획만 돌려준다 — {@code Planning:} 아래 버퍼는 계획 수립 비용이라 섞으면 안 된다.
     *
     * <p>🔴 여기 {@code LIMIT 500}은 <b>진짜 쌍둥이다.</b> 운영이 실제로 던질 상한을 그대로 재야
     * 계획이 같아진다 — 태스크 3이 상한 상수를 만들면 <b>이 숫자도 같이 본다</b>.
     */
    private String 실행계획() {
        List<String> lines = jdbc.queryForList("""
                        EXPLAIN (ANALYZE, BUFFERS)
                        SELECT stream_id AS streamId, streamer_id AS streamerId, started_at AS startedAt
                          FROM broadcasts
                         WHERE status = 'live'
                         ORDER BY started_at DESC NULLS LAST
                         LIMIT 500""")
                .stream().map(row -> String.valueOf(row.get("QUERY PLAN"))).toList();
        int planningAt = lines.indexOf("Planning:");
        return String.join("\n", planningAt < 0 ? lines : lines.subList(0, planningAt));
    }

    /** 맨 위 노드의 버퍼가 자식을 포함하므로 최대값이 곧 이 질의가 읽은 페이지 수다. */
    private static int 버퍼_최대값(String 계획) {
        Matcher m = Pattern.compile("(?:hit|read|dirtied|written)=(\\d+)").matcher(계획);
        int max = 0;
        while (m.find()) {
            max = Math.max(max, Integer.parseInt(m.group(1)));
        }
        return max;
    }

    /**
     * 한 문장에 {@code generate_series}로 심는다 — 줄마다 왕복하면 2만 줄에 시험이 분 단위가 된다.
     * 시작 시각을 {@code g}만큼 벌려 <b>전부 다르게</b> 둔다: 같으면 정렬이 안정적이지 않아
     * 색인을 타는지와 무관하게 순서가 흔들린다.
     */
    private void 대량으로_심는다(int live, int ended) {
        대량으로_심는다("live-", "live", live);
        대량으로_심는다("ended-", "ended", ended);
    }

    private void 대량으로_심는다(String 접두, String status, int count) {
        jdbc.update("""
                        INSERT INTO broadcasts (stream_id, streamer_id, status, started_at, last_sequence)
                        SELECT ? || g, ?, ?,
                               TIMESTAMPTZ '2026-08-31 00:00:00+00' - (g || ' seconds')::interval, 1
                          FROM generate_series(1, ?) g""",
                접두, TestIds.STREAMER, status, count);
    }

    private static List<String> 방송이름(List<LiveBroadcastRow> rows) {
        return rows.stream().map(LiveBroadcastRow::getStreamId).toList();
    }

    private void 방송을_넣는다(String streamId, String status, Instant startedAt) {
        jdbc.update("""
                        INSERT INTO broadcasts
                            (stream_id, streamer_id, status, started_at, last_sequence)
                        VALUES (?, ?, ?, ?, 1)""",
                streamId, TestIds.STREAMER, status,
                startedAt == null ? null : OffsetDateTime.ofInstant(startedAt, ZoneOffset.UTC));
    }
}
