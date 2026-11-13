package com.pokeclip.chat.collector.sync;

import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import com.pokeclip.chat.collector.support.StreamSegmentsFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 판 2 후보 조회는 <b>서버 준비 문장으로 올라가지 않는다</b>(kty 2026-11-13 L3 (나)). pgjdbc 는 같은 문장을 다섯 번째
 * 실행부터 이름 붙은 서버 준비 문장으로 올리고, 그러면 PG 가 t 값을 모르는 일반 계획을 쓸 수 있다 — 첫 판 2 PDT 바로 뒤
 * 시각에서 그 계획은 그 스트림 판 2 행 거의 전부를 읽는다(판 2 30만 행 7,249 버퍼 — {@code SegmentLedgerIndexTest} 기록).
 * 이 조회만 문장 단위로 그 승격을 끄므로 매번 이름 없는 문장 · 바인딩 값으로 세운 계획이다(같은 기록의 사용자 지정 계획 8
 * 버퍼).
 *
 * <p>실제 앱 경로({@link SegmentLedger} 빈)로 열두 번 부른다 — 승격 문턱(다섯 번)을 넘는다. 한 트랜잭션 안이라 같은 연결이고,
 * 그 연결의 {@code pg_prepared_statements}(드라이버가 이름 붙여 올린 문장도 보인다)에 이 조회가 없어야 한다. 대조로, 같은
 * 조회에 꼬리표 주석만 붙인 문장을 기본 설정의 {@link JdbcTemplate}으로 열두 번 부르면 올라가야 한다 — 측정이 승격을
 * 실제로 보는지 확인한다.
 *
 * <p><b>방송 번호 접두는 {@code prep-}다.</b>
 */
@SpringBootTest
@ActiveProfiles("test")
class SegmentLedgerPrepareTest extends IntegrationTestSupport {

    private static final Instant T0 = Instant.parse("2026-11-13T00:00:00Z");
    private static final String STREAM = "prep-v2";
    private static final int CALLS = 12;
    private static final String CONTROL_TAG = "/* prepare-control */";

    /**
     * 그 연결의 서버 준비 문장 가운데 판 2 후보 조회 꼴(확인 술어 조각)인 것을 센다. 드라이버는 {@code ?} 를 {@code $1 …} 로 바꾼
     * 글자를 올리므로 글자 그대로 견주지 않고 조각으로 찾는다. 첫 자리 표시는 대조 꼬리표를 넣을지(참) 뺄지(거짓)다.
     */
    private static final String PREPARED_COUNT = """
            SELECT count(*) FROM pg_prepared_statements
             WHERE statement LIKE '%min(e.playback_pdt)%'
               AND (statement LIKE '%prepare-control%') = ?
            """;

    private final SegmentLedger ledger;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    SegmentLedgerPrepareTest(SegmentLedger ledger, JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
    }

    @BeforeEach
    void 판_2_행을_세운다() {
        StreamSegmentsFixture.ensureTable(jdbc);
        StreamSegmentsFixture.clear(jdbc, STREAM);
        for (int k = 0; k < 5; k++) {
            StreamSegmentsFixture.insertV2(jdbc, STREAM, k + 1, k * 4000L, T0.plusMillis(k * 4000L - 200),
                    T0.plusMillis(k * 4000L), 4000, false);
        }
    }

    @Test
    void 판_2_후보_조회는_여러_번_불러도_서버_준비_문장으로_올라가지_않는다() {
        String control = SegmentLedger.FLOOR_BY_PLAYBACK_PDT + CONTROL_TAG;
        Timestamp at = Timestamp.from(T0.plusMillis(1));

        long[] counts = transaction.execute(status -> {
            for (int i = 0; i < CALLS; i++) {
                assertThat(ledger.floorByPlaybackPdt(STREAM, T0.plusMillis(1)))
                        .as("답은 그대로 — 첫 행").hasValueSatisfying(s -> assertThat(s.seq()).isEqualTo(1L));
                jdbc.query(control, (row, n) -> row.getLong("seq"), STREAM, at, STREAM, at);
            }
            return new long[]{
                    jdbc.queryForObject(PREPARED_COUNT, Long.class, false),
                    jdbc.queryForObject(PREPARED_COUNT, Long.class, true)};
        });

        assertThat(counts[1]).as("대조 — 기본 설정이면 %d번 부른 문장은 올라간다", CALLS).isEqualTo(1L);
        assertThat(counts[0]).as("판 2 후보 조회는 이름 없는 문장으로만 돈다").isZero();
    }
}
