package com.pokeclip.chat.collector;

import com.pokeclip.chat.collector.support.TestHealth;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>health를 렌더하는 동안 상태가 바뀌어도 한 스냅숏으로 그린다.</b>
 *
 * <p>상태와 사유는 참조 하나로 함께 바뀌므로, 필드를 따로 읽으면 <b>서로 다른
 * 순간의 값이 한 응답에 섞인다.</b> RECONNECTING을 보고 그 갈래로 들어간 뒤 사유를
 * 읽으면, 그 사이에 재접속이 성공했을 때 사유가 비어 있다 — 상세가
 * {@code reason=UNKNOWN}으로 나가거나, 널 검사와 렌더가 서로 다른 읽기라면
 * {@code /actuator/health}가 아예 500으로 터진다.
 *
 * <p><b>하필 재연결이 성공하는 그 순간에 터진다.</b> 운영에서 가장 신뢰가 필요한
 * 시점이라, "가끔 health가 이상하다"로 넘어가면 원인을 못 찾는다.
 */
class CollectorHealthSnapshotTest {

    private static final Instant DISCONNECTED_AT = Instant.parse("2026-08-09T00:00:00Z");

    @Test
    void 사유를_읽는_사이에_재접속이_성공해도_한_순간의_값으로_렌더한다() {
        FlippingStatus status = new FlippingStatus();
        status.reconnecting(StopReason.PONG_TIMEOUT, DISCONNECTED_AT, 3);

        Health health = healthOf(status);

        // 양성 대조. 렌더 도중에 상태가 실제로 안 바뀌었다면 아래 단언들은
        // 겨냥한 순간을 한 번도 안 만든 채 저절로 참이 된다.
        assertThat(status.flipped())
                .as("렌더 도중에 상태를 안 바꿨다면 이 검사는 찢어진 읽기를 만든 적이 없다")
                .isTrue();
        assertThat(status.state())
                .as("바꾼 결과가 COLLECTING이 아니면 무엇과 섞였는지 알 수 없다")
                .isEqualTo(CollectionStatus.State.COLLECTING);

        assertThat(health.getStatus().getCode())
                .as("재연결 중으로 판단해 놓고 그 뒤 값을 섞으면 상태와 상세가 어긋난다")
                .isEqualTo("DOWN");
        assertThat(health.getDetails())
                .as("사유·절단 시각·시도 횟수가 상태와 같은 순간의 것이어야 한다")
                .containsEntry("status", "reconnecting")
                .containsEntry("reason", "PONG_TIMEOUT")
                .containsEntry("disconnectedAt", DISCONNECTED_AT.toString())
                .containsEntry("attempt", 3);
    }

    /** 등록부·큐는 이 갈래가 보는 것이 아니다 — 재는 것은 옛 경로 상태를 <b>한 스냅숏으로</b> 그리는가다. */
    private static Health healthOf(CollectionStatus status) {
        return TestHealth.legacyOnly(status).health();
    }

    /**
     * <b>처음 읽히는 순간 재접속이 성공한 것으로 넘어가는 상태.</b>
     *
     * <p>실제 전이는 다른 스레드가 일으키고 창은 몇 인스트럭션이라 결정적으로 못
     * 연다. 그래서 <b>읽는 행위 자체를 장벽으로 쓴다</b> — 첫 읽기가 돌아간 직후에
     * 전이를 끼워 넣으면 그다음 읽기는 반드시 다음 순간의 값을 본다.
     *
     * <p>{@code state()}와 {@code snapshot()} 둘 다에 건다. 한쪽만 걸면 렌더가
     * 다른 쪽만 부르는 구현에서 전이가 아예 안 일어나 양성 대조가 무너진다.
     */
    private static final class FlippingStatus extends CollectionStatus {

        private final AtomicBoolean flipped = new AtomicBoolean();

        boolean flipped() { return flipped.get(); }

        @Override
        public State state() {
            State state = super.state();
            flip();
            return state;
        }

        @Override
        public Snapshot snapshot() {
            Snapshot snapshot = super.snapshot();
            flip();
            return snapshot;
        }

        private void flip() {
            if (flipped.compareAndSet(false, true)) {
                // RECONNECTING에서 들어오는 정상 전이다. 지어낸 상태가 아니다.
                collectingIfPending();
            }
        }
    }
}
