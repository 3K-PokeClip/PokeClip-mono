package com.pokeclip.auth.youtube;

import com.pokeclip.auth.streamkey.secret.SecretStore;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 토큰을 버리기 전에 무엇을 보는가. 구글 revoke는 <b>그 구글 계정이 이 프로젝트에 준 동의 전부</b>를 죽이므로,
 * 「버려도 되는가」의 판단 범위가 <b>revoke의 영향 범위와 같아야</b> 한다.
 *
 * <p>봇 리뷰(PR #116)가 그 범위 차이를 둘 짚었고 <b>둘 다 재현됐다</b>:
 * <ul>
 *   <li><b>시점</b> — 재연동이 <b>커밋 전</b>이면 조회에 안 보여 가드가 통과된다(진행 중인 새 토큰이 죽는다).</li>
 *   <li><b>대상</b> — 「이 회원의 살아있는 행」만 보면, <b>같은 구글 계정</b>을 쓰는 다른 회원의 멀쩡한 토큰을 죽인다.</li>
 * </ul>
 */
class YoutubeDiscardGuardTest extends YoutubeLinkTestSupport {

    private final YoutubeTokenDiscarder discarder;
    private final YoutubeOAuthClient oauthClient;
    private final PlatformTransactionManager txManager;
    private final YoutubeDiscardGuard guard;

    YoutubeDiscardGuardTest(MockMvc mockMvc, UserService userService, UserRepository userRepository,
                            TokenService tokenService, YoutubeLinkStateCodec codec,
                            YoutubeChannelLinkRepository linkRepository, SecretStore secretStore,
                            YoutubeLinkWriter writer, JdbcTemplate jdbc, YoutubeCleanupExecutor cleanup,
                            YoutubeTokenDiscarder discarder, YoutubeOAuthClient oauthClient,
                            PlatformTransactionManager txManager, YoutubeDiscardGuard guard) {
        super(mockMvc, userService, userRepository, tokenService, codec, linkRepository, secretStore, writer,
                jdbc, cleanup);
        this.discarder = discarder;
        this.oauthClient = oauthClient;
        this.txManager = txManager;
        this.guard = guard;
    }

    /**
     * 🔴 시점. 재연동 트랜잭션이 <b>아직 커밋 전</b>일 때 정리 잡이 가드를 지나면, 진행 중인 새 토큰을 죽인다.
     * 회원 행 락으로 직렬화하면 정리 잡이 커밋을 기다렸다가 살아있는 행을 보게 된다.
     */
    @Test
    void 재연동이_진행_중이면_정리_잡이_기다렸다가_버리지_않는다() throws Exception {
        User u = newUser();
        ExecutorService cleanupThread = Executors.newSingleThreadExecutor();

        Future<?> discardJob = new TransactionTemplate(txManager).execute(status -> {
            writer.create(u.getId(), new YoutubeChannel("UC-inflight", "채널"),
                    new YoutubeTokens("at-new", "rt-new", Duration.ofHours(1), null));
            // 아직 커밋 전이다. 정리 잡이 도는 자리에서 「버려도 되나」를 묻는다 —
            // 락이 없으면 즉시 「살아있는 행 없음」을 보고 revoke가 나간다.
            Future<?> job = cleanupThread.submit(
                    () -> discarder.discardIfNoLiveLink(u.getId(), "UC-inflight", "at-old", "rt-old"));
            sleep(300);   // 락이 있으면 이 사이 내내 대기한다
            return job;
        });
        discardJob.get(30, TimeUnit.SECONDS);
        cleanupThread.shutdown();

        assertThat(YOUTUBE.revokeCalls())
                .as("커밋 전 재연동을 못 보고 버렸다 — 진행 중인 새 토큰이 죽는다").isZero();
    }

    /**
     * 🔴 대상. 409는 「그 채널이 남에게 묶여 있다」는 뜻이고, 채널이 같으면 <b>구글 계정도 같다</b>.
     * 그 계정의 토큰을 버리면 원래 주인의 멀쩡한 연동이 끊긴다 — 우리가 만든 것도, 우리 것도 아닌 토큰이다.
     */
    @Test
    void 채널이_남에게_묶여_있으면_버리지_않는다() throws Exception {
        YOUTUBE.cascadeOnRevoke(true);   // 실물처럼 — 같은 구글 계정의 동의가 통째로 죽는다
        User owner = newUser();
        YoutubeChannelLink theirs = linked(owner, "at-owner", "rt-owner");
        String ownerRefresh = secretStore.get(theirs.getRefreshTokenRef()).orElseThrow();
        YOUTUBE.channelsResponds(200, "{\"items\":[{\"id\":\"" + theirs.getChannelId()
                + "\",\"snippet\":{\"title\":\"채널\"}}]}");
        User other = newUser();

        mockMvc.perform(link(other)).andExpect(status().isConflict());
        awaitCleanup();

        assertThat(YOUTUBE.revokedTokens()).as("남의 채널이라 409인데 그 계정의 토큰을 버렸다").isEmpty();
        assertThat(oauthClient.refresh(ownerRefresh).accessToken())
                .as("원래 주인의 연동이 끊겼다 — revoke는 구글 계정 단위로 먹는다").isNotNull();
    }

    /**
     * 🔴 <b>여기까지가 가드가 막을 수 있는 경계다</b> — 그 바깥을 시험으로 못박는다.
     *
     * <p>재연동이 <b>교환은 끝나고(구글엔 grant가 이미 있다) 저장은 아직 전</b>인 순간에는 우리 표에
     * 아무것도 없어 가드가 못 본다. 락도 소용없다 — {@code writer.create}가 아직 시작되지 않아 잠글 것이 없다.
     * 그래서 <b>해제 정리가 그 순간 발사되면 새 grant가 죽는다</b>(봇 리뷰 2판 ②).
     *
     * <p><b>이 시험은 결함을 기록한다</b>(characterization). 「막힌 곳」({@code 재연동이_진행_중이면…})과
     * 나란히 두어 <b>경계가 어디인지를 코드에서 읽게</b> 하려는 것이다. 완화책 셋과 각각의 대가는
     * {@code auth/CLAUDE.md} 「알려진 구멍」 20번에 있다 — (가)는 커넥션을 쥔 채 외부 호출을 하게 되고
     * (실측: afterCommit 시점 활성 커넥션 1), (다)는 미완료 행 청소가 필요해져 POK-89와 부딪힌다.
     *
     * <p><b>이 시험이 빨간불이 되면 창이 닫혔다는 뜻이다</b> — 그때 알려진 구멍 20번과 이 시험을 함께 지운다.
     */
    @Test
    void 저장_전_재연동은_가드가_못_본다_알려진_한계() {
        User u = newUser();

        // 「교환은 끝났고 저장은 전」 = 우리 표에 그 회원의 행도, 그 채널의 행도 없다.
        boolean blocks = guard.blocksDiscard(u.getId(), "UC-exchanged-not-saved");

        assertThat(blocks)
                .as("가드가 이 상태를 보게 됐다면 창이 닫힌 것이다 — 알려진 구멍 20번을 지워라").isFalse();
    }

    /** 대조군 — 아무도 안 쓰는 채널이면 그대로 버린다. 위 둘이 「아무것도 안 버린다」로 바뀌면 여기서 걸린다. */
    @Test
    void 살아있는_연동도_채널_점유도_없으면_버린다() {
        User u = newUser();

        discarder.discardIfNoLiveLink(u.getId(), "UC-nobody", "at-x", "rt-x");

        assertThat(YOUTUBE.revokedTokens()).containsExactly("rt-x");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
