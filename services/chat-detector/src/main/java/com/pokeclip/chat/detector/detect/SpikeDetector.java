package com.pokeclip.chat.detector.detect;

import com.pokeclip.chat.detector.config.DetectionProperties;
import com.pokeclip.chat.detector.metrics.MetricRow;
import org.springframework.stereotype.Component;

/**
 * 급증인지 판정한다. <b>순수 계산이다</b> — DB도 시계도 안 본다.
 *
 * <p>두 조건을 <b>동시에</b> 만족해야 한다(POK-138). 배율만 보면 작은 채널에서 2건→4건이
 * "2배 급증"으로 잡히고, 건수만 보면 큰 채널이 늘 걸린다.
 */
@Component
public class SpikeDetector {

    private final DetectionProperties props;

    public SpikeDetector(DetectionProperties props) {
        this.props = props;
    }

    /**
     * @param baselineCounts 이 창 <b>이전</b>의 같은 창 크기 값들. 지금 창은 포함하지 않는다 —
     *                       포함하면 자기가 자기 기준선을 올려 큰 스파이크일수록 덜 잡힌다
     */
    public SpikeVerdict judge(MetricRow row, int[] baselineCounts) {
        int count = value(row);

        if (baselineCounts.length < props.warmupWindows()) {
            return verdict(false, "warming_up", 0.0, 0.0, row, count);
        }

        double median = Baseline.median(baselineCounts);

        // 🔴 최소 건수를 배율보다 먼저 본다. 기준선이 0이면 배율이 무한대가 되어
        // 아무것도 못 거르고, 그때 유일한 그물이 이것이다.
        if (count < props.minCount()) {
            return verdict(false, "below_min_count", ratio(count, median), median, row, count);
        }
        if (median > 0 && count < props.spikeRatio() * median) {
            return verdict(false, "below_ratio", ratio(count, median), median, row, count);
        }
        return verdict(true, "spike", ratio(count, median), median, row, count);
    }

    /** 지표를 설정으로 고른다. 말한 사람 수는 기능명세 C7(M5)이라 기본값이 아니다. */
    private int value(MetricRow row) {
        return switch (props.metric()) {
            case MESSAGE -> row.messageCount();
            case CHATTER -> row.chatterCount();
        };
    }

    /** 기준선이 0이면 배율이 정의되지 않는다. 0을 실어 보낸다 — 무한대는 JSON에 못 싣는다. */
    private static double ratio(int count, double median) {
        return median > 0 ? count / median : 0.0;
    }

    private static SpikeVerdict verdict(boolean spike, String reason, double ratio, double median,
                                        MetricRow row, int count) {
        return new SpikeVerdict(spike, reason, ratio, median, row.windowSizeMs(),
                row.messageCount(), row.chatterCount());
    }
}
