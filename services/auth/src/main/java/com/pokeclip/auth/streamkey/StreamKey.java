package com.pokeclip.auth.streamkey;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "stream_keys")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StreamKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "streamid_hash", nullable = false, length = 64)
    private String streamidHash;

    @Column(name = "passphrase_ref", nullable = false)
    private String passphraseRef;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    private StreamKey(Long userId, String streamidHash, String passphraseRef, Instant createdAt) {
        this.userId = userId;
        this.streamidHash = streamidHash;
        this.passphraseRef = passphraseRef;
        this.createdAt = createdAt;
    }

    static StreamKey of(Long userId, String streamidHash, String passphraseRef, Instant createdAt) {
        return new StreamKey(userId, streamidHash, passphraseRef, createdAt);
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }
}
