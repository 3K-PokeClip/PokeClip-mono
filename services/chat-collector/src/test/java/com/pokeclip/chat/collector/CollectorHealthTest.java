package com.pokeclip.chat.collector;

import com.pokeclip.chat.collector.archive.ChatArchive;
import com.pokeclip.chat.collector.broadcast.BroadcastEventProcessor;
import com.pokeclip.chat.collector.broadcast.BroadcastSessions;
import com.pokeclip.chat.collector.broadcast.attach.StreamerSerialExecutor;
import com.pokeclip.chat.collector.broadcast.EndedStreamStore;
import com.pokeclip.chat.collector.broadcast.LifecycleEnvelope;
import com.pokeclip.chat.collector.broadcast.ProcessResult;
import com.pokeclip.chat.collector.broadcast.StreamerId;
import com.pokeclip.chat.collector.broadcast.intake.IntakeProperties;
import com.pokeclip.chat.collector.broadcast.intake.IntakeStatus;
import com.pokeclip.chat.collector.broadcast.intake.SqsIntakeRunner;
import com.pokeclip.chat.collector.broadcast.reattach.ReattachStatus;
import com.pokeclip.chat.collector.fake.FakeChzzkBehavior;
import com.pokeclip.chat.collector.fake.FakeChzzkTest;
import com.pokeclip.chat.collector.persist.ChatBuffer;
import com.pokeclip.chat.collector.session.SessionKey;
import com.pokeclip.chat.collector.session.SessionRegistry;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import com.pokeclip.chat.collector.support.TestPersistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;
import software.amazon.awssdk.services.sqs.model.SqsException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>몇 명이 붙어 있고 그중 몇이 안 걷히고 있는지가 health에 나오는가.</b>
 *
 * <p>세션이 하나뿐일 때는 "수집이 도는가"가 곧 "이 서버가 건강한가"였다. 여럿이면
 * 그 등식이 깨진다 — 열 명 중 한 명이 재연결 중이라고 전체를 DOWN으로 두면 나머지
 * 아홉이 멀쩡한데 배포가 막히고, 반대로 <b>편지를 아예 못 꺼내고 있는데</b> 붙어 있는
 * 세션이 하나도 없으면 UP처럼 보인다. 그래서 이 카드는 두 가지를 갈랐다:
 * <b>개별 세션 상태는 상세로, 전체 DOWN은 「편지를 못 받는 상태」로.</b>
 *
 * <p><b>다중 세션 문항</b>({@code .claude/skills/multi-session-test-reality}) —
 * 갈래마다 답을 주석으로 붙였다. 특히 문항 2가 위험한 자리다: health 상세 단언은
 * <b>그 키가 아예 없어도 통과하는 모양</b>으로 쓰기 쉬워서, 전부 {@code containsEntry}로
 * 값까지 본다.
 */
@FakeChzzkTest
class CollectorHealthTest extends IntegrationTestSupport {

    private static final Duration AWAIT = Duration.ofSeconds(10);
    private static final Instant 발생시각 = Instant.parse("2026-08-19T00:00:00Z");

    /** 재연결 상한을 크게 둬야 끊긴 세션이 RECONNECTING에 머문다. 짧으면 바로 붙어 관측 창이 닫힌다. */
    private static final Duration FIRST_DELAY = Duration.ofMillis(200);
    private static final Duration MAX_DELAY = Duration.ofSeconds(60);

    @LocalServerPort int port;
    @Autowired FakeChzzkBehavior behavior;
    @Autowired RestClient.Builder restClientBuilder;
    @Autowired EndedStreamStore store;

    /** 옛 경로의 상태. 편지 경로를 쓰는 프로세스에서는 DISABLED로 남는다(같이 못 켠다). */
    private final CollectionStatus legacy = new CollectionStatus();
    private final IntakeStatus intake = new IntakeStatus(true);
    /** 기본은 「켜졌고 아직 한 번도 안 돌았다」다 — 부팅 직후의 실제 상태다. */
    private ReattachStatus reattach = new ReattachStatus(true);
    private final ToggleQueue queue = new ToggleQueue();

    private SessionRegistry registry;
    private BroadcastEventProcessor processor;
    private SqsIntakeRunner intakeRunner;

    @AfterEach
    void tearDown() {
        if (registry != null) registry.closeAll();
        behavior.reset();
    }

    // 문항 1: 세션 하나로 바꾸면 activeSessions=1이라 이 단언이 빨간불이다 — 그래서 둘을 연다.
    // 문항 4: activeSessions=2는 <b>둘이 같은 스트리머여도</b> 참이다. 그때 치지직 소켓은
    //         하나뿐이고 같은 채팅이 두 번 들어온다 — 상대 쪽이 본 토큰 수를 같이 본다.
    @Test
    void 붙어_있는_방송_수가_health에_보인다() {
        given();
        registry.open(key("s1", 1L, "chA"), "tokA");
        registry.open(key("s2", 2L, "chB"), "tokB");

        assertThat(health().getDetails())
                .as("몇이 붙어 있는지가 안 나가면 「아홉은 멀쩡한데 하나가 안 걷힌다」를 밖에서 못 본다")
                .containsEntry("activeSessions", 2);
        assertThat(behavior.connectedTokens())
                .as("같은 스트리머에 둘을 세도 activeSessions는 2다. 상대 쪽에서 센다")
                .containsExactlyInAnyOrder("tokA", "tokB");
    }

    /**
     * 1번이 식별자 체계를 UUID로 바꾸면 <b>모든 방송이 조용히 안 걷힌다.</b> 편지는 계속
     * 오고 폴링도 성공하므로 health는 초록이고 예외도 없다 — 이 값이 유일한 신호다.
     */
    // 문항 2: containsEntry는 키가 없으면 빨간불이다. isNotNegative 같은 단언이면
    //         키가 없어도 통과하는 모양이 되므로 값까지 본다.
    // 문항 4: 셋을 한 카운터로 합친 구현도 이 단언 하나만으로는 통과한다 —
    //         나머지 둘이 0인지 같이 본다(1번이 고칠 자리가 셋 다 다르다).
    // 문항 5: health에서 이 항만 빼면 이 갈래만 빨간불이다(주입 C1).
    @Test
    void 신원_매칭_실패가_health에_보인다() {
        given();

        assertThat(processor.process(started("e1", "s1", "uuid-form"))).isEqualTo(ProcessResult.UNREADABLE);

        assertThat(health().getDetails())
                .containsEntry("unreadableStreamerIds", 1L)
                .containsEntry("unknownTypes", 0L)
                .containsEntry("malformedEnvelopes", 0L);
    }

    // 문항 4: 위 갈래와 같은 이유로 나머지 둘이 0인지 같이 본다.
    // 문항 5: health에서 이 항만 빼면 이 갈래만 빨간불이다(주입 C2).
    @Test
    void 모르는_종류의_편지가_health에_보인다() {
        given();

        assertThat(processor.process(envelope("e1", "broadcast.paused", "s1", "42")))
                .isEqualTo(ProcessResult.UNREADABLE);

        assertThat(health().getDetails())
                .containsEntry("unknownTypes", 1L)
                .containsEntry("unreadableStreamerIds", 0L)
                .containsEntry("malformedEnvelopes", 0L);
    }

    // 문항 4: 위 갈래와 같은 이유로 나머지 둘이 0인지 같이 본다.
    // 문항 5: health에서 이 항만 빼면 이 갈래만 빨간불이다(주입 C3).
    @Test
    void 못_쓸_봉투가_health에_보인다() {
        given();

        assertThat(processor.process(envelope("e1", "broadcast.ended", null, "42")))
                .isEqualTo(ProcessResult.UNREADABLE);

        assertThat(health().getDetails())
                .containsEntry("malformedEnvelopes", 1L)
                .containsEntry("unreadableStreamerIds", 0L)
                .containsEntry("unknownTypes", 0L);
    }

    /**
     * <b>전체 DOWN은 여기 하나다.</b> 편지를 못 꺼내면 새 방송이 하나도 안 붙는다 —
     * 그런데 이미 붙어 있던 세션은 멀쩡히 채팅을 받으므로 세션 쪽 신호로는 안 드러난다.
     *
     * <p>세 단계를 한 갈래에 둔 이유는 <b>양성 대조</b>다. DOWN만 재면
     * {@code healthy()}가 늘 false여도 통과한다(문항 2). 앞의 UP과 뒤의 UP이 그 길을 막는다.
     */
    // 문항 2: 「한 번도 못 돌았다」도 UP이라 첫 단언이 그것을 못박는다 —
    //         부팅 직후 창에서 DOWN이면 뜰 때마다 빨간불이 뜬다.
    // 문항 5: health가 IntakeStatus를 안 보게 되돌리면 가운데 DOWN이 빨간불이다(주입 B).
    @Test
    void 편지를_한_건도_못_받고_있으면_health가_DOWN이다() {
        given();
        assertThat(health().getStatus())
                .as("켜졌는데 아직 한 번도 못 돌았을 뿐이면 건강하다")
                .isEqualTo(Status.UP);

        queue.reachable = false;
        assertThat(intakeRunner.pollOnce()).as("한 회차가 실제로 실패해야 이 갈래가 성립한다").isFalse();

        assertThat(health().getStatus())
                .as("편지를 못 꺼내면 새 방송이 하나도 안 붙는데 세션 쪽 신호로는 안 드러난다")
                .isEqualTo(Status.DOWN);

        queue.reachable = true;
        assertThat(intakeRunner.pollOnce()).isTrue();
        assertThat(health().getStatus())
                .as("회복을 못 읽으면 health가 영영 DOWN이라 다시 붙은 것을 아무도 모른다")
                .isEqualTo(Status.UP);
    }

    /**
     * <b>방송이 없는 시간대가 정상이다.</b> 여기서 DOWN이면 밤마다 알람이 운다.
     */
    // 문항 2: 「UP이다」는 등록부를 아예 안 읽는 구현에서도 참이다 — 그래서
    //         activeSessions=0을 같이 단언한다. 그 키가 없으면 여기서 빨간불이다.
    @Test
    void 세션이_하나도_없어도_UP이다() {
        given();

        assertThat(registry.counts().active()).isZero();
        assertThat(health().getStatus()).isEqualTo(Status.UP);
        assertThat(health().getDetails()).containsEntry("activeSessions", 0);
    }

    /**
     * <b>한 명이 끊겼다고 전체를 DOWN으로 두면 나머지 아홉이 멀쩡한데 배포가 막힌다.</b>
     * 세션이 하나뿐일 때는 그 규칙이 맞았고, 여럿이 되면서 성립하지 않는다.
     */
    // 문항 1: 세션 하나면 「나머지는 멀쩡하다」가 성립하지 않는다. 둘을 열고 하나만 끊는다.
    // 문항 2: 「UP이다」만 재면 등록부를 안 읽는 구현도 통과한다 —
    //         reconnectingSessions=1이 그 자리를 지킨다(그 키가 없으면 빨간불).
    // 문항 4: reconnectingSessions=1은 <b>멀쩡한 쪽이 같이 끊겨도</b> 참일 수 있다.
    //         상대 쪽에서 B의 소켓이 살아 있는지를 같이 본다.
    @Test
    void 한_방송이_재연결_중이어도_전체가_DOWN이_되지는_않는다() throws Exception {
        given();
        registry.open(key("s1", 1L, "chA"), "tokA");
        registry.open(key("s2", 2L, "chB"), "tokB");
        // A만 영영 못 붙게 막는다. 안 막으면 즉시 다시 붙어 재연결 구간을 한 번도 못 본다.
        behavior.failSessionCreateFor("tokA", 503);

        behavior.dropConnectionFor("tokA");

        // 키가 없으면 NPE가 아니라 아래 단언이 이유를 말하게 한다 — 순서를 뒤집어 널을 안 깬다.
        awaitUntil(AWAIT, () -> Integer.valueOf(1).equals(health().getDetails().get("reconnectingSessions")));
        assertThat(health().getDetails())
                .as("누가 안 걷히고 있는지가 상세에 없으면 「전체는 UP」이 곧 「아무 문제 없음」으로 읽힌다")
                .containsEntry("reconnectingSessions", 1)
                .containsEntry("activeSessions", 2);
        assertThat(health().getStatus())
                .as("한 명이 끊겼다고 전체를 DOWN으로 두면 나머지가 멀쩡한데 배포가 막힌다")
                .isEqualTo(Status.UP);
        assertThat(behavior.isConnected("tokB"))
                .as("멀쩡한 쪽까지 같이 끊겼으면 reconnectingSessions=1은 우연히 맞은 것이다")
                .isTrue();
    }

    /**
     * 🔴 <b>재부착이 clip에 계속 못 닿아도 지금까지는 초록이었다.</b> 이 카드가 만든 사각이다 —
     * {@code Reattacher.sweep()}이 {@code @Scheduled}를 지키려고 어떤 실패든 삼키므로,
     * 「재배포로 잃은 방송을 줍는」 장치가 통째로 죽어 있어도 밖에서는 차이가 없다.
     *
     * <p><b>전체는 UP이다.</b> 재부착이 멈춰도 새 방송은 알림으로 그대로 붙으므로
     * 「새 방송을 하나도 못 받는 상태」(전체 DOWN의 정의)가 아니다. 여기서 DOWN을 주면
     * clip 장애가 이 서버의 배포를 막는데 재시작으로는 안 풀린다.
     *
     * <p>문항 2: 「늘 {@code ok}」인 구현도 마지막 단언은 통과한다 — 그래서 <b>회복까지</b>
     * 같은 검사에서 본다.
     */
    @Test
    void 재부착이_clip에_못_닿으면_상세에_드러나고_전체는_UP이다() {
        given();

        reattach.sweepFailed();

        Health health = health();
        assertThat(health.getDetails()).containsEntry("reattach", "failing");
        assertThat(health.getStatus())
                .as("재부착은 복구 장치다 — 멈춰도 새 방송은 알림으로 붙는다")
                .isEqualTo(Status.UP);

        reattach.sweepSucceeded();
        assertThat(health().getDetails())
                .as("회복이 표시를 지워야 한다 — 안 지우면 한 번 못 닿은 뒤로 영영 아프다")
                .containsEntry("reattach", "ok");
    }

    /**
     * <b>「꺼짐」과 「켜졌는데 아직 한 번도 안 돌았다」를 가른다.</b> 뭉치면 처음부터 clip을
     * 못 잡고 있는 프로세스가 「아직 안 돌았을 뿐」으로 읽힌다 — {@code letterIntake}의
     * {@code disabled}/{@code starting}과 같은 이유로 가른 자리다.
     */
    @Test
    void 재부착이_꺼진_것과_아직_안_돈_것을_가른다() {
        given();

        assertThat(health().getDetails())
                .as("켜졌지만 첫 회차 전이다")
                .containsEntry("reattach", "starting");

        reattach = new ReattachStatus(false);
        assertThat(health().getDetails()).containsEntry("reattach", "disabled");
    }

    // ------------------------------------------------------------------
    // 도우미
    // ------------------------------------------------------------------

    private void given() {
        registry = new SessionRegistry(
                new ChzzkProperties(true, "설정-토큰-쓰면-안-된다",
                        "http://localhost:" + port, Duration.ofSeconds(5), FIRST_DELAY, MAX_DELAY),
                restClientBuilder,
                new ChatBuffer(1_000), TestPersistence.disabledPersister(), ChatArchive.NONE);
        // 판정기는 <b>진짜 표</b>를 쓴다. 여기서 보는 갈래 셋은 표에 닿기 전에 갈리지만,
        // 가짜로 바꾸면 「닿기 전에 갈린다」는 사실 자체가 검사에서 사라진다.
        processor = new BroadcastEventProcessor(store, new RefusingSessions());
        // 줄 상한을 넉넉히 준다 — 이 검사가 재는 것은 health이지 백프레셔가 아니다.
        intakeRunner = new SqsIntakeRunner(queue, intakeProperties(), intake, processor,
                new ObjectMapper(), new StreamerSerialExecutor(100));
    }

    private Health health() {
        return healthAt(Instant.now());
    }

    /** 시계를 손에 쥐고 본다 — 「얼마나 오래 안 꺼냈나」를 재려면 필요하다. */
    private Health healthAt(Instant now) {
        return new CollectorHealth(legacy, registry, intake, reattach, provider(processor), () -> now)
                .health();
    }

    /**
     * 🔴 <b>편지를 오래 못 꺼내고 있으면 DOWN이다.</b> `services/README.md`가 이미 그렇게
     * 약속해 뒀다 — 「켜졌는데 2분 넘게 폴링 성공이 없으면 DOWN이고 상세에 {@code stalled}과
     * 마지막 성공 시각·실패 사유가 실린다」.
     *
     * <p><b>문서만 앞서 있었다</b>(codex P1, 재현함): 판정이 「실패 사유가 있나」 하나뿐이라,
     * 폴링 스레드가 {@code pollFailed}를 안 거치고 죽으면(잡히지 않은 {@code Error}·행)
     * <b>마지막 성공 기록이 그대로 남아 영원히 UP</b>이다. 그동안 새 방송이 하나도 안 붙는데
     * 액추에이터는 초록이다 — 이 서비스가 유일한 치명 실패로 규정한 「UP인데 수집 없음」의
     * 새 갈래다.
     *
     * <p><b>부팅 직후 창을 DOWN으로 만들지 않는다.</b> 아직 한 번도 못 돌았을 때는 이
     * 부품이 생긴 시각을 기준으로 재므로, 뜨자마자 빨간불이 뜨지 않는다.
     *
     * <p>문항 2: 「늘 DOWN」인 구현도 첫 단언은 통과한다 — 그래서 임계 <b>안</b>과
     * 대조군(정상 폴링)을 같은 검사에서 본다.
     * <p>문항 4: {@code status}만 보면 <b>이유가 안 실려도</b> 통과한다 — 상세의
     * {@code letterIntake}까지 본다. 그것이 없으면 운영에서 무엇이 DOWN인지 못 가른다.
     */
    @Test
    void 편지를_오래_못_꺼내면_아프다고_말한다() {
        given();
        Instant polled = Instant.parse("2026-08-19T12:00:00Z");
        intake.pollSucceeded(polled);

        // 임계 안 — 아직 건강하다.
        Health fresh = healthAt(polled.plusSeconds(119));
        assertThat(fresh.getStatus()).isEqualTo(Status.UP);
        assertThat(fresh.getDetails()).containsEntry("letterIntake", "ok");

        // 임계를 넘겼다 — 그동안 편지를 하나도 안 꺼냈다는 뜻이다.
        Health stale = healthAt(polled.plusSeconds(121));
        assertThat(stale.getStatus())
                .as("2분 넘게 못 꺼냈는데 UP이면 「UP인데 수집 없음」이다")
                .isEqualTo(Status.DOWN);
        assertThat(stale.getDetails())
                .as("무엇이 DOWN인지 상세가 말해야 운영에서 가를 수 있다")
                .containsEntry("letterIntake", "stalled")
                .containsEntry("lastLetterPollAt", polled.toString());
    }

    /**
     * <b>부르는 순서대로 방송 시작 시각이 늦어진다.</b> 대부분의 검사가 「먼저 연 방송 →
     * 나중 방송」 순으로 부르므로 그 의도와 맞는다. 앞뒤를 뒤집어 재는 검사는
     * {@link #keyStartedAt} 로 시각을 직접 준다.
     */
    private static SessionKey key(String streamId, long streamerId, String channelId) {
        return keyStartedAt(streamId, streamerId, channelId,
                Instant.EPOCH.plusSeconds(KEY_SEQ.incrementAndGet()));
    }

    private static SessionKey keyStartedAt(String streamId, long streamerId, String channelId,
                                           Instant startedAt) {
        return new SessionKey(streamId, streamerId, channelId, startedAt);
    }

    private static final java.util.concurrent.atomic.AtomicLong KEY_SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    private static LifecycleEnvelope started(String eventId, String streamId, String streamerId) {
        return envelope(eventId, "broadcast.started", streamId, streamerId);
    }

    private static LifecycleEnvelope envelope(String eventId, String eventType,
                                              String streamId, String streamerId) {
        return new LifecycleEnvelope(1, eventId, eventType, 발생시각, streamId, streamerId, 1L, "t-1", null);
    }

    private static IntakeProperties intakeProperties() {
        return new IntakeProperties(true, "http://localhost/queue", "ap-northeast-2", "",
                Duration.ofSeconds(1), 10);
    }

    private static ObjectProvider<BroadcastEventProcessor> provider(BroadcastEventProcessor processor) {
        return new ObjectProvider<>() {
            @Override
            public BroadcastEventProcessor getObject() {
                return processor;
            }

            @Override
            public BroadcastEventProcessor getObject(Object... args) {
                return processor;
            }
        };
    }

    /**
     * 못 쓸 편지 셋은 <b>세션 자리에 닿기 전에</b> 갈려야 한다. 닿으면 여기서 터진다 —
     * 「세지긴 했는데 세션도 열었다」가 조용히 통과하는 길을 막는다.
     */
    private static final class RefusingSessions implements BroadcastSessions {
        @Override
        public ProcessResult start(String streamId, StreamerId streamer, Instant startedAt) {
            throw new AssertionError("못 쓸 편지가 세션 자리까지 갔다");
        }

        @Override
        public boolean stop(String streamId) {
            throw new AssertionError("못 쓸 편지가 세션 자리까지 갔다");
        }
    }

    /**
     * 큐에 닿을지 말지를 검사가 정한다. {@code receiveMessage}만 동작하고 나머지는 SDK
     * 인터페이스의 기본 구현이 그대로라, 러너가 그 밖의 무언가를 부르면 터져서 드러난다.
     */
    private static final class ToggleQueue implements SqsClient {

        volatile boolean reachable = true;

        @Override
        public ReceiveMessageResponse receiveMessage(ReceiveMessageRequest request) {
            if (!reachable) {
                throw SqsException.builder().message("unreachable").build();
            }
            return ReceiveMessageResponse.builder().messages(List.of()).build();
        }

        @Override
        public String serviceName() {
            return "sqs";
        }

        @Override
        public void close() {
        }
    }
}
