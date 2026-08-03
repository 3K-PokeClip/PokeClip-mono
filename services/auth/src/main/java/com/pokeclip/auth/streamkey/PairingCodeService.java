package com.pokeclip.auth.streamkey;

import io.micrometer.core.instrument.MeterRegistry;
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
    private static final int EXCHANGE_PER_MINUTE = 5;

    private final PairingCodeRepository pairingCodeRepository;
    private final StreamKeyService streamKeyService;
    private final PairingAttemptRecorder attemptRecorder;
    private final MeterRegistry meterRegistry;
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

    /**
     * 코드를 자격증명으로 바꾼다. 이 경로는 로그인 없이 열려 있다 —
     * 플러그인은 OAuth를 구현하지 않는다(ADR-019).
     */
    @Transactional
    public StreamKeyMaterial exchange(String rawCode, String clientIp) {
        Instant now = Instant.now();
        recordAttemptAndCheckRate(clientIp, now);

        String codeHash;
        try {
            codeHash = Sha256.hex(CrockfordBase32.normalize(rawCode));
        } catch (IllegalArgumentException e) {
            // Crockford 밖 문자다. 존재 여부를 볼 것도 없이 없는 코드다.
            throw new StreamKeyException(StreamKeyFailure.PAIRING_CODE_NOT_FOUND,
                    "코드 형식이 아니다");
        }

        // 소비와 경합 판정을 한 문장으로 끝낸다. 진 쪽은 0행이다.
        if (pairingCodeRepository.markUsed(codeHash, now) == 0) {
            throw rejectionOf(codeHash, now);
        }

        Long userId = pairingCodeRepository.findByCodeHash(codeHash).orElseThrow().getUserId();
        // POK-72: 키가 이미 있으면 같은 것을 준다. ensureKey가 그것을 보장한다.
        StreamKeyMaterial material = streamKeyService.ensureKey(userId);

        log.info("auth.pairing.code_exchanged userId={}", userId);
        return material;
    }

    /**
     * 시도를 먼저 기록하고 그 다음 센다. 검사를 먼저 하면 429일 때도 따로
     * 기록해야 해서 같은 코드가 두 곳에 생긴다.
     *
     * <p>기록은 <b>반드시 별도 트랜잭션이어야 한다</b>. exchange가
     * &#64;Transactional이고 StreamKeyException이 RuntimeException이라, 실패할
     * 때마다 롤백되면서 방금 넣은 시도 행이 같이 사라진다. 그러면 실패 시도가
     * 한 행도 안 쌓여 <b>카운트가 한도에 영영 도달하지 않는다</b> — rate limit이
     * 통째로 죽는 것이고, 그것을 전제로 한 "사유 구분" 결정까지 같이 무너진다.
     * PairingAttemptRecorder가 그래서 있다.
     *
     * <p>거부당한 시도도 행을 만든다. 그래야 rate limit이 작동하는데,
     * <b>청소 작업이 없어 이것이 디스크가 차는 경로다</b>(알려진 구멍 1번).
     */
    private void recordAttemptAndCheckRate(String clientIp, Instant now) {
        long recentAttempts = attemptRecorder.recordAndCount(
                Sha256.hex(clientIp), now, now.minus(Duration.ofMinutes(1)));

        if (recentAttempts > EXCHANGE_PER_MINUTE) {
            // 실패는 INFO로 찍히므로 rate limit이 깨졌는지 볼 눈이 사라진다.
            // 값만 남긴다 — 알람은 걸지 않는다.
            meterRegistry.counter("pokeclip.pairing.exchange.rate_limited").increment();
            throw new StreamKeyException(StreamKeyFailure.PAIRING_CODE_RATE_LIMITED,
                    "페어링 코드 교환이 분당 한도를 넘었다");
        }
    }

    /**
     * markUsed가 0행일 때만 부른다. 경합은 이미 끝나 있어 여기서 다시 읽어도 안전하다.
     *
     * <p>사유를 나누면 "그 코드가 존재하나"가 새지만, 위의 IP당 분당 5회가 그
     * 누출을 무의미하게 만든다. ADR-019가 "길이·만료·rate limit은 세트로만
     * 유효하다"고 못박은 지점이다.
     */
    private StreamKeyException rejectionOf(String codeHash, Instant now) {
        return pairingCodeRepository.findByCodeHash(codeHash)
                .map(code -> code.getUsedAt() != null
                        ? new StreamKeyException(StreamKeyFailure.PAIRING_CODE_ALREADY_USED,
                                "이미 쓴 코드다")
                        : new StreamKeyException(StreamKeyFailure.PAIRING_CODE_EXPIRED,
                                "만료된 코드다"))
                .orElseGet(() -> new StreamKeyException(StreamKeyFailure.PAIRING_CODE_NOT_FOUND,
                        "모르는 코드다"));
    }

    /** 사람이 읽는 자리에서만 XXXX-XXXX로 끊는다. 해시는 끊기 전 값으로 만든다. */
    private String format(String code) {
        return code.substring(0, 4) + "-" + code.substring(4);
    }

    /** <b>{}에 통째로 넣지 않는다.</b> SecretLeakTest가 "IssuedCode["를 금지한다. */
    public record IssuedCode(String code, Instant expiresAt) {
    }
}
