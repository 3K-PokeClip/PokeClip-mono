package com.pokeclip.auth.youtube;

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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 회원 행 락({@code users.findByIdForUpdate})이 <b>실제로 무엇을 막는가</b>. 갱신기 쪽은
 * {@code YoutubeTokenRefresherTest}가 「구글 호출 1회」로 재고, 여기서는 쓰기부의 경합 — 재연동끼리,
 * 그리고 재연동과 해제가 겹치는 자리 — 를 잰다(감사 1라운드 중대-A의 나머지 절반).
 *
 * <p>락이 없으면 무엇이 깨지나: 두 재연동이 같은 순간에 「살아있는 행이 없다」를 보고 <b>둘 다 INSERT</b>해
 * 부분 유니크(uq_youtube_links_alive_user)를 때린다 — 사용자에겐 500이다. 해제와 겹치면 해제가
 * 「닫을 행」을 읽은 뒤 새 행이 들어와 <b>해제했는데 살아있는 연동이 남는다.</b>
 *
 * <p>이 검사가 무언가를 잰다는 증거는 락 한 줄을 지우고 빨간불을 본 것이다(태스크 12 보고서에 결과를 남겼다).
 */
class YoutubeLinkWriterConcurrencyTest extends YoutubeLinkTestSupport {

    private static final int THREADS = 10;

    private final YoutubeOAuthClient oauthClient;

    YoutubeLinkWriterConcurrencyTest(MockMvc mockMvc, UserService userService, UserRepository userRepository,
                                     TokenService tokenService, YoutubeLinkStateCodec codec,
                                     YoutubeChannelLinkRepository linkRepository, SecretStore secretStore,
                                     YoutubeLinkWriter writer, JdbcTemplate jdbc, YoutubeCleanupExecutor cleanup,
                                     YoutubeOAuthClient oauthClient) {
        super(mockMvc, userService, userRepository, tokenService, codec, linkRepository, secretStore, writer,
                jdbc, cleanup);
        this.oauthClient = oauthClient;
    }

    @Test
    void 같은_회원을_10스레드가_동시에_재연동해도_살아있는_행은_하나다() throws Exception {
        User u = newUser();
        YoutubeChannel same = new YoutubeChannel("UC-race", "채널");

        List<Object> outcomes = runTogether(java.util.stream.IntStream.range(0, THREADS)
                .<Callable<Object>>mapToObj(i -> () -> writer.create(u.getId(), same,
                        new YoutubeTokens("at-" + i, "rt-" + i, Duration.ofHours(1), null)))
                .toList());
        awaitCleanup();

        assertThat(outcomes).as("락이 없으면 둘 이상이 같이 INSERT해 유니크 위반(500)이 난다")
                .allSatisfy(o -> assertThat(o).isInstanceOf(YoutubeChannelLink.class));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM youtube_channel_links WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class, u.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM youtube_channel_links WHERE user_id = ?",
                Integer.class, u.getId())).isEqualTo(THREADS);
        // 살아있는 행의 토큰만 남는다 — 닫힌 아홉의 secrets는 커밋 뒤 정리가 지웠다.
        YoutubeChannelLink alive = linkRepository.findByUserIdAndRevokedAtIsNull(u.getId()).orElseThrow();
        assertThat(secretStore.get(alive.getAccessTokenRef())).isPresent();
        assertThat(secretCount()).as("고아 secret이 남았다").isEqualTo(2);
    }

    /**
     * 재연동과 해제가 겹치는 자리. 어느 쪽이 마지막이든 <b>종료 상태는 일관</b>해야 한다 —
     * 살아있는 행은 0개나 1개이고, 있으면 그 secrets가 실재하고, 없으면 secrets가 0이다.
     * 락이 없으면 「해제했는데 살아있는 행이 남고 그 토큰은 구글에서 죽은」 상태가 만들어진다.
     */
    @Test
    void 재연동과_해제가_동시에_들어와도_종료_상태가_일관된다() throws Exception {
        User u = newUser();
        YoutubeChannel same = new YoutubeChannel("UC-mixed", "채널");
        List<Callable<Object>> jobs = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            int n = i;
            jobs.add(n % 2 == 0
                    ? () -> writer.create(u.getId(), same,
                            new YoutubeTokens("at-" + n, "rt-" + n, Duration.ofHours(1), null))
                    : () -> {
                        writer.revoke(u.getId(), Instant.now());
                        return "revoked";
                    });
        }

        List<Object> outcomes = runTogether(jobs);
        awaitCleanup();

        assertThat(outcomes).allSatisfy(o -> assertThat(o).isNotInstanceOf(Throwable.class));
        List<YoutubeChannelLink> alive = linkRepository.findAll().stream()
                .filter(l -> l.getUserId().equals(u.getId()) && !l.isRevoked()).toList();
        assertThat(alive).as("살아있는 연동이 둘 이상 남았다").hasSizeLessThanOrEqualTo(1);
        if (alive.isEmpty()) {
            assertThat(secretCount()).as("해제로 끝났는데 secrets가 남았다").isZero();
        } else {
            assertThat(secretStore.get(alive.get(0).getAccessTokenRef()))
                    .as("살아있는 연동인데 토큰이 없다 — 다음 resolve가 500이 된다").isPresent();
            assertThat(secretCount()).isEqualTo(2);
        }
    }

    /**
     * 🔴 정리 잡이 <b>큐에서 밀리는 동안</b> 사용자가 재연동을 끝내는 경로(감사 3라운드 중대-1).
     *
     * <p><b>지금은 해제 정리가 구글을 아예 안 부르므로 이 사건이 원리적으로 안 난다</b>(2026-08-24 결정).
     * 그래도 남겨 둔다 — <b>누가 해제 정리에 revoke를 되살리면 즉시 빨간불</b>이 되는 회귀 그물이다.
     * 캐스케이드 모드를 켜 두었으므로 revoke가 한 번이라도 나가면 새 refresh가 죽어 아래 단언이 깨진다.
     *
     * <p>해제(DELETE)의 정리 잡은 옛 토큰의 revoke를 품고 전용 스레드로 간다. 그 잡이 늦게 발사되면
     * 구글 revoke가 <b>그 사용자의 동의 전부</b>를 죽이므로 <b>방금 만든 새 grant까지 끊는다</b> —
     * 표는 ACTIVE인데 다음 갱신이 invalid_grant다. 사용자 눈에는 「다시 연동했는데 또 끊겼다」로 보인다.
     *
     * <p>창이 좁아 보이지만 <b>철회 점검 스케줄러가 그것을 넓힌다</b> — 하루 한 번 거부된 회원 수만큼
     * 정리 잡이 한꺼번에 큐에 들어가고, 잡마다 구글 revoke(최대 5초)를 품는데 스레드는 둘뿐이다.
     *
     * <p>정리 스레드 둘을 래치로 점유해 그 지연을 결정론적으로 만든다.
     */
    @Test
    void 해제_정리가_밀린_사이_재연동하면_새_토큰이_살아_있어야_한다() throws Exception {
        YOUTUBE.cascadeOnRevoke(true);   // 실물처럼 — revoke 한 번이면 그 사용자의 grant 전체가 죽는다
        User u = newUser();
        linked(u, "at-old", "rt-old");
        CountDownLatch hold = occupyCleanupThreads();

        writer.revoke(u.getId(), Instant.now());              // 정리 잡이 큐에서 대기한다
        assertThat(YOUTUBE.revokeCalls()).as("잡이 아직 안 돌았는지").isZero();
        YoutubeChannelLink fresh = writer.create(u.getId(), new YoutubeChannel("UC-back", "채널"),
                new YoutubeTokens("at-new", "rt-new", Duration.ofHours(1), null));   // 사용자가 곧바로 재연동
        hold.countDown();                                     // 이제 밀린 정리 잡이 발사된다
        awaitCleanup();

        assertThat(YOUTUBE.revokeCalls()).as("해제 정리가 구글을 불렀다 — 되살아났다").isZero();
        String newRefresh = secretStore.get(fresh.getRefreshTokenRef()).orElseThrow();
        assertThat(oauthClient.refresh(newRefresh).accessToken())
                .as("밀린 해제 정리의 revoke가 방금 만든 새 grant까지 죽였다").isNotNull();
        assertThat(linkRepository.findByUserIdAndRevokedAtIsNull(u.getId())).isPresent();
    }

    /** 정리 스레드 둘을 붙잡아 그 뒤의 잡이 큐에서 기다리게 한다. 반환한 래치를 내리면 풀린다. */
    private CountDownLatch occupyCleanupThreads() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch occupied = new CountDownLatch(YoutubeCleanupExecutor.THREADS);
        for (int i = 0; i < YoutubeCleanupExecutor.THREADS; i++) {
            cleanup.submit(cleanup.new Job(0L, null, () -> {
                occupied.countDown();
                try {
                    hold.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        assertThat(occupied.await(10, TimeUnit.SECONDS)).as("정리 스레드를 붙잡지 못했다").isTrue();
        return hold;
    }

    /** 열 스레드를 같은 순간에 풀어놓고 결과(정상 반환값 또는 잡은 예외)를 그대로 모은다. */
    private List<Object> runTogether(List<Callable<Object>> jobs) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(jobs.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> futures = jobs.stream().map(job -> pool.submit(() -> {
            go.await();
            try {
                return job.call();
            } catch (Exception e) {
                return e;   // 예외도 결과로 모은다 — 단언에서 「무엇이 터졌는지」가 보이게
            }
        })).toList();
        go.countDown();
        List<Object> results = new ArrayList<>();
        for (Future<Object> f : futures) {
            results.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return results;
    }
}
