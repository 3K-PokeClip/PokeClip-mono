package com.pokeclip.auth.streamkey.secret;

import java.util.Optional;

/**
 * 비밀 보관소. 지금 구현은 PostgreSQL + AES-256-GCM이고, 운영에서는 AWS
 * Secrets Manager 구현체로 갈아탄다(ADR-018).
 *
 * <p>메서드를 셋으로 좁힌 것은 Secrets Manager 의미론과 겹치는 최소 집합이기
 * 때문이다. 여기에 목록 조회·버전 같은 것을 더하면 교체가 어려워진다.
 */
public interface SecretStore {

    /** 같은 ref로 다시 넣으면 덮어쓴다. 재발급이 이 동작에 기댄다. */
    void put(String ref, String value);

    Optional<String> get(String ref);

    /** 없는 ref를 지우는 것은 오류가 아니다. 재발급 재시도가 여기 걸리면 안 된다. */
    void delete(String ref);
}
