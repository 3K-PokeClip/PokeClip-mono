package com.pokeclip.chat.collector.relay;

import com.pokeclip.chat.collector.CollectorApplication;
import com.pokeclip.chat.collector.CollectorHealth;
import com.pokeclip.chat.collector.CollectionStatus;
import com.pokeclip.chat.collector.fake.FakeChzzkBehavior;
import com.pokeclip.chat.collector.fake.FakeChzzkTest;
import com.pokeclip.chat.collector.session.SessionKey;
import com.pokeclip.chat.collector.session.SessionRegistry;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 🔴 <b>운영 배선을 지나는</b> 중계 종료·health·판정 줄 시험(감사 L6). A 교훈: 판정 줄 함수에 값을 직접 넘긴
 * 시험은 운영에서 늘 0인 항을 초록으로 통과시켰다(「받은 후원 수」). 그래서 여기서는 수집기를 <b>따로 띄운
 * 컨텍스트로 올리고</b> 닫는다 — 러너·등록부·중계기가 스프링이 물린 그대로다({@code SessionShutdownTest}와 같은 수법:
 * 같은 컨텍스트에서 닫으면 가짜 치지직의 톰캣이 같이 죽는다).
 */
@FakeChzzkTest
class RelayShutdownWiringTest extends IntegrationTestSupport {

    private static final Duration AWAIT = Duration.ofSeconds(10);
    private static final AtomicLong KEY_SEQ = new AtomicLong();

    @LocalServerPort int port;
    @Autowired FakeChzzkBehavior behavior;
    @Autowired JdbcTemplate jdbc;

    private FakeClipRelay clip;
    private ConfigurableApplicationContext booted;

    @AfterEach
    void tearDown() {
        if (booted != null && booted.isActive()) booted.close();
        if (clip != null) clip.close();
        behavior.reset();
        jdbc.update("DELETE FROM chat_messages WHERE stream_id LIKE 'relay-sd-%'");
        jdbc.update("DELETE FROM chat_donations WHERE stream_id LIKE 'relay-sd-%'");
    }

    /**
     * 켠 컨텍스트를 닫으면 러너가 중계를 공유 기한 안에서 닫고, <b>판정 줄에 중계 셈 넷</b>이 실리며 등식
     * {@code relayOffered = relayed + relayDropped + relayBufferDropped}가 닫힌다(닫힌 뒤라 바구니 잔량 0).
     *
     * <p>clip이 요청마다 300ms를 붙들게 한다 — 채팅이 등록부에 닿자마자 닫으므로, 러너가 중계 닫기를 안 기다리면
     * 판정 줄 시점에 아직 못 보낸 것이 남아 {@code relayed}가 모자란다.
     */
    @Test
    void 켠_컨텍스트를_닫으면_판정_줄에_중계_셈이_실리고_등식이_닫힌다() throws Exception {
        clip = FakeClipRelay.start();
        clip.holdFor(Duration.ofMillis(300));
        booted = boot(clip.baseUrl());
        SessionRegistry registry = booted.getBean(SessionRegistry.class);
        registry.open(key("relay-sd-ok", 201L), "tok-201");
        awaitCollecting(registry, "relay-sd-ok");

        for (int i = 0; i < 5; i++) {
            behavior.emitChatTo("tok-201", chatJson("판정" + i, 1_723_700_000_000L + i));
        }
        behavior.emitDonationTo("tok-201", donationJson("판정후원"));
        RelayCounters counters = booted.getBean(RelayCounters.class);
        awaitUntil(AWAIT, () -> counters.relayOffered() == 6);
        assertThat(counters.relayOffered()).as("양성 대조 — 여섯 건이 중계 바구니에 번호를 받았다").isEqualTo(6);

        String verdict;
        try (LogCaptor captor = new LogCaptor()) {
            booted.close();
            verdict = captor.messages().stream().filter(m -> m.startsWith("chat.session.verdict"))
                    .findFirst().orElseThrow(() -> new AssertionError("판정 줄이 없다"));
        }

        assertThat(field(verdict, "relayOffered")).isEqualTo(6);
        assertThat(field(verdict, "relayed")).as("러너가 중계 닫기를 기다렸다면 여섯 다 보냈다").isEqualTo(6);
        assertThat(field(verdict, "relayDropped")).isZero();
        assertThat(field(verdict, "relayBufferDropped")).isZero();
        assertThat(field(verdict, "relayOffered"))
                .isEqualTo(field(verdict, "relayed") + field(verdict, "relayDropped") + field(verdict, "relayBufferDropped"));
        assertThat(clip.requests().stream().mapToInt(r -> r.body().get("events").size()).sum()).isEqualTo(6);
    }

    /**
     * clip이 죽어도 <b>health는 UP이다</b> — clip 장애로 ECS 헬스체크가 수집기를 죽이면 저장까지 멈춘다(F12).
     * {@code relay}는 <b>직전 호출 이후</b> 버린 것이 늘었을 때만 {@code dropping}이다 — 누적으로 가르면 한 번
     * 버린 뒤로 영원히 dropping이라 아무것도 안 알려 준다.
     */
    @Test
    void clip이_죽어도_health는_UP이고_relay는_직전_호출_대비로_dropping이다() throws Exception {
        booted = boot(closedPortBaseUrl());
        SessionRegistry registry = booted.getBean(SessionRegistry.class);
        CollectorHealth health = booted.getBean(CollectorHealth.class);
        assertThat(health.health().getDetails().get("relay")).as("아직 버린 것이 없다").isEqualTo("ok");

        registry.open(key("relay-sd-dead", 202L), "tok-202");
        awaitCollecting(registry, "relay-sd-dead");
        for (int i = 0; i < 3; i++) {
            behavior.emitChatTo("tok-202", chatJson("죽은clip" + i, 1_723_710_000_000L + i));
        }
        RelayCounters counters = booted.getBean(RelayCounters.class);
        awaitUntil(AWAIT, () -> counters.relayDropped() == 3);
        assertThat(counters.relayDropped()).as("양성 대조 — clip이 정말 죽었다").isEqualTo(3);

        Health first = health.health();
        Health second = health.health();

        assertThat(first.getStatus()).as("clip 장애가 수집기 health를 내리면 안 된다").isEqualTo(Status.UP);
        assertThat(first.getDetails().get("relay")).isEqualTo("dropping");
        assertThat(first.getDetails().get("relayOffered")).isEqualTo(3L);
        assertThat(first.getDetails().get("relayed")).isEqualTo(0L);
        assertThat(first.getDetails().get("relayDropped")).isEqualTo(3L);
        assertThat(first.getDetails().get("relayBufferDropped")).isEqualTo(0L);
        assertThat(second.getDetails().get("relay")).as("그 뒤로 더 버린 것이 없으면 ok로 돌아온다").isEqualTo("ok");
        assertThat(second.getStatus()).isEqualTo(Status.UP);
    }

    // ------------------------------------------------------------------

    private ConfigurableApplicationContext boot(String clipBaseUrl) {
        return new SpringApplicationBuilder(CollectorApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("test")
                .run("--pokeclip.chzzk.base-url=http://localhost:" + port,
                        "--pokeclip.chzzk.establish-timeout=" + Duration.ofSeconds(5),
                        "--pokeclip.relay.enabled=true",
                        "--pokeclip.relay.clip-base-url=" + clipBaseUrl,
                        "--pokeclip.link.internal-token=relay-shutdown-token",
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword());
    }

    private static void awaitCollecting(SessionRegistry registry, String streamId) throws InterruptedException {
        awaitUntil(AWAIT, () -> registry.statusOf(streamId) != null
                && registry.statusOf(streamId).state() == CollectionStatus.State.COLLECTING);
        assertThat(registry.statusOf(streamId).state()).isEqualTo(CollectionStatus.State.COLLECTING);
    }

    private static long field(String line, String name) {
        Matcher m = Pattern.compile(" " + name + "=(\\d+)").matcher(line);
        if (!m.find()) {
            throw new AssertionError(name + "이 판정 줄에 없다: " + line);
        }
        return Long.parseLong(m.group(1));
    }

    private static SessionKey key(String streamId, long streamerId) {
        return new SessionKey(streamId, streamerId, "CH", Instant.EPOCH.plusSeconds(KEY_SEQ.incrementAndGet()));
    }

    private static String chatJson(String content, long messageTime) {
        return "{\"channelId\":\"CH\",\"senderChannelId\":\"u-1\",\"content\":\"" + content
                + "\",\"messageTime\":" + messageTime + "}";
    }

    private static String donationJson(String text) {
        return "{\"donationType\":\"CHAT\",\"channelId\":\"CH\",\"donatorChannelId\":\"D\","
                + "\"donatorNickname\":\"n\",\"payAmount\":\"1000\",\"donationText\":\"" + text + "\"}";
    }

    private static String closedPortBaseUrl() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return "http://" + socket.getInetAddress().getHostAddress() + ":" + socket.getLocalPort();
        }
    }
}
