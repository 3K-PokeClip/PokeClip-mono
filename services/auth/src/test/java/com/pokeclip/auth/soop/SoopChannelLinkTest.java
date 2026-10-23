package com.pokeclip.auth.soop;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 살아 있는 행의 상태 파생과 갱신만 잰다. 닫힌 행(BROKEN·UNLINKED)은 엔티티로 닫지 않고 저장소 일괄 UPDATE로만
 * 닫으므로 {@link SoopChannelLinkSchemaTest}가 DB에서 다시 읽어 잰다.
 */
class SoopChannelLinkTest {

    private static final Instant NOW = Instant.parse("2026-10-23T00:00:00Z");

    private SoopChannelLink alive() {
        return SoopChannelLink.of(1L, "soop-user", "닉네임", null, "soop:a", "soop:r",
                NOW.plus(Duration.ofHours(8)), NOW);
    }

    @Test
    void 살아있고_access가_유효하면_ACTIVE() {
        assertThat(alive().status(NOW)).isEqualTo(LinkStatus.ACTIVE);
    }

    @Test
    void 살아있는데_access가_지났으면_EXPIRED() {
        assertThat(alive().status(NOW.plus(Duration.ofHours(9)))).isEqualTo(LinkStatus.EXPIRED);
    }

    /** 만료 시각 그 순간은 이미 지난 것으로 본다(치지직과 같다). */
    @Test
    void 만료_시각_그_순간은_EXPIRED() {
        assertThat(alive().status(NOW.plus(Duration.ofHours(8)))).isEqualTo(LinkStatus.EXPIRED);
    }

    @Test
    void 생성하면_연동_시각이_마지막_확인_시각이다() {
        SoopChannelLink link = alive();
        assertThat(link.getLastRefreshedAt()).isEqualTo(NOW);
        assertThat(link.getCreatedAt()).isEqualTo(NOW);
        assertThat(link.getRevokedAt()).isNull();
        assertThat(link.getRevokeReason()).isNull();
    }

    @Test
    void refreshed는_만료_scope_갱신시각을_바꾼다() {
        SoopChannelLink link = alive();
        Instant later = NOW.plus(Duration.ofHours(7));
        link.refreshed(later.plus(Duration.ofHours(8)), "chat", later);
        assertThat(link.getAccessExpiresAt()).isEqualTo(later.plus(Duration.ofHours(8)));
        assertThat(link.getScope()).isEqualTo("chat");
        assertThat(link.getLastRefreshedAt()).isEqualTo(later);
    }

    @Test
    void refreshed에_scope가_없으면_기존_값을_지우지_않는다() {
        SoopChannelLink link = alive();
        link.refreshed(NOW.plus(Duration.ofHours(8)), "chat", NOW);
        link.refreshed(NOW.plus(Duration.ofHours(16)), null, NOW.plus(Duration.ofHours(1)));
        assertThat(link.getScope()).isEqualTo("chat");
        assertThat(link.getAccessExpiresAt()).isEqualTo(NOW.plus(Duration.ofHours(16)));
    }
}
