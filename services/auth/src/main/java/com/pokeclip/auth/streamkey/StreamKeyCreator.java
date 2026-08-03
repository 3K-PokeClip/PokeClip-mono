package com.pokeclip.auth.streamkey;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 삽입만 담당한다. StreamKeyService에서 떼어낸 이유는 UserCreator와 같다 —
 * &#64;Transactional은 프록시로 동작해서 같은 클래스의 메서드를 직접 부르면
 * 무시된다.
 *
 * <p>제약 위반을 여기서 잡지 않는다. 잡으면 이 트랜잭션 안에서 재조회하게 되는데,
 * 제약 위반이 난 트랜잭션은 rollback-only로 표시되고 Hibernate 세션도 오염된다.
 * 예외를 밖으로 던져 트랜잭션을 끝내고, 호출한 쪽이 새 트랜잭션에서 재조회한다.
 */
@Component
@RequiredArgsConstructor
class StreamKeyCreator {

    private final StreamKeyRepository streamKeyRepository;
    private final SecretStore secretStore;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    StreamKey create(Long userId, String passphraseRef, StreamKeyMaterial material) {
        // PostgresSecretStore.put이 @Transactional이라 이 REQUIRES_NEW 트랜잭션에
        // 참여한다. 그래서 아래 삽입이 유니크 위반으로 깨지면 secret도 함께
        // 롤백돼 고아가 남지 않는다 — 지금 구현에서는 순서가 원자성에 영향을 주지 않는다.
        //
        // 그래도 "비밀 먼저"로 고정해 두는 이유는 SecretStore가 외부 서비스
        // (Secrets Manager) 구현으로 바뀌면 롤백이 안 따라오기 때문이다. 그때
        // 이 순서면 최악이 아무도 참조하지 않는 고아 secret 하나이고, 반대 순서면
        // "행은 있는데 secret이 없는" 복구 불능 상태가 된다. 재발급의 afterCommit
        // 삭제와 같은 판단을 미리 해 둔다.
        secretStore.put(passphraseRef, material.serialize());

        return streamKeyRepository.saveAndFlush(StreamKey.of(
                userId, Sha256.hex(material.streamToken()), passphraseRef, Instant.now()));
    }
}
