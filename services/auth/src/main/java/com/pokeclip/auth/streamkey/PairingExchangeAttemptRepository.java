package com.pokeclip.auth.streamkey;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;

public interface PairingExchangeAttemptRepository
        extends JpaRepository<PairingExchangeAttempt, Long> {

    long countByClientIpHashAndAttemptedAtAfter(String clientIpHash, Instant since);
}
