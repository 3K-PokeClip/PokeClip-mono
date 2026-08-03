package com.pokeclip.auth.streamkey;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 교환 시도를 기록하고 최근 건수를 돌려준다. StreamKeyCreator와 같은 이유로
 * 떼어냈다 — 다만 목적이 반대다. 저쪽은 오염된 세션을 피하려고 나갔고,
 * <b>여기는 바깥 트랜잭션이 롤백돼도 기록이 살아남아야 해서</b> 나간다.
 *
 * <p>exchange는 실패할 때마다 RuntimeException을 던져 롤백된다. 기록이 같은
 * 트랜잭션에 있으면 그 행이 함께 사라져 실패 시도가 한 건도 안 쌓이고,
 * 카운트가 한도에 영영 도달하지 않는다. REQUIRES_NEW가 그것을 끊는다.
 *
 * <p>발급 쪽(계정당 분당 3회)에는 이 문제가 없다. 거기는 <b>성공한 발급</b>이
 * 커밋되고 그것을 세기 때문이다.
 */
@Component
@RequiredArgsConstructor
class PairingAttemptRecorder {

    private final PairingExchangeAttemptRepository attemptRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    long recordAndCount(String clientIpHash, Instant now, Instant since) {
        attemptRepository.saveAndFlush(PairingExchangeAttempt.of(clientIpHash, now));
        return attemptRepository.countByClientIpHashAndAttemptedAtAfter(clientIpHash, since);
    }
}
