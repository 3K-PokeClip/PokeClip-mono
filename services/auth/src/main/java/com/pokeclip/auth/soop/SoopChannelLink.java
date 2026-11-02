package com.pokeclip.auth.soop;

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
 * SOOP 채널 연동 한 건. 토큰 원문은 없고 SecretStore 참조만 있다.
 *
 * <p>{@code toString}을 만들지 않는다: SOOP 아이디·닉네임은 로그에 찍지 않는다(치지직 channelId와 같다).
 *
 * <p>🔴 닫는 메서드가 엔티티에 없다. 닫기는 언제나 {@link SoopChannelLinkRepository#closeAndPurgeAlive} 한 문장이다
 * (닫기와 아이디·닉네임 비우기가 함께 가야 하고, 엔티티로 고치면 재연동 INSERT·탈퇴의 clear 쿼리보다 늦게 flush된다).
 */
@Entity
@Table(name = "soop_channel_links")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SoopChannelLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "soop_user_id", length = 64) // 닫히면 NULL(V116 CHECK가 살아 있는 행만 막는다)
    private String soopUserId;

    @Column(name = "nickname") // 닫히면 NULL
    private String nickname;

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

    public static SoopChannelLink of(Long userId, String soopUserId, String nickname, String scope,
                                     String accessTokenRef, String refreshTokenRef,
                                     Instant accessExpiresAt, Instant now) {
        SoopChannelLink l = new SoopChannelLink();
        l.userId = userId;
        l.soopUserId = soopUserId;
        l.nickname = nickname;
        l.scope = scope;
        l.accessTokenRef = accessTokenRef;
        l.refreshTokenRef = refreshTokenRef;
        l.accessExpiresAt = accessExpiresAt;
        l.lastRefreshedAt = now;
        l.createdAt = now;
        return l;
    }

    /** 갱신 성공. 응답에 scope가 없어도 아는 값을 지우지 않는다(치지직과 같다. SOOP 갱신 응답의 scope 유무는 실계정 확인 전). */
    public void refreshed(Instant accessExpiresAt, String scope, Instant now) {
        this.accessExpiresAt = accessExpiresAt;
        if (scope != null) {
            this.scope = scope;
        }
        this.lastRefreshedAt = now;
    }

    /** 상태는 컬럼이 아니라 파생이다. WITHDRAWN은 UNLINKED로 본다(탈퇴한 회원에게 「다시 연동」을 권할 일이 없다). */
    public LinkStatus status(Instant now) {
        if (revokedAt != null) {
            return revokeReason == RevokeReason.REFRESH_REJECTED ? LinkStatus.BROKEN : LinkStatus.UNLINKED;
        }
        return accessExpiresAt.isAfter(now) ? LinkStatus.ACTIVE : LinkStatus.EXPIRED;
    }
}
