package com.pokeclip.auth.retention;

import com.pokeclip.auth.support.IntegrationTestSupport;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserService;

import java.util.UUID;

/**
 * 청소 통합 시험의 공용 베이스. {@code newUser()}가 세 파일에 글자 그대로 같았다 — {@code ChzzkLinkTestSupport} 등
 * 다른 support 다섯이 같은 이유로 그 헬퍼를 올려 뒀다.
 *
 * <p>{@code clear()}는 올리지 않는다 — 파일마다 지우는 표 집합이 정당하게 다르다(시도 기록만 · users의 자식 하나 ·
 * 교환 경로가 만드는 여섯).
 *
 * <p>컨텍스트는 늘지 않는다 — {@code IntegrationTestSupport}를 그대로 상속하고 프로퍼티·애노테이션을 더하지 않아
 * 캐시 키가 같다. {@code RetentionCleanupSchedulerIntegrationTest}는 {@code @TestPropertySource}로 이미 별도 컨텍스트라
 * 이 클래스가 있든 없든 같다. 컨텍스트 수의 정본은 {@code IntegrationTestSupport} 주석이다.
 */
public abstract class RetentionTestSupport extends IntegrationTestSupport {

    protected final UserService userService;

    protected RetentionTestSupport(UserService userService) {
        this.userService = userService;
    }

    protected User newUser() {
        return userService.findOrCreate("sub-" + UUID.randomUUID(), "a@example.com", "김태현", null);
    }
}
