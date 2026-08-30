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
    void 상태_문자열이_열거형과_갈리지_않는다() {
        assertThat(리포지터리_질의())
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
     * 🔴 <b>시간도 버퍼 수도 안 쓴다. 계획의 모양만 본다.</b> 시간·배율은 빠른 쪽이 잡음 구간이라
     * 흔들리고(POK-174 실측), <b>버퍼 수는 색인이 아니라 힙 물리 배치를 잰다</b>(감사 2회 재현).
     *
     * <p><b>버퍼 천장 50이 있었는데 뺐다.</b> 색인·질의와 <b>무관한</b> 주입에서 이 시험이 빨간불이
     * 났고 그때 계획은 완전히 정상이었다 — {@code Index Scan} · {@code Sort} 없음 · 깨진 것은
     * 버퍼 266 하나. 기제도 재현됐다: 같은 계획인데 <b>앞 작업이 남긴 빈자리</b>에 씨앗이 흩어져
     * 들어가면 4가 84가 된다. 즉 <b>거짓 경보를 내는 값</b>이었다.
     *
     * <p>🔴 <b>같은 뿌리가 계획의 모양까지 흔들 수 있다 — 그래서 씨앗을 새 파일에 심는다</b>
     * ({@link #표_파일을_새로_만든다()}). 흩어진 표 위에 씨앗을 심으면 플래너가 색인 순서로
     * 힙을 집는 값을 비싸게 보고 <b>{@code Bitmap Heap Scan} + {@code Sort}</b>로 넘어간다.
     * <b>그러면 남은 단언 둘 다 거짓 경보를 낸다.</b>
     *
     * <table border="1"><caption>씨앗 200/20,000 · 이 시험대에서 주입으로 잰 것</caption>
     * <tr><th>씨앗을 심기 직전의 표</th><th>계획</th><th>판정</th></tr>
     * <tr><td>새 파일(지금)</td><td>{@code Index Scan} · {@code Sort} 없음</td><td>초록</td></tr>
     * <tr><td>앞 줄이 남은 흩어진 표</td><td><b>{@code Bitmap Heap Scan} + {@code Sort}</b>
     *     ({@code Heap Blocks: exact=94})</td><td><b>빨간불</b></td></tr>
     * <tr><td>같은 표 + {@code TRUNCATE}</td><td>{@code Index Scan} · {@code Sort} 없음</td><td>초록</td></tr>
     * </table>
     *
     * <p>🔴 <b>정직하게 갈라 적는다 — 이 한 줄은 「지금 나는 실패」를 막는 것이 아니다.</b>
     * {@code @BeforeEach}가 표를 통째로 비우는 지금 경로로는 위 가운데 줄을 <b>재현하지 못했다</b>
     * (빈 페이지만 남은 표에 심으면 계획이 안 갈렸다). 막는 것은 <b>그 창</b>이다 —
     * 감사가 실제로 관측한 버퍼 <b>266</b>(정상은 3)은 씨앗이 이미 그만큼 흩어졌다는 뜻이고,
     * 흩어짐이 더 가면 위 가운데 줄이 된다. 비용은 한 줄이다.
     *
     * <p><b>대신 무엇으로 메웠나 — 계획을 리포지터리의 SQL로 뽑는다</b>({@link #실행계획()}).
     * 전에는 이 시험이 SQL을 손으로 다시 적어, {@code ORDER BY}를 {@code ASC}로 뒤집는 주입에서
     * 다른 6건이 빨간불인데 <b>이 시험만 초록</b>이었다(감사 2회 사소 2). 이제는 그 주입이
     * {@code Index Scan Backward}를 만들어 첫 단언이 깨진다 — <b>신호가 줄기는커녕 늘었다.</b>
     *
     * <p>남은 단언 둘이 각각 다른 것을 막는다.
     * <ul>
     *   <li><b>색인 이름</b> — 부분 색인을 아예 안 타는 것. {@code Seq Scan}도, 비트맵도
     *       ({@code Bitmap Index Scan on …}이라 <b>낱말이 다르다</b>) 여기서 걸린다</li>
     *   <li><b>{@code Sort} 부재</b> — 색인을 타고도 정렬을 다시 하는 것. {@code NULLS LAST}가
     *       쿼리와 색인 중 <b>한쪽에만</b> 있으면 정확히 이 모양이 된다(계획 검증 지적)</li>
     * </ul>
     *
     * <p>🔴 <b>「색인 이름이 나오면서도 표를 통째로 읽는 계획」을 만들어 보려다 못 만들었다</b>
     * (별도 postgres:17, 같은 씨앗 200/20,000). 색인의 {@code WHERE}를 지우거나 넓혀도
     * ({@code status <> 'vod_ready'}) 플래너가 색인을 아예 버리고 {@code Seq Scan}을 골라
     * <b>첫 단언이 먼저 깨진다.</b> 그래서 {@code Rows Removed by Filter} 단언도 안 넣었다 —
     * 이 씨앗에서는 <b>자동으로 참</b>이다. 「그런 계획이 없다」가 아니라 <b>「이 씨앗으로는
     * 못 만들었다」</b>가 정확한 문장이다.
     *
     * <p><b>{@code pg_stat_user_indexes}로 실제 호출을 재는 길도 재 봤고 안 골랐다.</b> 통계가
     * 백엔드 flush에 걸려 {@code pg_stat_clear_snapshot()}으로는 <b>3회 호출 뒤에도 0</b>이고
     * {@code pg_stat_force_next_flush()}를 부른 뒤에야 3이 보인다(실측). 그 함수는 <b>부른
     * 백엔드의 것만</b> 밀어내는데 풀에서 같은 커넥션이 나온다는 보장이 없어 간헐 실패가 된다.
     * 실기동은 커넥션이 하나뿐이라 그 방법을 쓸 수 있었다({@code _workspace/05_verifier_runtime.md}).
     *
     * <p>표를 키우는 것이 전제다 — 20줄짜리 표에서는 순차 스캔이 실제로 더 빨라 플래너가
     * 색인을 무시하고, 그러면 이 시험이 색인이 <b>있어도</b> 빨간불이 된다(문항 5).
     */
    @Test
    void 색인을_탄다() {
        표_파일을_새로_만든다();
        대량으로_심는다(200, 20_000);
        jdbc.execute("ANALYZE broadcasts");

        String 계획 = 실행계획();

        assertThat(계획)
                .as("부분 색인을 안 타면 표 전체를 훑는다%n%s", 계획)
                .contains("Index Scan using idx_broadcasts_live_started_at");
        assertThat(계획)
                .as("색인을 타고도 Sort가 붙으면 NULLS LAST가 한쪽에만 있는 것이다%n%s", 계획)
                .doesNotContain("Sort");
    }

    // ── 도우미 ──────────────────────────────────────────────────

    /**
     * 🔴 <b>{@code DELETE}는 줄만 지우고 페이지는 남긴다.</b> 남은 빈자리에 씨앗이 흩어져
     * 들어가면 <b>색인이 멀쩡한데도</b> 계획이 갈려 빨간불이 된다(위 표의 가운데 줄, 주입으로
     * 재현). {@code TRUNCATE}는 파일을 새로 만들어 그 창을 닫는다 — 이 시험의 입력이
     * <b>「앞 시험이 표를 어떻게 남겼나」에 안 걸리게</b> 하는 것이 요점이다.
     *
     * <p>{@code CASCADE}가 필요한 것은 {@code jump_cards}가 {@code broadcasts}를 FK로 참조하기
     * 때문이다 — 참조하는 표가 있으면 PostgreSQL이 {@code CASCADE} 없는 {@code TRUNCATE}를
     * 거부한다(<b>그 표가 비어 있어도 그렇다</b>). {@code @BeforeEach}가 방금 비운 뒤라
     * 여기서 새로 지워지는 줄은 없다.
     */
    private void 표_파일을_새로_만든다() {
        jdbc.execute("TRUNCATE broadcasts CASCADE");
    }

    /**
     * <b>리포지터리가 실제로 던지는 SQL이다</b> — 시험 안에 다시 적지 않는다. 베껴 두면
     * 리포지터리 쪽만 바뀌었을 때 이 파일이 <b>모른 채 초록</b>이 된다(감사 2회 사소 2:
     * {@code DESC}→{@code ASC} 주입에서 6건이 빨간불인데 색인 시험만 초록이었다).
     */
    private static String 리포지터리_질의() {
        try {
            return BroadcastRepository.class
                    .getMethod("findLive", int.class)
                    .getAnnotation(Query.class)
                    .value();
        } catch (NoSuchMethodException e) {
            throw new AssertionError("findLive가 사라졌거나 시그니처가 바뀌었다", e);
        }
    }

    /**
     * 실행 단계의 계획만 돌려준다 — {@code Planning:} 아래 줄은 계획 수립 비용이라 섞으면 안 된다.
     *
     * <p>🔴 <b>이름 있는 파라미터만 상한으로 바꾼다.</b> {@code EXPLAIN}은 {@code :limit}을 모른다.
     * 리포지터리가 그 이름을 안 쓰게 되는 날 치환이 빈손이 되고 <b>SQL 문법 오류로 빨간불</b>이라,
     * 조용히 옛 질의를 재는 일은 안 생긴다.
     *
     * <p>🔴 <b>상한을 {@link LiveBroadcastService#FETCH_ROWS}에서 끌어온다.</b> 여기 숫자를
     * 베끼면 운영 상한과 진짜 쌍둥이가 된다. 운영이 던지는 수가 상한(500)이 아니라 <b>상한+1</b>인
     * 것에도 뜻이 있다 — 「잘렸나」를 개수 질의 없이 보려고 한 줄을 더 받는다.
     *
     * <p><b>상한이 이 시험의 판정을 움직이지 않는 것도 쟀다</b> — 계획의 모양은 상한
     * 20~100,000에서 안 갈렸다(감사 2회가 별도 postgres에서 다시 확인). 그때 갈리던 것은
     * 버퍼 수였고, 그 단언은 위 이유로 이제 없다. <b>그래도 FETCH_ROWS를 쓰는 이유는</b>
     * 「운영과 같은 질의를 잰다」가 이 시험의 전제이기 때문이다.
     */
    private String 실행계획() {
        String sql = 리포지터리_질의().replace(":limit", String.valueOf(LiveBroadcastService.FETCH_ROWS));
        List<String> lines = jdbc.queryForList("EXPLAIN (ANALYZE) " + sql)
                .stream().map(row -> String.valueOf(row.get("QUERY PLAN"))).toList();
        int planningAt = lines.indexOf("Planning:");
        return String.join("\n", planningAt < 0 ? lines : lines.subList(0, planningAt));
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
