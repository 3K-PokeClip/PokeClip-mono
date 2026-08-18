package com.pokeclip.clip.broadcast.intake;

import ch.qos.logback.classic.Level;
import com.pokeclip.clip.broadcast.BroadcastEventProcessor;
import com.pokeclip.clip.broadcast.LifecycleEnvelope;
import com.pokeclip.clip.broadcast.ProcessResult;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
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

    /**
     * <b>백오프가 없으면 큐에 못 닿는 동안 루프가 쉬지 않고 돈다.</b> 롱폴링 20초는
     * 연결이 성립한 뒤의 이야기라, 권한이 빠졌거나 없는 큐를 가리키면
     * receiveMessage가 즉시 던지고 곧바로 다음 회차가 시작된다 — 코어 하나가
     * 100%로 돌고 poll_failed가 초당 수백 줄 쌓인다.
     *
     * <p><b>실제로 자지 않고 "얼마나 자라고 했는지"를 잰다.</b> 시간에 기대면
     * 간헐 실패를 부른다.
     */
    // 루프를 실제로 돌리는 검사다. 백오프가 사라지면 아무것도 루프를 멈추지 않아
    // 영원히 돈다 — 시한이 없으면 CI가 멈춘 채로 매달린다. SEPARATE_THREAD가 아니면
    // 기본 시한은 메서드가 끝난 뒤에야 판정하므로 무한 루프를 못 끊는다(실측).
    // 정상 경로는 마이크로초라 10초는 타이밍 단언이 아니라 안전장치다.
    @Timeout(value = 10, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @Test
    void 폴링이_계속_실패하면_쉬는_간격이_두_배씩_늘어난다() {
        RecordingSleeper sleeper = new RecordingSleeper(4);
        SqsIntakeRunner runner = newRunner(FakeSqsClient.thatFails(),
                envelope -> ProcessResult.PROCESSED, new IntakeStatus(true), sleeper);

        runner.runLoop();

        assertThat(sleeper.requested()).containsExactly(
                Duration.ofSeconds(1), Duration.ofSeconds(2),
                Duration.ofSeconds(4), Duration.ofSeconds(8));
    }

    /**
     * 성공하면 간격을 되돌린다. 안 되돌리면 한 번 흔들린 뒤로 영영 60초마다 한 번씩만
     * 꺼내게 된다 — 큐가 멀쩡해졌는데도 처리가 느려진다.
     */
    // 루프를 실제로 돌리는 검사다. 백오프가 사라지면 아무것도 루프를 멈추지 않아
    // 영원히 돈다 — 시한이 없으면 CI가 멈춘 채로 매달린다. SEPARATE_THREAD가 아니면
    // 기본 시한은 메서드가 끝난 뒤에야 판정하므로 무한 루프를 못 끊는다(실측).
    // 정상 경로는 마이크로초라 10초는 타이밍 단언이 아니라 안전장치다.
    @Timeout(value = 10, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @Test
    void 폴링에_성공하면_쉬는_간격이_처음으로_돌아간다() {
        RecordingSleeper sleeper = new RecordingSleeper(3);
        // 실패 2회 → 성공 1회 → 그 뒤 계속 실패(대본이 끝나면 실패한다)
        SqsIntakeRunner runner = newRunner(FakeSqsClient.scripted(false, false, true),
                envelope -> ProcessResult.PROCESSED, new IntakeStatus(true), sleeper);

        runner.runLoop();

        assertThat(sleeper.requested())
                .as("성공 뒤에도 4초로 이어가면 간격을 안 되돌린 것이다")
                .containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(1));
    }

    /** 성공하면 안 쉰다 — 롱폴링이 이미 대기 역할을 한다. */
    @Test
    void 폴링에_성공하면_쉬지_않는다() {
        RecordingSleeper sleeper = new RecordingSleeper(1);
        SqsIntakeRunner runner = newRunner(FakeSqsClient.withMessages(),
                envelope -> ProcessResult.PROCESSED, new IntakeStatus(true), sleeper);

        runner.pollOnce();

        assertThat(sleeper.requested()).isEmpty();
    }

    /**
     * <b>자는 동안에도 종료 신호에 반응해야 한다.</b> stop()이 25초를 기다리는데
     * 백오프가 60초면 종료가 그만큼 늦어진다.
     *
     * <p>실물 sleeper를 쓰되 <b>먼저 멈춘 뒤</b> 잰다 — 신호가 이미 와 있으면
     * 즉시 true로 돌아와야 한다. Thread.sleep으로 구현돼 있으면 60초를 꼬박 자고
     * false를 돌려준다(느린 실패이지 간헐 실패가 아니다).
     */
    @Test
    void 자는_동안_종료_신호가_오면_기다리지_않고_깬다() throws Exception {
        SqsIntakeRunner runner = newRunner(FakeSqsClient.thatFails(),
                envelope -> ProcessResult.PROCESSED);

        runner.stop();

        assertThat(runner.awaitStop(Duration.ofSeconds(60)))
                .as("종료 신호를 받고도 시한을 다 채우면 배포가 그만큼 늦어진다")
                .isTrue();
    }

    /** 자는 도중 종료 신호가 오면 루프가 거기서 끝난다. */
    // 루프를 실제로 돌리는 검사다. 백오프가 사라지면 아무것도 루프를 멈추지 않아
    // 영원히 돈다 — 시한이 없으면 CI가 멈춘 채로 매달린다. SEPARATE_THREAD가 아니면
    // 기본 시한은 메서드가 끝난 뒤에야 판정하므로 무한 루프를 못 끊는다(실측).
    // 정상 경로는 마이크로초라 10초는 타이밍 단언이 아니라 안전장치다.
    @Timeout(value = 10, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @Test
    void 자는_도중_종료_신호가_오면_루프가_끝난다() {
        RecordingSleeper sleeper = new RecordingSleeper(2);
        SqsIntakeRunner runner = newRunner(FakeSqsClient.thatFails(),
                envelope -> ProcessResult.PROCESSED, new IntakeStatus(true), sleeper);

        runner.runLoop();

        assertThat(sleeper.requested()).hasSize(2);
    }

    /** 요청받은 대기 시간만 적어 둔다. 실제로는 안 잔다 — 정해진 횟수 뒤 멈추라고 답한다. */
    private static final class RecordingSleeper implements SqsIntakeRunner.Sleeper {

        private final List<Duration> requested = new ArrayList<>();
        private final int stopAfter;

        RecordingSleeper(int stopAfter) {
            this.stopAfter = stopAfter;
        }

        List<Duration> requested() {
            return List.copyOf(requested);
        }

        @Override
        public boolean sleepOrStop(Duration duration) {
            requested.add(duration);
            return requested.size() >= stopAfter;
        }
    }

    private static SqsIntakeRunner newRunner(SqsClient sqs,
                                             Function<LifecycleEnvelope, ProcessResult> behavior,
                                             IntakeStatus status,
                                             SqsIntakeRunner.Sleeper sleeper) {
        return new SqsIntakeRunner(sqs, propertiesFor(), status, new StubProcessor(behavior),
                new ObjectMapper(), sleeper);
    }

    private static SqsIntakeRunner newRunner(SqsClient sqs,
                                             Function<LifecycleEnvelope, ProcessResult> behavior) {
        return newRunner(sqs, behavior, new IntakeStatus(true));
    }

    private static SqsIntakeRunner newRunner(SqsClient sqs,
                                             Function<LifecycleEnvelope, ProcessResult> behavior,
                                             IntakeStatus status) {
        return new SqsIntakeRunner(sqs, propertiesFor(), status, new StubProcessor(behavior),
                new ObjectMapper());
    }

    private static IntakeProperties propertiesFor() {
        return new IntakeProperties(true, "http://localhost:4566/000000000000/q.fifo",
                "ap-northeast-2", null, Duration.ofSeconds(20), 10);
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
