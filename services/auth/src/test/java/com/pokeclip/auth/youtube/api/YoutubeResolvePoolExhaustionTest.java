package com.pokeclip.auth.youtube.api;

import com.pokeclip.auth.streamkey.secret.SecretStore;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import com.pokeclip.auth.youtube.YoutubeChannelLinkRepository;
import com.pokeclip.auth.youtube.YoutubeCleanupExecutor;
import com.pokeclip.auth.youtube.YoutubeLinkStateCodec;
import com.pokeclip.auth.youtube.YoutubeLinkTestSupport;
import com.pokeclip.auth.youtube.YoutubeLinkWriter;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

/**
 * resolve는 커넥션을 쥔 채 최대 read-timeout 동안 구글 HTTP를 기다린다. 클립 업로드가 몰리면 동시에 들어온다.
 *
 * <p><b>운영과 같은 풀 10에서 뜬다</b>(`@TestPropertySource`). 다른 테스트가 쓰는 풀 30에서 돌리면
 * 25요청이 줄도 안 서고 통과해 <b>초록인 채 아무것도 안 잰다</b> — 그것이 이 검사가 성립하는 첫 번째 조건이다.
 *
 * <p>두 번째 조건은 <b>거부(4xx) 갈래를 포함하는 것</b>이다. 성공 경로와 달리 그 갈래만
 * {@code YoutubeTokenRefresher.reject}의 커밋 뒤 정리(secrets 삭제 REQUIRES_NEW + revoke)를 지난다.
 * 그것을 {@code afterCommit} 안에서 직접 하면 원 커넥션을 쥔 채 두 번째를 요구해 풀 데드락이 된다 —
 * POK-93 실측에서 풀 10·동시 25가 21건 500·30초 마비·고아 secrets 42로 나온 그 패턴이다.
 * 지금은 전용 스레드({@code YoutubeCleanupExecutor})가 돌아 커넥션이 하나다.
 *
 * <p>재는 것: (a) 전부 200 (b) 활성 커넥션 피크가 풀 크기에 닿았다 — 「고갈 직전 조건이 실제로 만들어졌다」는
 * 하한 단언 (c) 총 소요가 지연×1회보다 길다 — 줄이 실제로 섰다는 하한 단언 (d) 고아 secret 0.
 * 상한 단언(피크 ≤ 풀)은 물리적으로 항상 참이라 두지 않는다.
 */
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=10")
class YoutubeResolvePoolExhaustionTest extends YoutubeLinkTestSupport {

    private static final int CALLERS = 25;

    private final DataSource dataSource;

    YoutubeResolvePoolExhaustionTest(MockMvc mockMvc, UserService userService, UserRepository userRepository,
                                     TokenService tokenService, YoutubeLinkStateCodec codec,
                                     YoutubeChannelLinkRepository linkRepository, SecretStore secretStore,
                                     YoutubeLinkWriter writer, JdbcTemplate jdbc, YoutubeCleanupExecutor cleanup,
                                     DataSource dataSource) {
        super(mockMvc, userService, userRepository, tokenService, codec, linkRepository, secretStore, writer,
                jdbc, cleanup);
        this.dataSource = dataSource;
    }

    @Test
    void 풀보다_많은_회원_25명이_동시에_즉석_갱신을_타도_대기_타임아웃_없이_전부_200이다() throws Exception {
        List<User> users = linkedUsers();
        YOUTUBE.tokenDelays(Duration.ofSeconds(2));
        HikariDataSource hikari = (HikariDataSource) dataSource;
        assertThat(hikari.getMaximumPoolSize()).as("이 컨텍스트의 풀이 운영과 같은 10인지").isEqualTo(10);
        HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
        AtomicInteger peak = new AtomicInteger();
        ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor();
        sampler.scheduleAtFixedRate(() -> peak.accumulateAndGet(pool.getActiveConnections(), Math::max),
                0, 20, TimeUnit.MILLISECONDS);

        long started = System.nanoTime();
        List<MvcResult> results = callAll(users.stream().map(u -> resolve(u.getId())).toList());
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        sampler.shutdownNow();

        assertThat(results).allSatisfy(r -> {
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            assertThat(r.getResponse().getContentAsString()).contains("\"valid\":true");
        });
        assertThat(YOUTUBE.tokenCalls()).isEqualTo(CALLERS);
        assertThat(peak.get()).as("풀을 실제로 꽉 채웠다 — 아니면 이 검사는 경합을 안 만든 것이다").isEqualTo(10);
        assertThat(elapsedMs).as("줄이 실제로 섰다 — 25요청/10커넥션·지연 2s면 최소 3라운드").isGreaterThan(4_000);
        System.out.printf("youtube resolve 동시 %d · 풀 10 · 지연 2s · 활성 커넥션 피크 %d · 총 %dms%n",
                CALLERS, peak.get(), elapsedMs);
    }

    /**
     * 🔴 거부 갈래. 여기만 {@code YoutubeTokenRefresher.reject}의 커밋 뒤 정리(secrets 삭제 + revoke)를 지난다 —
     * 태스크 8이 만든 <b>두 번째</b> 풀 데드락 후보다. 성공 경로만 재는 위 검사는 이것을 못 본다.
     */
    @Test
    void 풀보다_많은_회원_25명의_즉석_갱신이_동시에_거부돼도_전부_200이고_secrets가_결국_지워진다() throws Exception {
        List<User> users = linkedUsers();
        YOUTUBE.tokenResponds(400, "{\"error\":\"invalid_grant\"}");
        YOUTUBE.tokenDelays(Duration.ofSeconds(2));

        long started = System.nanoTime();
        List<MvcResult> results = callAll(users.stream().map(u -> resolve(u.getId())).toList());
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(results).allSatisfy(r -> {
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            assertThat(r.getResponse().getContentAsString()).contains("BROKEN");
        });
        assertThat(elapsedMs).as("Hikari 30s 대기 타임아웃이 났다 = 풀 데드락").isLessThan(30_000);
        assertThat(YOUTUBE.tokenCalls()).isEqualTo(CALLERS);
        assertThat(cleanup.awaitIdle(Duration.ofSeconds(10))).as("커밋 뒤 정리가 10초 안에 끝났다").isTrue();
        assertThat(secretCount()).as("고아 secret").isZero();
        assertThat(YOUTUBE.revokeCalls()).as("죽은 grant마다 한 번씩만").isEqualTo(CALLERS);
        System.out.printf("youtube 거부 resolve 동시 %d · 풀 10 · 지연 2s · 총 %dms%n", CALLERS, elapsedMs);
    }

    /** 해제도 같은 자리(커밋 뒤 secrets 삭제 + revoke)를 지난다 — 재연동의 옛 행 폐기와 같은 코드다. */
    @Test
    void 풀보다_많은_회원_25명이_동시에_해제해도_전부_204이고_secrets가_결국_지워진다() throws Exception {
        List<User> users = linkedUsers();

        long started = System.nanoTime();
        List<MvcResult> results = callAll(users.stream()
                .map(u -> delete("/api/youtube-link").header("Authorization", bearer(u))).toList());
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(results).allSatisfy(r -> assertThat(r.getResponse().getStatus()).isEqualTo(204));
        assertThat(elapsedMs).as("Hikari 30s 대기 타임아웃이 났다 = 풀 데드락").isLessThan(30_000);
        assertThat(cleanup.awaitIdle(Duration.ofSeconds(10))).isTrue();
        assertThat(secretCount()).as("고아 secret").isZero();
        assertThat(YOUTUBE.revokeCalls()).as("회원마다 한 번 — 구글은 한 번이면 grant 전체가 죽는다").isEqualTo(CALLERS);
        System.out.printf("youtube unlink 동시 %d · 풀 10 · 총 %dms%n", CALLERS, elapsedMs);
    }

    /** 25명 전원이 「즉석 갱신이 필요한」 상태다 — access를 이미 만료시켜 두어야 resolve가 구글까지 간다. */
    private List<User> linkedUsers() {
        List<User> users = IntStream.range(0, CALLERS).mapToObj(i -> newUser()).toList();
        users.forEach(u -> accessRemaining(linked(u, "at-old", "rt-old"), Duration.ofMinutes(-1)));
        return users;
    }

    private List<MvcResult> callAll(List<MockHttpServletRequestBuilder> requests) throws Exception {
        ExecutorService callers = Executors.newFixedThreadPool(requests.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<MvcResult>> futures = requests.stream().map(req -> callers.submit(() -> {
            go.await();
            return mockMvc.perform(req).andReturn();
        })).toList();
        go.countDown();
        List<MvcResult> results = new ArrayList<>();
        for (Future<MvcResult> f : futures) {
            results.add(f.get(90, TimeUnit.SECONDS));
        }
        callers.shutdown();
        return results;
    }
}
