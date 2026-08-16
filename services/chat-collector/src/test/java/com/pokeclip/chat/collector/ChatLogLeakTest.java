package com.pokeclip.chat.collector;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.StackTraceElementProxy;
import com.pokeclip.chat.collector.archive.ArchiveCounters;
import com.pokeclip.chat.collector.fake.FakeChzzkBehavior;
import com.pokeclip.chat.collector.fake.FakeChzzkTest;
import com.pokeclip.chat.collector.observe.Heartbeat;
import com.pokeclip.chat.collector.observe.SummaryLogger;
import com.pokeclip.chat.collector.persist.ChatBuffer;
import com.pokeclip.chat.collector.persist.ChatPersister;
import com.pokeclip.chat.collector.persist.PersistableChat;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import com.pokeclip.chat.collector.support.TestPersistence;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T7. 채팅 본문·작성자 식별자·닉네임·토큰이 <b>어느 경로에서도</b> 로그에 안 남는지.
 *
 * <p>개별 메시지 로그는 0줄이 원칙이라 정상 경로만 보면 검사가 싱겁다.
 * 새는 자리는 대개 실패 경로다 — 디코딩 실패 로그에 본문을 붙이거나,
 * 수립 실패 로그에 응답 본문을 붙이거나, 예외 메시지에 쿼리의 auth=가 딸려 온다.
 *
 * <p>탐지기는 {@code auth}의 SecretLeakTest를 그대로 베꼈다. 포맷된 한 줄만
 * 보면 {@code log.error("...", e)}로 새는 값을 못 잡는다 — 그건 ThrowableProxy
 * 안에 있다.
 */
@FakeChzzkTest
class ChatLogLeakTest extends IntegrationTestSupport {

    private static final Logger log = LoggerFactory.getLogger(ChatLogLeakTest.class);

    private static final String CONTENT = needle("chat-content");
    private static final String SENDER = needle("sender-channel-id");
    private static final String NICKNAME = needle("nickname");
    private static final String TOKEN = needle("access-token");

    private static final List<String> SECRETS = List.of(CONTENT, SENDER, NICKNAME, TOKEN);

    /** application.yml이 info로 박아 둔 바인딩 값 로거 둘. 스프링과 드라이버. */
    private static final List<String> PINNED_JDBC_LOGGERS =
            List.of("org.springframework.jdbc", "org.postgresql");

    @LocalServerPort int port;
    @Autowired FakeChzzkBehavior behavior;
    @Autowired RestClient.Builder restClientBuilder;
    @Autowired JdbcTemplate jdbc;
    @Autowired Environment environment;

    private CollectorRunner runner;

    @AfterEach
    void tearDown() {
        if (runner != null) runner.stop();
        behavior.reset();
    }

    @Test
    void 정상_수집에서_본문과_작성자와_닉네임이_로그에_안_남는다() throws Exception {
        try (LogCaptor captor = new LogCaptor()) {
            CollectionStatus status = start();
            assertThat(status.state()).isEqualTo(CollectionStatus.State.COLLECTING);

            for (int i = 0; i < 20; i++) {
                behavior.emitChat(chatWithNeedles());
            }
            awaitReceived(20);

            // 양성 대조. 한 건도 안 흘렀으면 바늘이 코드 안을 지나간 적이 없어
            // 아래 단언들이 아무것도 검사하지 않은 채 초록이 된다.
            assertThat(runner.metrics().totalReceived())
                    .as("채팅이 코드 안을 지나가지 않았다면 유출 검사는 아무것도 안 본 것이다")
                    .isGreaterThanOrEqualTo(20);

            assertNoSecretsIn(captor, SECRETS);

            // 통째로 찍는 코드가 들어온 것 자체를 잡는다. record 기본 toString이
            // content를 그대로 뱉으므로, 객체를 로그에 넘기는 순간 평문이 나간다.
            assertThat(renderAll(captor))
                    .doesNotContain("ChatMessage[")
                    .doesNotContain("SystemEvent[")
                    .doesNotContain("Established[");
        }
    }

    /** 깨진 JSON이 곧 채팅 본문이다. 실패 로그에 본문을 붙이면 그대로 샌다. */
    @Test
    void 디코딩이_깨져도_본문이_로그에_안_남는다() throws Exception {
        try (LogCaptor captor = new LogCaptor()) {
            start();

            behavior.emitChat("{\"content\":\"" + CONTENT + "\" 깨진다");
            behavior.emitChat(chatWithNeedles());   // 순서 보장용 — 이게 오면 앞은 처리됐다
            awaitReceived(1);

            assertThat(runner.metrics().snapshot().decodeFailures())
                    .as("깨진 본문이 디코더를 지나가지 않았다면 이 검사는 무의미하다")
                    .isGreaterThanOrEqualTo(1);

            assertNoSecretsIn(captor, SECRETS);
        }
    }

    /** 수립 실패 로그·예외 메시지에 응답 본문이나 URL이 붙으면 토큰이 되비친다. */
    @Test
    void 세션_발급이_401이어도_토큰이_로그에_안_남는다() throws Exception {
        behavior.authStatus = 401;

        try (LogCaptor captor = new LogCaptor()) {
            CollectionStatus status = start();

            // 영구 정지는 재연결 스레드가 찍는다. 상태를 안 기다리면 그 스레드가
            // 남길 줄이 아직 창 밖이라, 부정 단언이 아무것도 안 본 채 초록이 된다.
            awaitState(status, CollectionStatus.State.STOPPED);
            assertThat(status.state())
                    .as("실패 경로를 안 태웠다면 검사할 로그가 애초에 없다")
                    .isEqualTo(CollectionStatus.State.STOPPED);
            assertThat(status.reason()).isEqualTo(StopReason.SESSION_AUTH_REJECTED);
            assertThat(renderAll(captor)).as("실패가 로그에 남기는 남아야 한다").contains("chat.session.stopped");

            assertNoSecretsIn(captor, SECRETS);
        }
    }

    /** 서버가 조용히 끊는 경로. 예외 메시지에 쿼리의 auth=가 딸려 오기 쉬운 자리다. */
    @Test
    void 전송이_끊겨도_토큰이_로그에_안_남는다() throws Exception {
        try (LogCaptor captor = new LogCaptor()) {
            CollectionStatus status = start();
            assertThat(status.state()).isEqualTo(CollectionStatus.State.COLLECTING);

            behavior.closeSession();
            awaitReleased(captor);

            assertThat(status.reason()).isEqualTo(StopReason.TRANSPORT_CLOSED);
            // 양성 대조. 절단 뒤에 나가는 줄은 판정과 반납 결말인데, STOPPED만 보고
            // 앞지르면 둘 다 창 밖이라 <b>끊긴 뒤의 로그를 한 줄도 안 본 채</b> 초록이 된다.
            assertThat(renderAll(captor))
                    .as("뒷정리 줄이 창 밖이면 이 검사는 절단 경로의 로그를 아무것도 안 본 것이다")
                    .contains("chat.session.released");
            assertNoSecretsIn(captor, SECRETS);
        }
    }

    /**
     * <b>로그로 실제로 나가는 요약 줄</b>을 훑는다. SummaryLoggerTest는 render()를
     * 직접 부르므로 그 결과가 어떻게 찍히는지는 거기서 안 본다. 완료 조건은
     * "render가 본문을 안 담는다"가 아니라 "나가는 줄에 본문이 없다"다.
     *
     * <p>러너의 요약 주기는 30초라 테스트 안에서 안 찍힌다. 그래서 <b>러너가
     * 실제로 채운 지표</b>를 그대로 물려 SummaryLogger를 짧은 주기로 돌린다 —
     * 데이터도 발신 경로(log.info)도 운영과 같고, 다른 것은 주기와 스케줄러
     * 인스턴스뿐이다. 그 둘은 바늘을 나르지 않는다.
     */
    @Test
    void 로그로_나가는_요약_줄에_본문이_없다() throws Exception {
        try (LogCaptor captor = new LogCaptor()) {
            CollectionStatus status = start();
            assertThat(status.state()).isEqualTo(CollectionStatus.State.COLLECTING);

            for (int i = 0; i < 5; i++) {
                behavior.emitChat(chatWithNeedles());
            }
            awaitReceived(5);

            try (SummaryLogger logger = SummaryLogger.start(runner.metrics(),
                    Heartbeat.idleForTest(), Duration.ofMillis(150),
                    () -> 0L, TestPersistence.disabledPersister(), () -> 0L, ArchiveCounters.NONE)) {
                awaitSummaryLine(captor);
                assertThat(logger.emitterThreadNames()).containsExactly("chzzk-summary");
            }

            String summary = captor.messages().stream()
                    .filter(m -> m.startsWith("chat.summary "))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("요약 줄이 한 번도 안 찍혔다"));

            // 양성 대조. 수신 0건인 줄을 훑으면 바늘이 지나간 적이 없어 자동으로 참이 된다.
            assertThat(summary)
                    .as("이 줄이 바늘을 나른 적이 없다면 아래 검사는 아무것도 안 본다")
                    .contains("received=5");

            assertNoSecretsIn(summary, SECRETS);
        }
    }

    /**
     * 최종 판정 라인도 유출 검사를 지나야 한다. <b>새 로그 줄이 이 검사를 안 거치면
     * 여기서 닫은 구멍이 그대로 다시 열린다.</b>
     */
    @Test
    void 최종_판정_라인에_본문이_없다() throws Exception {
        try (LogCaptor captor = new LogCaptor()) {
            CollectionStatus status = start();
            assertThat(status.state()).isEqualTo(CollectionStatus.State.COLLECTING);

            for (int i = 0; i < 3; i++) {
                behavior.emitChat(chatWithNeedles());
            }
            awaitReceived(3);
            runner.stop();

            String verdict = captor.messages().stream()
                    .filter(m -> m.startsWith("chat.session.verdict"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("판정 라인이 안 나갔다"));

            // 양성 대조. 수신 0건인 줄을 훑으면 바늘이 지나간 적이 없어 자동으로 참이 된다.
            assertThat(verdict)
                    .as("이 줄이 바늘을 나른 적이 없다면 아래 검사는 아무것도 안 본다")
                    .contains("received=3");

            assertNoSecretsIn(verdict, SECRETS);
        }
    }

    /**
     * 적재 경로(POK-84)가 새로 연 로그 자리 둘 — 성공 flush와 실패 flush —
     * 을 바늘이 지나가게 하고 무유출을 단언한다. <b>새 로그 줄이 이 검사를
     * 안 거치면 위에서 닫은 구멍이 적재 쪽에서 다시 열린다.</b>
     */
    @Test
    void 적재_경로가_돌아도_본문이_로그에_안_남는다() {
        try (LogCaptor captor = new LogCaptor()) {
            ChatBuffer buffer = new ChatBuffer(100);
            buffer.offer(new PersistableChat("leak-ch", SENDER, CONTENT,
                    1_754_300_000_000L, 1_754_300_000_175L));
            int saved = new ChatPersister(jdbc, buffer).flushOnce();
            // 양성 대조. 표까지 안 갔다면 적재 경로가 바늘을 나른 적이 없다.
            assertThat(saved).as("저장이 안 됐다면 성공 경로의 로그를 아무것도 안 본 것이다")
                    .isEqualTo(1);

            // 실패 경로 — DB가 죽어 chat.persist.failed가 나가는 자리가
            // 본문을 싣기 가장 쉬운 자리다 (태스크 5의 실패 주입 방식 재사용).
            JdbcTemplate broken = new JdbcTemplate(jdbc.getDataSource()) {
                @Override
                public int[] batchUpdate(String sql, List<Object[]> args) {
                    throw new DataAccessResourceFailureException("db down");
                }
            };
            buffer.offer(new PersistableChat("leak-ch", SENDER, CONTENT,
                    1_754_300_000_001L, 1_754_300_000_176L));
            assertThat(new ChatPersister(broken, buffer).flushOnce()).isZero();
            assertThat(renderAll(captor))
                    .as("실패 줄이 안 나갔다면 실패 경로의 로그를 아무것도 안 본 것이다")
                    .contains("chat.persist.failed");

            // 격리 경로 — chat.persist.poisoned가 나가는 자리가 실패 경로 다음으로
            // 본문을 싣기 쉬운 자리다. NUL은 생성 지점에서 제거돼 실데이터로는 격리를
            // 못 태우므로, 바늘 본문만 SQLSTATE 22021로 거부하는 스텁으로 강제한다.
            // 실패 경로가 되돌린 잔여를 먼저 비운다 — 안 비우면 격리 배치에 섞여
            // poisoned가 2가 되고, 단언이 무엇을 셌는지 흐려진다.
            new ChatPersister(jdbc, buffer).flushOnce();
            ChatPersister isolating = new ChatPersister(
                    TestPersistence.rejecting22(jdbc.getDataSource(), CONTENT), buffer);
            buffer.offer(new PersistableChat("leak-ch", SENDER, CONTENT,
                    1_754_300_000_002L, 1_754_300_000_177L));
            isolating.flushOnce();
            assertThat(isolating.poisonedCount())
                    .as("격리를 안 탔다면 poisoned 줄의 로그를 아무것도 안 본 것이다")
                    .isEqualTo(1);
            assertThat(renderAll(captor)).contains("chat.persist.poisoned");

            assertNoSecretsIn(captor, SECRETS);
        }
    }

    /**
     * 우리 코드가 아니라 프레임워크가 여는 자리. 위 검사들은 INFO 기준선이라
     * (LogCaptor 기본값) TRACE에서 늘어나는 줄을 못 본다 — 그런데 거기서 늘어나는
     * 것이 바로 <b>바인딩 값</b>이고, 이 표에서 바인딩되는 값이 본문과 작성자다.
     * auth의 SecretLeakTest는 "TRACE는 볼 것이 없다"로 닫았지만(바늘이 SQL에
     * 안 닿는다) 여기서는 반대다.
     *
     * <p>실측(2026-08-15): root를 TRACE로 내리면 두 로거가 찍는다.
     * {@code org.springframework.jdbc.core.StatementCreatorUtils}가 배치 파라미터를
     * 한 값씩, {@code org.postgresql.core.v3.QueryExecutorImpl}(JUL→SLF4J 브릿지)이
     * Bind 메시지에 여섯 파라미터를 통째로. 그래서 application.yml이 두 로거를
     * info로 박았고, 이 검사는 <b>root를 TRACE로 올려 놓고</b> 적재를 돌려 그 두 줄이
     * 실제로 이기는지를 행동으로 잰다 — 운영자가 LOGGING_LEVEL_ROOT=trace로 내린
     * 상황 그대로다. yml 줄을 지우면 빨간불이다(주입 확인함).
     *
     * <p>뒤의 양성 대조가 이 검사의 자기검사다. 두 로거를 <b>직접</b> TRACE로 밀면
     * 실제로 새야 한다 — 안 새면 프레임워크가 바뀐 것이고, 그때는 앞의 단언이
     * 아무것도 안 본 채 초록인 상태이니 yml 주석과 이 검사를 다시 볼 때다.
     */
    @Test
    void JDBC_파라미터_로거는_root를_TRACE로_내려도_본문을_안_찍는다() {
        // 프로퍼티가 Environment에 있고 부팅이 logback까지 박았는지 — auth와 같은
        // 두 겹. 기본값을 주지 않는다: 주면 yml에서 줄이 사라져도 초록이다.
        for (String pinned : PINNED_JDBC_LOGGERS) {
            String level = environment.getProperty("logging.level." + pinned);
            assertThat(level).as("application.yml에 " + pinned + " 레벨이 박혀 있어야 root를 내려도 버틴다")
                    .isNotNull();
            assertThat(Level.toLevel(level, Level.TRACE).toInt())
                    .as(pinned + "가 이 레벨이면 아래 양성 대조가 재현하는 유출이 열린다: " + level)
                    .isGreaterThanOrEqualTo(Level.INFO.toInt());
            assertThat(levelOf(pinned)).as(pinned + " 프로퍼티가 Environment에만 있고 logback까지 안 닿았다")
                    .isNotNull();
        }

        try (LogCaptor captor = new LogCaptor()) {
            ChatBuffer buffer = new ChatBuffer(100);
            ChatPersister persister = new ChatPersister(jdbc, buffer);

            // ① root TRACE — 구체 로거의 info가 이겨야 한다.
            Level rootBefore = levelOf(Logger.ROOT_LOGGER_NAME);
            setLevel(Logger.ROOT_LOGGER_NAME, Level.TRACE);
            try {
                buffer.offer(new PersistableChat("leak-ch", SENDER, CONTENT,
                        1_754_300_000_010L, 1_754_300_000_185L));
                assertThat(persister.flushOnce())
                        .as("표까지 안 갔다면 바인딩 로거가 바늘을 나른 적이 없다").isEqualTo(1);
            } finally {
                setLevel(Logger.ROOT_LOGGER_NAME, rootBefore);
            }
            assertNoSecretsIn(captor, List.of(CONTENT, SENDER));

            // ② 양성 대조 — 로거마다 따로 TRACE로 밀고 따로 단언한다. 묶으면 한쪽이
            // 조용해져도(더 위험한 드라이버 쪽이 사라져도) 다른 쪽이 초록을 유지한다.
            for (String pinned : PINNED_JDBC_LOGGERS) {
                Level before = levelOf(pinned);
                setLevel(pinned, Level.TRACE);
                try {
                    buffer.offer(new PersistableChat("leak-ch", SENDER, CONTENT,
                            1_754_300_000_011L, 1_754_300_000_186L));
                    assertThat(persister.flushOnce()).isEqualTo(1);
                } finally {
                    setLevel(pinned, before);
                }
                assertThat(captor.events())
                        .as(pinned + "를 TRACE로 밀어도 본문이 안 새면 yml에 박은 근거를 다시 볼 때다")
                        .anyMatch(e -> e.getLoggerName().startsWith(pinned)
                                && renderFully(e).contains(CONTENT));
            }
        }
    }

    // ── 탐지기 자기검사 셋. 이게 없으면 위 검사 전체가 초록불 장식이다 ────────

    @Test
    void 탐지기는_메시지에_있는_본문을_잡는다() {
        String planted = needle("planted-in-message");

        try (LogCaptor captor = new LogCaptor()) {
            log.info("chat-leak-self-check {}", planted);

            assertThatThrownBy(() -> assertNoSecretsIn(captor, List.of(planted)))
                    .isInstanceOf(AssertionError.class);
        }
    }

    /**
     * 이 자기검사가 가장 중요하다. {@code log.error("...", e)}로 새는 값은
     * getFormattedMessage()에 없고 ThrowableProxy 안에 있다. 탐지기가 메시지만
     * 보면 위 검사 전부가 아무것도 안 보면서 초록이 된다.
     */
    @Test
    void 탐지기는_예외의_cause와_suppressed에_숨은_본문도_잡는다() {
        String inCause = needle("planted-in-cause");
        String inSuppressed = needle("planted-in-suppressed");

        RuntimeException thrown =
                new RuntimeException("겉면은 깨끗하다", new IllegalStateException(inCause));
        thrown.addSuppressed(new IllegalStateException(inSuppressed));

        try (LogCaptor captor = new LogCaptor()) {
            log.error("chat-leak-self-check", thrown);

            assertThat(captor.messages())
                    .as("메시지만 보는 탐지기가 왜 부족한지가 이 단언에 걸려 있다")
                    .noneMatch(m -> m.contains(inCause) || m.contains(inSuppressed));
            assertThatThrownBy(() -> assertNoSecretsIn(captor, List.of(inCause)))
                    .isInstanceOf(AssertionError.class);
            assertThatThrownBy(() -> assertNoSecretsIn(captor, List.of(inSuppressed)))
                    .isInstanceOf(AssertionError.class);
        }
    }

    /**
     * 반대 방향. 항상 걸리는 탐지기는 검사를 통과시키지 못하니 덜 위험하지만,
     * 그때는 유출 검사가 <b>영원히 빨간불</b>이라 사람이 곧 무시하게 된다.
     * 무작위 바늘을 쓰는 이유가 여기 있다 — 고정 문자열이면 로그에 우연히
     * 들어 있는 흔한 낱말과 겹쳐 오탐이 난다.
     */
    @Test
    void 탐지기는_바늘이_없으면_통과시킨다() {
        try (LogCaptor captor = new LogCaptor()) {
            log.info("chat-leak-self-check 깨끗한 줄이다");

            assertNoSecretsIn(captor, List.of(needle("never-logged")));
        }
    }

    // ── 도우미 ────────────────────────────────────────────────────────────

    /**
     * <b>{@code run()}으로 띄운다.</b> {@code start()}는 수립 실패를 밖으로 던지므로
     * 401 검사에서 예외가 테스트 메서드를 뚫고 나가 단언에 못 닿는다.
     *
     * <p>재시도 간격을 크게 준다. 유출 검사는 <b>한 번의 실패·절단이 남기는 줄</b>을
     * 훑는 것이라, 짧은 간격이면 같은 줄이 계속 쌓여 무엇을 본 것인지 흐려진다.
     * 새는 자리가 늘지는 않는다 — 재시도가 찍는 줄은 이미 훑는 그 줄들이다.
     */
    private CollectionStatus start() {
        CollectionStatus status = new CollectionStatus();
        runner = new CollectorRunner(
                new ChzzkProperties(true, TOKEN, "http://localhost:" + port, Duration.ofSeconds(5),
                        Duration.ofSeconds(30), Duration.ofSeconds(60)),
                status, restClientBuilder,
                        TestPersistence.unusedBuffer(), TestPersistence.disabledPersister());
        runner.run(null);
        return status;
    }

    private static void awaitState(CollectionStatus status, CollectionStatus.State state)
            throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (status.state() != state && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    private void awaitSummaryLine(LogCaptor captor) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (captor.messages().stream().noneMatch(m -> m.startsWith("chat.summary "))
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    private static String chatWithNeedles() {
        return "{\"senderChannelId\":\"" + SENDER + "\""
                + ",\"content\":\"" + CONTENT + "\""
                + ",\"profile\":{\"nickname\":\"" + NICKNAME + "\"}"
                // messageTime이 없으면 디코더가 통째로 버려 바늘이 코드 안을
                // 지나가지 않는다. 그러면 유출 검사가 자동으로 초록이 된다.
                + ",\"messageTime\":1754300000000}";
    }

    private void awaitReceived(long count) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (runner.metrics().totalReceived() < count && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    /**
     * <b>상태가 아니라 창에 들어와야 할 그 줄을 기다린다.</b>
     *
     * <p>{@code STOPPED}는 뒷정리보다 <b>먼저</b> 찍히므로, 상태만 보고 앞지르면
     * 판정·반납 줄이 통째로 창 밖에 남는다. 이쪽은 부정 단언이라 빨간불이 아니라
     * <b>조용한 초록</b>으로 나타난다 — 검사한 적 없는 줄을 검사했다고 믿게 된다.
     * {@code chat.session.released}가 절단 경로의 마지막 줄이다.
     */
    private void awaitReleased(LogCaptor captor) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (captor.messages().stream().noneMatch(m -> m.startsWith("chat.session.released"))
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    /**
     * 바늘은 매 실행 무작위다. 짧은 상수는 다른 로그와 우연히 겹쳐
     * "안 샜다"가 아니라 "겹친 것을 못 봤다"로 통과할 수 있다.
     */
    private static String needle(String label) {
        return "LEAK-" + label + "-" + UUID.randomUUID();
    }

    private static void setLevel(String loggerName, Level level) {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(loggerName)).setLevel(level);
    }

    /** 명시 레벨만 돌려준다. 안 박혀 있으면 null이다(부모에서 물려받는 상태). */
    private static Level levelOf(String loggerName) {
        return ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(loggerName)).getLevel();
    }

    private static void assertNoSecretsIn(LogCaptor captor, List<String> secrets) {
        assertNoSecretsIn(renderAll(captor), secrets);
    }

    private static void assertNoSecretsIn(String haystack, List<String> secrets) {
        for (String secret : secrets) {
            assertThat(secret).as("빈 바늘은 어디서나 발견돼 검사를 무력화한다").isNotEmpty();
            assertThat(haystack).as("비밀이 남았다: " + secret).doesNotContain(secret);
        }
    }

    /** 모인 로그 전부를 한 덩어리로. 딸려 붙은 예외까지 포함한다. */
    private static String renderAll(LogCaptor captor) {
        return captor.events().stream()
                .map(ChatLogLeakTest::renderFully)
                .collect(Collectors.joining("\n"));
    }

    private static String renderFully(ILoggingEvent event) {
        StringBuilder text = new StringBuilder(event.getFormattedMessage());
        appendThrowable(text, event.getThrowableProxy());
        return text.toString();
    }

    private static void appendThrowable(StringBuilder text, IThrowableProxy throwable) {
        if (throwable == null) {
            return;
        }
        text.append('\n').append(throwable.getClassName()).append(": ").append(throwable.getMessage());
        for (StackTraceElementProxy frame : throwable.getStackTraceElementProxyArray()) {
            text.append('\n').append(frame.getSTEAsString());
        }
        for (IThrowableProxy suppressed : throwable.getSuppressed()) {
            appendThrowable(text, suppressed);
        }
        appendThrowable(text, throwable.getCause());
    }
}
