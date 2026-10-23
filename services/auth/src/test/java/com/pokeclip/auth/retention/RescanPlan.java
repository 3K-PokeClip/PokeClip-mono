package com.pokeclip.auth.retention;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * 「지울 행을 고르는 질의가 바깥 행마다 다시 도는」 실행 계획을 한 트랜잭션 안에서 강제한다(POK-413).
 *
 * <p>상한 시험이 CI에서 「1,000을 지워야 하는데 1,500」으로 흔들렸다. 옛 모양 {@code DELETE … WHERE id IN (SELECT …
 * LIMIT n FOR UPDATE SKIP LOCKED)}은 계획이 {@code Nested Loop Semi Join}의 안쪽에 그 질의를 두면 바깥 행마다 다시
 * 돌고, 다시 돌 때마다 이 문장이 이미 지운 행을 건너뛰고 다음 n개를 잠가 결국 다 지운다. 어느 계획이 고르는지는 그때의
 * 표 통계가 정해서 평소 시험으로는 「가끔」만 보인다. 그래서 그 계획을 설정으로 고정해 계획과 무관하게 상한을 잰다.
 *
 * <p>{@code SET LOCAL}은 이 트랜잭션에만 걸린다. 청소 메서드의 {@code @Transactional}이 이 트랜잭션에 합류해 같은
 * 연결에서 돈다(풀의 다른 연결과 다른 시험에는 안 샌다).
 */
final class RescanPlan {

    private RescanPlan() {
    }

    static <T> T forcing(TransactionTemplate tx, JdbcTemplate jdbc, Supplier<T> call) {
        return tx.execute(status -> {
            // 해시·병합·정렬·임시 저장이 다 막히면 남는 것이 「안쪽을 다시 도는」 중첩 반복이다
            jdbc.execute("SET LOCAL enable_hashjoin = off");
            jdbc.execute("SET LOCAL enable_mergejoin = off");
            jdbc.execute("SET LOCAL enable_hashagg = off");
            jdbc.execute("SET LOCAL enable_sort = off");
            jdbc.execute("SET LOCAL enable_material = off");
            return call.get();
        });
    }
}
