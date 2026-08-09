package com.pokeclip.chat.collector;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 상태 전이와 그것이 health로 나가는 모양을 붙여서 본다.
 *
 * <p>{@code CollectorHealthEndpointTest}에 넣지 않는 이유는 그쪽이 중첩
 * {@code @SpringBootTest} 둘로 <b>HTTP 응답만</b> 보는 구조라 {@code CollectionStatus}를
 * 임의의 상태로 놓을 수 없어서다. 여기서는 {@code new CollectorHealth(status)}를 직접 부른다.
 */
class CollectionStatusTest {

    private final CollectionStatus status = new CollectionStatus();

    @Test
    void 재연결_중이면_DOWN이고_언제_끊겼는지_몇_번째인지_보인다() {
        Instant disconnectedAt = Instant.parse("2026-08-07T12:00:00Z");
        status.reconnecting(StopReason.TRANSPORT_CLOSED, disconnectedAt, 3);

        Health health = new CollectorHealth(status).health();

        assertThat(health.getStatus())
                .as("재연결 중에는 채팅이 실제로 안 들어온다. UP이면 '수집이 죽었는데 health는 UP'이다")
                .isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .as("끊긴 시각과 시도 횟수가 없으면 '잠깐 끊긴 것'과 '한참째 못 붙는 것'이 같아 보인다")
                .containsEntry("status", "reconnecting")
                .containsEntry("reason", "TRANSPORT_CLOSED")
                .containsEntry("disconnectedAt", disconnectedAt.toString())
                .containsEntry("attempt", 3);
    }

    /** 재연결이 성공하면 돌아와야 한다. 안 돌아오면 영구 DOWN이다. */
    @Test
    void 재연결_뒤_수집으로_돌아오면_UP이다() {
        status.reconnecting(StopReason.TRANSPORT_CLOSED, Instant.now(), 1);
        // 양성 대조. 여기 안 서면 "돌아왔다"가 아니라 "떠난 적이 없다"를 읽는다 —
        // reconnecting()이 아무것도 안 하면 상태가 DISABLED에 머무는데,
        // 거기서 establishing() → collectingIfPending()도 그대로 UP이 된다.
        assertThat(status.state())
                .as("재연결 중으로 가지도 않았다면 돌아오는 것을 검사할 수 없다")
                .isEqualTo(CollectionStatus.State.RECONNECTING);

        status.establishing();
        assertThat(status.collectingIfPending()).isTrue();

        assertThat(new CollectorHealth(status).health().getStatus()).isEqualTo(Status.UP);
    }

    /**
     * 재연결 루프는 시도마다 {@code start()}를 부르고 그 안에서 {@code establishing()}이
     * 불린다. 조건 없이 덮으면 <b>시도할 때마다 health가 UP으로 돌아간다</b> —
     * 수립이 시한(운영 15초)을 다 쓰는 동안 "채팅은 안 오는데 UP"이 된다.
     */
    @Test
    void 재연결_중에는_수립_중으로_되돌리지_않는다() {
        status.reconnecting(StopReason.TRANSPORT_CLOSED, Instant.now(), 2);
        status.establishing();

        assertThat(status.state())
                .as("재시도마다 ESTABLISHING으로 덮이면 health가 UP으로 돌아가고, "
                        + "수립이 시한을 다 쓰는 동안 '채팅은 안 오는데 UP'이 된다")
                .isEqualTo(CollectionStatus.State.RECONNECTING);
        assertThat(new CollectorHealth(status).health().getStatus())
                .as("상태만 지키고 health가 UP이면 밖에서는 아무 신호도 없다")
                .isEqualTo(Status.DOWN);
    }

    /** STOPPED는 영구 정지다. 수립 중으로 되돌리면 그 사유가 어디에도 안 남는다. */
    @Test
    void 이미_STOPPED면_수립_중으로_되돌리지_않는다() {
        status.stopped(StopReason.SESSION_AUTH_REJECTED);
        status.establishing();

        assertThat(status.state()).isEqualTo(CollectionStatus.State.STOPPED);
        assertThat(status.reason()).isEqualTo(StopReason.SESSION_AUTH_REJECTED);
    }

    /** 첫 부팅 경로가 여전히 도는지. 이게 막히면 서버가 아예 수집을 못 한다. */
    @Test
    void 첫_부팅은_DISABLED에서_ESTABLISHING을_지나_COLLECTING으로_간다() {
        status.establishing();
        assertThat(status.state()).isEqualTo(CollectionStatus.State.ESTABLISHING);

        assertThat(status.collectingIfPending()).isTrue();
        assertThat(status.state()).isEqualTo(CollectionStatus.State.COLLECTING);
    }

    /**
     * 수립을 마치는 사이에 영구 정지가 찍혔으면 올라가지 않는다.
     * 올라가면 <b>정리는 끝났는데 health는 UP</b>인, 이 서비스의 유일한 치명 실패다.
     */
    @Test
    void 이미_STOPPED면_수집으로_올라가지_않는다() {
        status.establishing();
        status.stopped(StopReason.TRANSPORT_CLOSED);

        assertThat(status.collectingIfPending()).isFalse();
        assertThat(status.state()).isEqualTo(CollectionStatus.State.STOPPED);
    }

    /**
     * STOPPED는 첫 사유가 이긴다. RECONNECTING이 덮으면 진짜 원인이 사라진다.
     *
     * <p><b>이 테스트만으로는 {@code reconnecting()}이 일한다는 증거가 안 된다</b> —
     * 아무것도 안 하는 구현도 "안 덮었다"를 만족한다. 양성 대조는 위 두 테스트다.
     */
    @Test
    void 이미_STOPPED면_재연결_중으로_되돌리지_않는다() {
        status.stopped(StopReason.SESSION_AUTH_REJECTED);
        status.reconnecting(StopReason.TRANSPORT_CLOSED, Instant.now(), 1);

        assertThat(status.state()).isEqualTo(CollectionStatus.State.STOPPED);
        assertThat(status.reason()).isEqualTo(StopReason.SESSION_AUTH_REJECTED);
    }
}
