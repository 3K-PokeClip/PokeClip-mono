package com.pokeclip.clip.broadcast.intake;

import ch.qos.logback.classic.Level;
import com.pokeclip.clip.broadcast.BroadcastEventProcessor;
import com.pokeclip.clip.broadcast.LifecycleEnvelope;
import com.pokeclip.clip.broadcast.ProcessResult;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * <b>같은 그룹의 편지 둘이 한 배치에 온다</b>(감사자 실측: maxNumberOfMessages=10에
     * started·ended가 순서대로). 앞 편지 처리가 실패했는데 for 루프가 계속 돌면
     * 뒤 편지가 명부를 앞질러 {@code lastSequence}를 올리고, 재전송된 앞 편지는
     * {@code IGNORED_STALE}이 되어 <b>러너가 지운다</b> — 큐가 비고 편지 기록이 남아
     * 재전송으로도 못 고치는 영구 유실이다(PR #82 P1, 전 과정 재현됨).
     *
     * <p>그래서 못 지운 편지가 나오면 <b>그 회차를 통째로 끝낸다.</b> 안 지운 것들은
     * 가시성 타임아웃 뒤 같은 순서로 다시 온다.
     */
    @Test
    void 배치_중_하나가_실패하면_그_회차의_뒤_편지를_처리하지_않는다() {
        FakeSqsClient sqs = FakeSqsClient.withMessages(
                startedJson("evt-s", "s1", 1L),
                envelopeJson("evt-e", "s1", 2L, "broadcast.ended"));
        List<String> seen = new ArrayList<>();
        SqsIntakeRunner runner = newRunner(sqs, envelope -> {
            seen.add(envelope.eventId());
            throw new IllegalStateException("일시적 실패");
        });

        runner.pollOnce();

        assertThat(seen)
                .as("앞 편지를 못 지웠는데 뒤 편지를 처리하면 명부가 앞질러 간다")
                .containsExactly("evt-s");
        assertThat(sqs.deletedReceiptHandles()).isEmpty();
    }

    /** 앞 편지가 잘 끝났으면 뒤 편지도 같은 회차에서 처리한다 — 중단이 지나치게 넓으면 안 된다. */
    @Test
    void 앞_편지가_성공하면_같은_회차에서_뒤_편지도_처리한다() {
        FakeSqsClient sqs = FakeSqsClient.withMessages(
                startedJson("evt-s", "s1", 1L),
                envelopeJson("evt-e", "s1", 2L, "broadcast.ended"));
        SqsIntakeRunner runner = newRunner(sqs, envelope -> ProcessResult.PROCESSED);

        runner.pollOnce();

        assertThat(sqs.deletedReceiptHandles()).containsExactly("rh-0", "rh-1");
    }

    /**
     * 버리는 갈래(형식 깨짐·모르는 종류)는 <b>중단 사유가 아니다.</b> 그 편지는 지워졌으니
     * 큐 앞을 막지 않는다 — 여기서 멈추면 멀쩡한 뒤 편지가 공연히 미뤄진다.
     */
    @Test
    void 읽을_수_없는_편지가_섞여_있어도_뒤_편지는_그_회차에서_처리한다() {
        FakeSqsClient sqs = FakeSqsClient.withMessages(
                "{ 이건 JSON이 아니다", startedJson("evt-s", "s1", 1L));
        SqsIntakeRunner runner = newRunner(sqs, envelope -> ProcessResult.PROCESSED);

        runner.pollOnce();

        assertThat(sqs.deletedReceiptHandles()).containsExactly("rh-0", "rh-1");
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

    /**
     * <b>칸이 빠진 봉투가 파싱을 통과한다.</b> 글자 칸(eventId·streamId·streamerId)은
     * 없어도 null로 바인딩되고, 저장에서 NOT NULL 위반이 나 {@code handle_failed}가
     * 된다 — 삭제되지 않으므로 <b>영구 반복</b>이고 FIFO라 같은 그룹의 뒤 편지가 막힌다
     * (PR #82 P2, 감사자 재현).
     *
     * <p>{@code occurredAt}은 다르게 아프다 — 그냥 PROCESSED가 되어 {@code started_at}이
     * 빈 줄을 만들고, <b>역순 도착 placeholder와 구분되지 않는다</b>(감사자가 봇 지적
     * 밖에서 찾았다).
     *
     * <p>넷 다 재시도해도 계속 같으므로 "읽을 수 없는 편지"와 같은 갈래로 보낸다.
     * 로그 키는 나눈다 — 형식이 깨진 것과 칸이 빠진 것은 다른 신호다.
     */
    @ParameterizedTest(name = "{0} 누락")
    @ValueSource(strings = {"eventId", "streamId", "streamerId", "occurredAt"})
    void 필수_칸이_빠진_봉투는_지우고_어느_칸인지_남긴다(String missing) {
        FakeSqsClient sqs = FakeSqsClient.withMessages(envelopeWithout(missing));
        SqsIntakeRunner runner = newRunner(sqs, envelope -> ProcessResult.PROCESSED);

        try (LogCaptor captor = new LogCaptor()) {
            runner.pollOnce();

            assertThat(captor.messages())
                    .as("형식이 깨진 것과 칸이 빠진 것은 다른 신호라 키를 나눈다")
                    .anyMatch(m -> m.contains("broadcast.intake.incomplete_dropped"));
            assertThat(captor.messages())
                    .as("어느 칸이 빠졌는지 없으면 발행 쪽을 못 고친다")
                    .anyMatch(m -> m.contains(missing) && m.contains("reason=missing"));
            assertThat(captor.levelOf("broadcast.intake.incomplete_dropped")).isEqualTo(Level.WARN);
        }

        assertThat(sqs.deletedReceiptHandles())
                .as("안 지우면 영구 반복이고 FIFO라 뒤 편지가 막힌다")
                .containsExactly("rh-0");
    }

    /**
     * <b>PostgreSQL은 긴 값을 자르지 않고 거부한다</b>
     * ({@code value too long for type character varying(128)}, 감사자 재현).
     * 걸러내지 않으면 저장에서 터져 {@code handle_failed}가 되는데, <b>값이 안 바뀌므로
     * 재전송해도 영영 안 풀리고</b> FIFO라 뒤 이벤트는 시도조차 못 한다 —
     * 그동안 헬스체크는 큐에 닿고 있어 초록이다.
     *
     * <p>빠진 칸과 같은 갈래로 버리되 <b>사유는 나눈다</b> — 발행자에게 "빠졌다"와
     * "너무 길다"는 다른 신호다.
     */
    @ParameterizedTest(name = "{0} 129자")
    @ValueSource(strings = {"eventId", "streamId", "streamerId"})
    void 식별자가_칸_폭을_넘으면_지우고_너무_길다고_남긴다(String field) {
        FakeSqsClient sqs = FakeSqsClient.withMessages(envelopeWith(field, "x".repeat(129)));
        SqsIntakeRunner runner = newRunner(sqs, envelope -> ProcessResult.PROCESSED);

        try (LogCaptor captor = new LogCaptor()) {
            runner.pollOnce();

            assertThat(captor.messages())
                    .as("빠진 칸과 사유가 구분되지 않으면 발행 쪽이 어디를 볼지 모른다")
                    .anyMatch(m -> m.contains("broadcast.intake.incomplete_dropped")
                            && m.contains(field) && m.contains("reason=too_long"));
        }

        assertThat(sqs.deletedReceiptHandles())
                .as("안 지우면 값이 안 바뀌어 영영 안 풀리고 뒤 이벤트가 막힌다")
                .containsExactly("rh-0");
    }

    /**
     * <b>경계의 반대쪽.</b> 이것이 없으면 "무조건 거른다"로 바꿔도 위 검사가 통과한다.
     * 128자는 칸에 정확히 들어가므로 평소대로 처리돼야 한다.
     */
    @ParameterizedTest(name = "{0} 128자")
    @ValueSource(strings = {"eventId", "streamId", "streamerId"})
    void 식별자가_칸_폭과_같으면_평소대로_처리한다(String field) {
        FakeSqsClient sqs = FakeSqsClient.withMessages(envelopeWith(field, "x".repeat(128)));
        SqsIntakeRunner runner = newRunner(sqs, envelope -> ProcessResult.PROCESSED);

        try (LogCaptor captor = new LogCaptor()) {
            runner.pollOnce();

            assertThat(captor.messages())
                    .as("칸에 들어가는 값을 버리면 멀쩡한 이벤트가 사라진다")
                    .noneMatch(m -> m.contains("broadcast.intake.incomplete_dropped"));
            assertThat(captor.messages()).anyMatch(m -> m.contains("broadcast.intake.handled"));
        }

        assertThat(sqs.deletedReceiptHandles()).containsExactly("rh-0");
    }

    /**
     * 숫자 칸은 이미 파싱 갈래가 막는다 — Jackson 3가 기본형에 null을 매핑할 때
     * MismatchedInputException을 던진다(감사자 실측: 봇 지적의 이 대목은 사실과 다르다).
     * 여기서 확인하는 것은 그 갈래가 <b>여전히</b> 삭제로 이어진다는 것이다.
     */
    @Test
    void 숫자_칸이_빠진_봉투는_파싱_갈래가_막는다() {
        FakeSqsClient sqs = FakeSqsClient.withMessages(envelopeWithout("sequence"));
        SqsIntakeRunner runner = newRunner(sqs, envelope -> ProcessResult.PROCESSED);

        try (LogCaptor captor = new LogCaptor()) {
            runner.pollOnce();

            assertThat(captor.messages())
                    .anyMatch(m -> m.contains("broadcast.intake.unreadable_dropped"));
        }

        assertThat(sqs.deletedReceiptHandles()).containsExactly("rh-0");
    }

    /** 정상 봉투가 이 갈래로 새면 안 된다. */
    @Test
    void 칸이_다_있는_봉투는_불완전_갈래로_새지_않는다() {
        FakeSqsClient sqs = FakeSqsClient.withMessages(startedJson("evt-1", "s1", 1L));
        SqsIntakeRunner runner = newRunner(sqs, envelope -> ProcessResult.PROCESSED);

        try (LogCaptor captor = new LogCaptor()) {
            runner.pollOnce();

            assertThat(captor.messages())
                    .noneMatch(m -> m.contains("broadcast.intake.incomplete_dropped"));
        }
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

    /**
     * 시작 시각은 <b>빈이 만들어진 때가 아니라 루프가 도는 때</b>여야 한다 —
     * 컨텍스트 로딩과 실제 시작 사이에 간격이 있고, 꺼져 있으면 루프가 아예 안 돈다.
     */
    @Timeout(value = 10, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @Test
    void 루프가_시작하면_상태에_시작_시각이_남는다() throws Exception {
        IntakeStatus status = new IntakeStatus(true);
        SqsIntakeRunner runner = newRunner(FakeSqsClient.thatFails(),
                envelope -> ProcessResult.PROCESSED, status, new RecordingSleeper(1));

        runner.startLoop();
        runner.stop();

        assertThat(status.snapshot().loopStartedAt()).isNotNull();
    }

    /**
     * 양성 대조. 이것이 없으면 시작 시각을 생성 시점에 무조건 찍어도 위 검사가
     * 통과하고, 그러면 꺼진 상태에서도 "기동 중"으로 보인다.
     */
    @Test
    void 꺼져_있으면_루프가_안_돌고_시작_시각도_안_남는다() {
        IntakeStatus status = new IntakeStatus(false);
        SqsIntakeRunner runner = newRunner(null,
                envelope -> ProcessResult.PROCESSED, status, new RecordingSleeper(1));

        runner.startLoop();

        assertThat(status.snapshot().loopStartedAt()).isNull();
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

    // ── 종료 알림 (태스크 10) ──────────────────────────────────────────────

    @Test
    void 종료_편지를_PROCESSED로_처리하면_리스너가_불린다() {
        List<String> called = new ArrayList<>();
        FakeSqsClient sqs = FakeSqsClient.withMessages(endedBody("evt-e", "stream-1", 2L));

        newRunnerWithListener(sqs, envelope -> ProcessResult.PROCESSED, called::add).pollOnce();

        assertThat(called).containsExactly("stream-1");
    }

    /**
     * DUPLICATE·IGNORED_STALE은 <b>명부를 안 바꿨다.</b> 그런데도 알리면 붙어 있는 화면이
     * 멀쩡히 진행 중인 방송에서 쫓겨난다.
     */
    @Test
    void 시작_편지나_DUPLICATE_IGNORED_STALE에는_안_불린다() {
        List<String> called = new ArrayList<>();

        newRunnerWithListener(FakeSqsClient.withMessages(baseEnvelopeJson()),
                envelope -> ProcessResult.PROCESSED, called::add).pollOnce();
        assertThat(called).as("시작 편지에는 안 불려야 한다").isEmpty();

        newRunnerWithListener(FakeSqsClient.withMessages(endedBody("evt-d", "stream-2", 2L)),
                envelope -> ProcessResult.DUPLICATE, called::add).pollOnce();
        newRunnerWithListener(FakeSqsClient.withMessages(endedBody("evt-s", "stream-3", 2L)),
                envelope -> ProcessResult.IGNORED_STALE, called::add).pollOnce();

        assertThat(called).as("명부를 안 바꾼 결과에는 안 불려야 한다").isEmpty();
    }

    /** 알림 실패는 편지를 되돌리지 않는다 — 이미 지웠고 명부는 반영됐다. */
    @Test
    void 리스너가_던져도_편지는_지워지고_회차는_계속된다() {
        FakeSqsClient sqs = FakeSqsClient.withMessages(endedBody("evt-e", "stream-1", 2L));

        try (LogCaptor captor = new LogCaptor()) {
            boolean ok = newRunnerWithListener(sqs, envelope -> ProcessResult.PROCESSED,
                    streamId -> {
                        throw new IllegalStateException("알림 실패");
                    }).pollOnce();

            assertThat(ok).as("알림 실패로 회차가 실패로 끝나면 편지가 다시 온다").isTrue();
            assertThat(sqs.deletedReceiptHandles()).as("이미 명부에 반영됐는데 편지가 남았다").hasSize(1);
            assertThat(captor.messages())
                    .anyMatch(m -> m.startsWith("broadcast.intake.ended_listener_failed"));
        }
    }

    /**
     * <b>편지를 못 지운 것과 방송이 끝난 것은 별개다.</b> {@code delete}가 던지면 그 뒤의
     * {@code notifyEnded}까지 못 가서 <b>붙어 있는 화면이 종료를 영영 못 받는다</b> —
     * 재전달이 와도 {@code processor}가 {@code DUPLICATE}를 주고 알림은 {@code PROCESSED}만
     * 타므로 <b>두 번째 기회가 없다</b>(PR #111 봇 지적 ①, 2026-08-23 재현).
     *
     * <p>그래서 알림을 삭제 <b>앞</b>으로 옮겼다. 알림은 명부가 이미 반영된 뒤의 통보이고,
     * 편지를 지우는 것과 순서를 맞출 이유가 없다.
     */
    @Test
    void 삭제가_실패해도_종료_알림은_간다() {
        FakeSqsClient sqs = FakeSqsClient.thatFailsOnDelete(endedBody("evt-e", "s-1", 2L));
        List<String> notified = new ArrayList<>();

        newRunnerWithListener(sqs, envelope -> ProcessResult.PROCESSED, notified::add).pollOnce();

        assertThat(sqs.deletedReceiptHandles()).as("삭제는 여전히 실패한 채다").isEmpty();
        assertThat(notified)
                .as("편지를 못 지웠다는 이유로 종료 알림이 사라지면 화면이 끝난 방송에 남는다")
                .containsExactly("s-1");
    }

    /**
     * <b>순서를 바꿔도 안 닫히는 구멍</b>을 못 박는다. 재전달은 {@code DUPLICATE}라 알림을
     * 안 타므로, <b>유실을 실제로 막는 것은 순서가 아니라 「첫 번째에 알렸는가」다.</b>
     * 이 갈래가 초록인 채로 남아 있어야 다음 사람이 "재전달이 메워 준다"고 오해하지 않는다.
     */
    @Test
    void 재전달은_어느_순서에서도_알림을_안_탄다() {
        FakeSqsClient sqs = FakeSqsClient.withMessages(endedBody("evt-e", "s-1", 2L));
        List<String> notified = new ArrayList<>();

        newRunnerWithListener(sqs, envelope -> ProcessResult.DUPLICATE, notified::add).pollOnce();

        assertThat(sqs.deletedReceiptHandles()).hasSize(1);
        assertThat(notified).as("DUPLICATE가 알림을 타면 멀쩡한 방송에서 화면이 쫓겨난다").isEmpty();
    }

    /** 리스너 없이 만드는 기존 생성자 경로가 그대로 살아 있어야 한다. */
    @Test
    void 리스너가_없으면_기존과_같다() {
        FakeSqsClient sqs = FakeSqsClient.withMessages(endedBody("evt-e", "stream-1", 2L));

        assertThat(newRunner(sqs, envelope -> ProcessResult.PROCESSED).pollOnce()).isTrue();
        assertThat(sqs.deletedReceiptHandles()).hasSize(1);
    }

    private static String endedBody(String eventId, String streamId, long sequence) {
        return """
                {"schemaVersion":1,"eventId":"%s","eventType":"broadcast.ended",
                 "occurredAt":"2026-08-23T01:00:00Z","streamId":"%s","streamerId":"%s",
                 "sequence":%d,"traceId":"trace-1","payload":{}}
                """.formatted(eventId, streamId, TestIds.STREAMER, sequence);
    }

    private static String baseEnvelopeJson() {
        return new ObjectMapper().writeValueAsString(baseEnvelope());
    }

    private static SqsIntakeRunner newRunnerWithListener(SqsClient sqs,
                                                         Function<LifecycleEnvelope, ProcessResult> behavior,
                                                         EndedListener listener) {
        return new SqsIntakeRunner(sqs, propertiesFor(), new IntakeStatus(true),
                new StubProcessor(behavior), new ObjectMapper(), null, listener);
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

    /** 정상 봉투에서 칸 하나만 뺀다. */
    private static String envelopeWithout(String field) {
        Map<String, Object> envelope = baseEnvelope();
        assertThat(envelope.remove(field)).as("없는 칸 이름을 뺐다: %s", field).isNotNull();
        return new ObjectMapper().writeValueAsString(envelope);
    }

    /** 정상 봉투에서 칸 하나만 바꾼다. */
    private static String envelopeWith(String field, String value) {
        Map<String, Object> envelope = baseEnvelope();
        assertThat(envelope.put(field, value)).as("없는 칸 이름을 바꿨다: %s", field).isNotNull();
        return new ObjectMapper().writeValueAsString(envelope);
    }

    private static Map<String, Object> baseEnvelope() {
        return new LinkedHashMap<>(Map.of(
                "schemaVersion", 1,
                "eventId", "evt-1",
                "eventType", "broadcast.started",
                "occurredAt", "2026-08-18T00:00:00Z",
                "streamId", "s1",
                "streamerId", TestIds.STREAMER,
                "sequence", 1,
                "traceId", "trace-1",
                "payload", Map.of()));
    }

    private static String startedJson(String eventId, String streamId, long sequence) {
        return envelopeJson(eventId, streamId, sequence, "broadcast.started");
    }

    private static String envelopeJson(String eventId, String streamId, long sequence, String eventType) {
        return """
                {"schemaVersion":1,"eventId":"%s","eventType":"%s",
                 "occurredAt":"2026-08-18T00:00:00Z","streamId":"%s","streamerId":"%s",
                 "sequence":%d,"traceId":"trace-1","payload":{}}
                """.formatted(eventId, eventType, streamId, TestIds.STREAMER, sequence);
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
