package com.pokeclip.chat.detector.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 부팅을 막는 검증 둘을 잰다. <b>둘 다 「조용히 실패하는 것」을 「시끄럽게 실패하는 것」으로
 * 바꾸는 장치</b>라, 지워도 서버는 멀쩡히 뜬다 — 그래서 그물이 없으면 아무도 모른다.
 * 감사 1회차가 실제로 그것을 실측했다(검증 블록을 지워도 25건 전부 초록, C-3).
 *
 * <p><b>여기는 생성자만 잰다.</b> 그 생성자가 <b>부팅에 실제로 걸리는지</b>는 다른 데서
 * 보증된다 — 이 record는 {@code @ConfigurationPropertiesScan}으로 실리고, 스프링 시험이
 * 스물넷이라 바인딩이 깨지면 그쪽이 통째로 빨개진다(감사 1회차 주입 J25·J26이 각각 18건을
 * 빨갛게 만든 것으로 확인됐다).
 */
class DetectionPropertiesTest {

    /** 검증 대상 칸만 인자로 받고 나머지는 운영 기본값으로 채운다. */
    private static DetectionProperties 설정(List<Long> windowSizesMs, long publishWindowMs) {
        return new DetectionProperties(Duration.ofSeconds(1), windowSizesMs, publishWindowMs,
                Duration.ofSeconds(2), Duration.ofMinutes(10), Duration.ofSeconds(60),
                Duration.ofMinutes(1), Duration.ofMinutes(15), 24, 3.0, 10,
                DetectionProperties.Metric.MESSAGE, Duration.ofHours(24));
    }

    @Test
    void 운영_기본값은_통과한다() {
        assertThatCode(() -> 설정(List.of(3_000L, 5_000L, 10_000L), 5_000L)).doesNotThrowAnyException();
    }

    /**
     * 발행 창이 집계 창 목록에 없으면 <b>발행할 줄이 영영 안 생긴다</b> — 서버는 뜨고 집계도
     * 돌지만 카드가 하나도 안 나간다. 로그도 안 남는다.
     */
    @Test
    void 발행_창이_집계_목록에_없으면_부팅을_막는다() {
        assertThatThrownBy(() -> 설정(List.of(3_000L, 5_000L, 10_000L), 7_000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("publish-window-ms(7000)")
                .hasMessageContaining("[3000, 5000, 10000]");
    }

    /**
     * 🔴 {@code 0}은 {@code @NotEmpty}를 통과하고 {@code contains(publishWindowMs)}도 통과한다 —
     * 목록에 발행 창만 들어 있으면 되기 때문이다. 그대로 두면 {@code WindowGrid.floorTo}의
     * 나눗셈이 터지는데, 그 자리는 태스크 6의 {@code @Scheduled} 안이라 <b>판정이 통째로 멈춘다.</b>
     */
    @Test
    void 창_크기에_0이_섞이면_부팅을_막는다() {
        assertThatThrownBy(() -> 설정(List.of(0L, 5_000L), 5_000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window-sizes-ms의 원소는 1 이상");
    }

    /** 음수는 눈금을 거꾸로 뒤집는다. 0과 같은 자리에서 막는다. */
    @Test
    void 창_크기가_음수여도_부팅을_막는다() {
        assertThatThrownBy(() -> 설정(List.of(-5_000L, 5_000L), 5_000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window-sizes-ms의 원소는 1 이상");
    }

    /** 목록에 null이 섞이는 것은 바인딩이 이상해졌다는 뜻이다. NPE 대신 읽을 수 있는 메시지로 죽는다. */
    @Test
    void 창_크기에_null이_섞여도_읽을_수_있는_메시지로_죽는다() {
        assertThatThrownBy(() -> 설정(Arrays.asList(null, 5_000L), 5_000L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window-sizes-ms의 원소는 1 이상");
    }

    /**
     * 칸 이름 하나로 {@code Duration} 칸 일곱 중 하나만 0으로 만든 설정. 나머지는 운영 기본값이다.
     *
     * <p><b>목록을 여기 한자리에 두는 것이 요점이다</b> — 칸이 늘 때 이 배열에 넣는 것을
     * 잊으면 아래 두 검사가 그 칸을 안 보게 되므로, 새 칸은 이 표에 같이 적는다.
     */
    private static DetectionProperties 시간칸을_바꾼_설정(String name, Duration value) {
        Duration cycle = Duration.ofSeconds(1), grace = Duration.ofSeconds(2),
                late = Duration.ofMinutes(10), active = Duration.ofSeconds(60),
                lookback = Duration.ofMinutes(1), baseline = Duration.ofMinutes(15),
                retention = Duration.ofHours(24);
        switch (name) {
            case "cycle-interval" -> cycle = value;
            case "window-grace" -> grace = value;
            case "late-report-interval" -> late = value;
            case "active-stream-window" -> active = value;
            case "collect-lookback" -> lookback = value;
            case "baseline-window" -> baseline = value;
            case "retention" -> retention = value;
            default -> throw new IllegalArgumentException("모르는 칸: " + name);
        }
        return new DetectionProperties(cycle, List.of(3_000L, 5_000L, 10_000L), 5_000L,
                grace, late, active, lookback, baseline, 24, 3.0, 10,
                DetectionProperties.Metric.MESSAGE, retention);
    }

    /**
     * 🔴 <b>{@code @NotNull}은 값이 있는지만 본다 — 0과 음수를 그대로 통과시킨다</b>
     * (봇 리뷰 1판, codex).
     *
     * <p>codex 는 {@code retention} 하나를 짚었다. 0이면 {@code MetricsSweeper}가 매 주기
     * 표를 <b>통째로 비우고</b>, 기준선이 사라져 모든 방송이 워밍업으로 되돌아가는데
     * <b>아무 오류도 없다.</b> 같은 구멍이 나머지 여섯에도 있어 전수로 닫았다 —
     * {@code window-sizes-ms}에 0을 막은 것과 같은 계열이고, 그때 <b>한 자리만</b> 막은 것이
     * 여기까지 왔다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"cycle-interval", "late-report-interval", "active-stream-window",
            "collect-lookback", "baseline-window", "retention"})
    void 시간_칸이_0이면_부팅을_막는다(String name) {
        assertThatThrownBy(() -> 시간칸을_바꾼_설정(name, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"cycle-interval", "window-grace", "late-report-interval",
            "active-stream-window", "collect-lookback", "baseline-window", "retention"})
    void 시간_칸이_음수면_부팅을_막는다(String name) {
        assertThatThrownBy(() -> 시간칸을_바꾼_설정(name, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(name);
    }

    /**
     * 🔴 <b>{@code window-grace}만 0이 뜻이 선다</b> — 「유예 없이 바로 집계」는 유효한 설정이고
     * 검사들이 실제로 그 값을 쓴다. 위 두 검사의 목록이 다른 이유가 이것이다.
     *
     * <p>이 갈래가 없으면 「전부 양수 강제」로 뭉뚱그려도 통과해 <b>검사 스물넷이 한꺼번에</b>
     * 깨지는 것으로만 드러난다.
     */
    @Test
    void 유예만_0을_허용한다() {
        assertThatCode(() -> 시간칸을_바꾼_설정("window-grace", Duration.ZERO)).doesNotThrowAnyException();
    }

    /**
     * 칸 <b>사이의 관계</b>를 재는 설정. 위 검사들이 칸 하나하나를 보는 것과 다른 층이다.
     *
     * <p>운영 기본값에서 넷이 전부 여유롭게 통과한다 — 기준선 15분/5초 = 180 대 워밍업 24 ·
     * 활성 60초 대 (유예 2초 + 가장 긴 창 10초) · 되돌아보기 60초 대 10초 ·
     * 보관 24시간 대 기준선 15분.
     */
    private static DetectionProperties 관계를_바꾼_설정(Duration baseline, int warmup,
                                                Duration active, Duration grace,
                                                Duration lookback, Duration retention) {
        return new DetectionProperties(Duration.ofSeconds(1), List.of(3_000L, 5_000L, 10_000L), 5_000L,
                grace, Duration.ofMinutes(10), active, lookback, baseline, warmup, 3.0, 10,
                DetectionProperties.Metric.MESSAGE, retention);
    }

    private static DetectionProperties 기본_관계() {
        return 관계를_바꾼_설정(Duration.ofMinutes(15), 24, Duration.ofSeconds(60),
                Duration.ofSeconds(2), Duration.ofMinutes(1), Duration.ofHours(24));
    }

    @Test
    void 운영_기본값은_관계_검사도_통과한다() {
        assertThatCode(DetectionPropertiesTest::기본_관계).doesNotThrowAnyException();
    }

    /**
     * 🔴 <b>기준선 기간에 워밍업이 안 들어가면 영영 {@code warming_up}이다</b>(봇 리뷰 2판, codex).
     *
     * <p>기준선 조회는 그 기간 안의 창만 읽으므로 <b>창 수가 물리적 상한</b>이다.
     * {@code baseline-window=1m}·5초 창이면 최대 12창인데 워밍업이 24면 절대 못 넘는다 —
     * <b>서버는 뜨고 집계도 도는데 카드가 하나도 안 나가고 오류도 없다.</b>
     */
    @Test
    void 워밍업이_기준선_기간에_안_들어가면_부팅을_막는다() {
        assertThatThrownBy(() -> 관계를_바꾼_설정(Duration.ofMinutes(1), 24, Duration.ofSeconds(60),
                Duration.ofSeconds(2), Duration.ofMinutes(1), Duration.ofHours(24)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("warmup-windows(24)")
                .hasMessageContaining("영영 워밍업");
    }

    /** 딱 들어맞는 값은 통과해야 한다 — 경계를 한 칸 잘못 잡으면 멀쩡한 설정이 막힌다. */
    @Test
    void 워밍업이_기준선_기간에_딱_들어가면_통과한다() {
        assertThatCode(() -> 관계를_바꾼_설정(Duration.ofMinutes(1), 12, Duration.ofSeconds(60),
                Duration.ofSeconds(2), Duration.ofMinutes(1), Duration.ofHours(24)))
                .doesNotThrowAnyException();
    }

    /**
     * 🔴 <b>활성 창이 「창이 닫히고 유예까지」보다 짧으면 급증이 통째로 유실된다</b>
     * (봇 리뷰 2판, codex).
     *
     * <p>급증 뒤 조용해진 방송은 그 창을 집계하기도 전에 활성 목록에서 빠진다.
     * <b>유예는 튜닝하라고 만든 값이라</b>({@code detect.late_arrivals}가 근거를 모은다)
     * 실제로 밟히는 경로다 — 60초로 올리면 활성 창 60초와 정확히 부딪힌다.
     */
    @Test
    void 활성_창이_집계_지연보다_짧으면_부팅을_막는다() {
        assertThatThrownBy(() -> 관계를_바꾼_설정(Duration.ofMinutes(15), 24, Duration.ofSeconds(60),
                Duration.ofSeconds(60), Duration.ofMinutes(1), Duration.ofHours(24)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("active-stream-window")
                .hasMessageContaining("유실");
    }

    /**
     * 되돌아보는 폭이 가장 긴 창보다 짧으면 <b>닫힌 창이 하나도 안 나온다</b> — 집계가 0줄이다.
     * <b>봇이 짚지 않았다.</b> 위 둘과 같은 모양을 세다가 찾았다.
     */
    @Test
    void 되돌아보기가_가장_긴_창보다_짧으면_부팅을_막는다() {
        assertThatThrownBy(() -> 관계를_바꾼_설정(Duration.ofMinutes(15), 24, Duration.ofSeconds(60),
                Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofHours(24)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("collect-lookback")
                .hasMessageContaining("집계가 0줄");
    }

    /**
     * 보관 기간이 기준선 기간보다 짧으면 <b>치우기가 기준선을 지운다</b>.
     * <b>봇이 짚지 않았다.</b> 이것도 같은 모양을 세다가 찾았다.
     */
    @Test
    void 보관_기간이_기준선_기간보다_짧으면_부팅을_막는다() {
        assertThatThrownBy(() -> 관계를_바꾼_설정(Duration.ofMinutes(15), 24, Duration.ofSeconds(60),
                Duration.ofSeconds(2), Duration.ofMinutes(1), Duration.ofMinutes(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retention")
                .hasMessageContaining("기준선을 지운다");
    }

    /** 0을 막는 것이 실제로 나눗셈을 지키는지 — 막지 않았을 때 무슨 일이 나는지를 같이 적어 둔다. */
    @Test
    void 창_크기가_0이면_눈금_계산이_터진다는_것이_막는_이유다() {
        assertThatThrownBy(() -> com.pokeclip.chat.detector.metrics.WindowGrid.floorTo(1_000L, 0L))
                .isInstanceOf(ArithmeticException.class);
        assertThat(com.pokeclip.chat.detector.metrics.WindowGrid.floorTo(1_000L, 5_000L)).isZero();
    }
}
