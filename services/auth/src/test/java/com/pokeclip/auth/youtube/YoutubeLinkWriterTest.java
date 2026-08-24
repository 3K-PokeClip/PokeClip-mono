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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 쓰기부의 계약 — 한 커밋, 커밋 뒤 정리, 그리고 <b>revoke를 언제 부르고 언제 안 부르는가</b>(계획 2절 결정 8).
 *
 * <p>구글 revoke는 그 사용자가 이 프로젝트에 준 동의 전부를 죽인다. 그래서 재연동 정리에서 옛 토큰을
 * revoke하면 <b>방금 저장한 새 토큰이 같이 죽는다</b> — 표는 ACTIVE인데 첫 갱신이 invalid_grant다.
 * 그 사건을 실제로 재는 것이 {@code cascadeOnRevoke} 검사 둘이다.
 */
class YoutubeLinkWriterTest extends YoutubeLinkTestSupport {

    private final YoutubeOAuthClient oauthClient;

    YoutubeLinkWriterTest(MockMvc mockMvc, UserService userService, UserRepository userRepository,
                          TokenService tokenService, YoutubeLinkStateCodec codec,
                          YoutubeChannelLinkRepository linkRepository, SecretStore secretStore,
                          YoutubeLinkWriter writer, JdbcTemplate jdbc, YoutubeCleanupExecutor cleanup,
                          YoutubeOAuthClient oauthClient) {
        super(mockMvc, userService, userRepository, tokenService, codec, linkRepository, secretStore, writer,
                jdbc, cleanup);
        this.oauthClient = oauthClient;
    }

    @Test
    void 표에는_참조만_남고_원문은_secrets에_있다() {
        User u = newUser();

        YoutubeChannelLink link = linked(u, "at-1", "rt-1");

        assertThat(link.getAccessTokenRef()).startsWith("youtube-access:");
        assertThat(link.getRefreshTokenRef()).startsWith("youtube-refresh:");
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM youtube_channel_links WHERE id = " + link.getId());
        assertThat(row.values().stream().map(String::valueOf))
                .as("표에 토큰 원문이 들어갔다").noneMatch(v -> v.contains("at-1") || v.contains("rt-1"));
        assertThat(secretStore.get(link.getAccessTokenRef())).contains("at-1");
        assertThat(secretStore.get(link.getRefreshTokenRef())).contains("rt-1");
    }

    /** 재연동은 옛 행을 닫고 옛 secrets를 지우지만 <b>구글에 revoke를 부르지 않는다</b> — 새 동의가 옛 grant를 대체한다. */
    @Test
    void 재연동은_옛_행을_닫고_secrets를_지우되_revoke를_부르지_않는다() {
        User u = newUser();
        YoutubeChannelLink old = linked(u, "at-old", "rt-old");

        YoutubeChannelLink fresh = linked(u, "at-new", "rt-new");
        awaitCleanup();

        assertThat(linkRepository.findById(old.getId()).orElseThrow().isRevoked()).isTrue();
        assertThat(linkRepository.findByUserIdAndRevokedAtIsNull(u.getId()).orElseThrow().getId())
                .isEqualTo(fresh.getId());
        assertThat(secretStore.get(old.getAccessTokenRef())).as("옛 secrets가 안 지워졌다").isEmpty();
        assertThat(secretStore.get(old.getRefreshTokenRef())).isEmpty();
        assertThat(secretStore.get(fresh.getRefreshTokenRef())).contains("rt-new");
        assertThat(YOUTUBE.revokedTokens()).as("재연동이 옛 토큰을 revoke했다 — 새 토큰까지 죽는다").isEmpty();
    }

    /**
     * 🔴 치명-1을 재는 자리. 가짜 구글을 <b>실물처럼</b>(revoke 한 번이면 그 사용자의 grant 전체가 죽는다) 켜 두고
     * 재연동한 뒤, 새 refresh로 갱신이 되는지 본다. 재연동 정리가 revoke를 부르면 여기서 400 invalid_grant다.
     */
    @Test
    void 캐스케이드를_켠_채_재연동해도_새_토큰이_살아_있다() {
        YOUTUBE.cascadeOnRevoke(true);
        User u = newUser();
        linked(u, "at-old", "rt-old");

        YoutubeChannelLink fresh = linked(u, "at-new", "rt-new");
        awaitCleanup();

        String newRefresh = secretStore.get(fresh.getRefreshTokenRef()).orElseThrow();
        assertThat(oauthClient.refresh(newRefresh).accessToken())
                .as("재연동 뒤 새 refresh가 죽어 있다 — 옛 토큰 revoke가 새 grant까지 끊었다").isNotNull();
    }

    /**
     * 위 검사가 <b>실제로 무언가를 잰다</b>는 대조군 — 같은 캐스케이드 모드에서 <b>사용자 해제</b>는
     * revoke를 부르므로 그 뒤의 갱신이 거부된다. 둘이 갈리지 않으면 위 검사는 아무것도 안 재는 것이다.
     */
    @Test
    void 사용자_해제는_같은_모드에서_토큰을_실제로_죽인다() {
        YOUTUBE.cascadeOnRevoke(true);
        User u = newUser();
        YoutubeChannelLink link = linked(u, "at-1", "rt-1");
        String refresh = secretStore.get(link.getRefreshTokenRef()).orElseThrow();

        writer.revoke(u.getId(), Instant.now());
        awaitCleanup();

        assertThat(YOUTUBE.revokedTokens()).containsExactly(refresh);
        assertThatThrownBy(() -> oauthClient.refresh(refresh)).isInstanceOf(YoutubeRejectedException.class);
    }

    /** 해제는 refresh를 우선으로 <b>한 번만</b> 부른다 — 구글은 한 번이면 grant 전체가 죽는다. */
    @Test
    void 사용자_해제는_행을_남기고_revoke를_한_번_부른다() {
        User u = newUser();
        YoutubeChannelLink link = linked(u, "at-1", "rt-1");

        writer.revoke(u.getId(), Instant.now());
        awaitCleanup();

        YoutubeChannelLink closed = linkRepository.findById(link.getId()).orElseThrow();
        assertThat(closed.isRevoked()).isTrue();
        assertThat(closed.status()).isEqualTo(LinkStatus.UNLINKED);
        assertThat(secretStore.get(link.getAccessTokenRef())).isEmpty();
        assertThat(YOUTUBE.revokedTokens()).containsExactly("rt-1");
        assertThat(YOUTUBE.revokeCalls()).isEqualTo(1);
    }

    @Test
    void 연동이_없으면_해제는_아무것도_안_한다() {
        User u = newUser();

        writer.revoke(u.getId(), Instant.now());
        writer.revoke(u.getId(), Instant.now());
        awaitCleanup();

        assertThat(YOUTUBE.revokeCalls()).isZero();
        assertThat(linkRepository.findFirstByUserIdOrderByCreatedAtDesc(u.getId())).isEmpty();
    }

    @Test
    void 다른_계정에_묶인_채널은_연동할_수_없다() {
        User owner = newUser();
        User other = newUser();
        YoutubeChannelLink mine = linked(owner);

        assertThatThrownBy(() -> writer.create(other.getId(),
                new YoutubeChannel(mine.getChannelId(), "채널"),
                new YoutubeTokens("at-x", "rt-x", Duration.ofHours(1), null)))
                .isInstanceOf(YoutubeLinkException.class)
                .satisfies(e -> assertThat(((YoutubeLinkException) e).failure())
                        .isEqualTo(YoutubeLinkFailure.CHANNEL_ALREADY_LINKED));
    }

    @Test
    void 재선택은_채널만_바꾸고_토큰은_그대로다() {
        User u = newUser();
        YoutubeChannelLink link = linked(u, "at-1", "rt-1");

        YoutubeChannelLink updated = writer.selectChannel(u.getId(), new YoutubeChannel("UC-second", "두번째"));

        assertThat(updated.getId()).isEqualTo(link.getId());
        YoutubeChannelLink reloaded = linkRepository.findById(link.getId()).orElseThrow();
        assertThat(reloaded.getChannelId()).isEqualTo("UC-second");
        assertThat(reloaded.getChannelName()).isEqualTo("두번째");
        assertThat(reloaded.getAccessTokenRef()).isEqualTo(link.getAccessTokenRef());
        assertThat(secretStore.get(reloaded.getRefreshTokenRef())).contains("rt-1");
        assertThat(YOUTUBE.revokeCalls()).as("재선택은 토큰을 건드리지 않는다").isZero();
    }

    @Test
    void 재선택_대상이_남에게_묶여_있으면_거부한다() {
        User owner = newUser();
        User me = newUser();
        YoutubeChannelLink theirs = linked(owner);
        linked(me);

        assertThatThrownBy(() -> writer.selectChannel(me.getId(), new YoutubeChannel(theirs.getChannelId(), "채널")))
                .isInstanceOf(YoutubeLinkException.class)
                .satisfies(e -> assertThat(((YoutubeLinkException) e).failure())
                        .isEqualTo(YoutubeLinkFailure.CHANNEL_ALREADY_LINKED));
    }

    @Test
    void 연동이_없으면_재선택은_NOT_LINKED다() {
        User u = newUser();

        assertThatThrownBy(() -> writer.selectChannel(u.getId(), new YoutubeChannel("UC-x", "채널")))
                .isInstanceOf(YoutubeLinkException.class)
                .satisfies(e -> assertThat(((YoutubeLinkException) e).failure())
                        .isEqualTo(YoutubeLinkFailure.NOT_LINKED));
    }

    /**
     * {@code SecretStore.put}이 REQUIRED라 저장이 롤백되면 secrets도 같이 사라진다 —
     * 고아 secret이 남지 않는다. INSERT를 실패시키려고 컬럼 상한(channel_id VARCHAR(64))을 넘긴다.
     */
    @Test
    void 저장이_롤백되면_secrets도_같이_사라진다() {
        User u = newUser();
        int before = secretCount();
        String tooLong = "UC-" + "x".repeat(70);

        assertThatThrownBy(() -> writer.create(u.getId(), new YoutubeChannel(tooLong, "채널"),
                new YoutubeTokens("at-orphan", "rt-orphan", Duration.ofHours(1), null)))
                .isInstanceOf(RuntimeException.class);

        assertThat(secretCount()).as("롤백됐는데 secrets가 남았다 — 고아다").isEqualTo(before);
        assertThat(linkRepository.findByUserIdAndRevokedAtIsNull(u.getId())).isEmpty();
    }

    /** 같은 회원이 자기 채널을 다시 연동하는 것은 409가 아니다 — 재연동 경로다. */
    @Test
    void 자기_채널을_다시_연동하는_것은_막지_않는다() {
        User u = newUser();
        String channelId = "UC-" + UUID.randomUUID();
        writer.create(u.getId(), new YoutubeChannel(channelId, "채널"),
                new YoutubeTokens("at-1", "rt-1", Duration.ofHours(1), null));

        YoutubeChannelLink again = writer.create(u.getId(), new YoutubeChannel(channelId, "채널"),
                new YoutubeTokens("at-2", "rt-2", Duration.ofHours(1), null));
        awaitCleanup();

        assertThat(again.getChannelId()).isEqualTo(channelId);
        assertThat(secretStore.get(again.getAccessTokenRef())).contains("at-2");
    }
}
