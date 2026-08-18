package com.pokeclip.clip.broadcast.intake;

import com.pokeclip.clip.broadcast.BroadcastEventProcessor;
import com.pokeclip.clip.broadcast.BroadcastEventRepository;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.broadcast.BroadcastStatus;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.LocalStackFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 가짜 SqsClient가 아니라 진짜 SQS 구현에 붙여 한 바퀴 돈다. 꺼내고 지우는 경로가
 * 한 번도 안 돌면 다음 카드가 그 위에 선다.
 *
 * <p>FIFO 큐를 만든다 — MessageGroupId·MessageDeduplicationId가 실제로 요구되는지
 * 여기서 드러난다. 큐 이름이 .fifo로 끝나야 하고, ADR-016이 정한 값
 * (그룹=streamId, 중복제거=eventId)을 그대로 쓴다.
 */
class SqsIntakeEndToEndTest extends IntegrationTestSupport {

    private final BroadcastEventProcessor processor;
    private final BroadcastRepository broadcasts;
    private final BroadcastEventRepository events;

    SqsIntakeEndToEndTest(BroadcastEventProcessor processor, BroadcastRepository broadcasts,
                          BroadcastEventRepository events) {
        this.processor = processor;
        this.broadcasts = broadcasts;
        this.events = events;
    }

    /** 앞 클래스들과 같은 DB를 쓴다. 둘째 시험의 hasSize(1)이 남은 줄에 오염된다. */
    @BeforeEach
    void 앞_테스트의_흔적을_지운다() {
        events.deleteAllInBatch();
        broadcasts.deleteAllInBatch();
    }

    @Test
    void 큐에_넣은_이벤트가_명부에_반영되고_큐에서_사라진다() {
        String queueUrl = LocalStackFixture.createFifoQueue("broadcast-lifecycle-clip.fifo");
        LocalStackFixture.sendStarted(queueUrl, "evt-e2e", "stream-e2e", 1L);

        newRunnerFor(queueUrl).pollOnce();

        assertThat(broadcasts.findByStreamId("stream-e2e")).isPresent().get()
                .satisfies(b -> assertThat(b.getStatus()).isEqualTo(BroadcastStatus.LIVE));
        assertThat(LocalStackFixture.approximateMessageCount(queueUrl)).isZero();
    }

    @Test
    void 같은_중복제거_ID로_두_번_보내면_큐가_하나만_전달한다() {
        // SQS FIFO의 5분 중복제거 창. 우리 멱등과 별개인 1차 방어선이 실제로 도는지 본다.
        String queueUrl = LocalStackFixture.createFifoQueue("broadcast-dedup-test.fifo");
        LocalStackFixture.sendStarted(queueUrl, "evt-same", "stream-dedup", 1L);
        LocalStackFixture.sendStarted(queueUrl, "evt-same", "stream-dedup", 1L);

        newRunnerFor(queueUrl).pollOnce();

        assertThat(events.findAll()).hasSize(1);
    }

    /**
     * 운영과 같은 조립이되 큐 주소·엔드포인트만 LocalStack을 가리킨다.
     * 롱폴링은 5초로 줄인다 — 20초면 빈 응답을 기다리는 동안 시험이 멈춘다.
     */
    private SqsIntakeRunner newRunnerFor(String queueUrl) {
        IntakeProperties properties = new IntakeProperties(true, queueUrl,
                LocalStackFixture.region(), LocalStackFixture.endpoint(),
                Duration.ofSeconds(5), 10);
        return new SqsIntakeRunner(LocalStackFixture.client(), properties,
                new IntakeStatus(true), processor, new ObjectMapper());
    }
}
