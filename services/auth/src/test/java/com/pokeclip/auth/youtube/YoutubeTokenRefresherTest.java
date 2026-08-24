package com.pokeclip.auth.youtube;

import com.pokeclip.auth.streamkey.secret.SecretStore;
import com.pokeclip.auth.support.FakeYoutubeServer;
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

/**
 * 갱신기. 치지직과 갈리는 곳이 둘이다.
 *
 * <p>① <b>구글 refresh는 재사용형이다</b> — 갱신 응답에 refresh_token이 <b>없는 것이 정상</b>이고(실측 확인)
 * 그때는 기존 것을 계속 쓴다. 「없으면 null로 덮어쓴다」로 분기가 뒤집히면 <b>연동이 통째로 죽는다</b> —
 * 만료 시각만 보는 단언으로는 안 잡히므로 「다음 갱신이 그 refresh로 성공하는가」를 잰다.
 *
 * <p>② <b>갱신 거부(BROKEN) 정리에서는 revoke를 부른다.</b> 이미 죽은 grant라 부작용이 없고,
 * 구글 쪽에 남은 흔적을 지우는 의미가 있다. 재연동·실패 정리와는 갈래가 다르다(계획 2절 결정 8).
 */
class YoutubeTokenRefresherTest extends YoutubeLinkTestSupport {

    /** 실물의 갱신 응답 — refresh_token이 없다. scope도 안 온다. */
    private static final String REFRESH_WITHOUT_REFRESH_TOKEN =
            "{\"access_token\":\"at-fresh\",\"expires_in\":3600,\"token_type\":\"Bearer\"}";

    private final YoutubeTokenRefresher refresher;

    YoutubeTokenRefresherTest(MockMvc mockMvc, UserService userService, UserRepository userRepository,
                              TokenService tokenService, YoutubeLinkStateCodec codec,
                              YoutubeChannelLinkRepository linkRepository, SecretStore secretStore,
                              YoutubeLinkWriter writer, JdbcTemplate jdbc, YoutubeCleanupExecutor cleanup,
                              YoutubeTokenRefresher refresher) {
        super(mockMvc, userService, userRepository, tokenService, codec, linkRepository, secretStore, writer,
                jdbc, cleanup);
        this.refresher = refresher;
    }

    /** 남은 수명 60분 > 요구 30분 — 구글을 안 부른다. 스냅샷에 access 원문이 실린다(resolve가 다시 안 읽게). */
    @Test
    void 남은_수명이_충분하면_구글을_부르지_않는다() {
        User u = newUser();
        linked(u, "at-old", "rt-old");

        RefreshResult r = refresher.refreshIfExpiringWithin(u.getId(), Duration.ofMinutes(30));

        assertThat(r.outcome()).isEqualTo(RefreshOutcome.SKIPPED_FRESH);
        assertThat(r.snapshot().accessToken()).isEqualTo("at-old");
        assertThat(YOUTUBE.tokenCalls()).isZero();
    }

    @Test
    void 임박하면_갱신하고_secrets_만료_갱신시각이_한꺼번에_바뀐다() {
        User u = newUser();
        YoutubeChannelLink before = linked(u, "at-old", "rt-old");

        RefreshResult r = refresher.refreshIfExpiringWithin(u.getId(), Duration.ofHours(2));

        assertThat(r.outcome()).isEqualTo(RefreshOutcome.REFRESHED);
        YoutubeChannelLink after = linkRepository.findById(before.getId()).orElseThrow();
        assertThat(secretStore.get(after.getAccessTokenRef())).contains("at-1");   // 새 토큰, 같은 ref
        assertThat(after.getAccessExpiresAt()).isAfter(Instant.now().plus(Duration.ofMinutes(50)));
        assertThat(after.getLastRefreshedAt()).isAfter(before.getLastRefreshedAt());
        assertThat(r.snapshot().accessExpiresAt()).isEqualTo(after.getAccessExpiresAt());
        assertThat(r.snapshot().accessToken()).as("스냅샷의 토큰 = 방금 저장한 secrets 값").isEqualTo("at-1");
        assertThat(YOUTUBE.tokenRequests().get(0))
                .containsEntry("grant_type", "refresh_token")
                .containsEntry("refresh_token", "rt-old");
        assertThat(YOUTUBE.revokeCalls()).as("정상 갱신이 revoke를 불렀다").isZero();
    }

    /** 응답에 refresh_token이 오면 교체한다 — 구글이 회전시킬 때가 있다(문서). */
    @Test
    void 응답에_refresh가_있으면_교체한다() {
        User u = newUser();
        YoutubeChannelLink link = linked(u, "at-old", "rt-old");

        refresher.refreshIfExpiringWithin(u.getId(), Duration.ofHours(2));

        assertThat(secretStore.get(link.getRefreshTokenRef())).contains("rt-1");
    }

    /**
     * 🔴 ①의 분기가 뒤집혔는지 재는 자리. 응답에 refresh가 없는 것이 <b>정상</b>이므로 기존 것을 유지해야 하고,
     * 유지되었다는 것은 「그 값이 남아 있다」가 아니라 <b>「다음 갱신이 그것으로 성공한다」</b>로 재야 한다.
     * 만료 시각만 보면 null로 덮어써도 초록일 수 있다.
     */
    @Test
    void 응답에_refresh가_없으면_기존_것으로_다음_갱신이_된다() {
        User u = newUser();
        YoutubeChannelLink link = linked(u, "at-old", "rt-old");
        YOUTUBE.tokenResponds(200, REFRESH_WITHOUT_REFRESH_TOKEN);

        RefreshResult first = refresher.refreshIfExpiringWithin(u.getId(), Duration.ofHours(2));

        assertThat(first.outcome()).isEqualTo(RefreshOutcome.REFRESHED);
        assertThat(secretStore.get(link.getRefreshTokenRef())).contains("rt-old");
        assertThat(secretStore.get(link.getAccessTokenRef())).contains("at-fresh");

        RefreshResult second = refresher.refreshIfExpiringWithin(u.getId(), Duration.ofHours(2));

        assertThat(second.outcome()).as("두 번째 갱신이 죽었다 — 기존 refresh를 잃었다")
                .isEqualTo(RefreshOutcome.REFRESHED);
        assertThat(YOUTUBE.tokenRequests().get(1)).containsEntry("refresh_token", "rt-old");
    }

    /** 갱신 응답에 scope가 없는 것도 정상이다 — 아는 값을 지우지 않는다. */
    @Test
    void 응답에_scope가_없으면_기존_scope를_지우지_않는다() {
        User u = newUser();
        YoutubeChannelLink link = writer.create(u.getId(),
                new YoutubeChannel("UC-scope", "채널"),
                new YoutubeTokens("at-old", "rt-old", Duration.ofHours(1), FakeYoutubeServer.SCOPE_GRANTED));
        YOUTUBE.tokenResponds(200, REFRESH_WITHOUT_REFRESH_TOKEN);

        refresher.refreshIfExpiringWithin(u.getId(), Duration.ofHours(2));

        assertThat(linkRepository.findById(link.getId()).orElseThrow().getScope())
                .isEqualTo(FakeYoutubeServer.SCOPE_GRANTED);
    }

    /**
     * 사용자가 구글 쪽에서 권한을 끊었거나 테스트 모드 7일이 지났다 — 다시 시도해도 같다.
     * 닫고, 커밋 뒤에 secrets를 지우고, <b>revoke도 부른다</b>(이미 죽은 grant라 부작용이 없다).
     */
    @Test
    void 갱신이_invalid_grant면_BROKEN으로_닫고_secrets를_지우고_revoke한다() {
        User u = newUser();
        YoutubeChannelLink link = linked(u, "at-old", "rt-old");
        YOUTUBE.tokenResponds(400, "{\"error\":\"invalid_grant\",\"error_description\":\"Token has been expired or revoked.\"}");

        RefreshResult r = refresher.refreshIfExpiringWithin(u.getId(), Duration.ofHours(2));

        assertThat(r.outcome()).isEqualTo(RefreshOutcome.REJECTED);
        assertThat(r.snapshot()).isNull();
        YoutubeChannelLink after = linkRepository.findById(link.getId()).orElseThrow();
        assertThat(after.status()).isEqualTo(LinkStatus.BROKEN);
        awaitCleanup();   // secrets 삭제·revoke는 커밋 뒤 전용 스레드에서 — "결국" 된다
        assertThat(secretStore.get(link.getAccessTokenRef())).isEmpty();
        assertThat(secretStore.get(link.getRefreshTokenRef())).isEmpty();
        assertThat(YOUTUBE.revokedTokens()).as("죽은 grant의 흔적을 구글에서도 지운다").containsExactly("rt-old");
        assertThat(refresher.refreshIfExpiringWithin(u.getId(), Duration.ofHours(2)).outcome())
                .as("BROKEN인데 다시 시도했다").isEqualTo(RefreshOutcome.NOT_LINKED);
        assertThat(YOUTUBE.tokenCalls()).isEqualTo(1);
    }

    @Test
    void 갱신이_5xx면_행을_그대로_두고_다음에_다시_시도한다() {
        User u = newUser();
        YoutubeChannelLink link = linked(u, "at-old", "rt-old");
        YOUTUBE.tokenResponds(503, "{}");

        RefreshResult r = refresher.refreshIfExpiringWithin(u.getId(), Duration.ofHours(2));

        assertThat(r.outcome()).isEqualTo(RefreshOutcome.UNAVAILABLE);
        assertThat(r.snapshot()).isNull();
        assertThat(linkRepository.findById(link.getId()).orElseThrow().isRevoked()).isFalse();
        assertThat(secretStore.get(link.getRefreshTokenRef())).contains("rt-old");
        assertThat(YOUTUBE.revokeCalls()).as("일시 실패가 토큰을 죽였다").isZero();

        YOUTUBE.tokenResponds(200, REFRESH_WITHOUT_REFRESH_TOKEN);
        assertThat(refresher.refreshIfExpiringWithin(u.getId(), Duration.ofHours(2)).outcome())
                .isEqualTo(RefreshOutcome.REFRESHED);
    }

    /** 앱 자격증명 오류는 우리 설정 문제(시크릿 회전·오타)다 — 회원 전원을 BROKEN으로 만들면 전부 재동의해야 한다. */
    @Test
    void 갱신이_invalid_client면_행을_그대로_두고_다음에_다시_시도한다() {
        User u = newUser();
        YoutubeChannelLink link = linked(u, "at-old", "rt-old");
        YOUTUBE.tokenResponds(401, "{\"error\":\"invalid_client\"}");

        RefreshResult r = refresher.refreshIfExpiringWithin(u.getId(), Duration.ofHours(2));

        assertThat(r.outcome()).isEqualTo(RefreshOutcome.UNAVAILABLE);
        assertThat(linkRepository.findById(link.getId()).orElseThrow().isRevoked()).isFalse();
        assertThat(secretStore.get(link.getRefreshTokenRef())).contains("rt-old");
    }

    /** 403 할당량 소진은 태평양시 자정에 스스로 풀린다 — 끊으면 전 회원이 재동의해야 한다. */
    @Test
    void 갱신이_403_quotaExceeded면_행을_그대로_둔다() {
        User u = newUser();
        YoutubeChannelLink link = linked(u, "at-old", "rt-old");
        YOUTUBE.tokenResponds(403, "{\"error\":{\"code\":403,\"errors\":[{\"reason\":\"quotaExceeded\"}]}}");

        RefreshResult r = refresher.refreshIfExpiringWithin(u.getId(), Duration.ofHours(2));

        assertThat(r.outcome()).isEqualTo(RefreshOutcome.UNAVAILABLE);
        assertThat(linkRepository.findById(link.getId()).orElseThrow().isRevoked()).isFalse();
    }

    @Test
    void 미연동이면_NOT_LINKED() {
        User u = newUser();

        RefreshResult r = refresher.refreshIfExpiringWithin(u.getId(), Duration.ofHours(2));

        assertThat(r.outcome()).isEqualTo(RefreshOutcome.NOT_LINKED);
        assertThat(r.snapshot()).isNull();
        assertThat(YOUTUBE.tokenCalls()).isZero();
    }

    /**
     * 두 진입점(점검 스케줄러·resolve)이 같은 회원에 겹쳐도 구글 호출은 한 번이어야 한다.
     * 구글 refresh는 재사용형이라 이중 소비 자체는 없지만, 겹치면 <b>같은 초에 같은 회원의 access를
     * 두 번 발급</b>받아 하나가 즉시 고아가 되고 할당량도 그만큼 쓴다. 회원 행 락이 그것을 직렬화한다.
     */
    @Test
    void 같은_회원을_10스레드가_동시에_갱신해도_구글_호출은_1회다() throws Exception {
        User u = newUser();
        YoutubeChannelLink link = linked(u, "at-old", "rt-old");
        // 이미 만료된 access로 시작한다 — 요구 수명(30분)을 「갱신 뒤에는 충족, 갱신 전에는 미충족」으로
        // 두어야 첫 스레드만 구글을 부르고 나머지는 SKIPPED_FRESH가 된다. 요구를 새 토큰 수명(1시간)보다
        // 크게 잡으면 갱신 뒤에도 임박이라 열 스레드가 전부 부르는 것이 정상이 돼 아무것도 못 잰다.
        accessRemaining(link, Duration.ofMinutes(-1));
        YOUTUBE.tokenDelays(Duration.ofMillis(300));   // 경합 창을 벌린다
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<RefreshResult>> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return refresher.refreshIfExpiringWithin(u.getId(), Duration.ofMinutes(30));
            }));
        }

        go.countDown();
        List<RefreshOutcome> outcomes = new ArrayList<>();
        for (Future<RefreshResult> f : results) {
            outcomes.add(f.get(30, TimeUnit.SECONDS).outcome());
        }
        pool.shutdown();

        assertThat(YOUTUBE.tokenCalls()).as("락이 없으면 여럿이 동시에 구글을 부른다").isEqualTo(1);
        assertThat(outcomes).containsOnlyOnce(RefreshOutcome.REFRESHED);
        assertThat(outcomes.stream().filter(o -> o == RefreshOutcome.SKIPPED_FRESH).count()).isEqualTo(9);
    }
}
