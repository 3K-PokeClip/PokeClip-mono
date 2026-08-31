package com.pokeclip.chat.collector.broadcast.attach;

import com.pokeclip.chat.collector.broadcast.StreamerId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 줄 이름 정규화가 {@link StreamerId#parse}와 <b>같은 회원을 같은 줄로</b> 보는지를 잰다.
 *
 * <p><b>왜 이 검사가 있나</b>: 알림 경로와 재부착이 같은 줄에 들어가야 같은 스트리머의 두
 * 방송이 동시에 수립되는 것을 막는데, 두 경로가 각자 정규화하면 언젠가 한쪽만 고쳐진다
 * (계획 검증 I5).
 *
 * <p>🔴 <b>이 검사는 한 번 약했다.</b> 이름이 「같은 값을 같은 줄로 본다」는 <b>일반
 * 불변식</b>을 주장하는데 실제로 재는 것은 {@code " 7"}/{@code "7"} <b>한 쌍뿐</b>이었고,
 * 그것은 {@code trim()}이 마침 처리하는 유일한 갈래였다. 감사가 실측하니 {@code StreamerId}가
 * 같은 회원 7로 읽는 입력 <b>8종 중 4종이 다른 줄</b>이었다({@code "07"}·{@code "007"}·
 * {@code "+7"}·{@code "0000000007"}). <b>시험 이름이 코드가 만족하지 않는 것을 주장하고
 * 있었다.</b> 그래서 여덟 가지를 전부 먹인다.
 *
 * <p><b>문항 1·3·4·5</b>: 이 부품에는 스레드도 비동기도 없다. 재 볼 동시성이 없어
 * 해당하지 않는다(재 보지 않은 것이 아니다).
 */
class LaneKeyTest {

    /**
     * 문항 2: 이 단언을 통과시키는 잘못된 결과가 있나 — {@code null}을 던져도 통과하지 않는다.
     * 던지면 검사가 예외로 죽는다. 「빈 줄」이라는 <b>값</b>이어야 뒤에서 카운터가 그것을 센다.
     */
    @Test
    void null이_빈_줄이_된다() {
        assertThat(LaneKey.of(null)).isEmpty();
    }

    @Test
    void 공백만_있으면_빈_줄이_된다() {
        assertThat(LaneKey.of("  ")).isEmpty();
        assertThat(LaneKey.of("")).isEmpty();
    }

    /**
     * <b>{@code StreamerId}가 같은 회원으로 읽는 원문은 전부 같은 줄이어야 한다.</b>
     *
     * <p>첫 단언이 <b>양성 대조</b>다 — 여덟이 정말 같은 회원으로 읽히는지를 먼저 확인한다.
     * 그것 없이 줄 이름만 비교하면 「둘 다 우연히 같다」와 구분되지 않는다(문항 6).
     */
    @ParameterizedTest
    @ValueSource(strings = {"7", " 7", "7 ", " 7 ", "07", "007", "+7", "0000000007"})
    void StreamerId가_같은_회원으로_읽는_원문은_전부_같은_줄이_된다(String raw) {
        assertThat(StreamerId.parse(raw).value()).isEqualTo(7L);
        assertThat(LaneKey.of(raw)).isEqualTo(LaneKey.of("7"));
    }

    /**
     * <b>못 읽는 식별자는 뭉치지 않는다.</b> 판정기가 그것을 세어야 하는 값이고
     * (읽을 수 없는 스트리머 수), 여기서 하나로 뭉치면 그 카운터가 층을 넘어온다.
     */
    @Test
    void 숫자로_못_읽는_식별자는_원문_그대로_줄이_된다() {
        assertThat(StreamerId.parse("abc").valid()).isFalse();
        assertThat(LaneKey.of("abc")).isEqualTo("abc");
        assertThat(LaneKey.of(" abc ")).isEqualTo("abc");
        // 서로 다른 못 읽는 값이 같은 줄로 뭉치지 않는다.
        assertThat(LaneKey.of("abc")).isNotEqualTo(LaneKey.of("def"));
    }

    /**
     * 범위를 넘는 값도 {@code StreamerId}가 못 읽으므로 원문 그대로 간다.
     * {@code Long.parseLong}이 던지는 갈래다.
     */
    @Test
    void 범위를_넘는_숫자는_원문_그대로_줄이_된다() {
        String tooBig = "99999999999999999999";
        assertThat(StreamerId.parse(tooBig).valid()).isFalse();
        assertThat(LaneKey.of(tooBig)).isEqualTo(tooBig);
    }
}
