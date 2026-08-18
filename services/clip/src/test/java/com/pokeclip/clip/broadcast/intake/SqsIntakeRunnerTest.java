package com.pokeclip.clip.broadcast.intake;

import ch.qos.logback.classic.Level;
import com.pokeclip.clip.broadcast.BroadcastEventProcessor;
import com.pokeclip.clip.broadcast.LifecycleEnvelope;
import com.pokeclip.clip.broadcast.ProcessResult;
import com.pokeclip.web.support.LogCaptor;
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

    /**
     * 유실은 없지만 <b>로그가 거짓을 말하면 안 된다</b>(감사 2차 지적). 삭제가 try 안에
     * 있으면 "처리는 됐는데 삭제가 실패"까지 {@code handle_failed}로 남아, 나중에 이 줄을
     * 보는 사람이 멀쩡한 처리 쪽을 뒤진다. 삭제 실패는 큐에 못 닿는 것이므로
     * {@code poll_failed}로 가야 한다.
     */
    @Test
    void 처리는_됐는데_삭제가_실패하면_처리_실패로_남지_않는다() {
        FakeSqsClient sqs = FakeSqsClient.thatFailsOnDelete(startedJson("evt-1", "s1", 1L));
        SqsIntakeRunner runner = newRunner(sqs, envelope -> ProcessResult.PROCESSED);

        try (LogCaptor captor = new LogCaptor()) {
            runner.pollOnce();

            assertThat(captor.messages())
                    .as("삭제가 실패했는데 처리가 실패한 것처럼 남으면 원인을 반대로 가리킨다")
                    .noneMatch(m -> m.contains("broadcast.intake.handle_failed"));
            assertThat(captor.messages())
                    .as("삭제 실패는 큐에 못 닿는 것이라 poll_failed로 남아야 한다")
                    .anyMatch(m -> m.contains("broadcast.intake.poll_failed"));
        }
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

    /**
     * 모르는 종류는 재시도해도 계속 모른다. 안 지우면 FIFO 같은 그룹의 뒤 편지가
     * 영원히 못 넘어온다 — 줄이 막히는 피해가 소식 하나를 놓치는 것보다 크다.
     *
     * <p>이 판단은 <b>러너의 것</b>이다. LifecycleEventType.from은 계속 던진다 —
     * 그것을 "재시도 불가"로 분류하는 자리가 여기다.
     */
    @Test
    void 모르는_종류의_편지는_지우고_종류를_로그에_남긴다() {
        FakeSqsClient sqs = FakeSqsClient.withMessages(envelopeJson("evt-x", "s1", 1L, "broadcast.paused"));
        // 진짜 프로세서는 envelope.type()에서 던진다. 스텁이 조용히 성공하면 갈래를
        // 지워도 편지가 지워져 아래 삭제 단언이 저절로 참이 된다 — 그러면 이 시험이
        // 로그만 재고 정작 "큐가 안 막힌다"는 못 잰다.
        SqsIntakeRunner runner = newRunner(sqs, envelope -> {
            throw new IllegalArgumentException("모르는 생명주기 이벤트: " + envelope.eventType());
        });

        try (LogCaptor captor = new LogCaptor()) {
            runner.pollOnce();

            // 형식이 깨진 것과 다른 키를 쓴다 — 후자는 1번이 새 이벤트를 냈다는 신호라
            // 로그에서 갈라낼 수 있어야 한다.
            assertThat(captor.messages())
                    .anyMatch(m -> m.contains("broadcast.intake.unknown_type_dropped"));
            assertThat(captor.messages())
                    .as("버린 종류 이름이 없으면 무엇이 새로 생겼는지 알 수 없다")
                    .anyMatch(m -> m.contains("broadcast.paused"));
            assertThat(captor.levelOf("broadcast.intake.unknown_type_dropped")).isEqualTo(Level.WARN);
        }

        assertThat(sqs.deletedReceiptHandles()).containsExactly("rh-0");
    }

    /** 모르는 종류를 버리는 것이지 아는 종류까지 버리는 것이 아니다. */
    @Test
    void 아는_종류는_모르는_종류_갈래로_새지_않는다() {
        FakeSqsClient sqs = FakeSqsClient.withMessages(envelopeJson("evt-y", "s1", 1L, "broadcast.ended"));
        SqsIntakeRunner runner = newRunner(sqs, envelope -> {
            throw new IllegalStateException("DB 연결 끊김");
        });

        runner.pollOnce();

        assertThat(sqs.deletedReceiptHandles())
                .as("아는 종류의 처리 실패는 재시도해야 한다")
                .isEmpty();
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
     * 결함 #4가 다시 들어오면 여기서 잡는다. {@code Optional} 주입으로 되돌리면
     * SqsClient 타입 빈이 0개가 되고 러너는 빈손을 받는다 — 그 결함은 다른 어떤
     * 검사로도 안 잡혔다(계획의 검사가 전부 초록인 채로 통과했다).
     *
     * <p><b>러너를 실제로 컨텍스트에 올려 확인한다.</b> 예전에는 빈 존재만 봤는데,
     * 그러면 이름이 말하는 "러너가 받는다"를 재지 않아 다음 사람이
     * "러너까지 확인됐다"고 오해한다(감사 2차 지적).
     */
    @Test
    void 켜진_컨텍스트에서_러너가_큐_클라이언트를_받는다() {
        intakeContext("true").run(context -> {
            assertThat(context).hasSingleBean(SqsClient.class);
            assertThat(context.getBean(SqsIntakeRunner.class).hasQueueClient())
                    .as("빈은 있는데 러너가 못 받으면 켜도 폴링이 영원히 안 돈다")
                    .isTrue();
        });
    }

    /**
     * 양성 대조. 이것이 없으면 {@code hasQueueClient()}가 무조건 true를 돌려줘도
     * 위 검사가 통과한다 — 켜짐/꺼짐을 실제로 가르는지 여기서 못 박는다.
     */
    @Test
    void 꺼진_컨텍스트에서는_러너가_큐_클라이언트를_받지_않는다() {
        intakeContext("false").run(context -> {
            assertThat(context).doesNotHaveBean(SqsClient.class);
            assertThat(context.getBean(SqsIntakeRunner.class).hasQueueClient()).isFalse();
        });
    }

    /** 러너까지 올린 최소 컨텍스트. 프로세서·매퍼는 러너가 배선되는 데만 필요하다. */
    private static ApplicationContextRunner intakeContext(String enabled) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(IntakeConfiguration.class, SqsIntakeRunner.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(BroadcastEventProcessor.class,
                        () -> new StubProcessor(envelope -> ProcessResult.PROCESSED))
                .withPropertyValues(
                        "pokeclip.broadcast.intake.enabled=" + enabled,
                        "pokeclip.broadcast.intake.queue-url=http://localhost:4566/000000000000/q.fifo",
                        "pokeclip.broadcast.intake.region=ap-northeast-2",
                        "pokeclip.broadcast.intake.wait-time=20s",
                        "pokeclip.broadcast.intake.max-messages=10");
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
        return envelopeJson(eventId, streamId, sequence, "broadcast.started");
    }

    private static String envelopeJson(String eventId, String streamId, long sequence, String eventType) {
        return """
                {"schemaVersion":1,"eventId":"%s","eventType":"%s",
                 "occurredAt":"2026-08-18T00:00:00Z","streamId":"%s","streamerId":"streamer-1",
                 "sequence":%d,"traceId":"trace-1","payload":{}}
                """.formatted(eventId, eventType, streamId, sequence);
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
