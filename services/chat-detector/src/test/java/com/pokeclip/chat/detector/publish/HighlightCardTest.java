package com.pokeclip.chat.detector.publish;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link HighlightCard#valid()}는 <b>보내기 전에 거르는 문</b>이다. clip이 같은 것을
 * {@code @Valid}와 표의 CHECK로 두 번 보지만, 여기서 안 거르면 못 들어갈 카드에 재시도
 * 횟수를 다 쓰고 로그만 쌓인다.
 *
 * <p><b>이 검사가 없었다.</b> {@code windowStartMs >= 0}을 지우거나 {@code valid()}를 통째로
 * {@code true}로 바꿔도 <b>예순일곱 건이 전부 초록</b>이었다(직접 실측). 태스크 5가 만드는
 * 메서드인데 재는 자리가 어디에도 없어 여기 만든다.
 */
class HighlightCardTest {

    private static HighlightCard 카드(long startMs, long endMs, long timestampMs) {
        return new HighlightCard("s1", "detect-1", timestampMs, startMs, endMs, "{}");
    }

    @Test
    void 창_안에_있는_지점은_통과한다() {
        assertThat(카드(10_000L, 15_000L, 12_500L).valid()).isTrue();
    }

    /**
     * 🔴 방송 아주 초반의 창이 이 경우가 된다 — 채팅 시각에서 보정값(약 4초)을 빼면
     * <b>녹화 시작 이전</b>이 된다. clip은 {@code @PositiveOrZero}라 400으로 거절하고,
     * 우리가 안 거르면 그 400에 시도 횟수를 쓴다.
     */
    @Test
    void 위치가_음수면_보내기_전에_거른다() {
        assertThat(카드(-1L, 15_000L, 12_500L).valid()).isFalse();
    }

    /** 0은 음수가 아니다 — 녹화 맨 처음은 정상적인 위치다. 경계를 한 칸 밀면 여기가 빨개진다. */
    @Test
    void 위치가_0인_것은_거르지_않는다() {
        assertThat(카드(0L, 5_000L, 0L).valid()).isTrue();
    }

    @Test
    void 시작이_끝보다_뒤면_거른다() {
        assertThat(카드(15_000L, 10_000L, 12_500L).valid()).isFalse();
    }

    /** 길이 0짜리 창은 clip의 {@code startMs < endMs}에 걸린다. */
    @Test
    void 시작과_끝이_같으면_거른다() {
        assertThat(카드(10_000L, 10_000L, 10_000L).valid()).isFalse();
    }

    @Test
    void 대표_지점이_창_밖이면_거른다() {
        assertThat(카드(10_000L, 15_000L, 9_999L).valid()).isFalse();
        assertThat(카드(10_000L, 15_000L, 15_001L).valid()).isFalse();
    }

    /** 양 끝은 창 안이다(clip이 {@code start <= t <= end}로 본다). */
    @Test
    void 대표_지점이_창의_양_끝이면_통과한다() {
        assertThat(카드(10_000L, 15_000L, 10_000L).valid()).isTrue();
        assertThat(카드(10_000L, 15_000L, 15_000L).valid()).isTrue();
    }
}
