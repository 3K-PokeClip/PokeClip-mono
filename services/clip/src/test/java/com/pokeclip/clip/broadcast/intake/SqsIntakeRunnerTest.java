package com.pokeclip.clip.broadcast.intake;

import com.pokeclip.clip.broadcast.BroadcastEventProcessor;
import com.pokeclip.clip.broadcast.LifecycleEnvelope;
import com.pokeclip.clip.broadcast.ProcessResult;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class SqsIntakeRunnerTest {

    @Test
    void 처리에_성공하면_편지를_지운다() {
        FakeSqsClient sqs = FakeSqsClient.withMessages(startedJson("evt-1", "s1", 1L));
        SqsIntakeRunner runner = newRunner(sqs, envelope -> ProcessResult.PROCESSED);

        runner.pollOnce();

        assertThat(sqs.deletedReceiptHandles()).containsExactly("rh-0");
    }

    @Test
    void 중복이어도_편지를_지운다() {
        // 중복은 오류가 아니다. 안 지우면 같은 편지가 영원히 되돌아온다.
        FakeSqsClient sqs = FakeSqsClient.withMessages(startedJson("evt-1", "s1", 1L));
        SqsIntakeRunner runner = newRunner(sqs, envelope -> ProcessResult.DUPLICATE);

        runner.pollOnce();

        assertThat(sqs.deletedReceiptHandles()).containsExactly("rh-0");
    }

    @Test
    void 낡은_편지여도_지운다() {
        FakeSqsClient sqs = FakeSqsClient.withMessages(startedJson("evt-1", "s1", 1L));
        SqsIntakeRunner runner = newRunner(sqs, envelope -> ProcessResult.IGNORED_STALE);

        runner.pollOnce();

        assertThat(sqs.deletedReceiptHandles()).containsExactly("rh-0");
    }

    @Test
    void 처리에_실패하면_편지를_지우지_않는다() {
        FakeSqsClient sqs = FakeSqsClient.withMessages(startedJson("evt-1", "s1", 1L));
        SqsIntakeRunner runner = newRunner(sqs, envelope -> {
            throw new IllegalStateException("DB 연결 끊김");
        });

        runner.pollOnce();

        assertThat(sqs.deletedReceiptHandles()).isEmpty();
    }

    @Test
    void 읽을_수_없는_편지는_지우고_경고를_남긴다() {
        // 파싱 실패는 재시도해도 계속 실패한다. 안 지우면 FIFO 같은 그룹의 뒤 편지가
        // 영원히 못 넘어온다.
        FakeSqsClient sqs = FakeSqsClient.withMessages("{ 이건 JSON이 아니다");
        SqsIntakeRunner runner = newRunner(sqs, envelope -> ProcessResult.PROCESSED);

        runner.pollOnce();

        assertThat(sqs.deletedReceiptHandles()).containsExactly("rh-0");
    }

    @Test
    void 폴링에_성공하면_마지막_성공_시각이_갱신된다() {
        FakeSqsClient sqs = FakeSqsClient.withMessages();   // 빈 응답도 성공이다
        IntakeStatus status = new IntakeStatus(true);
        SqsIntakeRunner runner = newRunner(sqs, envelope -> ProcessResult.PROCESSED, status);

        runner.pollOnce();

        assertThat(status.snapshot().lastPollSucceededAt()).isNotNull();
    }

    /**
     * 결함 #4가 다시 들어오면 여기서 잡는다. Optional 주입으로 되돌리면 SqsClient
     * 타입 빈이 0개가 되어 이 단언이 빨간불이 된다 — 그 결함은 다른 어떤 테스트로도
     * 안 잡혔다(계획의 테스트가 전부 초록인 채로 통과했다).
     */
    @Test
    void 켜진_컨텍스트에서_러너가_큐_클라이언트를_받는다() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(IntakeConfiguration.class)
                .withPropertyValues(
                        "pokeclip.broadcast.intake.enabled=true",
                        "pokeclip.broadcast.intake.queue-url=http://localhost:4566/000000000000/q.fifo",
                        "pokeclip.broadcast.intake.region=ap-northeast-2",
                        "pokeclip.broadcast.intake.wait-time=20s",
                        "pokeclip.broadcast.intake.max-messages=10")
                .run(context -> assertThat(context).hasSingleBean(SqsClient.class));
    }

    @Test
    void 큐에_못_닿으면_성공_시각이_갱신되지_않는다() {
        FakeSqsClient sqs = FakeSqsClient.thatFails();
        IntakeStatus status = new IntakeStatus(true);
        SqsIntakeRunner runner = newRunner(sqs, envelope -> ProcessResult.PROCESSED, status);

        runner.pollOnce();   // 예외가 밖으로 튀면 루프가 죽는다 — 안에서 삼킨다

        assertThat(status.snapshot().lastPollSucceededAt()).isNull();
        assertThat(status.snapshot().lastFailureReason()).isNotNull();
    }

    private static SqsIntakeRunner newRunner(SqsClient sqs,
                                             Function<LifecycleEnvelope, ProcessResult> behavior) {
        return newRunner(sqs, behavior, new IntakeStatus(true));
    }

    private static SqsIntakeRunner newRunner(SqsClient sqs,
                                             Function<LifecycleEnvelope, ProcessResult> behavior,
                                             IntakeStatus status) {
        IntakeProperties properties = new IntakeProperties(true,
                "http://localhost:4566/000000000000/q.fifo", "ap-northeast-2", null,
                Duration.ofSeconds(20), 10);
        return new SqsIntakeRunner(sqs, properties, status, new StubProcessor(behavior),
                new ObjectMapper());
    }

    private static String startedJson(String eventId, String streamId, long sequence) {
        return """
                {"schemaVersion":1,"eventId":"%s","eventType":"broadcast.started",
                 "occurredAt":"2026-08-18T00:00:00Z","streamId":"%s","streamerId":"streamer-1",
                 "sequence":%d,"traceId":"trace-1","payload":{}}
                """.formatted(eventId, streamId, sequence);
    }

    /**
     * {@code BroadcastEventProcessor}는 인터페이스가 아니라 구체 @Component라 람다를
     * 넘길 수 없다. 상속해 {@code process}를 통째로 덮는다 — 리포지토리를 한 번도 안
     * 쓰므로 null을 넘긴다.
     */
    static class StubProcessor extends BroadcastEventProcessor {

        private final Function<LifecycleEnvelope, ProcessResult> behavior;

        StubProcessor(Function<LifecycleEnvelope, ProcessResult> behavior) {
            super(null, null);
            this.behavior = behavior;
        }

        @Override
        public ProcessResult process(LifecycleEnvelope envelope) {
            return behavior.apply(envelope);
        }
    }
}
