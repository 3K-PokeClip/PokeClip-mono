package com.pokeclip.auth.chzzk;

import com.pokeclip.auth.streamkey.secret.SecretStore;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ChzzkTokenRefresherTest extends ChzzkLinkTestSupport {

    private final ChzzkTokenRefresher refresher;

    ChzzkTokenRefresherTest(MockMvc mockMvc, UserService userService, UserRepository userRepository,
                            TokenService tokenService, ChzzkLinkStateCodec codec,
                            ChzzkChannelLinkRepository linkRepository, SecretStore secretStore,
                            ChzzkLinkWriter writer, JdbcTemplate jdbc, ChzzkCleanupExecutor cleanup, ChzzkTokenRefresher refresher) {
        super(mockMvc, userService, userRepository, tokenService, codec, linkRepository, secretStore, writer, jdbc, cleanup);
        this.refresher = refresher;
    }

    @Test
    void 남은_수명이_충분하면_치지직을_부르지_않는다() {
        User u = newUser();
        linked(u, Duration.ofHours(20));
        RefreshResult r = refresher.refreshIfExpiringWithin(u.getId(), Duration.ofHours(6));
        assertThat(r.outcome()).isEqualTo(RefreshOutcome.SKIPPED_FRESH);
        // 스냅샷에 access 원문이 실린다 — resolve가 락 밖에서 secrets를 두 번째로 읽지 않게(그 사이 해제가 끼면 500).
        assertThat(r.snapshot().accessToken()).isEqualTo("at-old");
        assertThat(CHZZK.tokenCalls()).isZero();
    }

    @Test
    void 임박하면_갱신하고_secrets_만료_scope_갱신시각이_한꺼번에_바뀐다() {
        User u = newUser();
        ChzzkChannelLink before = linked(u, Duration.ofHours(1));
        RefreshResult r = refresher.refreshIfExpiringWithin(u.getId(), Duration.ofHours(6));
        assertThat(r.outcome()).isEqualTo(RefreshOutcome.REFRESHED);
        ChzzkChannelLink after = linkRepository.findById(before.getId()).orElseThrow();
        assertThat(secretStore.get(after.getAccessTokenRef())).contains("at-1");        // 새 토큰, 같은 ref
        assertThat(secretStore.get(after.getRefreshTokenRef())).contains("rt-1");
        assertThat(after.getAccessExpiresAt()).isAfter(Instant.now().plus(Duration.ofHours(23)));
        assertThat(after.getScope()).isEqualTo("chat");
        assertThat(after.getLastRefreshedAt()).isAfter(before.getLastRefreshedAt());
        assertThat(r.snapshot().accessExpiresAt()).isEqualTo(after.getAccessExpiresAt());
        assertThat(r.snapshot().accessToken()).as("스냅샷의 토큰 = 방금 저장한 secrets 값").isEqualTo("at-1")
                .isEqualTo(secretStore.get(after.getAccessTokenRef()).orElseThrow());
        assertThat(CHZZK.tokenRequests().get(0)).containsEntry("grantType", "refresh_token")
                .containsEntry("refreshToken", "rt-old");
    }

    @Test
    void 갱신이_4xx면_BROKEN으로_닫고_secrets를_지우고_다시_시도하지_않는다() {
        User u = newUser();
        ChzzkChannelLink link = linked(u, Duration.ofHours(1));
        CHZZK.tokenResponds(401, "{\"code\":401,\"message\":\"INVALID_TOKEN\"}");
        RefreshResult r = refresher.refreshIfExpiringWithin(u.getId(), Duration.ofHours(6));
        assertThat(r.outcome()).isEqualTo(RefreshOutcome.REJECTED);
        assertThat(r.snapshot()).isNull();
        ChzzkChannelLink after = linkRepository.findById(link.getId()).orElseThrow();
        assertThat(after.status(Instant.now())).isEqualTo(LinkStatus.BROKEN);
        awaitCleanup();   // secrets 삭제는 커밋 뒤 전용 스레드에서 — "결국" 지워진다
        assertThat(secretStore.get(link.getAccessTokenRef())).isEmpty();
        assertThat(secretStore.get(link.getRefreshTokenRef())).isEmpty();
        assertThat(refresher.refreshIfExpiringWithin(u.getId(), Duration.ofHours(6)).outcome())
                .isEqualTo(RefreshOutcome.NOT_LINKED);
        assertThat(CHZZK.tokenCalls()).isEqualTo(1);
    }

    @Test
    void 갱신이_5xx면_행을_그대로_두고_다음에_다시_시도한다() {
        User u = newUser();
        ChzzkChannelLink link = linked(u, Duration.ofHours(1));
        CHZZK.tokenResponds(503, "{}");
        RefreshResult r = refresher.refreshIfExpiringWithin(u.getId(), Duration.ofHours(6));
        assertThat(r.outcome()).isEqualTo(RefreshOutcome.UNAVAILABLE);
        assertThat(r.snapshot()).isNull();
        ChzzkChannelLink after = linkRepository.findById(link.getId()).orElseThrow();
        assertThat(after.isRevoked()).isFalse();
        assertThat(secretStore.get(link.getRefreshTokenRef())).contains("rt-old");
        CHZZK.reset();
        assertThat(refresher.refreshIfExpiringWithin(u.getId(), Duration.ofHours(6)).outcome())
                .isEqualTo(RefreshOutcome.REFRESHED);
    }

    @Test
    void 미연동이면_NOT_LINKED() {
        User u = newUser();
        RefreshResult r = refresher.refreshIfExpiringWithin(u.getId(), Duration.ofHours(6));
        assertThat(r.outcome()).isEqualTo(RefreshOutcome.NOT_LINKED);
        assertThat(r.snapshot()).isNull();
        assertThat(CHZZK.tokenCalls()).isZero();
    }

    /** refresh는 일회용이다. 두 진입점(스케줄러·resolve)이 겹쳐도 치지직 호출은 한 번이어야 한다. */
    @Test
    void 같은_회원을_10스레드가_동시에_갱신해도_치지직_호출은_1회다() throws Exception {
        User u = newUser();
        linked(u, Duration.ofHours(1));
        CHZZK.tokenDelays(Duration.ofMillis(300));                                          // 경합 창을 벌린다
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<RefreshResult>> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return refresher.refreshIfExpiringWithin(u.getId(), Duration.ofHours(6));
            }));
        }
        go.countDown();
        List<RefreshOutcome> outcomes = new ArrayList<>();
        for (var f : results) {
            outcomes.add(f.get(30, TimeUnit.SECONDS).outcome());
        }
        pool.shutdown();
        assertThat(CHZZK.tokenCalls()).isEqualTo(1);
        assertThat(outcomes).containsOnlyOnce(RefreshOutcome.REFRESHED);
        assertThat(outcomes.stream().filter(o -> o == RefreshOutcome.SKIPPED_FRESH).count()).isEqualTo(9);
    }
}
