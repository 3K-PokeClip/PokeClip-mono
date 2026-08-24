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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * resolve가 거절 <b>사유</b>를 무엇으로 고르는가. 워커는 이 사유로 「업로드를 포기할지, 재시도할지,
 * 사용자에게 재연동을 안내할지」를 가른다 — 사유가 사실과 다르면 조치가 틀린다.
 *
 * <p>🔴 여기서 재는 것은 <b>경합의 결과 상태</b>다. 갱신기가 회원 행 락 안에서 「살아있는 연동이 없다」고
 * 판정한 <b>뒤</b>, 사용자가 동의를 마쳐 새 행이 ACTIVE로 커밋될 수 있다. 그때 서비스가 <b>락 밖에서</b>
 * 마지막 행을 다시 읽으면 그 ACTIVE 행을 집고, 「BROKEN이 아니면 UNLINKED」 판정식에 걸려
 * <b>UNLINKED</b>가 나간다 — 방금 연동을 마친 사용자에게 「해제했다」고 답하는 것이다.
 *
 * <p>시간 창을 벌려 경합을 만들지 않는다. 그 창은 밀리초 단위라 재현이 불안정하고, <b>결과 상태는
 * 결정론적으로 만들 수 있다</b> — 갱신기가 {@code NOT_LINKED}를 돌려주는데 DB에는 ACTIVE 행이 있는 상태가
 * 정확히 그것이다. 갱신기만 mock으로 두고 나머지(리포지토리·DB)는 실물이다.
 */
class YoutubeResolveReasonTest extends YoutubeLinkTestSupport {

    private final YoutubeProperties properties;
    private final YoutubeOAuthClient oauthClient;

    YoutubeResolveReasonTest(MockMvc mockMvc, UserService userService, UserRepository userRepository,
                             TokenService tokenService, YoutubeLinkStateCodec codec,
                             YoutubeChannelLinkRepository linkRepository, SecretStore secretStore,
                             YoutubeLinkWriter writer, JdbcTemplate jdbc, YoutubeCleanupExecutor cleanup,
                             YoutubeProperties properties, YoutubeOAuthClient oauthClient) {
        super(mockMvc, userService, userRepository, tokenService, codec, linkRepository, secretStore, writer,
                jdbc, cleanup);
        this.properties = properties;
        this.oauthClient = oauthClient;
    }

    /** 갱신기가 준 결과만으로 사유를 고르게 조립한다 — 그 판정이 락 안에서 났는지가 이 검사의 요점이다. */
    private YoutubeLinkService serviceWith(RefreshResult stubbed) {
        YoutubeTokenRefresher refresher = mock(YoutubeTokenRefresher.class);
        when(refresher.refreshIfExpiringWithin(any(), any())).thenReturn(stubbed);
        return new YoutubeLinkService(properties, codec, oauthClient, writer, refresher, linkRepository);
    }

    /**
     * 🔴 락 안에서 「연동 없음」이 나온 뒤 동의가 끝난 경우. 갱신기가 본 것이 답이다 —
     * 서비스가 뒤늦게 DB를 다시 보면 방금 커밋된 ACTIVE 행을 집어 UNLINKED로 오분류한다.
     */
    @Test
    void 판정_뒤에_동의가_끝나도_UNLINKED로_오분류하지_않는다() {
        User u = newUser();
        linked(u, "at-new", "rt-new");   // 갱신기 판정 뒤에 커밋된 새 연동(ACTIVE)

        YoutubeResolveResult r = serviceWith(RefreshResult.of(RefreshOutcome.NOT_LINKED)).resolve(u.getId());

        assertThat(r.valid()).isFalse();
        assertThat(r.reason())
                .as("방금 연동을 마친 회원에게 「해제했다」고 답했다 — 락 밖에서 다시 읽은 탓이다")
                .isEqualTo("NOT_LINKED");
    }

    /** 사용자가 해제한 경우 — 갱신기가 락 안에서 마지막 행이 UNLINKED임을 보고 실어 보낸다. */
    @Test
    void 해제한_회원은_UNLINKED다() {
        User u = newUser();

        YoutubeResolveResult r = serviceWith(RefreshResult.of(RefreshOutcome.NOT_LINKED, LinkStatus.UNLINKED))
                .resolve(u.getId());

        assertThat(r.reason()).isEqualTo("UNLINKED");
    }

    /** 갱신이 거부돼 끊긴 경우 — 재동의가 복구 수단이라 워커의 안내가 다르다. */
    @Test
    void 갱신_거부로_끊긴_회원은_BROKEN이다() {
        User u = newUser();

        YoutubeResolveResult r = serviceWith(RefreshResult.of(RefreshOutcome.NOT_LINKED, LinkStatus.BROKEN))
                .resolve(u.getId());

        assertThat(r.reason()).isEqualTo("BROKEN");
    }

    /** 연동한 적이 없는 회원 — 마지막 행 자체가 없다. */
    @Test
    void 연동한_적_없는_회원은_NOT_LINKED다() {
        User u = newUser();

        YoutubeResolveResult r = serviceWith(RefreshResult.of(RefreshOutcome.NOT_LINKED, null)).resolve(u.getId());

        assertThat(r.reason()).isEqualTo("NOT_LINKED");
    }

    /** 사유를 고르느라 DB를 다시 보지 않는다 — 갱신기가 락 안에서 본 것만 쓴다. */
    @Test
    void 사유를_고를_때_토큰이나_채널이_새지_않는다() {
        User u = newUser();
        linked(u, "at-new", "rt-new");

        YoutubeResolveResult r = serviceWith(RefreshResult.of(RefreshOutcome.NOT_LINKED, LinkStatus.UNLINKED))
                .resolve(u.getId());

        assertThat(r.accessToken()).isNull();
        assertThat(r.channelId()).isNull();
        assertThat(r.expiresAt()).isNull();
    }

    /** 수명이 충분한 갈래는 스냅샷만 쓴다 — 이 파일의 다른 검사들이 사유 쪽만 본다는 것을 못박는 대조군. */
    @Test
    void 살아있는_연동은_스냅샷_그대로_준다() {
        User u = newUser();
        YoutubeChannelLink link = linked(u, "at-live", "rt-live");
        RefreshResult fresh = RefreshResult.of(RefreshOutcome.SKIPPED_FRESH, link, "at-live");

        YoutubeResolveResult r = serviceWith(fresh).resolve(u.getId());

        assertThat(r.valid()).isTrue();
        assertThat(r.accessToken()).isEqualTo("at-live");
        assertThat(r.expiresAt()).isEqualTo(link.getAccessExpiresAt());
        assertThat(r.reason()).isNull();
    }

    /** 수명 요구는 프로퍼티에서 온다 — 하드코딩하면 운영값을 바꿔도 안 따라온다. */
    @Test
    void 최소_잔여_수명은_프로퍼티_값이다() {
        assertThat(properties.resolveMinRemaining()).isEqualTo(Duration.ofMinutes(30));
    }
}
