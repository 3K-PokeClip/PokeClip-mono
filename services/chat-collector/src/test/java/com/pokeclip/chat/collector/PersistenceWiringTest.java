package com.pokeclip.chat.collector;

import com.pokeclip.chat.collector.fake.FakeChzzkBehavior;
import com.pokeclip.chat.collector.fake.FakeChzzkTest;
import com.pokeclip.chat.collector.persist.ChatBuffer;
import com.pokeclip.chat.collector.persist.ChatPersister;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 수신에서 표까지 전체 경로 (완료 조건 1의 자동판). 러너도 버퍼·퍼시스터도
 * 테스트가 직접 만든다 — 러너의 {@code stop()}이 판정 직전에 퍼시스터를 닫으므로
 * (PR #52 ①), 컨텍스트 빈 퍼시스터를 넘기면 첫 테스트가 싱글턴의 스케줄러를
 * 영영 꺼 버려 뒤 테스트가 전부 헛돈다. 스케줄러 경로 자체는 자기 퍼시스터의
 * {@code start()}로 동일하게 재현되고, 빈 배선(@Component + @PostConstruct)은
 * 스프링 표준이라 컨텍스트 로드 성공이 그 존재를 증명한다.
 *
 * <p>러너를 직접 만드는 이유는 {@code ChatLogLeakTest}와 같다 — fake 서버 포트가
 * 런타임에 정해져 정적 프로퍼티로 못 넘기고, application-test.yml이
 * {@code enabled: false}라 빈 러너는 아무 데도 안 붙는다.
 */
@FakeChzzkTest
class PersistenceWiringTest extends IntegrationTestSupport {

    @LocalServerPort int port;
    @Autowired FakeChzzkBehavior behavior;
    @Autowired RestClient.Builder restClientBuilder;
    @Autowired JdbcTemplate jdbc;

    private CollectorRunner runner;
    private ChatBuffer buffer;
    private ChatPersister persister;

    @AfterEach
    void tearDown() {
        if (runner != null) runner.stop();
        if (persister != null) persister.close();   // 멱등 — stop이 이미 닫았어도 무해
        behavior.reset();
    }

    /**
     * PR #52 P1 ①. 스프링 파괴 순서상 러너의 stop()(판정)이 퍼시스터의
     * @PreDestroy(마지막 flush)보다 먼저다 — 판정 줄의 persisted가 마지막
     * flush분을 빼고 찍히면 등식 received = persisted + conflicts + dropped가
     * 종료 로그에서 안 닫힌다. stop()이 판정 직전에 퍼시스터를 직접 닫아야 한다.
     */
    @Test
    void 종료_시_판정_줄의_persisted가_close의_마지막_플러시분을_포함한다() throws Exception {
        try (LogCaptor captor = new LogCaptor()) {
            start();
            for (int i = 0; i < 2; i++) {
                behavior.emitChat("{\"channelId\":\"wiring-verdict\",\"senderChannelId\":\"s-v\","
                        + "\"content\":\"v-" + i + "\",\"messageTime\":" + (1_723_600_100_000L + i) + "}");
            }
            awaitReceived(2);

            // 1초 틱이 저장하기 전에 멈춘다 — 판정 직전의 마지막 flush만이 이 두 건을
            // 실을 수 있는 시점이다. (틱이 우연히 먼저 저장해도 단언은 성립한다 —
            // 빨간불은 판정이 flush보다 앞설 때만 난다.)
            runner.stop();

            String verdict = captor.messages().stream()
                    .filter(m -> m.startsWith("chat.session.verdict"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("판정 라인이 안 나갔다"));
            assertThat(verdict)
                    .as("판정이 마지막 flush 전에 찍히면 종료 로그의 등식이 안 닫힌다")
                    .contains("persisted=2");
        }
    }

    @Test
    void 가짜_서버가_보낸_채팅이_표에_남는다() throws Exception {
        CollectionStatus status = start();

        for (int i = 0; i < 3; i++) {
            behavior.emitChat("{\"channelId\":\"wiring-ch\",\"senderChannelId\":\"s-" + i
                    + "\",\"content\":\"m-" + i
                    + "\",\"messageTime\":" + (1_723_600_000_000L + i) + "}");
        }
        awaitReceived(3);
        assertThat(runner.metrics().totalReceived())
                .as("수신조차 안 됐다면 아래 표 검사는 배선이 아니라 수신을 보는 것이 된다")
                .isGreaterThanOrEqualTo(3);

        // 저장은 chzzk-persist 스레드가 1초 주기로 한다 — 결과를 폴링한다.
        awaitRows("wiring-ch", 3);
        assertThat(countRows("wiring-ch")).isEqualTo(3);   // 수신 건수 = 표 건수 (완료 조건 1)
    }

    /**
     * 완료 조건 2(중복 0건)의 자동판. 치지직은 백필을 안 하지만, 재연결 직후
     * 우리 쪽 이중 처리가 같은 채팅을 두 번 넣으려 할 때를 재현하는 가장
     * 실전적인 경로가 절단 → 재연결 → 같은 프레임 재송신이다.
     */
    @Test
    void 재연결로_같은_채팅이_다시_와도_표에는_한_건이다() throws Exception {
        CollectionStatus status = start();
        String sameChat = "{\"channelId\":\"wiring-dup\",\"senderChannelId\":\"s-dup\","
                + "\"content\":\"같은채팅\",\"messageTime\":1723600000000}";

        behavior.emitChat(sameChat);
        awaitReceived(1);
        awaitRows("wiring-dup", 1);
        assertThat(countRows("wiring-dup"))
                .as("첫 건이 표에 없다면 아래는 중복이 아니라 적재 자체를 보는 것이 된다")
                .isEqualTo(1);

        behavior.closeSession();
        awaitUntil(() -> status.state() == CollectionStatus.State.COLLECTING
                && behavior.authCallCount() == 2);

        long conflictsBefore = persister.conflictedCount();
        behavior.emitChat(sameChat);   // 같은 지문 — 재연결 뒤 이중 처리 재현
        awaitReceived(2);
        // 접힘은 행이 안 느는 사건이라 행 수로는 못 기다린다 — 접힌 수가 오르는 것을 본다.
        long deadline = System.currentTimeMillis() + 5_000;
        while (persister.conflictedCount() == conflictsBefore
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(persister.conflictedCount())
                .as("접힌 수가 안 올랐다면 둘째 건이 아직 표 앞에 못 갔다 — 아래 단언이 헛돈다")
                .isGreaterThan(conflictsBefore);

        assertThat(countRows("wiring-dup")).isEqualTo(1);
        // 지문 중복이 0건임을 쿼리로 — Jira 완료 조건 문구 그대로
        Integer dup = jdbc.queryForObject(
                "SELECT count(*) FROM (SELECT channel_id, sender_channel_id, message_time, "
                        + "content_sha256 FROM chat_messages GROUP BY 1,2,3,4 "
                        + "HAVING count(*) > 1) d", Integer.class);
        assertThat(dup).isZero();
    }

    /**
     * code-review B. 판정이 나가는 두 시점 중 <b>영구 정지</b> 쪽 — revoked를 받으면
     * 재연결 루프가 판정을 찍는데, 거기서도 퍼시스터를 먼저 닫아야 마지막 flush분이
     * persisted에 실린다. stop() 쪽만 고치면 이 경로가 persisted=0으로 나간다.
     */
    @Test
    void 영구_정지_때도_판정_줄의_persisted가_마지막_플러시분을_포함한다() throws Exception {
        try (LogCaptor captor = new LogCaptor()) {
            CollectionStatus status = start();
            behavior.emitChat("{\"channelId\":\"wiring-revoked\",\"senderChannelId\":\"s-r\","
                    + "\"content\":\"r-1\",\"messageTime\":1723600200000}");
            awaitReceived(1);

            behavior.emitSystem("{\"type\":\"revoked\",\"data\":{\"eventType\":\"CHAT\"}}");
            awaitUntil(() -> status.state() == CollectionStatus.State.STOPPED);
            awaitUntil(() -> captor.messages().stream()
                    .anyMatch(m -> m.startsWith("chat.session.verdict")));

            String verdict = captor.messages().stream()
                    .filter(m -> m.startsWith("chat.session.verdict"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("판정 라인이 안 나갔다"));
            assertThat(verdict)
                    .as("영구 정지 판정이 마지막 flush 전에 찍히면 이 경로만 등식이 안 닫힌다")
                    .contains("persisted=1");
        }
    }

    private CollectionStatus start() {
        buffer = new ChatBuffer(10_000);
        persister = new ChatPersister(jdbc, buffer);
        persister.start();
        CollectionStatus status = new CollectionStatus();
        runner = new CollectorRunner(
                new ChzzkProperties(true, "test-token", "http://localhost:" + port,
                        Duration.ofSeconds(5), Duration.ofMillis(50), Duration.ofSeconds(1)),
                status, restClientBuilder, buffer, persister);
        runner.run(null);
        assertThat(status.state())
                .as("수집이 시작되지 않았다면 적재 경로는 아무것도 안 지나간다")
                .isEqualTo(CollectionStatus.State.COLLECTING);
        return status;
    }

    private void awaitRows(String channelId, long count) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (countRows(channelId) < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
    }

    private void awaitUntil(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    private void awaitReceived(long count) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (runner.metrics().totalReceived() < count && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    /** 이 테스트가 보낸 채널로 한정한다 — 다른 적재 테스트의 행과 안 섞인다. */
    private long countRows(String channelId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM chat_messages WHERE channel_id = ?", Long.class, channelId);
    }
}
