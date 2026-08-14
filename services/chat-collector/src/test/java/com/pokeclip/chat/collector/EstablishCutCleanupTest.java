package com.pokeclip.chat.collector;

import com.pokeclip.chat.collector.chzzk.ChatSession;
import com.pokeclip.chat.collector.chzzk.ChzzkSessionClient;
import com.pokeclip.chat.collector.fake.FakeChzzkBehavior;
import com.pokeclip.chat.collector.fake.FakeChzzkTest;
import com.pokeclip.chat.collector.observe.HeartbeatListener;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>수립을 마치는 사이에 전송이 끊겼을 때</b> 부팅 스레드가 그 위에 아무것도
 * 올리지 않는지.
 *
 * <p>수립 직후의 짧은 구간에서 WS 스레드가 절단을 처리하면, 정리는 이미 끝났는데
 * 부팅 스레드는 그 사실을 모르고 스케줄러 둘을 띄우고 상태를 COLLECTING으로
 * 되돌린다. 결과는 <b>정리 완료 + health UP + 수집 없음</b>이다 —
 * {@code CollectorHealth}와 {@code CollectorRunner}가 "이 서비스의 유일한 치명적
 * 실패"라고 못박은 상태 그 자체다. 게다가 정리 가드가 이미 소모돼
 * {@code @PreDestroy}의 {@code stop()}도 아무것도 못 한다.
 *
 * <p><b>같은 구간에 우리가 멈추는 경우도 여기서 본다.</b> 수립 구간은 부팅 스레드가
 * 아직 아무것도 못 올린 채 매달려 있는 자리라, 밖에서 들어오는 사건(절단 · 종료)이
 * 전부 여기서 갈린다.
 *
 * <p><b>순서를 우연에 맡기지 않는다.</b> 가짜 서버가 구독 REST 응답을 붙들고 있는
 * 동안 끊고, 클라이언트의 구독 반납이 도착한 것을 보고서야 응답한다. 그 시점에
 * 부팅 스레드는 아직 구독 HTTP 호출 안이라, 수립 마무리는 반드시 절단 처리
 * 다음이다.
 */
@FakeChzzkTest
class EstablishCutCleanupTest extends IntegrationTestSupport {

    /** 이 이름으로 도는 스레드가 곧 "아직 일하고 있다"의 증거다. */
    private static final Set<String> WORKER_NAMES = Set.of("chzzk-ping", "chzzk-summary");

    private static final Duration AWAIT = Duration.ofSeconds(5);

    @LocalServerPort int port;
    @Autowired FakeChzzkBehavior behavior;
    @Autowired RestClient.Builder restClientBuilder;

    private CollectorRunner runner;

    @AfterEach
    void tearDown() {
        if (runner != null) runner.stop();
        behavior.reset();
    }

    @Test
    void 수립을_마치는_사이에_끊기면_COLLECTING으로_덮지_않는다() throws Exception {
        try (LogCaptor captor = new LogCaptor()) {
            // 앞 테스트가 남긴 동명 스레드를 내 것으로 세지 않도록 차집합을 쓴다.
            List<Thread> before = liveWorkers();

            behavior.closeAfterSubscribed = true;

            CollectionStatus status = new CollectionStatus();
            runner = start(status, Duration.ofSeconds(5));

            // 절단 뒤 상태가 RECONNECTING인지 STOPPED인지는 여기서 안 고정한다 —
            // 재연결이 붙으면 그 값이 갈리고, 이 파일이 지키는 것은 그 이름이 아니라
            // "COLLECTING으로 덮이지 않는다"와 "health가 UP이 아니다"다.
            assertThat(status.state())
                    .as("정리가 이미 끝났는데 COLLECTING이면 health는 UP인 채로 수집만 죽는다")
                    .isNotEqualTo(CollectionStatus.State.COLLECTING);
            assertThat(status.reason()).isEqualTo(StopReason.TRANSPORT_CLOSED);
            assertThat(new CollectorHealth(status).health().getStatus().getCode())
                    .as("수집이 죽었는데 health가 UP이면 밖에서는 아무 신호도 없다")
                    .isEqualTo("DOWN");

            List<Thread> mine = liveWorkers().stream().filter(t -> !before.contains(t)).toList();
            awaitUntil(() -> mine.stream().noneMatch(Thread::isAlive));
            assertThat(mine.stream().filter(Thread::isAlive).map(Thread::getName).toList())
                    .as("이미 닫힌 소켓 위에 올린 실행기는 ping_send_failed와 요약만 계속 뱉는다")
                    .isEmpty();

            // 뒷정리가 실제로 돌았는가. 판정 줄이 이 자리에서 빠졌으므로
            // <b>가드 재소모 여부는 반납 횟수로 본다</b> — 정리가 두 번 돌면
            // 반납도 두 번 나가고, 아예 안 돌면 0이다.
            awaitUntil(() -> behavior.unsubscribeCallCount() == 1);
            assertThat(behavior.unsubscribeCallCount())
                    .as("수립 직후 절단에서 정리가 안 돌면 구독이 서버에 남아 상한 3개를 먹는다")
                    .isEqualTo(1);

            long session = runner.lastSessionNo();
            assertThat(verdicts(captor, session))
                    .as("절단은 끝이 아니다. 거기서 판정을 내면 최종이 아닌 최종 판정이 쌓인다")
                    .isZero();

            runner.stop();

            // 양성 대조. 아예 안 나가면 위 0줄 단언은 아무것도 안 본 것이다.
            assertThat(verdicts(captor, session))
                    .as("판정이 두 줄이면 어느 것이 진짜 끝인지 흐려진다")
                    .isEqualTo(1);
            assertThat(captor.messages())
                    .anyMatch(m -> m.startsWith("chat.session.verdict session=" + session + " ")
                            && m.contains("reason=" + StopReason.TRANSPORT_CLOSED));
        }
    }

    private static long verdicts(LogCaptor captor, long session) {
        return captor.messages().stream()
                .filter(m -> m.startsWith("chat.session.verdict session=" + session + " "))
                .count();
    }

    /**
     * 같은 뿌리의 변종. 절단이 {@code open()} 진행 중에 오면 부팅 스레드는 시한
     * 만료로 떨어진다. <b>그때도 COLLECTING으로 올라가지 않고 health가 UP이 아니다</b> —
     * 이 파일이 막는 것은 그 상태이고, 어느 사유가 남느냐는 그 다음 이야기다.
     *
     * <p><b>사유는 나중에 온 것이 남는다.</b> 절단이 {@code TRANSPORT_CLOSED}를 찍은
     * 뒤 시한 만료가 그 위에 {@code ESTABLISH_TIMEOUT}을 얹는다. 둘 다 재시도해도
     * 되는 사유라 어느 쪽이 남든 재연결 판단은 안 갈리고, 사유 필드의 뜻은
     * <b>"지금 왜 못 붙고 있나"</b>다. 앞의 것은 로그에 남는다 —
     * {@code chat.session.closed reason=TRANSPORT_CLOSED}.
     */
    @Test
    void 절단이_먼저여도_수립_시한_만료는_COLLECTING으로_올라가지_않는다() {
        behavior.sendSubscribed = false;        // ⑤가 영영 안 온다
        behavior.closeAfterSubscribed = true;   // 그 전에 이미 끊겼다

        CollectionStatus status = new CollectionStatus();
        runner = start(status, Duration.ofSeconds(1));

        assertThat(status.state())
                .as("수립 시한 만료가 COLLECTING을 찍으면 정리는 끝났는데 health는 UP이 된다")
                .isNotEqualTo(CollectionStatus.State.COLLECTING);
        assertThat(new CollectorHealth(status).health().getStatus().getCode())
                .as("수집이 죽었는데 health가 UP이면 밖에서는 아무 신호도 없다")
                .isEqualTo("DOWN");
        assertThat(status.reason())
                .as("사유가 없으면 재시도할 일인지 토큰을 갈 일인지 갈리지 않는다")
                .isEqualTo(StopReason.ESTABLISH_TIMEOUT);
    }

    /**
     * 같은 구간에 <b>우리가 멈추는</b> 경우. 수립이 중단 신호를 못 보면
     * {@code stop()}이 수립 시한(운영 15초)만큼 매달려 컨테이너 종료 유예를 넘기고,
     * 그러면 SIGKILL이 와서 구독 반납이 통째로 안 나간다 — 서버가 죽은 전송을
     * 알아챌 때까지 10초~4분 42초가 걸리고 상한이 3개라 금방 못 붙게 된다.
     */
    @Test
    void 멈추는_중이면_수립이_시한을_다_안_쓰고_끊긴다() throws Exception {
        behavior.sendSubscribed = false;        // ⑤가 영영 안 온다 — 시한까지 매달린다
        Duration establishTimeout = Duration.ofSeconds(5);

        CollectionStatus status = new CollectionStatus();
        runner = new CollectorRunner(new ChzzkProperties(
                true, "test-token", "http://localhost:" + port, establishTimeout,
                Duration.ofMillis(50), Duration.ofSeconds(1)),
                status, restClientBuilder);

        // start()가 아니라 run()이다. 중단 신호로 끊긴 수립은 예외를 밖으로 던지고,
        // 직접 부르면 그것이 이 스레드를 뚫고 나가 스택 트레이스만 남는다.
        Thread booting = new Thread(() -> runner.run(null), "test-boot");
        booting.start();
        // 수립이 실제로 시작된 것을 보고서야 멈춘다. 안 보고 멈추면 "①도 안 탄 채
        // 끝났다"를 "빨리 끊었다"로 읽는다.
        awaitUntil(() -> behavior.authCallCount() == 1);

        long began = System.nanoTime();
        runner.stop();
        booting.join(establishTimeout.toMillis());
        Duration waited = Duration.ofNanos(System.nanoTime() - began);

        assertThat(booting.isAlive())
                .as("수립이 중단 신호를 안 보면 stop()이 시한을 다 쓸 때까지 안 끝난다")
                .isFalse();
        assertThat(waited)
                .as("종료가 수립 시한만큼 매달리면 유예를 넘겨 SIGKILL이 오고, "
                        + "그러면 구독 반납이 통째로 안 나간다")
                .isLessThan(establishTimeout.dividedBy(2));
    }

    /**
     * <b>같은 구간에서 {@code start()}는 "붙었다"고 보고하면 안 된다.</b>
     *
     * <p>정리가 이미 끝난 위에 아무것도 안 올리는 것과, 그 사실을 <b>값으로 알리는
     * 것</b>은 다른 일이다. 재연결 루프는 성공 여부를 이 반환값으로 읽는다 —
     * {@code status.state()}로 읽으면 거절된 호출에서 그 상태는 앞 세션이 남긴 값이라
     * 「붙었다」로 오독되고, 루프가 빠져나가 <b>아무도 재시도하지 않는다.</b>
     *
     * <p>이 줄이 없으면 그 조기 반환을 {@code true}로 바꿔도 전 검사가 초록이다
     * (변이로 확인했다).
     */
    @Test
    void 수립을_마치는_사이에_끊기면_start가_붙었다고_보고하지_않는다() {
        behavior.closeAfterSubscribed = true;

        runner = new CollectorRunner(new ChzzkProperties(
                true, "test-token", "http://localhost:" + port, Duration.ofSeconds(5),
                Duration.ofSeconds(30), Duration.ofSeconds(60)),
                new CollectionStatus(), restClientBuilder);

        assertThat(runner.start())
                .as("정리가 끝난 세션을 붙었다고 보고하면 루프가 그것을 믿고 빠져나간다")
                .isFalse();
    }

    /**
     * <b>같은 뿌리, 한 걸음 뒤의 창.</b> 위 검사는 정리가 {@code open()}이 끝나기
     * <i>전</i>에 지나간 경우를 본다. 그런데 수립 마무리는 가드를 한 번 보고 나서도
     * <b>스케줄러 둘을 세우는 동안</b> 계속 열려 있다 — 그 사이에 정리가 지나가면
     * 이렇게 된다:
     *
     * <pre>
     * 수립 스레드                    WS 콜백 / 재연결 스레드
     * 가드 검사 — 아직 false, 통과
     *                                절단 도착 → 정리가 가드를 소모
     *                                (홀더가 아직 비어 있어 스케줄러를 못 본다)
     * 하트비트·요약 기동
     * 홀더에 넣는다                  ← 아무도 이걸 다시 안 읽는다
     * COLLECTING으로 올린다          ← RECONNECTING을 받아들여 health가 UP이 된다
     * </pre>
     *
     * <p>결과는 <b>정리 완료 + health UP + 죽은 소켓에 ping</b>이다. 위 검사가 막는
     * 것과 <b>같은 치명 상태</b>인데, 가드를 보는 시점과 상태를 올리는 시점이
     * 갈라져 있어 그 사이로 빠져나간다.
     *
     * <p><b>창을 결정적으로 벌린다.</b> {@code heartbeatListener(no)}는 그 창 안에서
     * 불리는 유일한 우리 코드다 — {@code Heartbeat.start(...)}의 인자라 스케줄러가
     * 서기도 전에 평가된다. 거기서 서버가 끊고, <b>구독 반납이 도착한 것</b>(= 정리가
     * 가드를 소모하고 자리까지 비운 뒤다)을 보고서야 돌아간다. 그러면 수립 마무리는
     * 반드시 정리 다음이고, 순서가 실행마다 갈리지 않는다.
     */
    @Test
    void 가드를_본_뒤_전이하기_전에_정리가_지나가도_COLLECTING으로_올라가지_않는다() throws Exception {
        List<Thread> before = liveWorkers();

        CollectionStatus status = new CollectionStatus();
        // 재시도 간격을 크게 준다. 창을 벌리는 사이에 다음 세션이 서면 무엇을
        // 읽었는지 흐려진다.
        CutInsideWindowRunner created = new CutInsideWindowRunner(new ChzzkProperties(
                true, "test-token", "http://localhost:" + port, Duration.ofSeconds(5),
                Duration.ofSeconds(30), Duration.ofSeconds(60)),
                status, restClientBuilder, behavior);
        runner = created;

        boolean started = created.start();

        // <b>양성 대조.</b> 이 값이 참이라는 것은 수립이 ①~⑤를 다 지나 마무리
        // 직전까지 갔고, 그 자리에서 정리가 통째로 지나갔다는 뜻이다. 거짓이면
        // 아래 부정 단언들은 겨냥한 순서를 한 번도 안 만든 채 저절로 참이 된다.
        assertThat(created.windowOpened())
                .as("정리가 창 안에서 안 지나갔다면 이 검사는 아무것도 안 본 것이다")
                .isTrue();

        assertThat(started)
                .as("정리가 끝난 세션을 붙었다고 보고하면 루프가 그것을 믿고 빠져나간다")
                .isFalse();
        assertThat(status.state())
                .as("정리가 이미 끝났는데 COLLECTING이면 health는 UP인 채로 수집만 죽는다")
                .isNotEqualTo(CollectionStatus.State.COLLECTING);
        assertThat(new CollectorHealth(status).health().getStatus().getCode())
                .as("수집이 죽었는데 health가 UP이면 밖에서는 아무 신호도 없다")
                .isEqualTo("DOWN");

        // 앞 테스트가 남긴 동명 스레드를 내 것으로 세지 않도록 차집합을 쓴다.
        List<Thread> mine = liveWorkers().stream().filter(t -> !before.contains(t)).toList();
        awaitUntil(() -> mine.stream().noneMatch(Thread::isAlive));
        assertThat(mine.stream().filter(Thread::isAlive).map(Thread::getName).toList())
                .as("정리가 못 본 실행기는 아무도 안 닫는다 — 죽은 소켓에 ping을 계속 쏜다")
                .isEmpty();
    }

    /**
     * <b>「가드를 본 뒤 · 상태를 올리기 전」에 정리를 통째로 끼워 넣는 러너.</b>
     *
     * <p>가짜 서버 쪽 손잡이로는 이 자리를 못 짚는다 — 그 창 안에는 붙잡을 I/O가
     * 없어서, 밖에서 끊기만 하면 두 스레드의 경합이라 순서가 실행마다 갈린다.
     * {@code heartbeatListener(no)}가 그 창 안에서 불리는 유일한 우리 코드라
     * 여기를 장벽으로 쓴다.
     *
     * <p><b>훅에서 던지지 않는다.</b> 여기는 수립 스레드 위라, 던지면 그 예외가
     * 수립 실패로 둔갑해 "재현이 안 됐다"가 아니라 엉뚱한 자리에서 깨진다.
     */
    private static final class CutInsideWindowRunner extends CollectorRunner {

        private final FakeChzzkBehavior behavior;
        private final AtomicBoolean once = new AtomicBoolean();
        private final AtomicBoolean opened = new AtomicBoolean();

        CutInsideWindowRunner(ChzzkProperties properties, CollectionStatus status,
                              RestClient.Builder restClientBuilder, FakeChzzkBehavior behavior) {
            super(properties, status, restClientBuilder);
            this.behavior = behavior;
        }

        /** 정리가 이 창 안에서 실제로 끝까지 지나갔는가. */
        boolean windowOpened() { return opened.get(); }

        @Override
        HeartbeatListener heartbeatListener(long sessionNo) {
            HeartbeatListener real = super.heartbeatListener(sessionNo);
            if (once.compareAndSet(false, true)) {
                behavior.closeSession();
                // 구독 반납이 왔다는 것은 정리가 가드를 소모하고 자리를 비운 뒤라는 뜻이다.
                opened.set(awaitQuietly(() -> behavior.unsubscribeCallCount() == 1));
            }
            return real;
        }
    }

    /**
     * <b>정리가 세션 키보다 먼저 지나가면, 그 뒤에 생긴 구독은 아무도 반납하지 않는다.</b>
     *
     * <p>수립은 소켓(②)과 구독(④)을 서로 다른 시점에 만든다. 절단 정리가 그 사이를
     * 지나가면 <b>반납할 키가 아직 없어</b> {@code subscription=skipped}로 지나가고
     * 가드를 소모한다. 그 뒤에 ④가 만든 구독은 서버에 남아 연결 상한 3개 중 하나를
     * 먹는다 — 우리 쪽에는 그것을 반납할 경로가 하나도 안 남는다.
     *
     * <p><b>순서를 우연에 맡기지 않는다.</b> 그 창에는 붙잡을 I/O가 없어 밖에서
     * 끊기만 하면 두 스레드의 경합이 된다. {@link ChatSession#beforeSessionKey()}가
     * 그 창 안에서 도는 유일한 자리라 거기서 끊고, <b>정리가 반납 없이 지나간 증거</b>
     * ({@code released … skipped})를 보고서야 돌아간다. 반납 건수로는 못 기다린다 —
     * 이 갈래는 REST를 한 건도 안 보낸다.
     *
     * <p>{@code sendSubscribed = false}로 ⑤를 시한 만료시켜 {@code start()}가 던지게
     * 한다. 그 catch가 {@code cleanUpOnce}로 들어가 가드에 막히는 것이
     * {@code releaseLate}의 유일한 입구다.
     */
    @Test
    void 정리가_지나간_뒤에_생긴_구독도_반납한다() {
        behavior.sendSubscribed = false;        // ⑤가 영영 안 온다 — start()가 던진다

        try (LogCaptor captor = new LogCaptor()) {
            CutBeforeKeyRunner created = new CutBeforeKeyRunner(new ChzzkProperties(
                    true, "test-token", "http://localhost:" + port, Duration.ofSeconds(2),
                    Duration.ofSeconds(30), Duration.ofSeconds(60)),
                    new CollectionStatus(), restClientBuilder, behavior, captor);
            runner = created;

            // start()가 아니라 run()이다. 수립 실패를 밖으로 던지므로 직접 부르면
            // 그 예외가 이 스레드를 뚫고 나간다.
            created.run(null);

            // <b>양성 대조.</b> 정리가 창 안에서 반납 없이 지나간 것을 못 봤다면
            // 아래 단언은 겨냥한 순서를 한 번도 안 만든 채 다른 이유로 참이 된다.
            assertThat(created.windowOpened())
                    .as("정리가 키보다 먼저 지나간 것을 못 봤다면 이 검사는 아무것도 안 본 것이다")
                    .isTrue();

            long session = created.lastSessionNo();
            assertThat(behavior.unsubscribeCallCount())
                    .as("정리가 못 본 구독을 아무도 반납 안 하면 상한 3개 중 하나가 그대로 남는다")
                    .isEqualTo(1);
            assertThat(captor.messages())
                    .as("늦은 반납을 평상시 반납과 같은 줄로 내보내면 세션당 한 줄이라는 모양이 흐려진다")
                    .contains("chat.session.released session=" + session
                            + " subscription=returned late=true");
        }
    }

    /**
     * <b>「가드는 이미 소모됐는데 세션 키는 아직 안 선」 자리에 정리를 통째로 끼워
     * 넣는 러너.</b>
     *
     * <p>{@link CutInsideWindowRunner}와 창이 다르다. 저쪽은 수립이 <b>끝난 뒤</b>
     * 마무리 구간이고, 이쪽은 수립 <b>한가운데</b>라 구독이 아직 없다.
     *
     * <p><b>훅에서 던지지 않는다.</b> 여기는 수립 스레드 위라, 던지면 그 예외가
     * 수립 실패로 둔갑해 "재현이 안 됐다"가 아니라 엉뚱한 자리에서 깨진다.
     */
    private static final class CutBeforeKeyRunner extends CollectorRunner {

        private final FakeChzzkBehavior behavior;
        private final LogCaptor captor;
        private final AtomicBoolean once = new AtomicBoolean();
        private final AtomicBoolean opened = new AtomicBoolean();

        CutBeforeKeyRunner(ChzzkProperties properties, CollectionStatus status,
                           RestClient.Builder restClientBuilder,
                           FakeChzzkBehavior behavior, LogCaptor captor) {
            super(properties, status, restClientBuilder);
            this.behavior = behavior;
            this.captor = captor;
        }

        /** 정리가 이 창 안에서 반납 없이 끝까지 지나갔는가. */
        boolean windowOpened() { return opened.get(); }

        @Override
        ChatSession newSession(ChzzkSessionClient client) {
            return new ChatSession(client) {
                @Override
                protected void beforeSessionKey() {
                    if (!once.compareAndSet(false, true)) {
                        return;
                    }
                    behavior.closeSession();
                    // 번호를 상수로 박지 않는다 — LogCaptor는 JVM 전역이라
                    // 남의 러너가 늦게 찍은 줄을 내 것으로 읽는다.
                    String skipped = "chat.session.released session="
                            + CutBeforeKeyRunner.this.lastSessionNo() + " subscription=skipped";
                    opened.set(awaitQuietly(() -> captor.messages().contains(skipped)));
                }
            };
        }
    }

    /**
     * <b>①(세션 발급 REST)에 매달려 있는 동안 멈추라고 하면, 응답이 온 뒤에
     * ②로 넘어가면 안 된다.</b>
     *
     * <p>중단 신호는 원래 {@code await()}(③⑤) 안에서만 읽혔다. ①은 이미 나간 REST라
     * 못 끊고(접속 2초 + 읽기 5초), {@code stop()}은 2초만 기다리고 지나간다 —
     * 그 뒤에 여는 소켓은 <b>정리 가드가 이미 소모돼 아무도 안 닫는다.</b> 서버 쪽
     * 자리는 죽은 전송을 알아챌 때까지 10초~4분 42초 남고(실측) 상한은 3개다.
     *
     * <p><b>재연결 스레드가 아니라 별도 스레드에서 띄운다.</b> 재연결 스레드로 재현하면
     * {@code stop()}의 {@code shutdownNow()}가 ①을 인터럽트로 깨 소켓이 아예 안 생기는
     * 실행이 섞인다 — 고쳐도 안 고쳐도 같은 값이 나와 검사가 헛돈다(CP4 실측).
     * 여기서 보는 것은 인터럽트가 운 좋게 깨 주는가가 아니라 <b>수립이 신호를 보는가</b>다.
     */
    @Test
    void 멈추는_중이면_세션_발급이_끝나도_소켓을_열지_않는다() throws Exception {
        behavior.authDelay = Duration.ofMillis(800);   // ①에 세워 둔다

        CollectionStatus status = new CollectionStatus();
        runner = new CollectorRunner(new ChzzkProperties(
                true, "test-token", "http://localhost:" + port, Duration.ofSeconds(5),
                Duration.ofSeconds(30), Duration.ofSeconds(60)),
                status, restClientBuilder);

        Thread booting = new Thread(() -> runner.run(null), "test-boot");
        booting.start();
        // 발급 요청이 서버에 <b>도착한</b> 시점이다(응답 전에 센다). 이 시점에
        // 부팅 스레드는 ① 안에 서 있다.
        awaitUntil(() -> behavior.authCallCount() == 1);
        assertThat(behavior.connectionCount())
                .as("이미 붙었다면 ①에 세워 두지 못한 것이고, 이 검사는 ①을 안 지난다")
                .isZero();

        runner.stop();
        booting.join(Duration.ofSeconds(5).toMillis());

        assertThat(booting.isAlive())
                .as("①이 끝난 뒤까지 안 봤다면 '아직 안 열었다'를 '안 연다'로 읽는다")
                .isFalse();
        assertThat(behavior.connectionCount())
                .as("멈추라고 한 뒤에 연 소켓은 stop()이 이미 지나가 아무도 안 닫는다")
                .isZero();
    }

    /**
     * <b>②(WS 접속)에 매달려 있는 동안 멈추라고 하면 거기서 끊어야 한다.</b>
     *
     * <p>②는 {@code buildAsync(...).join()}이라 <b>인터럽트에도 중단 신호에도 반응하지
     * 않는다.</b> 상대가 TCP만 받고 핸드셰이크에 답하지 않으면 접속 시한(5초)을 통째로
     * 쓰고, 그동안 {@code stop()}은 {@code awaitTermination}(2초)을 만료시킨 뒤
     * {@code shutdownNow()}로 넘어간다 — 그 뒤에 늦게 성립한 소켓은 아무도 안 닫는다.
     *
     * <p><b>가짜 서버로는 이 상태를 못 만든다.</b> 스프링 WS 핸들러는 핸드셰이크를
     * 언제나 즉시 끝낸다. 그래서 <b>받기만 하고 말이 없는 소켓</b>을 직접 띄우고
     * 세션 url의 포트를 그리로 돌린다.
     */
    @Test
    void 멈추는_중이면_WS_접속에_매달려_있어도_바로_끊긴다() throws Exception {
        try (java.net.ServerSocket silent = new java.net.ServerSocket(0)) {
            List<java.net.Socket> accepted = new java.util.concurrent.CopyOnWriteArrayList<>();
            Thread acceptor = new Thread(() -> {
                try {
                    accepted.add(silent.accept());     // 받아만 두고 아무 말도 안 한다
                } catch (Exception ignored) {
                    // 테스트가 끝나며 닫은 것이다.
                }
            }, "silent-accept");
            acceptor.setDaemon(true);
            acceptor.start();

            behavior.sessionUrlPort = silent.getLocalPort();
            Duration establishTimeout = Duration.ofSeconds(5);

            CollectionStatus status = new CollectionStatus();
            runner = new CollectorRunner(new ChzzkProperties(
                    true, "test-token", "http://localhost:" + port, establishTimeout,
                    Duration.ofSeconds(30), Duration.ofSeconds(60)),
                    status, restClientBuilder);

            Thread booting = new Thread(() -> runner.run(null), "test-boot");
            booting.start();
            // 실제로 ②에 매달린 것을 보고서야 멈춘다. 안 보고 멈추면 "②를 시작도
            // 안 한 채 끝났다"를 "빨리 끊었다"로 읽는다.
            awaitUntil(() -> !accepted.isEmpty());
            assertThat(accepted)
                    .as("접속이 안 들어왔다면 ②에 매달린 적이 없다")
                    .isNotEmpty();

            long began = System.nanoTime();
            runner.stop();
            booting.join(establishTimeout.multipliedBy(2).toMillis());
            Duration waited = Duration.ofNanos(System.nanoTime() - began);

            assertThat(booting.isAlive())
                    .as("②가 중단 신호를 안 보면 접속 시한을 통째로 쓴다")
                    .isFalse();
            assertThat(waited)
                    .as("종료가 접속 시한만큼 매달리면 유예를 넘겨 SIGKILL이 오고, "
                            + "그러면 구독 반납이 통째로 안 나간다")
                    .isLessThan(Duration.ofSeconds(1));
        }
    }

    /**
     * <b>{@code run()}으로 띄운다.</b> {@code start()}는 수립 실패를 밖으로 던진다.
     *
     * <p>재시도 간격을 크게 준다. 이 파일이 보는 것은 <b>수립을 마치는 그 한 구간</b>이라,
     * 짧은 간격이면 같은 구간이 계속 다시 만들어져 무엇을 읽었는지 흐려진다.
     */
    private CollectorRunner start(CollectionStatus status, Duration establishTimeout) {
        CollectorRunner created = new CollectorRunner(new ChzzkProperties(
                true, "test-token", "http://localhost:" + port, establishTimeout,
                Duration.ofSeconds(30), Duration.ofSeconds(60)),
                status, restClientBuilder);
        created.run(null);
        return created;
    }

    private static List<Thread> liveWorkers() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> WORKER_NAMES.contains(t.getName()))
                .filter(Thread::isAlive)
                .toList();
    }

    /** 조건이 설 때까지 기다린다. 안 서면 그대로 다음 단언이 사실을 말한다. */
    private static void awaitUntil(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + AWAIT.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    /**
     * 수립 스레드 위에서 기다린다. <b>결과를 값으로 돌려준다</b> — 거기서 던지면
     * 예외가 수립 실패로 둔갑해, 재현이 안 된 것을 엉뚱한 실패로 읽게 된다.
     */
    private static boolean awaitQuietly(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + AWAIT.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }
}
