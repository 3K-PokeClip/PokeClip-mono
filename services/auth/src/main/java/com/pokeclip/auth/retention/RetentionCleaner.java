package com.pokeclip.auth.retention;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 보관 기한이 지난 행을 표당 상한만큼 지운다(POK-89). 표마다 메서드 하나, 각각 <b>자기 트랜잭션</b>이다 —
 * 한 표의 실패가 다른 표의 삭제를 되돌리지 않는다. 그래서 스케줄러에는 트랜잭션을 안 붙인다
 * ({@code YoutubeRevocationCheckScheduler}와 같은 이유).
 * 서브쿼리는 {@code FOR UPDATE SKIP LOCKED}다. <b>왜 두는가</b>: 이 모양이 되기 전에는 잠금 순서가 회수 UPDATE
 * ({@code revokeAllOfUser}, {@code idx_refresh_tokens_expires_alive} 순서)와 어긋나, 같은 회원의 보관 기한이 지난
 * 미회수 만료 행이 둘 이상이면 사이클이 났다(리뷰 라운드 1 스크래치 재현 60회 중 30회 — rotate가 희생자면 봉쇄가
 * 롤백돼 500). 같은 시드로 pgbench 4클라이언트 15초: 원판 데드락 14회(30건 중 13 실패) → SKIP LOCKED 0회(298건 처리, 실패 0).
 *
 * <p>지금 실행 계획은 <b>id 순서로 잠근다</b>(리뷰 라운드 2 {@code EXPLAIN} 실측:
 * {@code Delete → Nested Loop → HashAggregate → Subquery Scan → Limit → LockRows → Index Scan pkey}).
 * 잠금은 서브쿼리 안의 {@code LockRows}가 {@code Limit} <b>아래</b>에서 스캔 순서(id)로 잡고, 바깥 {@code HashAggregate}는
 * 이미 잠긴 행을 지우기만 한다 — 예전 주석의 「해시 순서로 잠근다」는 틀린 서술이었다. 그래도 {@code SKIP LOCKED}를
 * 빼지 않는다: 그 순서 보장은 <b>실행 계획에 기대는 것</b>이라 계획이 바뀌는 날 데드락이 되살아난다. 건너뛴 행은
 * 다음 틱이 지운다(청소의 의미와 맞다).
 *
 * <p>같은 실측에서 확인된 것 하나 더 — 서브쿼리는 한 번만 평가되고 {@code LockRows}가 {@code Limit} 아래라
 * <b>잠긴 행을 건너뛰어도 상한을 까먹지 않는다</b>(3행을 잠근 채 실행해도 정확히 1,000행 삭제).
 *
 * <p>PostgreSQL {@code DELETE}에는 {@code LIMIT}이 없어 {@code id IN (SELECT id … LIMIT n)}으로 상한을 건다.
 * JPQL로는 이 모양을 못 만들어 {@code JdbcTemplate}을 쓴다({@code PairingAttemptRecorder}의 advisory lock이 선례).
 * native {@code @Query}로도 되지만 레포지토리 인터페이스에 {@code @Transactional}을 붙인 선례가 이 저장소에 없고
 * (인터페이스 선언 쿼리 메서드는 트랜잭션이 안 붙어 각 메서드에 직접 달아야 한다), 세 표의 삭제 조건을 한 파일에서
 * 보려고 {@code JdbcTemplate}이다.
 *
 * <p>{@code capped}는 「지운 수가 상한과 같다」다. 정확히 상한만큼 남아 있던 경우 거짓 양성이지만
 * 같은 틱의 다음 바퀴가 0건으로 끝내므로 조회 하나가 더 들 뿐이다. 남은 수를 따로 세도 조회가 하나 더 든다.
 *
 * <p><b>시각 파라미터는 반드시 {@link Timestamp#from(Instant)}로 바인딩한다</b> — {@code Instant}를 그대로 넘기면
 * pgjdbc가 SQL 타입을 못 정해 {@code BadSqlGrammarException}이다(chat-collector {@code SegmentLedger}와 같은 문장 —
 * 모노레포의 {@code JdbcTemplate} 시각 바인딩이 전부 이 모양이다).
 */
@Component
public class RetentionCleaner {

    /** 한 표를 한 바퀴 지운 결과. {@code capped}가 참이면 스케줄러가 같은 틱에서 이어 부른다(바퀴 상한 뒤는 다음 틱). */
    public record Result(int deleted, boolean capped) {
    }

    private final JdbcTemplate jdbc;
    private final RetentionProperties properties;

    public RetentionCleaner(JdbcTemplate jdbc, RetentionProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /**
     * 교환 시도 기록(POK-90). rate limit은 최근 1분만 세므로 보관 1시간 밖의 행은 판정에 안 든다.
     * 이 표에는 회원 칸이 없어 탈퇴가 못 지운다 — 청소가 유일한 삭제 경로다.
     */
    @Transactional
    public Result cleanPairingAttempts(Instant now) {
        Timestamp cutoff = Timestamp.from(now.minus(properties.pairingAttemptsKeepFor()));
        int deleted = jdbc.update("""
                DELETE FROM pairing_exchange_attempts WHERE id IN (
                    SELECT id FROM pairing_exchange_attempts WHERE attempted_at < ? ORDER BY id LIMIT ?
                    FOR UPDATE SKIP LOCKED)
                """, cutoff, properties.batchLimit());
        return result(deleted);
    }

    private Result result(int deleted) {
        return new Result(deleted, deleted == properties.batchLimit());
    }
}
