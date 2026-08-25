package com.pokeclip.chat.detector.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * 판정에 쓰는 값 전부. <b>코드에 상수로 박지 않는다</b> — 기준값이 멘토 협업 미결이라
 * 확정되면 값만 갈아 끼워야 한다(POK-138 완료 조건).
 *
 * @param metric 판정에 쓰는 지표. 말한 사람 수는 기능명세 C7(M5)이라 이번엔 집계만 한다
 */
@ConfigurationProperties(prefix = "pokeclip.detection")
@Validated
public record DetectionProperties(@NotNull Duration cycleInterval,
                                  @NotEmpty List<Long> windowSizesMs,
                                  @Positive long publishWindowMs,
                                  /*
                                   * 🔴 우리 구간 지연에 그대로 더해진다. 목표 3초 중 2초를
                                   * 여기서 쓰므로 비중이 크고, 실측 없이 고정하면 안 된다.
                                   * 조정 근거는 detect.late_arrivals 로그가 만든다.
                                   */
                                  @NotNull Duration windowGrace,
                                  @NotNull Duration lateReportInterval,
                                  @NotNull Duration activeStreamWindow,
                                  /*
                                   * 한 바퀴가 되돌아보며 다시 집계하는 기간. baselineWindow와
                                   * 다른 값이다 — 같게 두면 100 방송에서 1초 주기를 못 지킨다(F8).
                                   */
                                  @NotNull Duration collectLookback,
                                  @NotNull Duration baselineWindow,
                                  @Min(1) int warmupWindows,
                                  @Positive double spikeRatio,
                                  @Min(1) int minCount,
                                  @NotNull Metric metric,
                                  @NotNull Duration retention) {

    public enum Metric { MESSAGE, CHATTER }

    /**
     * 발행 창이 집계 창 목록에 없으면 발행할 줄이 영영 안 생긴다 — 서버는 뜨고 집계도
     * 돌지만 카드가 하나도 안 나간다. 조용히 실패하는 자리라 부팅에서 막는다.
     */
    public DetectionProperties {
        // @NotEmpty는 목록이 비었는지만 본다 — 원소는 안 본다. 0이 섞이면 WindowGrid.floorTo의
        // 나눗셈이 ArithmeticException(/ by zero)으로 터지고, 그 자리는 태스크 6의 @Scheduled
        // 안이라 판정이 통째로 멈춘다. 음수는 눈금을 거꾸로 뒤집는다. 부팅에서 막는 편이 낫다.
        // (감사 1회차 B-2. 실측: `0,5000` + publish=5000이면 아래 contains 검사를 그냥 통과한다.)
        for (Long size : windowSizesMs) {
            if (size == null || size <= 0) {
                throw new IllegalArgumentException(
                        "window-sizes-ms의 원소는 1 이상이어야 한다: " + windowSizesMs);
            }
        }
        if (!windowSizesMs.contains(publishWindowMs)) {
            throw new IllegalArgumentException(
                    "publish-window-ms(" + publishWindowMs + ")가 window-sizes-ms에 없다: " + windowSizesMs);
        }
    }
}
