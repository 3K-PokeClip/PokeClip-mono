package com.pokeclip.auth.withdrawal.purge;

import com.pokeclip.auth.chzzk.ChzzkCleanupExecutor;
import com.pokeclip.auth.chzzk.ChzzkLinkWriter;
import com.pokeclip.auth.chzzk.ChzzkMe;
import com.pokeclip.auth.chzzk.ChzzkTokens;
import com.pokeclip.auth.config.InternalApiProperties;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserService;
import com.pokeclip.auth.withdrawal.WithdrawalTestSupport;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 탈퇴 정리 알림(POK-256). 탈퇴가 장부에 적고, 발송기가 clip·수집기에 받을 때까지 보낸다.
 */
class WithdrawalPurgeTest extends WithdrawalTestSupport {

    private static final String INTERNAL_TOKEN = "test-only-internal-token-32bytes-long!!";

    private final ChzzkLinkWriter chzzkWriter;
    private final ChzzkCleanupExecutor chzzkCleanup;
    private final PurgeJobRepository jobs;
    private final RestClient.Builder restClientBuilder;
    private final InternalApiProperties internal;

    private HttpServer server;
    private final List<String> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger answer = new AtomicInteger(202);

    WithdrawalPurgeTest(MockMvc mockMvc, UserService userService, TokenService tokenService, JdbcTemplate jdbc,
                        ChzzkLinkWriter chzzkWriter, ChzzkCleanupExecutor chzzkCleanup, PurgeJobRepository jobs,
                        RestClient.Builder restClientBuilder, InternalApiProperties internal) {
        super(mockMvc, userService, tokenService, jdbc);
        this.chzzkWriter = chzzkWriter;
        this.chzzkCleanup = chzzkCleanup;
        this.jobs = jobs;
        this.restClientBuilder = restClientBuilder;
        this.internal = internal;
    }

    /** clip과 수집기 노릇을 하는 서버 하나. 받은 「메서드 경로 토큰일치」를 적는다. 루프백에 붙인다(가로채기 방지). */
    @BeforeEach
    void 가짜_서버를_띄운다() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/internal/", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            received.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
                    + (query == null ? "" : "?" + query) + " "
                    + INTERNAL_TOKEN.equals(exchange.getRequestHeaders().getFirst("X-Internal-Token")));
            exchange.sendResponseHeaders(answer.get(), -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void 내린다() {
        server.stop(0);
        chzzkCleanup.awaitIdle(Duration.ofSeconds(5));
    }

    @Test
    void 탈퇴하면_clip_한_줄과_연동했던_채널마다_수집기_줄을_적는다_남이_쥔_채널은_뺀다() throws Exception {
        User user = newUser();
        User other = newUser();
        String old = "chan-old-" + marker();
        String current = "chan-cur-" + marker();
        link(user, old);
        chzzkWriter.revoke(user.getId(), Instant.now());
        link(other, old);          // 같은 채널을 지금은 다른 회원이 쥐고 있다
        link(user, current);

        withdraw(user);

        assertThat(jobsOf(user)).containsExactlyInAnyOrder("CLIP:", "COLLECTOR:" + current);
        assertThat(jobsOf(other)).isEmpty();
    }

    @Test
    void 연동이_없으면_clip_줄만_적고_다시_적어도_줄이_안_는다() throws Exception {
        User user = newUser();

        withdraw(user);
        // 탈퇴한 회원은 두 번째 요청이 입구에서 401이라 장부를 직접 한 번 더 부른다(같은 탈퇴의 재시도와 같은 모양).
        jobs.enqueue(user.getId(), Instant.now());

        assertThat(jobsOf(user)).containsExactly("CLIP:");
    }

    @Test
    void 발송기는_두_서버의_지우기_문을_토큰과_함께_부르고_받으면_닫는다() throws Exception {
        User user = newUser();
        String channel = "chan-" + marker();
        link(user, channel);
        withdraw(user);

        dispatcher().dispatchOnce();

        // 수집기에는 탈퇴 시각(장부 줄을 만든 시각)을 싣는다. 보내는 시각을 실으면 재전송마다 범위가 밀린다.
        String since = jdbc.queryForObject("SELECT created_at FROM withdrawal_purge_jobs WHERE user_id = ? AND target = 'COLLECTOR'",
                java.sql.Timestamp.class, user.getId()).toInstant().toString();
        assertThat(received).containsExactlyInAnyOrder(
                "DELETE /internal/streamers/" + user.getId() + "/data true",
                "DELETE /internal/channels/" + channel + "/chat-data?since=" + since + " true");
        assertThat(openJobs(user)).isZero();
    }

    @Test
    void 받지_못하면_뒤로_미루고_때가_되면_다시_보낸다() throws Exception {
        User user = newUser();
        withdraw(user);
        answer.set(503);

        dispatcher().dispatchOnce();

        assertThat(openJobs(user)).isOne();
        Map<String, Object> row = jdbc.queryForMap("SELECT attempts, next_attempt_at > now() + interval '10 seconds' "
                + "AS later FROM withdrawal_purge_jobs WHERE user_id = ?", user.getId());
        assertThat(row).containsEntry("attempts", 1).containsEntry("later", true);

        // 미룬 동안에는 안 보낸다.
        received.clear();
        dispatcher().dispatchOnce();
        assertThat(received).isEmpty();

        // 때가 되면 다시 보내고, 이번엔 받는다.
        jdbc.update("UPDATE withdrawal_purge_jobs SET next_attempt_at = now() - interval '1 second' WHERE user_id = ?",
                user.getId());
        answer.set(202);
        dispatcher().dispatchOnce();
        assertThat(openJobs(user)).isZero();
    }

    @Test
    void 다시_보내는_간격은_두_배씩_늘고_한_시간에서_멈춘다() {
        assertThat(PurgeDispatcher.backoff(0)).isEqualTo(Duration.ofSeconds(15));
        assertThat(PurgeDispatcher.backoff(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(PurgeDispatcher.backoff(7)).isEqualTo(Duration.ofSeconds(1920));
        assertThat(PurgeDispatcher.backoff(8)).isEqualTo(Duration.ofHours(1));
        assertThat(PurgeDispatcher.backoff(500)).isEqualTo(Duration.ofHours(1));
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    private PurgeDispatcher dispatcher() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        WithdrawalPurgeProperties properties = new WithdrawalPurgeProperties(base, base, false, Duration.ofSeconds(15));
        return new PurgeDispatcher(jobs, new PurgeNotifier(restClientBuilder, properties, internal), properties);
    }

    private void link(User user, String channelId) {
        chzzkWriter.create(user.getId(), new ChzzkMe(channelId, "채널"),
                new ChzzkTokens("at-" + marker(), "rt-" + marker(), Duration.ofHours(24), "chat"));
    }

    private void withdraw(User user) throws Exception {
        mockMvc.perform(delete("/api/auth/me").header("Authorization", bearer(user)))
                .andExpect(status().isNoContent());
    }

    private List<String> jobsOf(User user) {
        return jdbc.queryForList("SELECT target || ':' || COALESCE(channel_id, '') FROM withdrawal_purge_jobs "
                + "WHERE user_id = ?", String.class, user.getId());
    }

    private int openJobs(User user) {
        return jdbc.queryForObject("SELECT count(*) FROM withdrawal_purge_jobs WHERE user_id = ? AND done_at IS NULL",
                Integer.class, user.getId());
    }
}
