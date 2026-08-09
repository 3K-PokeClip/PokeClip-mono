package com.pokeclip.chat.collector.reconnect;

import com.pokeclip.chat.collector.StopReason;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ReconnectPolicyTest {

    private final ReconnectPolicy policy =
            new ReconnectPolicy(Duration.ofSeconds(1), Duration.ofSeconds(60));

    @Test
    void 영원히_안_풀리는_사유는_재시도하지_않는다() {
        assertThat(ReconnectPolicy.retriable(StopReason.SESSION_AUTH_REJECTED)).isFalse();
        assertThat(ReconnectPolicy.retriable(StopReason.REVOKED)).isFalse();
        assertThat(ReconnectPolicy.retriable(StopReason.SUBSCRIBE_REJECTED))
                .as("발급이 200이어도 구독이 거부되면 다시 붙어도 같다. Scope나 동의가 빠진 것이다")
                .isFalse();
        assertThat(ReconnectPolicy.retriable(StopReason.SEND_MISUSE))
                .as("우리 버그로 재연결이 돌면 버그는 영영 안 보이고 자리만 태운다")
                .isFalse();
    }

    @Test
    void 일시적인_사유는_재시도한다() {
        assertThat(ReconnectPolicy.retriable(StopReason.TRANSPORT_CLOSED)).isTrue();
        assertThat(ReconnectPolicy.retriable(StopReason.PONG_TIMEOUT)).isTrue();
        assertThat(ReconnectPolicy.retriable(StopReason.PING_SEND_FAILED)).isTrue();
        assertThat(ReconnectPolicy.retriable(StopReason.SESSION_AUTH_FAILED))
                .as("5xx는 서버가 잠깐 아픈 것이다. 거부와 묶으면 영구 정지한다")
                .isTrue();
        assertThat(ReconnectPolicy.retriable(StopReason.SUBSCRIBE_FAILED))
                .as("구독 5xx도 마찬가지다. 거부와 묶으면 그 방송의 남은 채팅이 통째로 사라진다")
                .isTrue();
    }

    /**
     * <b>기본값이 "재시도한다"인지를 사유 전수로 못박는다.</b> 위 두 테스트는 열거값을
     * 손으로 적으므로, 사유가 새로 늘 때 그 값이 어느 쪽으로도 안 걸린다. 그때 기본이
     * 조용히 "안 한다"로 바뀌어 있으면 <b>모르는 사유 하나가 수집을 영영 멈춘다</b> —
     * 채팅 유실이 유일한 치명 실패라 그쪽이 더 나쁘다.
     */
    @Test
    void 거부_목록에_없는_사유는_전부_재시도한다() {
        for (StopReason reason : StopReason.values()) {
            boolean denied = reason == StopReason.SESSION_AUTH_REJECTED
                    || reason == StopReason.SUBSCRIBE_REJECTED
                    || reason == StopReason.REVOKED
                    || reason == StopReason.SEND_MISUSE;
            assertThat(ReconnectPolicy.retriable(reason))
                    .as("허용 목록으로 짜면 새 사유가 기본으로 영구 정지에 걸린다: " + reason)
                    .isEqualTo(!denied);
        }
    }

    @Test
    void 간격이_두_배씩_늘고_상한에서_멈춘다() {
        assertThat(policy.delayFor(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(policy.delayFor(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.delayFor(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(policy.delayFor(30))
                .as("상한이 없으면 오래 끊긴 뒤 복구가 몇 시간 뒤가 된다")
                .isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void 시도_횟수가_커도_넘치지_않는다() {
        assertThat(policy.delayFor(Integer.MAX_VALUE))
                .as("시프트가 넘치면 음수 간격이 나와 대기가 통째로 사라진다")
                .isEqualTo(Duration.ofSeconds(60));
    }
}
