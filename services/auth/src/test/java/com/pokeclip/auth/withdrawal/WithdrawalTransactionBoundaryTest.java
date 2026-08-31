package com.pokeclip.auth.withdrawal;

import com.pokeclip.auth.withdrawal.api.WithdrawalController;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 탈퇴의 트랜잭션 경계를 <b>구조로</b> 못박는다. {@code YoutubeTransactionBoundaryTest}와 같은 모양이다 —
 * 그쪽은 연동 창구 둘을 바로 이 이유로 묶어 뒀는데 탈퇴 창구만 밖에 있었다.
 *
 * <p>🔴 <b>왜 행동으로 못 재나.</b> {@code WithdrawalController}에 {@code @Transactional}을 붙여도
 * <b>665건이 전부 초록이다</b>(2026-08-31 실측). 주입이 무시된 것이 아니다 — 같은 판에서
 * {@code withdraw} 안의 {@code isNewTransaction()}을 찍어 보면 <b>창구를 지나는 8건이 최상단을 잃는다</b>
 * (주입 없음 {@code true} 11건 / 주입 뒤 {@code false} 8 + {@code true} 3). 잃는데 아무도 안 깨진다.
 *
 * <p>오늘 대가가 0인 이유는 창구 본문이 서비스 호출 한 줄뿐이기 때문이다 — 락은 서비스 안에서 잡히고,
 * {@code afterCommit} 로그는 바깥 커밋 뒤에 그대로 찍히고, {@code noRollbackFor}가 없어 롤백 범위도 같다.
 * <b>「대가가 생기는 날 치자」가 안 되는 이유</b>는 그날이 안 오기 때문이다: 연동 해제가 등록하는 정리도
 * 최상단 커밋에 붙으므로 제출 시점이 마이크로초 옮겨질 뿐이고, MockMvc 시험은 창구가 반환한 <b>뒤에</b>
 * 정리를 기다린다. 미루면 영영 안 친다.
 *
 * <p><b>대가는 0에 가깝다</b> — 스프링 컨텍스트를 안 띄우는 순수 리플렉션이다.
 *
 * <p>양방향으로 잰다 — 붙으면 안 되는 곳과 <b>붙어야 하는 곳</b>을 나란히. 한쪽만 있으면 목록이 비거나
 * 클래스·메서드 이름이 어긋났을 때 조용히 자동 참이 된다.
 */
class WithdrawalTransactionBoundaryTest {

    /**
     * 웹 계층. 여기에 트랜잭션이 붙으면 {@link WithdrawalService#withdraw}가 최상단이 아니게 되고,
     * 회원 행 락과 익명화가 <b>남의 트랜잭션 수명</b>에 묶인다.
     */
    private static final List<Class<?>> MUST_BE_TRANSACTION_FREE = List.of(WithdrawalController.class);

    /**
     * 실제로 트랜잭션이어야 하는 쓰기 진입점. 이름이 어긋나면 여기가 먼저 빨간불이 된다.
     * <b>탈퇴에 트랜잭션 진입점을 더하는 태스크는 이 목록에 한 줄을 더한다</b> — 그것이 유지비 전부다.
     */
    private static final List<String> MUST_BE_TRANSACTIONAL = List.of("WithdrawalService#withdraw");

    @Test
    void 탈퇴_창구에는_트랜잭션이_없다() {
        assertThat(MUST_BE_TRANSACTION_FREE).as("검사 목록이 비면 아래가 자동으로 참이 된다").hasSize(1);

        for (Class<?> type : MUST_BE_TRANSACTION_FREE) {
            assertThat(Arrays.stream(type.getDeclaredMethods())
                    .filter(m -> m.isAnnotationPresent(Transactional.class))
                    .map(Method::getName).toList())
                    .as("%s의 메서드에 @Transactional이 붙었다 — WithdrawalService.withdraw가 최상단이어야 한다",
                            type.getSimpleName())
                    .isEmpty();
            assertThat(type.isAnnotationPresent(Transactional.class))
                    .as("%s에 클래스 수준 @Transactional이 붙었다", type.getSimpleName())
                    .isFalse();
        }
    }

    @Test
    void 탈퇴의_쓰기_진입점은_트랜잭션이다() {
        List<String> annotated = new ArrayList<>(Arrays.stream(WithdrawalService.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Transactional.class))
                .map(m -> "WithdrawalService#" + m.getName())
                .toList());

        assertThat(annotated).containsExactlyInAnyOrderElementsOf(MUST_BE_TRANSACTIONAL);
    }
}
