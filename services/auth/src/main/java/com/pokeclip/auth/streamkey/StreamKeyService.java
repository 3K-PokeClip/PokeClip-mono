package com.pokeclip.auth.streamkey;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class StreamKeyService {

    private static final Logger log = LoggerFactory.getLogger(StreamKeyService.class);

    /** ADR-019: 26자 Crockford Base32 = 130bit. 128bit 랜덤을 담는다. */
    private static final int TOKEN_LENGTH = 26;

    /** 24바이트를 Base64url로 인코딩하면 패딩 없이 정확히 32자다 (ADR-019). */
    private static final int PASSPHRASE_BYTES = 24;

    private final StreamKeyRepository streamKeyRepository;
    private final StreamKeyCreator streamKeyCreator;
    private final SecretStore secretStore;
    private final SecureRandom random = new SecureRandom();

    /**
     * 발급의 유일한 입구다. 지금은 조건 없이 주지만 원래는 결제해야 발급이므로,
     * 나중에 조건 한 줄이 들어갈 자리가 여기 하나뿐이어야 한다.
     *
     * <p>이 메서드에 &#64;Transactional을 걸지 않는다. 걸면 재조회가 삽입 실패와 같은
     * 트랜잭션에 묶여 오염된 세션에서 쿼리를 돌리게 된다
     * (UserService.findOrCreate와 같은 이유).
     */
    public StreamKeyMaterial ensureKey(Long userId) {
        return findMaterial(userId).orElseGet(() -> createOrRead(userId));
    }

    private StreamKeyMaterial createOrRead(Long userId) {
        StreamKeyMaterial material = new StreamKeyMaterial(
                CrockfordBase32.random(random, TOKEN_LENGTH), randomPassphrase());
        String ref = "streamkey:" + UUID.randomUUID();

        try {
            StreamKey created = streamKeyCreator.create(userId, ref, material);
            // 경합에 져서 남의 키를 읽은 경우는 우리가 만든 것이 아니다.
            // 그래서 성공한 경로에서만 찍는다(UserService와 같은 규칙).
            log.info("auth.streamkey.issued userId={} streamKeyId={}", userId, created.getId());
            return material;
        } catch (DataIntegrityViolationException e) {
            // 동시 요청이 먼저 만들었다. 위 트랜잭션은 롤백됐으니 새로 읽는다.
            return findMaterial(userId).orElseThrow(() -> e);
        }
    }

    public Optional<StreamKeyMaterial> findMaterial(Long userId) {
        return findAlive(userId).map(this::materialOf);
    }

    public Optional<StreamKey> findAlive(Long userId) {
        return streamKeyRepository.findByUserIdAndRevokedAtIsNull(userId);
    }

    /**
     * 키 행은 있는데 secret이 없으면 우리 저장소가 어긋난 것이다. 500으로 올린다 —
     * Media 입장에서 "키가 틀림"이 아니라 "판단 불가"이고 조치가 정반대다.
     * RequestIdFilter의 request.failed ERROR가 상관 ID와 함께 잡는다.
     */
    StreamKeyMaterial materialOf(StreamKey key) {
        return StreamKeyMaterial.deserialize(secretStore.get(key.getPassphraseRef())
                .orElseThrow(() -> new IllegalStateException(
                        "스트림키 행은 있는데 secret이 없다 streamKeyId=" + key.getId())));
    }

    private String randomPassphrase() {
        byte[] bytes = new byte[PASSPHRASE_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
