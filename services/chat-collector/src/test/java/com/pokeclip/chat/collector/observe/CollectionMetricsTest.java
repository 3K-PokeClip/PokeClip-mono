package com.pokeclip.chat.collector.observe;

import com.pokeclip.chat.collector.chzzk.ChatMessage;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CollectionMetricsTest {

    @Test
    void 수신_건수와_최대_공백을_센다() {
        CollectionMetrics metrics = new CollectionMetrics();

        metrics.recordMessage(chat(1_000L), 10_000L);
        metrics.recordMessage(chat(2_000L), 10_500L);
        metrics.recordMessage(chat(3_000L), 14_000L);

        CollectionMetrics.Snapshot s = metrics.snapshot();
        assertThat(s.received()).isEqualTo(3);
        assertThat(s.maxReceiveGap()).isEqualTo(Duration.ofMillis(3_500));
    }

    /** 관측값이지 실패 조건이 아니다. 채팅은 원래 어긋나서 온다. */
    @Test
    void messageTime_순서_위반을_세되_실패로_보지_않는다() {
        CollectionMetrics metrics = new CollectionMetrics();

        metrics.recordMessage(chat(5_000L), 10_000L);
        metrics.recordMessage(chat(4_000L), 10_100L);   // 역전
        metrics.recordMessage(chat(6_000L), 10_200L);

        assertThat(metrics.snapshot().orderViolations()).isEqualTo(1);
    }

    /**
     * 위 테스트만 있으면 "항상 1을 돌려주는" 구현도 통과한다.
     * 순서가 멀쩡할 때 0인지 봐야 그 값이 무언가를 세고 있다는 뜻이 된다.
     */
    @Test
    void 순서가_멀쩡하면_위반이_0이다() {
        CollectionMetrics metrics = new CollectionMetrics();

        metrics.recordMessage(chat(1_000L), 10_000L);
        metrics.recordMessage(chat(2_000L), 10_100L);
        metrics.recordMessage(chat(3_000L), 10_200L);

        assertThat(metrics.snapshot().orderViolations()).isZero();
    }

    /**
     * 팀 사양서 TBD 첫 줄「이벤트 전달 지연 실측」을 메우는 값이다.
     *
     * <p><b>POK-92의 시차 보정 오프셋으로 쓰지 마라.</b> 이 값은
     * {@code messageTime}에서 우리 수신까지이고, 그 오프셋은 치지직 방송 지연과
     * 시청자 반응 지연(둘 다 초 단위·미측정)에서 <b>우리 인제스트 지연</b>
     * (인코딩+SRT 버퍼)을 뺀 것이다. 이 항 하나로 잡으면 자릿수가 어긋난다.
     */
    @Test
    void 전달_지연의_최소_중앙_최대를_낸다() {
        CollectionMetrics metrics = new CollectionMetrics();

        metrics.recordMessage(chat(1_000L), 1_100L);    // 100ms
        metrics.recordMessage(chat(2_000L), 2_500L);    // 500ms
        metrics.recordMessage(chat(3_000L), 3_300L);    // 300ms

        CollectionMetrics.Snapshot s = metrics.snapshot();
        assertThat(s.delayMin()).isEqualTo(Duration.ofMillis(100));
        assertThat(s.delayMedian()).isEqualTo(Duration.ofMillis(300));
        assertThat(s.delayMax()).isEqualTo(Duration.ofMillis(500));
    }

    /** 창마다 비운다. 안 비우면 10분 뒤 표본이 무한정 쌓인다. */
    @Test
    void 스냅샷을_찍으면_창이_비워진다() {
        CollectionMetrics metrics = new CollectionMetrics();
        metrics.recordMessage(chat(1_000L), 1_100L);

        assertThat(metrics.snapshot().received()).isEqualTo(1);
        assertThat(metrics.snapshot().received()).isZero();
    }

    /** 누적은 따로 센다. 최종 판정 라인이 이걸 쓴다. */
    @Test
    void 누적_건수는_창을_비워도_남는다() {
        CollectionMetrics metrics = new CollectionMetrics();
        metrics.recordMessage(chat(1_000L), 1_100L);
        metrics.snapshot();
        metrics.recordMessage(chat(2_000L), 2_100L);
        metrics.snapshot();

        assertThat(metrics.totalReceived()).isEqualTo(2);
    }

    /**
     * 마지막 수신 시각은 창을 비워도 남아야 한다. 창이 비었다고 이 값이 사라지면
     * "30초째 한 건도 못 받았다"를 요약이 말할 수 없게 된다 — 그게 정확히
     * 알아야 하는 상태다.
     */
    @Test
    void 마지막_수신_시각은_빈_창에서도_남는다() {
        CollectionMetrics metrics = new CollectionMetrics();
        metrics.recordMessage(chat(1_000L), 1_100L);
        metrics.snapshot();

        CollectionMetrics.Snapshot empty = metrics.snapshot();
        assertThat(empty.received()).isZero();
        assertThat(empty.lastReceivedAt()).isEqualTo(java.time.Instant.ofEpochMilli(1_100L));
    }

    /**
     * SYSTEM은 방송 내내 네 건뿐이라 창마다 비우면 대부분의 요약 줄에서 사라진다.
     * revoked가 왔다는 사실은 그 뒤 모든 줄에 남아 있어야 한다.
     */
    @Test
    void 시스템_이벤트는_종류별로_세고_창을_비워도_남는다() {
        CollectionMetrics metrics = new CollectionMetrics();
        metrics.recordSystemEvent("connected");
        metrics.recordSystemEvent("subscribed");
        metrics.snapshot();

        assertThat(metrics.snapshot().systemEvents())
                .containsEntry("connected", 1L)
                .containsEntry("subscribed", 1L);
    }

    /** 디코딩 실패는 창 단위다. 어느 30초에 몰렸는지가 보여야 한다. */
    @Test
    void 디코딩_실패는_창마다_센다() {
        CollectionMetrics metrics = new CollectionMetrics();
        metrics.recordDecodeFailure();
        metrics.recordDecodeFailure();

        assertThat(metrics.snapshot().decodeFailures()).isEqualTo(2);
        assertThat(metrics.snapshot().decodeFailures()).isZero();
    }

    private static ChatMessage chat(long messageTime) {
        return new ChatMessage("S1", "내용", messageTime);
    }
}
