package com.pokeclip.auth.chzzk.api;

import com.pokeclip.auth.chzzk.ChzzkChannelLinkRepository;
import com.pokeclip.auth.chzzk.ChzzkLinkStateCodec;
import com.pokeclip.auth.chzzk.ChzzkLinkTestSupport;
import com.pokeclip.auth.chzzk.ChzzkCleanupExecutor;
import com.pokeclip.auth.chzzk.ChzzkLinkWriter;
import com.pokeclip.auth.streamkey.secret.SecretStore;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
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
 * resolve는 커넥션을 쥔 채 최대 read-timeout(5s) 동안 외부 HTTP를 기다린다. 방송 시작이 몰리면 동시에 들어온다.
 * "스케줄러는 순차라 풀 10 안에 든다"는 스케줄러만 본 것이다 — 이 경로는 따로, 운영과 같은 풀 10에서,
 * 풀보다 많은 요청(25)으로 잰다. 대기가 실제로 생기는 조건이다.
 *
 * <p>별도 클래스·별도 컨텍스트(풀 10)인 이유: 풀 크기가 다르고 지연 설정이 다른 테스트를 느리게 하지 않게.
 * MockMvc는 호출 스레드에서 필터체인을 돌리므로 스레드 풀로 병렬 호출하면 커넥션도 스레드마다 잡힌다.
 *
 * <p>재는 것: (a) 전부 200·valid:true — 커넥션 대기 타임아웃(Hikari connection-timeout 30s)이 나지 않았다
 * (b) 활성 커넥션 피크가 풀 크기(10)에 닿았다 — "고갈 직전 조건이 실제로 만들어졌다"는 증거(하한 단언)
 * (c) 총 소요가 지연×1회보다 길다 — 줄이 실제로 섰다는 증거(하한 단언).
 * 상한 단언(피크 ≤ 풀)은 물리적으로 항상 참이라 두지 않는다(auth/CLAUDE.md "상한 단언은 구현 전에도 초록").
 * 수치는 PR 본문에 적는다 — "동시 25·풀 10·지연 2s에서 잰 값"임을 같이.
 */
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=10")
class ChzzkResolvePoolExhaustionTest extends ChzzkLinkTestSupport {

    private final DataSource dataSource;

    ChzzkResolvePoolExhaustionTest(MockMvc mockMvc, UserService userService, UserRepository userRepository,
                                   TokenService tokenService, ChzzkLinkStateCodec codec,
                                   ChzzkChannelLinkRepository linkRepository, SecretStore secretStore,
                                   ChzzkLinkWriter writer, JdbcTemplate jdbc, ChzzkCleanupExecutor cleanup, DataSource dataSource) {
        super(mockMvc, userService, userRepository, tokenService, codec, linkRepository, secretStore, writer, jdbc, cleanup);
        this.dataSource = dataSource;
    }

    @Test
    void 풀보다_많은_회원_25명이_동시에_즉석_갱신을_타도_대기_타임아웃_없이_전부_200이다() throws Exception {
        List<User> users = IntStream.range(0, 25).mapToObj(i -> newUser()).toList();
        users.forEach(u -> linked(u, Duration.ofHours(1)));
        CHZZK.tokenDelays(Duration.ofSeconds(2));
        HikariDataSource hikari = (HikariDataSource) dataSource;
        assertThat(hikari.getMaximumPoolSize()).as("이 컨텍스트의 풀이 운영과 같은 10인지").isEqualTo(10);
        HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
        AtomicInteger peak = new AtomicInteger();
        ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor();
        sampler.scheduleAtFixedRate(() -> peak.accumulateAndGet(pool.getActiveConnections(), Math::max),
                0, 20, TimeUnit.MILLISECONDS);
        ExecutorService callers = Executors.newFixedThreadPool(25);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<MvcResult>> futures = users.stream().map(u -> callers.submit(() -> {
            go.await();
            return mockMvc.perform(resolve(u.getId())).andReturn();
        })).toList();
        long started = System.nanoTime();
        go.countDown();
        List<MvcResult> results = new ArrayList<>();
        for (var f : futures) {
            results.add(f.get(90, TimeUnit.SECONDS));
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        sampler.shutdownNow();
        callers.shutdown();
        assertThat(results).allSatisfy(r -> {
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            assertThat(r.getResponse().getContentAsString()).contains("\"valid\":true");
        });
        assertThat(CHZZK.tokenCalls()).isEqualTo(25);
        assertThat(peak.get()).as("풀을 실제로 꽉 채웠다").isEqualTo(10);
        assertThat(elapsedMs).as("줄이 실제로 섰다 — 25요청/10커넥션·지연 2s면 최소 3라운드").isGreaterThan(4_000);
        System.out.printf("resolve 동시 25 · 풀 10 · 지연 2s · 활성 커넥션 피크 %d · 총 %dms%n", peak.get(), elapsedMs);
    }

    /**
     * 4xx 경로는 성공 경로와 다르다 — 커밋 뒤 secrets 삭제(REQUIRES_NEW)·revoke가 붙는다. 그것을 afterCommit 안에서
     * 직접 하면 원 커넥션을 쥔 채 두 번째 커넥션을 요구해, 운영 풀 10(application.yml) < 동시 25에서 풀 데드락
     * (Hikari connection-timeout 30s)이 된다 — 감사 3회차 재현: 200 4건/500 21건, 32초, 고아 secrets 42.
     * 성공 경로만 재는 위 검사는 이것을 못 본다. 지금은 전용 스레드(ChzzkCleanupExecutor)가 돌므로 커넥션은 1개다.
     * 재는 것: 전부 200(BROKEN) · 소요 < 30s(타임아웃 미발생) · secrets가 결국 0(최대 10초) · 치지직 호출 25.
     */
    @Test
    void 풀보다_많은_회원_25명의_즉석_갱신이_동시에_4xx로_거부돼도_대기_타임아웃_없이_전부_200이고_secrets가_결국_지워진다() throws Exception {
        List<User> users = IntStream.range(0, 25).mapToObj(i -> newUser()).toList();
        users.forEach(u -> linked(u, Duration.ofHours(1)));
        CHZZK.tokenResponds(401, "{\"code\":401,\"message\":\"INVALID_TOKEN\"}");
        CHZZK.tokenDelays(Duration.ofSeconds(2));
        long started = System.nanoTime();
        List<MvcResult> results = callAll(users.stream().map(u -> resolve(u.getId())).toList());
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        assertThat(results).allSatisfy(r -> {
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            assertThat(r.getResponse().getContentAsString()).contains("BROKEN");
        });
        assertThat(elapsedMs).as("Hikari 30s 대기 타임아웃이 나지 않았다").isLessThan(30_000);
        assertThat(CHZZK.tokenCalls()).isEqualTo(25);
        assertThat(cleanup.awaitIdle(Duration.ofSeconds(10))).as("커밋 뒤 정리가 10초 안에 끝났다").isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM secrets", Integer.class)).as("고아 secret").isZero();
        System.out.printf("4xx resolve 동시 25 · 풀 10 · 지연 2s · 총 %dms%n", elapsedMs);
    }

    /** 해제도 같은 자리(커밋 뒤 secrets 삭제·revoke 2)를 지난다 — 재연동의 옛 행 폐기와 같은 코드. */
    @Test
    void 풀보다_많은_회원_25명이_동시에_해제해도_대기_타임아웃_없이_전부_204이고_secrets가_결국_지워진다() throws Exception {
        List<User> users = IntStream.range(0, 25).mapToObj(i -> newUser()).toList();
        users.forEach(u -> linked(u, Duration.ofHours(20)));
        long started = System.nanoTime();
        List<MvcResult> results = callAll(users.stream()
                .map(u -> delete("/api/chzzk-link").header("Authorization", bearer(u))).toList());
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        assertThat(results).allSatisfy(r -> assertThat(r.getResponse().getStatus()).isEqualTo(204));
        assertThat(elapsedMs).as("Hikari 30s 대기 타임아웃이 나지 않았다").isLessThan(30_000);
        assertThat(cleanup.awaitIdle(Duration.ofSeconds(10))).as("커밋 뒤 정리가 10초 안에 끝났다").isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM secrets", Integer.class)).as("고아 secret").isZero();
        assertThat(CHZZK.revokedTokens()).as("회원마다 access·refresh 둘").hasSize(50);
        System.out.printf("unlink 동시 25 · 풀 10 · 총 %dms%n", elapsedMs);
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
        for (var f : futures) {
            results.add(f.get(90, TimeUnit.SECONDS));
        }
        callers.shutdown();
        return results;
    }
}
