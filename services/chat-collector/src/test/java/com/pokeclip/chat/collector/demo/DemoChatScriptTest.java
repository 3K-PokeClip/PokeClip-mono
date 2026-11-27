package com.pokeclip.chat.collector.demo;

import com.pokeclip.chat.collector.Platform;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 발표 데모용 임시 채팅 대본. 카드는 판별기가 아니라 {@link DemoChatScript#CARD_AT_CHAT}번째 채팅에서 수집기가 낸다
 * ({@link DemoHighlight}) — 그래서 판별기 설정(워밍업 · 배율)에 기대는 숫자가 더는 없다.
 */
class DemoChatScriptTest {

    private static final long NOW = 1_800_000_003_210L;

    /** 씨앗을 고정해 시험마다 같은 무작위다 */
    private static final DemoChatScript.Audience AUDIENCE = DemoChatScript.audience(new Random(7));

    private static List<DemoChat> burst() {
        return IntStream.range(0, DemoChatScript.BURST_TICKS)
                .mapToObj(i -> DemoChatScript.tick(i, NOW + i * 250L, AUDIENCE))
                .flatMap(List::stream)
                .toList();
    }

    @Test
    void 급증은_120건이고_두_플랫폼에서_말한_사람이_다_다르다() {
        List<DemoChat> burst = burst();

        assertThat(burst).hasSize(120);
        assertThat(burst).extracting(DemoChat::platform).contains(Platform.CHZZK, Platform.SOOP);
        assertThat(burst).extracting(DemoChat::senderId).as("말한 사람 수도 같이 오른다").doesNotHaveDuplicates();
    }

    /** 사용자 결정(2026-11-27): 120건 가운데 70번째에서 카드가 선다. 3건씩이라 24번째 틱(누른 뒤 5.75초)의 첫 채팅이다 */
    @Test
    void 카드는_급증_120건_가운데_70번째_채팅에서_선다() {
        assertThat(DemoChatScript.CARD_AT_CHAT).isEqualTo(70)
                .isLessThanOrEqualTo(DemoChatScript.BURST_TICKS * DemoChatScript.PER_TICK);
        int tick = (DemoChatScript.CARD_AT_CHAT - 1) / DemoChatScript.PER_TICK;
        assertThat(tick).isEqualTo(23);
        assertThat(DemoChatBurst.TICK.multipliedBy(tick).toMillis()).as("누른 뒤").isEqualTo(5_750);
    }

    @Test
    void 닉네임은_번호가_아니라_실제_시청자_같은_가상_이름이다() {
        List<DemoChat> burst = burst();

        assertThat(burst).extracting(DemoChat::nickname)
                .as("발표 화면에 「시청자012」가 보이면 가짜 티가 난다")
                .noneMatch(name -> name.matches(".*\\d{3}$"));
        assertThat(burst.stream().map(DemoChat::nickname).distinct().count())
                .as("한 사람이 도배하는 것처럼 보이지 않게 이름이 여럿이다").isGreaterThanOrEqualTo(30);
    }

    /**
     * 플랫폼은 번갈아가 아니라 무작위다(사용자 결정, 2026-11-27 — 치지직 · SOOP 아이콘이 정직하게 번갈아 나와 지어낸 티가 났다).
     * 같은 사람은 한 번 누른 동안 플랫폼이 같다: 줄마다 바뀌면 그것도 티가 난다
     */
    @Test
    void 플랫폼은_번갈아_나오지_않고_사람마다_무작위로_정한다() {
        List<DemoChat> burst = burst();

        boolean alternates = IntStream.range(1, burst.size())
                .allMatch(i -> burst.get(i).platform() != burst.get(i - 1).platform());
        assertThat(alternates).as("치지직 · SOOP가 한 줄씩 번갈아 나오면 지어낸 티가 난다").isFalse();

        Map<String, Set<Platform>> byNickname = new HashMap<>();
        burst.forEach(chat -> byNickname.computeIfAbsent(chat.nickname(), k -> new HashSet<>()).add(chat.platform()));
        assertThat(byNickname.values()).as("같은 닉네임이 줄마다 플랫폼을 바꾸지 않는다").allSatisfy(p -> assertThat(p).hasSize(1));

        assertThat(DemoChatScript.audience(new Random(8)))
                .as("씨앗이 다르면 배정도 다르다").isNotEqualTo(AUDIENCE);
        assertThat(DemoChatScript.audienceOf("stream-1"))
                .as("같은 방송은 다시 눌러도 같은 배정이다").isEqualTo(DemoChatScript.audienceOf("stream-1"))
                .as("방송이 다르면 배정도 다르다").isNotEqualTo(DemoChatScript.audienceOf("stream-2"));
    }

    @Test
    void 한_틱의_채팅은_보낸_사람이_달라_지문이_안_겹친다() {
        // 지문 UNIQUE가 (채널, 보낸 사람, 시각, 본문)이라 같은 ms에 같은 사람이 같은 말을 하면 하나가 버려진다
        List<DemoChat> tick = DemoChatScript.tick(7, NOW, AUDIENCE);

        assertThat(tick).extracting(DemoChat::senderId).doesNotHaveDuplicates();
        assertThat(tick).allSatisfy(chat -> assertThat(chat.messageTimeMillis()).isEqualTo(NOW));
    }
}
