package com.pokeclip.chat.collector;

import com.pokeclip.chat.collector.archive.ChatArchive;
import com.pokeclip.chat.collector.fake.FakeChzzkBehavior;
import com.pokeclip.chat.collector.fake.FakeChzzkTest;
import com.pokeclip.chat.collector.persist.ChatBuffer;
import com.pokeclip.chat.collector.persist.ChatPersister;
import com.pokeclip.chat.collector.persist.DonationBuffer;
import com.pokeclip.chat.collector.persist.DonationPersister;
import com.pokeclip.chat.collector.persist.PersistableDonation;
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
     * 감사 라운드 1 C1(중대). 수신 프레임의 닉네임·역할이 <b>표의 제 칸으로</b> 가는지를
     * 관통해서 잰다. 이 그물이 없으면 {@code StreamSession}의
     * {@code message.nickname(), message.userRole()} 두 인자를 <b>맞바꿔도</b>
     * 모듈 전체가 초록이다(감사자 주입 D로 재현). {@code toRow} 쪽 순서는
     * {@code ChatPersisterTest}가 막고 있었는데 <b>같은 뿌리의 이 한 자리</b>만 무방비였다.
     *
     * <p>두 값을 <b>서로 다르게</b> 주는 것이 요점이다. 같은 값이면 맞바꿔도 안 걸린다.
     */
    @Test
    void 프레임의_닉네임과_역할이_표의_제_칸으로_간다() throws Exception {
        start();

        behavior.emitChat("{\"channelId\":\"wiring-nick\",\"senderChannelId\":\"s-n\","
                + "\"content\":\"닉배선\",\"messageTime\":1723600600000,"
                + "\"profile\":{\"nickname\":\"겜돌이-와이어링\"},"
                + "\"userRoleCode\":\"streaming_chat_manager\"}");
        awaitReceived(1);
        awaitRows("wiring-nick", 1);

        assertThat(countRows("wiring-nick"))
                .as("행이 없으면 아래 두 단언은 아무것도 안 본다")
                .isEqualTo(1);
        java.util.Map<String, Object> row = jdbc.queryForMap(
                "SELECT nickname, user_role FROM chat_messages WHERE channel_id = 'wiring-nick'");
        assertThat(row)
                .as("두 인자를 맞바꾸면 여기서만 잡힌다 — 컴파일러도 DB도 안 막는다")
                .containsEntry("nickname", "겜돌이-와이어링")
                .containsEntry("user_role", "streaming_chat_manager");
    }

    /**
     * <b>옛 경로의 채팅은 방송 번호가 비어 있다.</b> 편지 없이 {@code CHZZK_ENABLED}로
     * 붙으면 방송 번호를 알 방법이 없다 — 구독은 「토큰 주인의 채팅」이라 방송을 못 고른다.
     * NULL이 곧 「모른다」는 표시이고, 여기서 저장이 막히면 이 경로의 수집 자체가 죽는다.
     */
    // 문항 1: 세션 하나짜리 경로를 일부러 재는 갈래다 — 옛 경로에는 세션이 하나뿐이다.
    // 문항 2: isNull은 <b>행이 없어도</b> 참처럼 보이므로 행 수를 먼저 확인한다.
    //         그리고 <b>번호를 아무 데도 안 찍는 구현에서도 초록</b>이다 — 실제로 찍히는지는
    //         StreamIdStampingTest가 잰다. 여기는 옛 경로만 본다.
    // 문항 5: 러너가 legacy() 대신 번호를 지어내게 되돌리면 빨간불 — 확인함(주입 S).
    @Test
    void 옛_경로의_채팅은_방송_번호가_비어_있다() throws Exception {
        start();

        behavior.emitChat("{\"channelId\":\"wiring-legacy\",\"senderChannelId\":\"s-l\","
                + "\"content\":\"옛경로\",\"messageTime\":1723600500000}");
        awaitReceived(1);
        awaitRows("wiring-legacy", 1);

        assertThat(countRows("wiring-legacy"))
                .as("저장이 막히면 옛 경로의 수집 자체가 죽는다")
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT stream_id FROM chat_messages WHERE channel_id = 'wiring-legacy'",
                String.class))
                .as("모르는 번호를 지어내 찍으면 없는 방송의 반응이 표에 생긴다")
                .isNull();
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

    /**
     * PR #53 P1 ②. 종료가 시작된 뒤에도 소켓이 살아 있으면 마무리 flush 도중에 새
     * 채팅이 계속 바구니에 들어온다 — 바쁜 방송 + 느린 DB면 {@code flushBacklog}가
     * "진전이 있는 동안 계속"이라 끝을 못 보고 5초 예산을 넘겨 {@code close_timeout}으로
     * 빠지고, 그 뒤 판정 줄은 아직 오는 채팅과 못 실은 잔량 때문에 등식이 안 닫힌다.
     * <b>수신을 먼저 끊고 나서 close</b>여야 flush가 유한하고 등식이 닫힌다.
     *
     * <p>느린 DB는 배치 하나에 200ms를 자는 JdbcTemplate으로, 바쁜 방송은 5ms마다
     * CHAT을 쏘는 스레드로 만든다. 수정 전 실측: close_timeout 뒤 판정에서
     * received > persisted+conflicts+poisoned+dropped(빨간불 확인).
     */
    @Test
    void 종료_중에_채팅이_계속_와도_close의_flush가_끝나고_판정_등식이_닫힌다() throws Exception {
        JdbcTemplate slow = new JdbcTemplate(jdbc.getDataSource()) {
            @Override
            public int[] batchUpdate(String sql, java.util.List<Object[]> args) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.batchUpdate(sql, args);
            }
        };
        start(slow);
        java.util.concurrent.atomic.AtomicBoolean emitting = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.atomic.AtomicInteger emitted = new java.util.concurrent.atomic.AtomicInteger();
        Thread emitter = new Thread(() -> {
            for (int i = 0; emitting.get(); i++) {
                try {
                    behavior.emitChat("{\"channelId\":\"wiring-busy\",\"senderChannelId\":\"s-b\","
                            + "\"content\":\"b-" + i + "\",\"messageTime\":" + (1_723_600_300_000L + i) + "}");
                    emitted.incrementAndGet();
                    Thread.sleep(5);
                } catch (IllegalStateException e) {
                    return;             // 소켓이 닫혔다 — 종료가 수신을 끊은 것이다
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "busy-emitter");
        emitter.start();
        try (LogCaptor captor = new LogCaptor()) {
            awaitReceived(20);

            runner.stop();

            emitting.set(false);
            emitter.join(2_000);
            String verdict = captor.messages().stream()
                    .filter(m -> m.startsWith("chat.session.verdict"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("판정 라인이 안 나갔다"));
            assertThat(captor.messages())
                    .as("수신이 계속 열려 있으면 flush가 끝을 못 보고 예산을 넘긴다")
                    .noneMatch(m -> m.startsWith("chat.persist.close_timeout"));
            long received = field(verdict, "received");
            assertThat(received)
                    .as("바쁜 방송이 재현됐는지 — 너무 적으면 아래 등식이 한산한 경우만 본 것이다")
                    .isGreaterThanOrEqualTo(20);
            // 양성 대조 — 종료 도중에도 서버가 실제로 계속 쐈다. 같으면 "종료 중 수신"이
            // 재현되지 않은 것이고 아래 등식은 한산한 종료만 본 것이다.
            assertThat(emitted.get())
                    .as("종료 도중 보낸 채팅이 0건이면 이 검사는 아무것도 재현하지 않았다")
                    .isGreaterThan((int) received);
            assertThat(received)
                    .as("등식 received = persisted + conflicts + poisoned + dropped — 판정 줄: " + verdict)
                    .isEqualTo(field(verdict, "persisted") + field(verdict, "conflicts")
                            + field(verdict, "poisoned") + field(verdict, "dropped"));
        } finally {
            emitting.set(false);
            emitter.join(2_000);
        }
    }

    /**
     * PR #55 P1 ②. 영구 정지(REVOKED)가 <b>DB 장애 중에</b> 오면 판정 직전의 close가
     * 마지막 flush 실패로 잔량을 복원한 채 스케줄러를 끈다 — 그 뒤 서버는 STOPPED로
     * 살아 있는데 아무도 다시 저장하지 않아, DB가 회복돼도 그 채팅은 메모리에만 남고
     * 프로세스가 죽는 순간 사라진다. 수집이 영영 안 되는 STOPPED로 살아 있을 이유가
     * 없으므로: <b>회복을 시한부로 기다려 잔량을 저장하고, 판정을 낸 뒤, 프로세스를
     * 내린다(exit 1)</b> — Flyway 영구 실패와 같은 방식이다.
     *
     * <p>수정 전 실측: STOPPED 뒤 DB가 회복돼도 표에 0건, exit 호출 0회.
     */
    @Test
    void 영구_정지가_DB_장애_중에_와도_회복을_기다려_잔량을_저장한_뒤_프로세스를_내린다() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean down = new java.util.concurrent.atomic.AtomicBoolean(true);
        JdbcTemplate flaky = new JdbcTemplate(jdbc.getDataSource()) {
            @Override
            public int[] batchUpdate(String sql, java.util.List<Object[]> args) {
                if (down.get()) {
                    throw new org.springframework.dao.DataAccessResourceFailureException("db down");
                }
                return super.batchUpdate(sql, args);
            }
        };
        java.util.concurrent.atomic.AtomicInteger exits = new java.util.concurrent.atomic.AtomicInteger();
        try (LogCaptor captor = new LogCaptor()) {
            CollectionStatus status = start(flaky, exits::incrementAndGet);
            behavior.emitChat("{\"channelId\":\"wiring-exit\",\"senderChannelId\":\"s-x\","
                    + "\"content\":\"x-1\",\"messageTime\":1723600400000}");
            awaitReceived(1);

            behavior.emitSystem("{\"type\":\"revoked\",\"data\":{\"eventType\":\"CHAT\"}}");
            awaitUntil(() -> status.state() == CollectionStatus.State.STOPPED);
            // 영구 정지 뒤에 저장 시도가 실제로 실패했다 — 이 뒤에 회복시켜야
            // "장애 중에 영구 정지가 왔다"가 재현된 것이다.
            awaitUntil(() -> captor.messages().stream().anyMatch(m -> m.startsWith("chat.persist.failed")));
            down.set(false);

            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (exits.get() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertThat(countRows("wiring-exit"))
                    .as("STOPPED로 살아만 있으면 DB가 회복돼도 잔량이 영영 안 저장된다")
                    .isEqualTo(1);
            assertThat(exits.get())
                    .as("수집이 영영 안 되는 프로세스를 살려 두면 잔량이 메모리에만 남는다")
                    .isEqualTo(1);
            String verdict = captor.messages().stream()
                    .filter(m -> m.startsWith("chat.session.verdict"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("판정 라인이 안 나갔다"));
            assertThat(verdict)
                    .as("판정이 회복 전에 찍히면 persisted가 잔량을 빼고 나간다")
                    .contains("persisted=1");
            assertThat(status.state()).isEqualTo(CollectionStatus.State.STOPPED);
        }
    }

    /**
     * 🔴 <b>러너의 종료가 후원 저장기도 닫는가</b> (POK-234 감사 라운드 2 A1).
     *
     * <p><b>안 닫으면 스프링 {@code @PreDestroy}가 예산 밖에서 닫는다.</b> 빈 파괴는 한
     * 스레드에서 차례로 돌므로(감사 탐침 1412ms) 그 시간이 종료 예산에 그대로 더해져 합이
     * 22초가 되고 운영 유예 20초를 넘긴다 — 그때 잘리는 것은 <b>이미 지나간 세션 닫기가 못
     * 끝낸 구독 반납</b>이라 계정 자리 3개가 마른다. {@code ShutdownBudgetTest}는 상수의 합만
     * 보므로 <b>그 항이 감시망에 아예 없던 것</b>을 못 봤다.
     *
     * <p><b>재는 방법</b>: {@code start()}를 안 부른 후원 저장기를 준다. 그러면 1초 틱이 안 돌아
     * 바구니를 비우는 유일한 길이 {@code close()}가 제출하는 마지막 flush뿐이다 — 표에 행이
     * 생겼다는 것이 곧 「러너가 close를 불렀다」다.
     */
    // 문항 2: 「표에 있다」만 보면 <b>틱이 저장한 것</b>도 통과한다 — start()를 안 불러
    //         그 길을 아예 막았다. 넣기 전 행 수가 0인 것도 같이 본다.
    // 문항 8(운영 배선): 여기서 재는 것은 <b>러너의 배선</b>이다. 빈 배선(@Component +
    //         @PostConstruct)은 DonationPersisterWiringTest가 컨텍스트 빈으로 따로 잰다.
    @Test
    void 종료가_후원_저장기의_마지막_플러시까지_돌린다() {
        DonationBuffer donationBuffer = new DonationBuffer(1_000);
        // start()를 부르지 않는다 — 1초 틱이 돌면 close를 안 불러도 저절로 저장돼
        // 이 검사가 아무것도 안 재게 된다.
        DonationPersister donationPersister = new DonationPersister(jdbc, donationBuffer);
        buffer = new ChatBuffer(10_000);
        persister = new ChatPersister(jdbc, buffer);
        persister.start();
        runner = new CollectorRunner(
                new ChzzkProperties(true, "test-token", "http://localhost:" + port,
                        Duration.ofSeconds(5), Duration.ofMillis(50), Duration.ofSeconds(1)),
                new CollectionStatus(), restClientBuilder, buffer, persister, ChatArchive.NONE,
                donationPersister, null, () -> { });
        runner.run(null);
        donationBuffer.offer(new PersistableDonation(
                "close-wiring", "close-wiring-ch", "donator-1", "닉",
                "CHAT", 1000L, "고마워요", 1_723_600_500_000L));
        assertThat(countDonationRows("close-wiring-ch"))
                .as("넣기 전에 이미 행이 있으면 아래 단언이 남의 행을 세는 것이다")
                .isZero();

        runner.stop();

        assertThat(countDonationRows("close-wiring-ch"))
                .as("러너가 후원 저장기를 안 닫으면 이 행은 @PreDestroy까지 — 즉 예산 밖까지 안 간다")
                .isEqualTo(1);
        assertThat(donationBuffer.size()).isZero();
    }

    private long countDonationRows(String channelId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM chat_donations WHERE channel_id = ?", Long.class, channelId);
    }

    private static long field(String verdict, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(" " + name + "=(\\d+)").matcher(verdict);
        if (!m.find()) {
            throw new AssertionError(name + "이 판정 줄에 없다: " + verdict);
        }
        return Long.parseLong(m.group(1));
    }

    private CollectionStatus start() {
        return start(jdbc);
    }

    private CollectionStatus start(JdbcTemplate template) {
        return start(template, () -> { });
    }

    private CollectionStatus start(JdbcTemplate template, Runnable exitAction) {
        buffer = new ChatBuffer(10_000);
        persister = new ChatPersister(template, buffer);
        persister.start();
        CollectionStatus status = new CollectionStatus();
        runner = new CollectorRunner(
                new ChzzkProperties(true, "test-token", "http://localhost:" + port,
                        Duration.ofSeconds(5), Duration.ofMillis(50), Duration.ofSeconds(1)),
                status, restClientBuilder, buffer, persister, ChatArchive.NONE, exitAction);
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
