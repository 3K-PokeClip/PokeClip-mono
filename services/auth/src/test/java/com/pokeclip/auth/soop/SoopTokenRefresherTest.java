package com.pokeclip.auth.soop;

import ch.qos.logback.classic.Level;
import com.pokeclip.auth.streamkey.secret.SecretStore;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code ChzzkTokenRefresherTest}의 쌍둥이 + SOOP만의 둘(신선도로 갱신 · 거절이 아이디·닉네임까지 비운다). */
class SoopTokenRefresherTest extends SoopLinkTestSupport {

    private static final Duration AHEAD = Duration.ofHours(2);

    private final SoopTokenRefresher refresher;
    private final SoopRefreshBrake brake;

    SoopTokenRefresherTest(MockMvc mockMvc, UserService userService, UserRepository userRepository,
                           TokenService tokenService, SoopLinkStateCodec codec,
                           SoopChannelLinkRepository linkRepository, SecretStore secretStore,
                           SoopLinkWriter writer, JdbcTemplate jdbc, SoopCleanupExecutor cleanup,
                           SoopTokenRefresher refresher, SoopRefreshBrake brake) {
        super(mockMvc, userService, userRepository, tokenService, codec, linkRepository, secretStore, writer, jdbc, cleanup);
        this.refresher = refresher;
        this.brake = brake;
    }

    private void lastCheckedAgo(SoopChannelLink link, Duration ago) {
        jdbc.update("UPDATE soop_channel_links SET last_refreshed_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minus(ago)), link.getId());
    }

    @Test
    void 만료가_멀고_확인도_신선하면_SOOP을_부르지_않는다() {
        User u = newUser();
        linked(u, Duration.ofHours(8));

        RefreshResult r = refresher.refreshIfExpiringWithin(u.getId(), AHEAD);
        RefreshResult check = refresher.refreshForCheck(u.getId());

        assertThat(r.outcome()).isEqualTo(RefreshOutcome.SKIPPED_FRESH);
        assertThat(check.outcome()).isEqualTo(RefreshOutcome.SKIPPED_FRESH);
        // 스냅샷에 access 원문이 실린다: resolve가 락 밖에서 secrets를 두 번째로 읽지 않게.
        assertThat(r.snapshot().accessToken()).isEqualTo("at-old");
        assertThat(SOOP.tokenCalls()).isZero();
    }

    /** 철회 확인: 만료가 멀어도 마지막 확인이 staleness(24시간)를 넘으면 점검(스케줄러)은 갱신해 본다. */
    @Test
    void 확인이_오래됐으면_점검은_갱신하고_resolve는_안_한다() {
        User u = newUser();
        SoopChannelLink link = linked(u, Duration.ofHours(8));
        lastCheckedAgo(link, Duration.ofHours(25));

        assertThat(refresher.refreshIfExpiringWithin(u.getId(), AHEAD).outcome())
                .as("resolve는 신선도를 안 본다(만료만)").isEqualTo(RefreshOutcome.SKIPPED_FRESH);
        assertThat(SOOP.tokenCalls()).isZero();
        assertThat(refresher.refreshForCheck(u.getId()).outcome()).isEqualTo(RefreshOutcome.REFRESHED);
        assertThat(SOOP.tokenCalls()).isEqualTo(1);
    }

    @Test
    void 확인이_staleness_안쪽이면_점검도_안_부른다() {
        User u = newUser();
        SoopChannelLink link = linked(u, Duration.ofHours(8));
        lastCheckedAgo(link, Duration.ofHours(23));

        assertThat(refresher.refreshForCheck(u.getId()).outcome()).isEqualTo(RefreshOutcome.SKIPPED_FRESH);
        assertThat(SOOP.tokenCalls()).isZero();
    }

    @Test
    void 임박하면_갱신하고_secrets_만료_갱신시각이_한꺼번에_바뀐다() {
        User u = newUser();
        SoopChannelLink before = linked(u, Duration.ofHours(1));

        RefreshResult r = refresher.refreshIfExpiringWithin(u.getId(), AHEAD);

        assertThat(r.outcome()).isEqualTo(RefreshOutcome.REFRESHED);
        SoopChannelLink after = linkRepository.findById(before.getId()).orElseThrow();
        assertThat(secretStore.get(after.getAccessTokenRef())).contains("at-1");   // 새 토큰, 같은 ref
        assertThat(secretStore.get(after.getRefreshTokenRef())).contains("rt-1");
        assertThat(after.getAccessExpiresAt()).isAfter(Instant.now().plus(Duration.ofHours(7)));
        assertThat(after.getLastRefreshedAt()).isAfter(before.getLastRefreshedAt());
        assertThat(after.getScope()).isEqualTo("broad,station,chat");
        assertThat(r.snapshot().accessExpiresAt()).isEqualTo(after.getAccessExpiresAt());
        assertThat(r.snapshot().accessToken()).isEqualTo("at-1");
        assertThat(SOOP.tokenRequests().get(0)).containsEntry("refresh_token", "rt-old");
    }

    /** 갱신 응답에 refresh가 없으면 옛 refresh를 그대로 둔다. null로 덮으면 다음 갱신이 못 돈다. */
    @Test
    void 갱신_응답에_refresh가_없으면_옛_refresh를_유지한다() {
        User u = newUser();
        SoopChannelLink before = linked(u, Duration.ofHours(1));
        SOOP.tokenResponds(200, "{\"access_token\":\"at-new\",\"expires_in\":28800}");

        assertThat(refresher.refreshIfExpiringWithin(u.getId(), AHEAD).outcome()).isEqualTo(RefreshOutcome.REFRESHED);

        assertThat(secretStore.get(before.getAccessTokenRef())).contains("at-new");
        assertThat(secretStore.get(before.getRefreshTokenRef())).contains("rt-old");
    }

    /** 철회·만료: 닫고(BROKEN) 같은 문장에서 아이디·닉네임을 비우고 커밋 뒤 secrets를 지운다. 다시 부르지 않는다. */
    @Test
    void 갱신이_4xx면_BROKEN으로_닫고_아이디_닉네임과_secrets를_지운다() {
        User u = newUser();
        SoopChannelLink link = linked(u, Duration.ofHours(1));
        SOOP.tokenResponds(400, "{\"error\":\"invalid_grant\"}");

        try (LogCaptor logs = new LogCaptor()) {
            assertThat(refresher.refreshIfExpiringWithin(u.getId(), AHEAD).outcome()).isEqualTo(RefreshOutcome.REJECTED);
            awaitCleanup();
            assertThat(logs.messages()).contains("auth.soop.link.refresh_rejected userId=" + u.getId() + " status=400");
            assertThat(logs.levelOf("auth.soop.link.refresh_rejected")).isEqualTo(Level.WARN);
            assertThat(logs.messages()).noneMatch(m -> m.contains(link.getSoopUserId()) || m.contains("rt-old"));
        }

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT soop_user_id, nickname, revoke_reason FROM soop_channel_links WHERE id = ?", link.getId());
        assertThat(row.get("revoke_reason")).isEqualTo(RevokeReason.REFRESH_REJECTED.name());
        assertThat(row.get("soop_user_id")).isNull();
        assertThat(row.get("nickname")).isNull();
        assertThat(linkRepository.findFirstByUserIdOrderByCreatedAtDesc(u.getId()).orElseThrow().status(Instant.now()))
                .isEqualTo(LinkStatus.BROKEN);
        assertThat(secretStore.get(link.getAccessTokenRef())).isEmpty();
        assertThat(secretStore.get(link.getRefreshTokenRef())).isEmpty();

        assertThat(refresher.refreshIfExpiringWithin(u.getId(), AHEAD).outcome()).isEqualTo(RefreshOutcome.NOT_LINKED);
        assertThat(SOOP.tokenCalls()).as("닫힌 연동은 다시 부르지 않는다").isEqualTo(1);
    }

    /**
     * 🔴 브레이크가 선 뒤에는 거절을 받아도 파기하지 않는다(감사 2회차 중대). 브레이크는 「앱 설정 오류」를 뜻하므로 그 4xx는
     * 그 회원의 철회가 아니다. 파기하면 수집기 resolve가 방송하는 스트리머마다 연동을 하나씩 닫는다. 일시 실패로 답한다.
     */
    @Test
    void 브레이크가_선_뒤의_4xx는_파기하지_않고_UNAVAILABLE이다() {
        User u = newUser();
        SoopChannelLink link = linked(u, Duration.ofHours(1));
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM soop_channel_links WHERE id = ?", link.getId());
        SOOP.tokenResponds(401, "{}");
        brake.engage();
        try {
            assertThat(refresher.refreshIfExpiringWithin(u.getId(), AHEAD).outcome()).isEqualTo(RefreshOutcome.UNAVAILABLE);
            awaitCleanup();
        } finally {
            brake.release();   // 컨텍스트 공유 빈이다: 세운 채 두면 다른 클래스의 거절 시험이 전부 갈린다
        }
        assertThat(jdbc.queryForMap("SELECT * FROM soop_channel_links WHERE id = ?", link.getId()))
                .as("🔴 브레이크가 섰는데 행이 닫히거나 아이디·닉네임이 비었다").isEqualTo(before);
        assertThat(secretStore.get(link.getAccessTokenRef())).contains("at-old");
        assertThat(secretStore.get(link.getRefreshTokenRef())).contains("rt-old");
    }

    @Test
    void 갱신이_5xx면_행과_secrets를_그대로_두고_다음에_다시_시도한다() {
        User u = newUser();
        SoopChannelLink link = linked(u, Duration.ofHours(1));
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM soop_channel_links WHERE id = ?", link.getId());
        SOOP.tokenResponds(503, "{}");

        assertThat(refresher.refreshIfExpiringWithin(u.getId(), AHEAD).outcome()).isEqualTo(RefreshOutcome.UNAVAILABLE);

        assertThat(jdbc.queryForMap("SELECT * FROM soop_channel_links WHERE id = ?", link.getId())).isEqualTo(before);
        assertThat(secretStore.get(link.getAccessTokenRef())).contains("at-old");
        assertThat(secretStore.get(link.getRefreshTokenRef())).contains("rt-old");
        SOOP.reset();
        assertThat(refresher.refreshIfExpiringWithin(u.getId(), AHEAD).outcome()).isEqualTo(RefreshOutcome.REFRESHED);
    }

    /** NOT_LINKED에는 락 안에서 읽은 마지막 행 상태가 실린다(resolve가 락 밖에서 다시 안 읽게). */
    @Test
    void NOT_LINKED에는_락_안에서_읽은_마지막_행_상태가_실린다() {
        User broken = newUser();
        linked(broken, Duration.ofHours(1));
        SOOP.tokenResponds(401, "{}");
        refresher.refreshIfExpiringWithin(broken.getId(), AHEAD);
        awaitCleanup();
        User unlinked = newUser();
        linked(unlinked, Duration.ofHours(8));
        writer.revoke(unlinked.getId(), Instant.now());
        awaitCleanup();

        assertThat(refresher.refreshIfExpiringWithin(broken.getId(), AHEAD).lastStatus()).isEqualTo(LinkStatus.BROKEN);
        assertThat(refresher.refreshIfExpiringWithin(unlinked.getId(), AHEAD).lastStatus()).isEqualTo(LinkStatus.UNLINKED);
        assertThat(refresher.refreshIfExpiringWithin(newUser().getId(), AHEAD).lastStatus()).isNull();
    }

    @Test
    void 미연동이면_NOT_LINKED() {
        assertThat(refresher.refreshIfExpiringWithin(newUser().getId(), AHEAD).outcome())
                .isEqualTo(RefreshOutcome.NOT_LINKED);
        assertThat(refresher.refreshForCheck(Long.MAX_VALUE).outcome()).isEqualTo(RefreshOutcome.NOT_LINKED);
    }

    /** refresh는 일회용으로 다룬다. 두 진입점(스케줄러·resolve)이 겹쳐도 SOOP 호출은 한 번이어야 한다. */
    @Test
    void 같은_회원을_10스레드가_동시에_갱신해도_SOOP_호출은_1회다() throws Exception {
        User u = newUser();
        linked(u, Duration.ofHours(1));
        SOOP.tokenDelays(Duration.ofMillis(300));
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<RefreshResult>> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return refresher.refreshIfExpiringWithin(u.getId(), AHEAD);
            }));
        }
        go.countDown();
        List<RefreshOutcome> outcomes = new ArrayList<>();
        for (var f : results) {
            outcomes.add(f.get(30, TimeUnit.SECONDS).outcome());
        }
        pool.shutdown();
        assertThat(SOOP.tokenCalls()).isEqualTo(1);
        assertThat(outcomes).containsOnlyOnce(RefreshOutcome.REFRESHED);
        assertThat(outcomes.stream().filter(o -> o == RefreshOutcome.SKIPPED_FRESH).count()).isEqualTo(9);
    }
}
