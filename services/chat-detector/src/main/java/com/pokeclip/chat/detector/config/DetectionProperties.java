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

        // 🔴 @NotNull 은 값이 있는지만 본다 — 0이나 음수를 그대로 통과시킨다.
        // codex 가 retention 하나를 짚었고(0이면 sweep 이 매 주기 표를 통째로 비워
        // 기준선이 사라지는데 아무 오류도 없다), 같은 구멍이 나머지 여섯에도 있어
        // 전수를 세어 한꺼번에 닫는다. window-sizes-ms 에 0을 막은 것과 같은 계열이고,
        // 그때 한 자리만 막은 것이 여기까지 왔다.
        requirePositive("cycle-interval", cycleInterval);
        requirePositive("late-report-interval", lateReportInterval);
        requirePositive("active-stream-window", activeStreamWindow);
        requirePositive("collect-lookback", collectLookback);
        requirePositive("baseline-window", baselineWindow);
        requirePositive("retention", retention);

        // 🔴 window-grace 만 0을 허용한다. 「유예 없이 바로 집계」는 뜻이 서는 설정이고
        // 검사들이 실제로 그 값을 쓴다. 음수는 아직 안 닫힌 창을 집계하게 만들어 막는다.
        if (windowGrace.isNegative()) {
            throw new IllegalArgumentException("window-grace는 음수일 수 없다: " + windowGrace);
        }

        // ── 칸 사이의 관계 ────────────────────────────────────────────
        // 🔴 위까지는 칸 <b>하나하나</b>가 말이 되는지만 본다. 여기서부터는 <b>칸 사이</b>다.
        // 봇 리뷰 2판에서 codex 가 둘을 짚었고(워밍업·활성 창), 같은 모양을 세어 보니 넷이었다.
        // 넷 다 「서버는 뜨고 집계도 도는데 카드가 하나도 안 나가는」 조용한 실패라,
        // publish-window-ms 가 목록에 없을 때 막는 것과 같은 자리에서 막는다.

        // ① 기준선 기간에 워밍업이 안 들어가면 영영 warming_up 이다.
        //    기준선 조회는 그 기간 안의 창만 읽으므로 창 수가 물리적 상한이다.
        long 기준선에_들어가는_창 = baselineWindow.toMillis() / publishWindowMs;
        if (warmupWindows > 기준선에_들어가는_창) {
            throw new IllegalArgumentException(
                    "warmup-windows(" + warmupWindows + ")가 baseline-window에 들어가는 창 수("
                            + 기준선에_들어가는_창 + ")보다 크다 — 영영 워밍업이라 카드가 안 나간다");
        }

        // ② 활성 창이 「창이 닫히고 유예까지」보다 짧으면, 급증 뒤 조용해진 방송이
        //    그 창을 집계하기도 전에 활성 목록에서 빠진다. 유예는 튜닝하라고 만든 값이라
        //    (detect.late_arrivals 가 근거를 모은다) 실제로 밟히는 경로다.
        long 가장_긴_창 = windowSizesMs.stream().mapToLong(Long::longValue).max().orElseThrow();
        long 집계까지_걸리는_시간 = windowGrace.toMillis() + 가장_긴_창;
        if (activeStreamWindow.toMillis() < 집계까지_걸리는_시간) {
            throw new IllegalArgumentException(
                    "active-stream-window(" + activeStreamWindow + ")가 window-grace + 가장 긴 창("
                            + 집계까지_걸리는_시간 + "ms)보다 짧다 — 급증 뒤 조용해진 방송의 그 창이 유실된다");
        }

        // ③ 되돌아보는 폭이 가장 긴 창보다 짧으면 닫힌 창이 하나도 안 나와 집계가 0줄이다.
        if (collectLookback.toMillis() < 가장_긴_창) {
            throw new IllegalArgumentException(
                    "collect-lookback(" + collectLookback + ")이 가장 긴 창(" + 가장_긴_창
                            + "ms)보다 짧다 — 닫힌 창이 안 나와 집계가 0줄이 된다");
        }

        // ④ 보관 기간이 기준선 기간보다 짧으면 치우기가 기준선을 지운다.
        if (retention.compareTo(baselineWindow) < 0) {
            throw new IllegalArgumentException(
                    "retention(" + retention + ")이 baseline-window(" + baselineWindow
                            + ")보다 짧다 — 치우기가 기준선을 지운다");
        }
    }

    /** 0도 음수도 막는다. 어느 칸인지 이름을 실어야 부팅 실패에서 바로 찾는다. */
    private static void requirePositive(String name, Duration value) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + "은(는) 0보다 커야 한다: " + value);
        }
    }
}
