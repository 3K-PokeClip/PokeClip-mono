package com.pokeclip.auth.youtube;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class YoutubeChannelLinkTest {

    private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");

    private YoutubeChannelLink alive() {
        return YoutubeChannelLink.of(1L, "UC-chan", "이름", null, "youtube-access:a", "youtube-refresh:r",
                NOW.plus(Duration.ofHours(1)), NOW);
    }

    @Test
    void 살아있으면_ACTIVE() {
        assertThat(alive().status()).isEqualTo(LinkStatus.ACTIVE);
    }

    /**
     * 치지직과 달리 EXPIRED가 없다 — 구글 access는 1시간이라 늘 만료되고 갱신으로 항상 해소된다.
     * 만료 시각이 지나도 상태는 ACTIVE다.
     */
    @Test
    void access_만료가_지나도_상태는_ACTIVE다() {
        assertThat(alive().status()).isEqualTo(LinkStatus.ACTIVE);
        assertThat(LinkStatus.values()).containsExactly(LinkStatus.ACTIVE, LinkStatus.BROKEN, LinkStatus.UNLINKED);
    }

    @Test
    void 갱신_거부로_닫히면_BROKEN() {
        YoutubeChannelLink link = alive();
        link.revoke(NOW, RevokeReason.REFRESH_REJECTED);
        assertThat(link.status()).isEqualTo(LinkStatus.BROKEN);
        assertThat(link.isRevoked()).isTrue();
    }

    @Test
    void 사용자가_해제하면_UNLINKED() {
        YoutubeChannelLink link = alive();
        link.revoke(NOW, RevokeReason.USER_UNLINKED);
        assertThat(link.status()).isEqualTo(LinkStatus.UNLINKED);
    }

    @Test
    void refreshed는_만료_scope_갱신시각을_바꾼다() {
        YoutubeChannelLink link = alive();
        Instant later = NOW.plus(Duration.ofMinutes(50));
        link.refreshed(later.plus(Duration.ofHours(1)), "scope-a", later);
        assertThat(link.getAccessExpiresAt()).isEqualTo(later.plus(Duration.ofHours(1)));
        assertThat(link.getScope()).isEqualTo("scope-a");
        assertThat(link.getLastRefreshedAt()).isEqualTo(later);
    }

    /** 구글 갱신 응답에는 scope가 없을 수 있다 — 아는 값을 지우지 않는다. */
    @Test
    void refreshed에_scope가_없으면_기존_값을_지우지_않는다() {
        YoutubeChannelLink link = alive();
        link.refreshed(NOW.plus(Duration.ofHours(1)), "scope-a", NOW);
        link.refreshed(NOW.plus(Duration.ofHours(2)), null, NOW.plus(Duration.ofHours(1)));
        assertThat(link.getScope()).isEqualTo("scope-a");
        assertThat(link.getAccessExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(2)));
    }

    /** 업로드 대상 재선택 — 채널만 바뀌고 토큰 참조·만료·갱신 시각은 그대로다. */
    @Test
    void selectChannel은_채널만_바꾼다() {
        YoutubeChannelLink link = alive();
        link.selectChannel("UC-other", "다른 채널");
        assertThat(link.getChannelId()).isEqualTo("UC-other");
        assertThat(link.getChannelName()).isEqualTo("다른 채널");
        assertThat(link.getAccessTokenRef()).isEqualTo("youtube-access:a");
        assertThat(link.getRefreshTokenRef()).isEqualTo("youtube-refresh:r");
        assertThat(link.getAccessExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
        assertThat(link.getLastRefreshedAt()).isEqualTo(NOW);
        assertThat(link.status()).isEqualTo(LinkStatus.ACTIVE);
    }
}
