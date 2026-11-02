package com.pokeclip.auth.youtube;

import com.pokeclip.auth.streamkey.secret.SecretStore;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 틱을 <b>실물 배선으로</b> 돌린다. 단위 검사(mock)로는 못 보는 것 하나를 재려고 컨텍스트를 따로 띄운다:
 * <b>스케줄러가 트랜잭션 최상단인가.</b>
 *
 * <p>{@code tick()}에 {@code @Transactional}이 붙으면 한 회원의 예외가 트랜잭션을 rollback-only로 만든다 —
 * 틱이 그 예외를 catch해도 <b>커밋 시점에 통째로 터지고, 그 앞에서 성공한 회원의 갱신까지 사라진다.</b>
 * mock 단위 검사는 스프링 프록시를 안 타므로 애노테이션을 붙여도 초록이다. 그래서 여기가 필요하다.
 *
 * <p>{@code @TestPropertySource}로 스케줄러를 켠 별도 컨텍스트다(테스트 프로파일 기본은 꺼짐).
 * 틱 주기가 1시간·initialDelay 1시간이라 이 테스트가 부르기 전에는 저절로 돌지 않는다.
 */
@TestPropertySource(properties = "pokeclip.youtube.check.enabled=true")
class YoutubeRevocationCheckSchedulerIntegrationTest extends YoutubeLinkTestSupport {

    private final YoutubeRevocationCheckScheduler scheduler;

    YoutubeRevocationCheckSchedulerIntegrationTest(MockMvc mockMvc, UserService userService,
                                                   UserRepository userRepository, TokenService tokenService,
                                                   YoutubeLinkStateCodec codec,
                                                   YoutubeChannelLinkRepository linkRepository,
                                                   SecretStore secretStore, YoutubeLinkWriter writer,
                                                   JdbcTemplate jdbc, YoutubeCleanupExecutor cleanup,
                                                   YoutubeRevocationCheckScheduler scheduler) {
        super(mockMvc, userService, userRepository, tokenService, codec, linkRepository, secretStore, writer,
                jdbc, cleanup);
        this.scheduler = scheduler;
    }

    /** 켠 프로파일에서는 빈이 실제로 있어야 한다 — 없으면 아래 검사들이 「배선이 없는데 초록」이 된다. */
    @Test
    void 켜면_스케줄러_빈이_있다() {
        assertThat(scheduler).isNotNull();
    }

    /**
     * 선별 축은 <b>마지막 갱신 시각</b>이지 access 만료가 아니다. 그래서 최근 행의 access도 일부러 만료시켜 둔다 —
     * 그러지 않으면 선별이 틀려 후보로 뽑혀도 갱신기가 「수명 충분」으로 넘겨 <b>이 검사가 초록으로 남는다</b>
     * (선별 부호를 뒤집는 주입에서 실제로 그랬다). 현실에서 나올 조합은 아니지만, 여기서 재려는 것은 선별이다.
     */
    @Test
    void 오래_확인_안_한_행만_갱신하고_최근_행은_건드리지_않는다() {
        User stale = newUser();
        YoutubeChannelLink staleLink = linked(stale, "at-stale", "rt-stale");
        agedLink(staleLink, Duration.ofHours(30));
        User fresh = newUser();
        YoutubeChannelLink freshLink = linked(fresh, "at-fresh", "rt-fresh");
        jdbc.update("UPDATE youtube_channel_links SET access_expires_at = now() - interval '1 hour' WHERE id = ?",
                freshLink.getId());

        scheduler.tick();

        assertThat(YOUTUBE.tokenCalls()).as("최근 행까지 갱신했다 — 회원당 하루 1회가 깨진다").isEqualTo(1);
        assertThat(secretStore.get(staleLink.getAccessTokenRef())).contains("at-1");
        assertThat(secretStore.get(freshLink.getAccessTokenRef())).contains("at-fresh");
    }

    /**
     * 🔴 최상단 검사(행동). 앞 회원이 터져도 뒤 회원의 갱신이 <b>실제로 커밋돼야</b> 한다.
     * 스케줄러에 트랜잭션이 붙으면 여기서 갈린다 — 갱신이 롤백되거나 틱 자체가 UnexpectedRollbackException으로 터진다.
     *
     * <p>앞 회원은 「행은 있는데 refresh secret이 없다」로 터뜨린다(저장소가 어긋난 상태 = 500 갈래).
     * 후보 정렬이 {@code lastRefreshedAt} 오름차순이라 더 오래된 쪽이 먼저 처리된다.
     */
    @Test
    void 한_회원이_터져도_다음_회원의_갱신이_커밋된다() {
        User broken = newUser();
        YoutubeChannelLink brokenLink = linked(broken, "at-broken", "rt-broken");
        agedLink(brokenLink, Duration.ofHours(40));
        jdbc.update("DELETE FROM secrets WHERE ref = ?", brokenLink.getRefreshTokenRef());
        User ok = newUser();
        YoutubeChannelLink okLink = linked(ok, "at-ok", "rt-ok");
        agedLink(okLink, Duration.ofHours(30));

        scheduler.tick();

        assertThat(secretStore.get(okLink.getAccessTokenRef()))
                .as("앞 회원의 예외가 뒤 회원의 갱신을 되돌렸다 — 틱이 트랜잭션 최상단이 아니다").contains("at-1");
        assertThat(linkRepository.findById(okLink.getId()).orElseThrow().getLastRefreshedAt())
                .isAfter(linkRepository.findById(brokenLink.getId()).orElseThrow().getLastRefreshedAt());
    }

    /** 사용자가 구글 쪽에서 권한을 끊은 경우 — 점검이 그것을 미리 드러낸다(방송 직전이 아니라). */
    @Test
    void 구글이_철회한_연동은_점검에서_BROKEN이_된다() {
        User u = newUser();
        YoutubeChannelLink link = linked(u, "at-old", "rt-old");
        agedLink(link, Duration.ofHours(30));
        YOUTUBE.tokenResponds(400, "{\"error\":\"invalid_grant\"}");

        scheduler.tick();
        awaitCleanup();

        assertThat(linkRepository.findById(link.getId()).orElseThrow().status()).isEqualTo(LinkStatus.BROKEN);
        assertThat(YOUTUBE.revokedTokens()).containsExactly("rt-old");
    }

    /** 「마지막 갱신 시각」과 access 만료를 함께 과거로 민다 — 실제로 24시간 방치된 행의 모양이다. */
    private void agedLink(YoutubeChannelLink link, Duration ago) {
        jdbc.update("UPDATE youtube_channel_links SET last_refreshed_at = now() - (? || ' hours')::interval, "
                        + "access_expires_at = now() - interval '1 hour' WHERE id = ?",
                String.valueOf(ago.toHours()), link.getId());
    }
}
