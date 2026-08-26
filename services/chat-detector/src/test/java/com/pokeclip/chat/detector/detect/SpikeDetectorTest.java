package com.pokeclip.chat.detector.detect;

import com.pokeclip.chat.detector.config.DetectionProperties;
import com.pokeclip.chat.detector.config.DetectionProperties.Metric;
import com.pokeclip.chat.detector.metrics.MetricRow;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpikeDetectorTest {

    /**
     * 배율 3.0 · 최소 10건 · 워밍업 5창.
     *
     * <p>위치 기반 생성자라 {@code DetectionProperties}에 칸이 늘면 여기가 컴파일 오류다.
     * 이 생성자를 부르는 자리가 셋이다 — 여기 둘과 {@code HighlightPublisherTest} 하나.
     */
    private static DetectionProperties props(Metric metric) {
        return new DetectionProperties(
                Duration.ofSeconds(1), List.of(5_000L), 5_000L,
                Duration.ofSeconds(2), Duration.ofMinutes(10),
                Duration.ofSeconds(60), Duration.ofMinutes(1), Duration.ofMinutes(15),
                5, 3.0, 10, metric, Duration.ofHours(24));
    }

    private static final DetectionProperties PROPS = props(Metric.MESSAGE);

    private final SpikeDetector detector = new SpikeDetector(PROPS);

    private static MetricRow 창(int messageCount, int chatterCount) {
        return new MetricRow("s1", 5_000L, 100_000L, messageCount, chatterCount);
    }

    /**
     * 카드의 핵심 예시다. 평소 2건이던 채널이 4건이 되면 배율은 2배지만 절대 건수가 모자라
     * 잡히면 안 된다 — 작은 채널이 늘 걸리는 것을 막는 장치다(POK-138).
     */
    @Test
    void 두건에서_네건은_안_잡힌다() {
        SpikeVerdict verdict = detector.judge(창(4, 3), new int[]{2, 2, 1, 2, 2});

        assertThat(verdict.spike()).isFalse();
        assertThat(verdict.reason()).isEqualTo("below_min_count");
    }

    @Test
    void 배율과_건수를_둘_다_넘으면_잡힌다() {
        SpikeVerdict verdict = detector.judge(창(40, 25), new int[]{10, 12, 11, 10, 9});

        assertThat(verdict.spike()).isTrue();
        assertThat(verdict.ratio()).isEqualTo(4.0);
        assertThat(verdict.baselineMedian()).isEqualTo(10.0);
    }

    /** 건수는 넘는데 배율이 모자란 경우. 큰 채널이 늘 걸리는 것을 막는 쪽이다. */
    @Test
    void 건수만_넘고_배율이_모자라면_안_잡힌다() {
        SpikeVerdict verdict = detector.judge(창(120, 80), new int[]{100, 110, 105, 100, 95});

        assertThat(verdict.spike()).isFalse();
        assertThat(verdict.reason()).isEqualTo("below_ratio");
    }

    /**
     * 워밍업. 창이 모자라면 "평소"를 말할 수 없다 — 기준선이 0에 가까워 아무 채팅이나
     * 무한대 배율이 된다(연구노트 6절, 확정 정책).
     */
    @Test
    void 창이_모자라면_판정하지_않는다() {
        SpikeVerdict verdict = detector.judge(창(9999, 500), new int[]{1, 1, 1, 1});

        assertThat(verdict.spike()).isFalse();
        assertThat(verdict.reason()).isEqualTo("warming_up");
    }

    /**
     * 🔴 기준선이 0이면 나눗셈이 무한대가 되므로 배율로는 아무것도 못 거른다 —
     * 최소 건수만이 유일한 그물이다.
     *
     * <p><b>이 입력은 실제 조회가 만들 수 없다</b>(계획 검증 F6 곁가지). 표의 모든 줄이
     * {@code message_count >= 1}이라 {@code baselineCounts}가 0만 든 배열을 돌려줄 길이 없다.
     * 그래도 남긴다 — 판정기는 순수 계산이고, 나중에 「조용한 창을 0으로 센다」로 정책이
     * 바뀌면 <b>이 갈래가 바로 살아난다.</b>
     */
    @Test
    void 기준선이_0이어도_최소_건수는_지킨다() {
        assertThat(detector.judge(창(5, 4), new int[]{0, 0, 0, 0, 0}).spike()).isFalse();
        assertThat(detector.judge(창(50, 30), new int[]{0, 0, 0, 0, 0}).spike()).isTrue();
    }

    /** 판정 근거가 카드에 실려야 한다 — 편집자가 왜 잡혔는지 안다(POK-138 완료 조건). */
    @Test
    void 판정_근거에_배수와_건수가_실린다() {
        String json = detector.judge(창(40, 25), new int[]{10, 12, 11, 10, 9}).evidenceJson(PROPS);

        assertThat(json)
                .contains("\"messageCount\":40")
                .contains("\"chatterCount\":25")
                .contains("\"baselineMedian\":10.0")
                .contains("\"ratio\":4.0")
                .contains("\"thresholdRatio\":3.0")
                .contains("\"thresholdMinCount\":10")
                .contains("\"metric\":\"MESSAGE\"")
                .contains("\"windowSizeMs\":5000");
    }

    /** 지표를 설정으로 바꾸면 판정도 바뀐다. 지금은 안 쓰지만 A/B의 준비다. */
    @Test
    void 지표를_말한_사람_수로_바꾸면_그것으로_판정한다() {
        // 메시지는 50건이지만 말한 사람은 1명 — 1인 도배다. 사람 수 기준이면 안 잡힌다.
        assertThat(new SpikeDetector(props(Metric.CHATTER)).judge(창(50, 1), new int[]{2, 2, 2, 2, 2}).spike())
                .isFalse();
    }
}
