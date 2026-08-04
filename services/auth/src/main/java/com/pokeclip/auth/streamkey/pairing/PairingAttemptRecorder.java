package com.pokeclip.auth.streamkey.pairing;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final JdbcTemplate jdbcTemplate;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    long recordAndCount(String clientIpHash, Instant now, Instant since) {
        lockByIp(clientIpHash);
        attemptRepository.saveAndFlush(PairingExchangeAttempt.of(clientIpHash, now));
        return attemptRepository.countByClientIpHashAndAttemptedAtAfter(clientIpHash, since);
    }

    /**
     * 같은 IP의 기록+집계를 직렬화한다. <b>이것이 없으면 rate limit이 동시 요청에
     * 무력하다</b> — 위가 REQUIRES_NEW라 각 트랜잭션이 자기 INSERT만 보고 세고,
     * 서로의 미커밋 행이 안 보여 동시에 온 10건이 전부 {@code count=1}을 읽는다.
     * 공격자는 순차가 아니라 <b>동시성을 직접 고르므로</b> 이 구멍이 곧 한도 무효화다.
     * ADR-019가 8자(40bit)를 허용한 근거가 "10분 만료 + 교환 rate limit"이라
     * 여기가 뚫리면 코드 길이 결정까지 같이 무너진다.
     *
     * <p>어드바이저리 락을 쓰는 이유: 잠글 행이 아직 없고(INSERT 전이다),
     * 트랜잭션이 끝나면 자동으로 풀리며, <b>다른 IP끼리는 안 막히고</b>
     * 새 표·마이그레이션이 필요 없다. 대안이던 IP별 카운터 표는 원자적이지만
     * 롤링 윈도우가 깨져 분 단위 버킷을 새로 설계해야 한다.
     *
     * <p>반환형이 SQL {@code void}라 JPA로 매핑하기 껄끄러워 JdbcTemplate을 쓴다.
     * {@code xact} 접미사가 트랜잭션 종료 시 자동 해제를 뜻한다 — 수동 해제가 없으므로
     * 예외로 빠져나가도 락이 남지 않는다.
     */
    private void lockByIp(String clientIpHash) {
        jdbcTemplate.query("SELECT pg_advisory_xact_lock(hashtext(?))",
                rs -> null, clientIpHash);
    }
}
