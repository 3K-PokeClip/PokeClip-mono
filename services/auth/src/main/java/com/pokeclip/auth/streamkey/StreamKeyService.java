package com.pokeclip.auth.streamkey;

import com.pokeclip.auth.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.SecureRandom;
import java.time.Instant;
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
    private final UserRepository userRepository;
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

    /**
     * Media가 SRT 연결을 받기 전에 한 번 부른다(계약4). 세그먼트마다 부르지 않으므로
     * 방송 도중 Auth가 죽어도 이미 나가는 방송은 안 끊긴다.
     *
     * <p>도메인 판단은 전부 valid 플래그로 돌려준다. 예외를 던지지 않는 이유는
     * Media가 "키가 틀림"과 "Auth 장애"를 구분해야 하기 때문이다.
     */
    public ResolveResult resolve(String rawStreamId) {
        return StreamId.parse(rawStreamId)
                .map(streamId -> streamKeyRepository.findByStreamidHash(Sha256.hex(streamId.token()))
                        .map(key -> key.isRevoked()
                                ? ResolveResult.rejected("REVOKED")
                                : ResolveResult.of(key.getUserId(), materialOf(key).passphrase()))
                        .orElseGet(() -> ResolveResult.rejected("NOT_FOUND")))
                .orElseGet(() -> ResolveResult.rejected("MALFORMED"));
    }

    /**
     * <b>이 record를 {}에 통째로 넣지 않는다.</b> passphrase를 담고 있다.
     * SecretLeakTest가 "ResolveResult[" 문자열을 금지해 못박는다.
     */
    public record ResolveResult(boolean valid, Long userId, String passphrase, String reason) {

        static ResolveResult of(Long userId, String passphrase) {
            return new ResolveResult(true, userId, passphrase, null);
        }

        static ResolveResult rejected(String reason) {
            return new ResolveResult(false, null, null, reason);
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

    /**
     * 재발급. 유출 대응이 목적이라 유예를 두지 않는다 — 옛 키는 이 트랜잭션이
     * 커밋되는 순간 죽는다.
     *
     * <p>키가 없으면 404다. 조용히 새로 발급하면 "무효화가 일어났다"는 로그가
     * 거짓이 되고, 사고 조사에서 거짓 알리바이가 된다.
     */
    @Transactional
    public Instant rotate(Long userId) {
        // 같은 사용자의 재발급을 직렬화한다. 이 락이 없으면 읽기(previous)·폐기
        // (revokeAlive)·삭제(staleRef)가 서로 다른 키를 가리킬 수 있다 — PostgreSQL
        // READ COMMITTED에서 UPDATE는 문장 시작 시점의 스냅샷을 쓰므로, 경합 상대가
        // 그 사이에 커밋한 새 키를 대상으로 잡는다. 그러면 남의 키를 폐기해 놓고
        // 삭제는 아까 읽은 previous의 ref로 나가 secret이 고아로 남는다.
        // 잠글 스트림키 행이 바뀌는 중이므로 사용자 행을 잠근다 —
        // TokenService.rotate가 같은 이유로 같은 락을 쓴다.
        userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new StreamKeyException(
                        StreamKeyFailure.STREAM_KEY_NOT_FOUND, "사용자가 없다"));

        StreamKey previous = findAlive(userId)
                .orElseThrow(() -> new StreamKeyException(
                        StreamKeyFailure.STREAM_KEY_NOT_FOUND, "폐기할 스트림키가 없다"));

        Instant now = Instant.now();
        if (streamKeyRepository.revokeAlive(userId, now) == 0) {
            // 동시 재발급에 졌다. "내가 폐기한 키는 없다"가 참이므로 위와 같게 다룬다.
            throw new StreamKeyException(
                    StreamKeyFailure.STREAM_KEY_NOT_FOUND, "폐기할 스트림키가 없다");
        }

        StreamKeyMaterial material = new StreamKeyMaterial(
                CrockfordBase32.random(random, TOKEN_LENGTH), randomPassphrase());
        // create가 아니다. REQUIRES_NEW로 부르면 새 트랜잭션이 위의 revokeAlive를
        // 못 봐 부분 유니크 인덱스에 걸린다.
        streamKeyCreator.createInCurrentTransaction(
                userId, "streamkey:" + UUID.randomUUID(), material);

        // 옛 secret 삭제를 커밋 뒤로 미룬다. 커밋 전에 지우면 롤백 시 "옛 키는
        // 살아 있는데 passphrase가 없는" 복구 불능 상태가 된다 — 그 스트리머는
        // 송출도 재발급도 못 한다. 커밋 후면 최악이 아무도 참조하지 않는 고아
        // secret 하나다. 후자를 택했다.
        //
        // 로그도 같은 자리에서 찍는다. 롤백됐는데 "재발급했다"가 남으면
        // 조사에서 거짓 알리바이가 된다(TokenService.logAfterCommit과 같은 이유).
        String staleRef = previous.getPassphraseRef();
        afterCommit(() -> {
            secretStore.delete(staleRef);
            log.info("auth.streamkey.rotated userId={}", userId);
        });

        return now;
    }

    private void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private String randomPassphrase() {
        byte[] bytes = new byte[PASSPHRASE_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
