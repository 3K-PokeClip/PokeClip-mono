package com.pokeclip.auth.streamkey.secret;

import java.util.Optional;

/**
 * 비밀 보관소. 구현은 둘이다. PostgreSQL + AES-256-GCM({@link PostgresSecretStore})과 AWS Secrets Manager
 * ({@link SecretsManagerSecretStore}). 스트림키 암호만 설정으로 둘 중에 고르고({@link StreamKeySecrets}),
 * 유튜브·치지직 토큰은 언제나 PG다(ADR-018, POK-272).
 *
 * <p>메서드를 셋으로 좁힌 것은 Secrets Manager 의미론과 겹치는 최소 집합이기
 * 때문이다. 여기에 목록 조회·버전 같은 것을 더하면 교체가 어려워진다.
 */
public interface SecretStore {

    /** 같은 ref로 다시 넣으면 덮어쓴다. 덮어쓰기는 OAuth 토큰 갱신이 쓴다. 스트림키는 언제나 새 ref다. */
    void put(String ref, String value);

    Optional<String> get(String ref);

    /** 없는 ref를 지우는 것은 오류가 아니다. 재발급 재시도가 여기 걸리면 안 된다. */
    void delete(String ref);
}
