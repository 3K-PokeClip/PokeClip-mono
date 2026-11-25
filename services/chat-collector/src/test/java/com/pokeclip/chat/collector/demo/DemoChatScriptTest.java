package com.pokeclip.chat.collector.demo;

import com.pokeclip.chat.collector.Platform;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 발표 데모용 임시 채팅 대본. 판별기(chat-detector)의 기본 설정에 기대는 숫자가 여기서 지켜진다:
 * 판정 창 5초 · 워밍업 「채팅이 있던 창 24개」 · 되돌아보기 150초 · 최소 건수 10 · 배율 3.
 */
class DemoChatScriptTest {

    private static final long NOW = 1_800_000_003_210L;

    @Test
    void 평소_채팅은_지난_창마다_한_건씩이고_되돌아보기_안에_있다() {
        List<DemoChat> calm = DemoChatScript.calm(NOW);

        Set<Long> windows = new HashSet<>();
        for (DemoChat chat : calm) {
            windows.add(chat.messageTimeMillis() / 5_000);
            assertThat(chat.messageTimeMillis()).as("지금보다 앞이어야 한다").isLessThan(NOW);
            assertThat(NOW - chat.messageTimeMillis()).as("판별기가 150초까지만 다시 집계한다").isLessThan(150_000);
        }
        assertThat(windows).as("창마다 한 건이어야 「채팅이 있던 창」이 그만큼 생긴다").hasSize(calm.size());
        assertThat(windows.size()).as("워밍업 24창을 넘긴다").isGreaterThanOrEqualTo(24);
        assertThat(windows).as("지금 창은 비워 둔다. 급증 창의 건수를 평소 쪽이 깎지 않게").doesNotContain(NOW / 5_000);
    }

    @Test
    void 급증은_두_플랫폼에서_오고_한_창에_최소_건수와_배율을_넘는다() {
        List<DemoChat> burst = IntStream.range(0, DemoChatScript.BURST_TICKS)
                .mapToObj(i -> DemoChatScript.tick(i, NOW + i * 250L))
                .flatMap(List::stream)
                .toList();

        assertThat(burst).hasSize(DemoChatScript.BURST_TICKS * DemoChatScript.PER_TICK);
        assertThat(burst).extracting(DemoChat::platform).contains(Platform.CHZZK, Platform.SOOP);
        long perWindow = burst.size() * 5_000L / (DemoChatScript.BURST_TICKS * 250L);
        assertThat(perWindow).as("평소가 창당 1건이면 배율 3을 한참 넘어야 한다").isGreaterThanOrEqualTo(30);
        assertThat(burst).extracting(DemoChat::senderId).as("말한 사람 수도 같이 오른다").doesNotHaveDuplicates();
    }

    @Test
    void 닉네임은_번호가_아니라_실제_시청자_같은_가상_이름이다() {
        List<DemoChat> all = new ArrayList<>(DemoChatScript.calm(NOW));
        IntStream.range(0, DemoChatScript.BURST_TICKS).forEach(i -> all.addAll(DemoChatScript.tick(i, NOW)));

        assertThat(all).extracting(DemoChat::nickname)
                .as("발표 화면에 「시청자012」가 보이면 가짜 티가 난다")
                .noneMatch(name -> name.matches(".*\\d{3}$"));
        assertThat(all.stream().map(DemoChat::nickname).distinct().count())
                .as("한 사람이 도배하는 것처럼 보이지 않게 이름이 여럿이다").isGreaterThanOrEqualTo(30);
    }

    @Test
    void 한_틱의_채팅은_보낸_사람이_달라_지문이_안_겹친다() {
        // 지문 UNIQUE가 (채널, 보낸 사람, 시각, 본문)이라 같은 ms에 같은 사람이 같은 말을 하면 하나가 버려진다
        List<DemoChat> tick = DemoChatScript.tick(7, NOW);

        assertThat(tick).extracting(DemoChat::senderId).doesNotHaveDuplicates();
        assertThat(tick).allSatisfy(chat -> assertThat(chat.messageTimeMillis()).isEqualTo(NOW));
    }
}
