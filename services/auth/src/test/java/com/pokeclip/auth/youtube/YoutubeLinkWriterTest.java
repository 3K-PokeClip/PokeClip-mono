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
 * <p>구글 revoke는 그 <b>구글 계정</b>이 우리 앱에 준 동의 전부를 죽인다. 그래서 이 클래스의 어느 경로도
 * (재연동이든 <b>사용자 해제든</b>) 구글에 revoke를 보내지 않는다 — 보내면 방금 저장한 새 토큰이나
 * 같은 채널을 연동한 다른 회원의 grant가 함께 죽는다. 근거는 {@code YoutubeLinkWriter.closeAlive} javadoc.
 *
 * <p>{@code cascadeOnRevoke} 검사들이 그것을 실물로 잰다 — 그 모드에서는 revoke가 한 번이라도 나가면
 * 해당 계정의 갱신이 즉시 죽으므로, <b>갱신이 되는 것이 「안 보냈다」의 증거</b>다.
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
     * 🔴 <b>해제도 구글에 revoke를 보내지 않는다</b>(2026-08-24 사용자 결정, 봇 3판 P1).
     * 캐스케이드 모드를 켜 두면 revoke가 나가는 순간 그 계정의 grant가 통째로 죽는데,
     * 여기서 아무것도 안 죽는다는 것이 「안 보냈다」의 실물 증거다.
     *
     * <p>왜 안 보내나 — 구글 revoke는 계정 단위라 같은 채널을 방금 연동한 <b>다른 회원</b>의 grant까지
     * 죽이고, 그 창은 조건으로 못 막는다(가드 확인과 revoke 발사 사이는 락으로 직렬화되지 않는다).
     * 대신 <b>참조를 지워 우리가 못 쓰게</b> 하고, 사용자는 구글 계정 화면에서 직접 지운다.
     */
    @Test
    void 사용자_해제는_행을_닫고_secrets를_지우되_구글을_부르지_않는다() {
        YOUTUBE.cascadeOnRevoke(true);
        User u = newUser();
        YoutubeChannelLink link = linked(u, "at-1", "rt-1");
        String refresh = secretStore.get(link.getRefreshTokenRef()).orElseThrow();

        writer.revoke(u.getId(), Instant.now());
        awaitCleanup();

        YoutubeChannelLink closed = linkRepository.findById(link.getId()).orElseThrow();
        assertThat(closed.isRevoked()).isTrue();
        assertThat(closed.status()).isEqualTo(LinkStatus.UNLINKED);
        assertThat(secretStore.get(link.getAccessTokenRef())).as("참조를 지워 우리가 못 쓰게 한다").isEmpty();
        assertThat(secretStore.get(link.getRefreshTokenRef())).isEmpty();
        assertThat(YOUTUBE.revokeCalls()).as("해제가 구글을 불렀다").isZero();
        assertThat(oauthClient.refresh(refresh).accessToken())
                .as("그 토큰이 구글에서 죽었다 = revoke가 나갔다는 뜻이다").isNotNull();
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
