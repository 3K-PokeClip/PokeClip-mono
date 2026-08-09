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
        status.establishing();
        assertThat(status.collectingIfEstablishing()).isTrue();

        assertThat(new CollectorHealth(status).health().getStatus()).isEqualTo(Status.UP);
    }

    /** STOPPED는 첫 사유가 이긴다. RECONNECTING이 덮으면 진짜 원인이 사라진다. */
    @Test
    void 이미_STOPPED면_재연결_중으로_되돌리지_않는다() {
        status.stopped(StopReason.SESSION_AUTH_REJECTED);
        status.reconnecting(StopReason.TRANSPORT_CLOSED, Instant.now(), 1);

        assertThat(status.state()).isEqualTo(CollectionStatus.State.STOPPED);
        assertThat(status.reason()).isEqualTo(StopReason.SESSION_AUTH_REJECTED);
    }
}
