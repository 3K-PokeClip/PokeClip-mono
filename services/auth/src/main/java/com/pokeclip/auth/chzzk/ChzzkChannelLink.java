package com.pokeclip.auth.chzzk;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 치지직 채널 연동 한 건. 토큰 원문은 없고 SecretStore 참조만 있다.
 *
 * <p>{@code toString}을 만들지 않는다 — 토큰 원문은 없지만 channelId가 있고,
 * channelId는 로그에 찍지 않는다(auth/CLAUDE.md).
 */
@Entity
@Table(name = "chzzk_channel_links")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChzzkChannelLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "channel_id", nullable = false, length = 64)
    private String channelId;

    @Column(name = "channel_name", nullable = false)
    private String channelName;

    @Column(name = "scope")
    private String scope;

    @Column(name = "access_token_ref", nullable = false)
    private String accessTokenRef;

    @Column(name = "refresh_token_ref", nullable = false)
    private String refreshTokenRef;

    @Column(name = "access_expires_at", nullable = false)
    private Instant accessExpiresAt;

    @Column(name = "last_refreshed_at", nullable = false)
    private Instant lastRefreshedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revoke_reason", length = 32)
    private RevokeReason revokeReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static ChzzkChannelLink of(Long userId, String channelId, String channelName, String scope,
                                      String accessTokenRef, String refreshTokenRef,
                                      Instant accessExpiresAt, Instant now) {
        ChzzkChannelLink l = new ChzzkChannelLink();
        l.userId = userId;
        l.channelId = channelId;
        l.channelName = channelName;
        l.scope = scope;
        l.accessTokenRef = accessTokenRef;
        l.refreshTokenRef = refreshTokenRef;
        l.accessExpiresAt = accessExpiresAt;
        l.lastRefreshedAt = now;
        l.createdAt = now;
        return l;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public void revoke(Instant now, RevokeReason reason) {
        this.revokedAt = now;
        this.revokeReason = reason;
    }

    /** 갱신 성공. scope는 발급·갱신 응답 둘 다에 실려 온다(실측 2026-08-17) — 없는 응답이 와도 아는 값을 지우지 않는다. */
    public void refreshed(Instant accessExpiresAt, String scope, Instant now) {
        this.accessExpiresAt = accessExpiresAt;
        if (scope != null) {
            this.scope = scope;
        }
        this.lastRefreshedAt = now;
    }

    /** 상태는 컬럼이 아니라 파생이다 — 상태 컬럼과 시각 컬럼이 어긋날 자리를 없앤다. */
    public LinkStatus status(Instant now) {
        if (revokedAt != null) {
            return revokeReason == RevokeReason.REFRESH_REJECTED ? LinkStatus.BROKEN : LinkStatus.UNLINKED;
        }
        return accessExpiresAt.isAfter(now) ? LinkStatus.ACTIVE : LinkStatus.EXPIRED;
    }
}
