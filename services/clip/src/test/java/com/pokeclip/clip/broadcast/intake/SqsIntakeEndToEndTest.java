package com.pokeclip.clip.broadcast.intake;

import com.pokeclip.clip.broadcast.BroadcastEventProcessor;
import com.pokeclip.clip.broadcast.LifecycleEnvelope;
import com.pokeclip.clip.broadcast.ProcessResult;
import com.pokeclip.clip.broadcast.BroadcastEventRepository;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.broadcast.BroadcastStatus;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.LocalStackFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

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
     * 감사자가 재현한 <b>영구 유실</b> 시나리오를 진짜 프로세서·진짜 DB로 다시 돌린다.
     * 같은 그룹의 started(seq 1)·ended(seq 2)가 한 배치에 오고 started가 일시적으로
     * 실패하는 상황이다.
     *
     * <p>고치기 전 결말: ended가 먼저 반영돼 lastSequence=2가 되고, 재전송된 started는
     * 1 &lt;= 2라 IGNORED_STALE이 되어 러너가 지운다 — startedAt·trackManifest가 null인
     * 줄이 영구히 남는다. 큐도 비고 편지 기록도 남아 복구 경로가 없다.
     *
     * <p>고친 뒤: 1회차가 앞 편지에서 끝나 명부가 안 움직이고, 2회차에서 둘 다 순서대로
     * 반영된다.
     */
    @Test
    void 배치_앞_편지가_한_번_실패해도_결국_둘_다_순서대로_반영된다() {
        FakeSqsClient sqs = FakeSqsClient.withMessages(
                LocalStackFixture.startedJson("evt-s", "stream-batch", 1L),
                endedJson("evt-e", "stream-batch", 2L));
        FailOnceProcessor failOnce = new FailOnceProcessor(processor, "evt-s");
        SqsIntakeRunner runner = new SqsIntakeRunner(sqs, propertiesFor("unused"),
                new IntakeStatus(true), failOnce, new ObjectMapper());

        runner.pollOnce();

        assertThat(broadcasts.findByStreamId("stream-batch"))
                .as("앞 편지가 실패했는데 뒤 편지가 명부를 만들면 순서가 뒤집힌다")
                .isEmpty();
        assertThat(sqs.deletedReceiptHandles()).isEmpty();

        runner.pollOnce();

        assertThat(broadcasts.findByStreamId("stream-batch")).isPresent().get()
                .satisfies(b -> {
                    assertThat(b.getStatus()).isEqualTo(BroadcastStatus.ENDED);
                    assertThat(b.getStartedAt())
                            .as("시작을 처리하다 실패한 줄이 역순 placeholder와 구분되지 않는다")
                            .isNotNull();
                    assertThat(b.getLastSequence()).isEqualTo(2L);
                });
        assertThat(sqs.deletedReceiptHandles()).containsExactly("rh-0", "rh-1");
    }

    private static String endedJson(String eventId, String streamId, long sequence) {
        return """
                {"schemaVersion":1,"eventId":"%s","eventType":"broadcast.ended",
                 "occurredAt":"2026-08-18T01:00:00Z","streamId":"%s","streamerId":"streamer-1",
                 "sequence":%d,"traceId":"trace-1","payload":{}}
                """.formatted(eventId, streamId, sequence);
    }

    /**
     * 진짜 프로세서에 <b>한 번만</b> 실패를 끼워 넣는다. 상속으로 감싸되 실제 처리는
     * 주입받은 빈에 넘긴다 — {@code @Transactional} 프록시를 그대로 타야 한다.
     */
    private static final class FailOnceProcessor extends BroadcastEventProcessor {

        private final BroadcastEventProcessor delegate;
        private final Set<String> failOnce;

        FailOnceProcessor(BroadcastEventProcessor delegate, String... eventIds) {
            super(null, null);
            this.delegate = delegate;
            this.failOnce = new HashSet<>(Set.of(eventIds));
        }

        @Override
        public ProcessResult process(LifecycleEnvelope envelope) {
            if (failOnce.remove(envelope.eventId())) {
                throw new IllegalStateException("일시적 실패");
            }
            return delegate.process(envelope);
        }
    }

    /**
     * 운영과 같은 조립이되 큐 주소·엔드포인트만 LocalStack을 가리킨다.
     * 롱폴링은 5초로 줄인다 — 20초면 빈 응답을 기다리는 동안 시험이 멈춘다.
     */
    private SqsIntakeRunner newRunnerFor(String queueUrl) {
        return new SqsIntakeRunner(LocalStackFixture.client(), propertiesFor(queueUrl),
                new IntakeStatus(true), processor, new ObjectMapper());
    }

    private static IntakeProperties propertiesFor(String queueUrl) {
        return new IntakeProperties(true, queueUrl, LocalStackFixture.region(),
                LocalStackFixture.endpoint(), Duration.ofSeconds(5), 10);
    }
}
