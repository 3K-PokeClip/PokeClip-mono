package com.pokeclip.auth.chzzk;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ChzzkChannelLinkTest {

    private static final Instant NOW = Instant.parse("2026-08-17T00:00:00Z");

    private ChzzkChannelLink alive() {
        return ChzzkChannelLink.of(1L, "chan", "이름", null, "chzzk:a", "chzzk:r",
                NOW.plus(Duration.ofHours(24)), NOW);
    }

    @Test
    void 살아있고_access가_유효하면_ACTIVE() {
        assertThat(alive().status(NOW)).isEqualTo(LinkStatus.ACTIVE);
    }

    @Test
    void 살아있는데_access가_지났으면_EXPIRED() {
        assertThat(alive().status(NOW.plus(Duration.ofHours(25)))).isEqualTo(LinkStatus.EXPIRED);
    }

    @Test
    void 갱신_거부로_닫히면_BROKEN() {
        ChzzkChannelLink link = alive();
        link.revoke(NOW, RevokeReason.REFRESH_REJECTED);
        assertThat(link.status(NOW)).isEqualTo(LinkStatus.BROKEN);
        assertThat(link.isRevoked()).isTrue();
    }

    @Test
    void 사용자가_해제하면_UNLINKED() {
        ChzzkChannelLink link = alive();
        link.revoke(NOW, RevokeReason.USER_UNLINKED);
        assertThat(link.status(NOW)).isEqualTo(LinkStatus.UNLINKED);
    }

    @Test
    void refreshed는_만료_scope_갱신시각을_바꾼다() {
        ChzzkChannelLink link = alive();
        Instant later = NOW.plus(Duration.ofHours(20));
        link.refreshed(later.plus(Duration.ofHours(24)), "chat", later);
        assertThat(link.getAccessExpiresAt()).isEqualTo(later.plus(Duration.ofHours(24)));
        assertThat(link.getScope()).isEqualTo("chat");
        assertThat(link.getLastRefreshedAt()).isEqualTo(later);
    }

    /** scope는 발급·갱신 응답 둘 다에 실려 오지만(실측) 없는 응답이 와도 이미 아는 값을 지우지 않는다. */
    @Test
    void refreshed에_scope가_없으면_기존_값을_지우지_않는다() {
        ChzzkChannelLink link = alive();
        link.refreshed(NOW.plus(Duration.ofHours(24)), "chat", NOW);
        link.refreshed(NOW.plus(Duration.ofHours(48)), null, NOW.plus(Duration.ofHours(1)));
        assertThat(link.getScope()).isEqualTo("chat");
        assertThat(link.getAccessExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(48)));
    }
}
