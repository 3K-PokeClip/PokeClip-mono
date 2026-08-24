package com.pokeclip.auth.youtube;

import com.pokeclip.auth.youtube.api.YoutubeLinkController;
import com.pokeclip.auth.youtube.api.YoutubeLinkResolveController;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 트랜잭션 경계를 <b>구조로</b> 못박는다 — `transaction-boundary` 스킬 5문의 질문 2(「이 메서드가 최상단인가」)는
 * 호출 사슬 전체를 묻는데, 그 답이 어긋나도 <b>테스트는 통과한다</b>(예외가 던져지는 것만 보기 때문).
 *
 * <p>양방향으로 잰다 — 붙으면 안 되는 곳과 <b>붙어야 하는 곳</b>을 나란히. 한쪽만 있으면 목록이 비거나
 * 클래스 이름이 어긋났을 때 조용히 자동 참이 된다(감사 2라운드 사소-H의 다음 판).
 *
 * <p>행동으로 재는 자리는 따로 있다 — {@code YoutubeRevocationCheckSchedulerIntegrationTest}의
 * 「한 회원이 터져도 다음 회원의 갱신이 커밋된다」가 스케줄러 쪽을 실물로 잡는다. 조합부(resolve)는
 * 롤백되는 경로가 없어 오늘 행동으로 잴 수 없고, 이 구조 검사가 유일한 그물이다.
 */
class YoutubeTransactionBoundaryTest {

    /** 조합·틱·HTTP 층. 여기에 트랜잭션이 붙으면 아래 쓰기부·갱신기가 최상단이 아니게 된다. */
    private static final List<Class<?>> MUST_BE_TRANSACTION_FREE = List.of(
            YoutubeLinkService.class,
            YoutubeRevocationCheckScheduler.class,
            YoutubeLinkController.class,
            YoutubeLinkResolveController.class);

    /** 실제로 트랜잭션이어야 하는 쓰기 진입점. 이름이 어긋나면 여기가 먼저 빨간불이 된다. */
    private static final List<String> MUST_BE_TRANSACTIONAL = List.of(
            "YoutubeLinkWriter#create", "YoutubeLinkWriter#revoke",
            "YoutubeTokenRefresher#refreshIfExpiringWithin");

    @Test
    void 조합부와_스케줄러와_컨트롤러에는_트랜잭션이_없다() {
        assertThat(MUST_BE_TRANSACTION_FREE).as("검사 목록이 비면 아래가 자동으로 참이 된다").hasSize(4);

        for (Class<?> type : MUST_BE_TRANSACTION_FREE) {
            assertThat(Arrays.stream(type.getDeclaredMethods())
                    .filter(m -> m.isAnnotationPresent(Transactional.class))
                    .map(Method::getName).toList())
                    .as("%s에 @Transactional이 붙었다 — 쓰기부·갱신기가 트랜잭션 최상단이어야 한다",
                            type.getSimpleName())
                    .isEmpty();
            assertThat(type.isAnnotationPresent(Transactional.class))
                    .as("%s에 클래스 수준 @Transactional이 붙었다", type.getSimpleName()).isFalse();
        }
    }

    @Test
    void 쓰기부와_갱신기의_진입점은_트랜잭션이다() {
        List<String> annotated = Arrays.stream(YoutubeLinkWriter.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Transactional.class))
                .map(m -> "YoutubeLinkWriter#" + m.getName())
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        Arrays.stream(YoutubeTokenRefresher.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Transactional.class))
                .map(m -> "YoutubeTokenRefresher#" + m.getName())
                .forEach(annotated::add);

        assertThat(annotated).containsExactlyInAnyOrderElementsOf(MUST_BE_TRANSACTIONAL);
    }
}
