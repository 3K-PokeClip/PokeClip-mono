package com.pokeclip.chat.collector.sync;

import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import com.pokeclip.chat.collector.support.StreamSegmentsFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>{@code CHAT_SYNC_V2_LEAD_CORRECTION_MS}가 yml 자리 표시를 거쳐 실제 변환에 걸리는지</b>를 행동으로 잰다 —
 * 환경변수 → {@link SyncProperties} 바인딩 → 판 2 보정값 → 위치. {@code SyncOffsetBindingTest}와 같은 자세다: 계산기
 * 검사는 {@code new SyncProperties(…)}로 yml을 건너뛰고, {@code DocumentedEnvVarsTest}는 문서 쪽만 보므로 yml 줄이
 * 환경변수를 못 읽어도(차감이 조용히 0) 둘 다 초록이다.
 *
 * <p>숫자는 r13 판별 순서 예다 — 옛 보정 3,900 · 차감 150 · 판 2 첫 행 PDT 4,000 · 메시지 7,800. 차감이 걸리면 판 2 시각
 * 4,050 이 판 2 행 안이라 위치 4,050(seq 2)이고, 안 걸리면 판 2 시각 3,900 이 그 행 앞이라 옛 행 위치 3,900(seq 1)이다.
 *
 * <p><b>방송 번호 접두는 {@code bind-v2}다.</b> 이 컨텍스트는 {@code properties}가 달라 캐시가 갈린다 — 그것이 목적이다.
 */
@SpringBootTest(properties = {"CHAT_SYNC_OFFSET_MS=3900",
        "CHAT_SYNC_V2_LEAD_CORRECTION_MS=" + SyncV2CorrectionBindingTest.CORRECTION_MS})
@ActiveProfiles("test")
class SyncV2CorrectionBindingTest extends IntegrationTestSupport {

    /** yml 기본값(0)과 갈리는 값. */
    static final String CORRECTION_MS = "150";

    private static final Instant T0 = Instant.parse("2026-11-13T00:00:00Z");
    private static final String STREAM = "bind-v2";

    private final JdbcTemplate jdbc;

    /** <b>빈으로 받는다</b> — 직접 만들면 yml → {@link SyncProperties} 경로를 건너뛰어 아무것도 안 잰다. */
    private final VideoPositionCalculator calculator;
    private final SyncProperties properties;

    SyncV2CorrectionBindingTest(JdbcTemplate jdbc, VideoPositionCalculator calculator, SyncProperties properties) {
        this.jdbc = jdbc;
        this.calculator = calculator;
        this.properties = properties;
    }

    @BeforeEach
    void 섞인_장부를_세운다() {
        StreamSegmentsFixture.ensureTable(jdbc);
        StreamSegmentsFixture.clear(jdbc, STREAM);
        StreamSegmentsFixture.insert(jdbc, STREAM, 1, 0, T0, 4000, false);
        StreamSegmentsFixture.insertV2(jdbc, STREAM, 2, 4000, T0.plusMillis(3800), T0.plusMillis(4000), 4000, false);
    }

    @Test
    void 환경변수로_준_판_2_차감이_실제_변환에_걸린다() {
        VideoPosition position = calculator.locate(STREAM, null, T0.plusMillis(7800));

        assertThat(properties.v2OffsetFor(null))
                .as("yml 의 v2-lead-correction-ms 가 CHAT_SYNC_V2_LEAD_CORRECTION_MS 를 못 읽으면 3900 이다")
                .isEqualTo(3750L);
        assertThat(position.state()).isEqualTo(VideoPosition.State.CONVERTED);
        assertThat(position.positionMs()).as("차감이 안 걸렸다면 옛 행 3900 이다").isEqualTo(4050L);
        assertThat(position.segmentSeq()).isEqualTo(2L);
        assertThat(position.appliedOffsetMs()).isEqualTo(3750L);
    }
}
