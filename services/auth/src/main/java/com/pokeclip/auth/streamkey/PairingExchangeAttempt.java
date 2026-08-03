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
@Table(name = "pairing_exchange_attempts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PairingExchangeAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_ip_hash", nullable = false, length = 64)
    private String clientIpHash;

    @Column(name = "attempted_at", nullable = false)
    private Instant attemptedAt;

    private PairingExchangeAttempt(String clientIpHash, Instant attemptedAt) {
        this.clientIpHash = clientIpHash;
        this.attemptedAt = attemptedAt;
    }

    static PairingExchangeAttempt of(String clientIpHash, Instant attemptedAt) {
        return new PairingExchangeAttempt(clientIpHash, attemptedAt);
    }
}
