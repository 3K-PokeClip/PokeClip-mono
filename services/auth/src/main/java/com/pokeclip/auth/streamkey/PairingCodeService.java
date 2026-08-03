package com.pokeclip.auth.streamkey;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class PairingCodeService {

    private static final Logger log = LoggerFactory.getLogger(PairingCodeService.class);

    /** ADR-019 확정값. 셋은 세트로만 유효하다 — 하나를 완화하면 나머지를 조여야 한다. */
    private static final int CODE_LENGTH = 8;
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final int ISSUE_PER_MINUTE = 3;

    private final PairingCodeRepository pairingCodeRepository;
    private final StreamKeyService streamKeyService;
    private final SecureRandom random = new SecureRandom();

    @Transactional
    public IssuedCode issue(Long userId) {
        Instant now = Instant.now();

        // 검사와 삽입 사이에 동시 요청이 끼면 한도를 살짝 넘길 수 있다. 목적이
        // brute-force 차단이라 ±1은 무의미하고, 여기에 락을 걸면 정상 발급이
        // 직렬화된다. 알고 남긴다.
        if (pairingCodeRepository.countByUserIdAndCreatedAtAfter(
                userId, now.minus(Duration.ofMinutes(1))) >= ISSUE_PER_MINUTE) {
            throw new StreamKeyException(StreamKeyFailure.PAIRING_CODE_RATE_LIMITED,
                    "페어링 코드 발급이 분당 한도를 넘었다");
        }

        // 내려줄 키가 있어야 한다. 발급의 유일한 입구가 ensureKey다.
        streamKeyService.ensureKey(userId);

        String code = CrockfordBase32.random(random, CODE_LENGTH);
        Instant expiresAt = now.plus(TTL);
        pairingCodeRepository.save(PairingCode.of(userId, Sha256.hex(code), expiresAt, now));

        log.info("auth.pairing.code_issued userId={}", userId);
        return new IssuedCode(format(code), expiresAt);
    }

    /** 사람이 읽는 자리에서만 XXXX-XXXX로 끊는다. 해시는 끊기 전 값으로 만든다. */
    private String format(String code) {
        return code.substring(0, 4) + "-" + code.substring(4);
    }

    /** <b>{}에 통째로 넣지 않는다.</b> SecretLeakTest가 "IssuedCode["를 금지한다. */
    public record IssuedCode(String code, Instant expiresAt) {
    }
}
