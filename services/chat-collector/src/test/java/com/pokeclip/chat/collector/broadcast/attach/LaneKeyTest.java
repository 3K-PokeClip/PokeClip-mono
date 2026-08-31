package com.pokeclip.chat.collector.broadcast.attach;

import com.pokeclip.chat.collector.broadcast.StreamerId;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 줄 이름 정규화가 {@link StreamerId#parse}와 <b>같은 값을 같은 줄로</b> 보는지를 잰다.
 *
 * <p><b>왜 이 검사가 있나</b>: 알림 경로와 재부착이 같은 줄에 들어가야 같은 방송에 동시에
 * 붙는 것을 막는데, 두 경로가 각자 정규화하면 언젠가 한쪽만 고쳐진다(계획 검증 I5).
 * 마지막 검사가 <b>두 정규화를 나란히</b> 단언하는 이유가 그것이다 — 한쪽만 고치면 깨진다.
 *
 * <p><b>문항 1·3·4·5</b>: 이 부품에는 스레드도 비동기도 없다. 재 볼 동시성이 없어
 * 해당하지 않는다(재 보지 않은 것이 아니다). 문항 2는 아래 각 검사의 주석에 있다.
 */
class LaneKeyTest {

    /**
     * 문항 2: 이 단언을 통과시키는 잘못된 결과가 있나 — {@code null}을 던져도 통과하지 않는다.
     * 던지면 검사가 예외로 죽는다. 「빈 줄」이라는 값이어야 뒤에서 카운터가 그것을 센다.
     */
    @Test
    void null이_빈_줄이_된다() {
        assertThat(LaneKey.of(null)).isEmpty();
    }

    @Test
    void 앞뒤_공백이_같은_줄로_모인다() {
        assertThat(LaneKey.of(" 7")).isEqualTo("7");
        assertThat(LaneKey.of("7 ")).isEqualTo("7");
        assertThat(LaneKey.of(" 7 ")).isEqualTo("7");
        assertThat(LaneKey.of("  ")).isEmpty();
    }

    /**
     * <b>두 정규화를 나란히 단언한다.</b> {@code StreamerId}가 같은 회원으로 읽는 두 원문은
     * {@code LaneKey}도 같은 줄로 봐야 한다. 한쪽만 고쳐지면 여기서 깨진다.
     *
     * <p>문항 6: {@code LaneKey}만 단언하면 「우연히 둘 다 {@code "7"}」인지
     * 「{@code StreamerId}도 같게 보는지」가 구분되지 않는다. 그래서 둘을 같이 본다.
     */
    @Test
    void StreamerId와_같은_값을_같은_줄로_본다() {
        assertThat(StreamerId.parse(" 7").value()).isEqualTo(StreamerId.parse("7").value());
        assertThat(LaneKey.of(" 7")).isEqualTo(LaneKey.of("7"));
    }

    /**
     * <b>숫자로 파싱하지 않는다.</b> 못 읽는 식별자도 줄에는 들어가야 한다 — 판정기가
     * 그것을 세어야 하는 값이고, 여기서 가르면 그 카운터가 층을 넘어온다.
     */
    @Test
    void 숫자가_아닌_식별자도_줄_이름이_된다() {
        assertThat(StreamerId.parse("abc").valid()).isFalse();
        assertThat(LaneKey.of("abc")).isEqualTo("abc");
    }
}
