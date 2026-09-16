package com.pokeclip.auth.streamkey;

import com.pokeclip.auth.streamkey.secret.SecretStore;
import com.pokeclip.auth.support.CrockfordBase32;
import com.pokeclip.auth.support.Sha256;
import com.pokeclip.auth.user.ActiveUserGuard;
import com.pokeclip.auth.user.User;
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
    private final ActiveUserGuard activeUserGuard;
    private final SecureRandom random = new SecureRandom();

    /**
     * 발급의 유일한 입구다. 지금은 조건 없이 주지만 원래는 결제해야 발급이므로,
     * 나중에 조건 한 줄이 들어갈 자리가 여기 하나뿐이어야 한다.
     * <b>그 「조건 한 줄」이 실제로 처음 들어온 것이 아래 탈퇴 확인이다.</b>
     *
     * <p>이 메서드에 &#64;Transactional을 걸지 않는다. 걸면 재조회가 삽입 실패와 같은
     * 트랜잭션에 묶여 오염된 세션에서 쿼리를 돌리게 된다
     * (UserService.findOrCreate와 같은 이유).
     *
     * <p>🔴 <b>확인이 만드는 갈래 앞이 아니라 맨 앞이다.</b> 「새로 만들 때만 본다」로 두면
     * <b>이미 있는 키를 탈퇴한 회원에게 그대로 내주는</b> 갈래가 남는다 — 회수를 넘어 만들어진
     * 키가 하나라도 있으면(위 창) 교환이 그것을 계속 돌려준다. 재는 것은
     * {@code WithdrawnWriteGuardStreamKeyTest}다.
     *
     * <p>대가는 발급·교환마다 회원 표 조회가 하나 느는 것이다. 둘 다 rate limit이 걸린 드문 경로고,
     * <b>Media가 부르는 {@code resolve}는 이 메서드를 안 지난다</b> — 그쪽 지연은 안 변한다.
     */
    public StreamKeyMaterial ensureKey(Long userId) {
        activeUserGuard.requireAlive(userId, "streamkey.ensure");
        return findMaterial(userId).orElseGet(() -> createOrRead(userId));
    }

    private StreamKeyMaterial createOrRead(Long userId) {
        StreamKeyMaterial material = newMaterial();
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
        lockUser(userId);

        StreamKey previous = findAlive(userId)
                .orElseThrow(() -> new StreamKeyException(
                        StreamKeyFailure.STREAM_KEY_NOT_FOUND, "폐기할 스트림키가 없다"));

        Instant now = Instant.now();
        replaceAlive(userId, previous, now, "auth.streamkey.rotated");
        return now;
    }

    /**
     * 페어링 코드 교환 전용 재발급(POK-245). <b>교환할 때마다 키를 바꾼다</b> — 마지막으로
     * 연결한 PC만 송출할 수 있게. 옛 PC 설정 파일에 남은 키는 이 커밋으로 죽는다.
     *
     * <p>{@link #rotate}와 달리 키가 없어도 실패하지 않고 새로 만든다. 교환의 목적은
     * "무효화"가 아니라 "이 PC에 줄 자격증명"이기 때문이다.
     *
     * <p>바깥 교환 트랜잭션에 참여한다. 코드 소비와 키 교체가 한 원자 단위라,
     * 어느 쪽이 실패해도 코드는 다시 쓸 수 있고 옛 키도 살아 있다.
     *
     * <p>🔴 <b>순서가 계약이다 — 회원 행 락 → 코드 소비 → 탈퇴 확인 → 키 교체.</b>
     * <ul>
     *   <li><b>락이 코드 소비보다 먼저다.</b> 탈퇴가 회원 행 → {@code pairing_codes} 순으로 잠그므로
     *       같은 순서여야 사이클이 안 생긴다({@link ActiveUserGuard} 「못 닫는 것」).
     *       그래서 코드 소비를 여기로 넘겨받는다.</li>
     *   <li><b>탈퇴 확인이 코드 소비보다 뒤다.</b> 탈퇴는 살아있는 코드를 함께 소비하므로, 탈퇴가 이미
     *       커밋됐으면 소비가 먼저 409({@code ALREADY_USED})를 낸다 — 로그인 없는 창구가 「그 계정은
     *       탈퇴했다」를 알려 주지 않는다({@code WithdrawalStreamKeyTest}). 확인은 탈퇴 표시만 있고
     *       코드가 살아 있는 어긋난 표에서만 걸린다({@code WithdrawnWriteGuardStreamKeyTest}).</li>
     *   <li>락을 쥔 채 확인하므로 {@link #ensureKey}와 달리 탈퇴와 겹치는 창이 없다.</li>
     * </ul>
     *
     * @param consumeCode 회원 행 락을 잡은 뒤 부른다. 코드를 못 쓰면 예외를 던진다.
     */
    @Transactional
    public StreamKeyMaterial reissueForPairing(Long userId, Runnable consumeCode) {
        User owner = lockUser(userId);
        consumeCode.run();
        activeUserGuard.requireAlive(owner, "streamkey.reissue_for_pairing");

        Instant now = Instant.now();
        Optional<StreamKey> previous = findAlive(userId);
        if (previous.isPresent()) {
            return replaceAlive(userId, previous.get(), now, "auth.streamkey.reissued_for_pairing");
        }

        // 키가 없다(발급 입구 ensureKey를 안 거친 계정). 같은 락 안이므로 경합 재조회가 필요 없다.
        // REQUIRES_NEW인 create를 쓰면 안 된다 — 그 키가 따로 커밋돼, 교환이 뒤에서 롤백돼도
        // 코드는 살아나고 키는 남는다. 코드 소비와 한 원자 단위여야 한다.
        StreamKeyMaterial material = newMaterial();
        StreamKey created = streamKeyCreator.createInCurrentTransaction(
                userId, "streamkey:" + UUID.randomUUID(), material);
        Long createdId = created.getId();
        afterCommit(() -> log.info("auth.streamkey.issued userId={} streamKeyId={} via=pairing", userId, createdId));
        return material;
    }

    /**
     * 같은 사용자의 키 교체를 직렬화한다. 이 락이 없으면 읽기(previous)·폐기
     * (revokeAlive)·삭제(staleRef)가 서로 다른 키를 가리킬 수 있다 — PostgreSQL
     * READ COMMITTED에서 UPDATE는 문장 시작 시점의 스냅샷을 쓰므로, 경합 상대가
     * 그 사이에 커밋한 새 키를 대상으로 잡는다. 그러면 남의 키를 폐기해 놓고
     * 삭제는 아까 읽은 previous의 ref로 나가 secret이 고아로 남는다.
     * 잠글 스트림키 행이 바뀌는 중이므로 사용자 행을 잠근다 —
     * TokenService.rotate가 같은 이유로 같은 락을 쓴다.
     */
    private User lockUser(Long userId) {
        return userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new StreamKeyException(
                        StreamKeyFailure.STREAM_KEY_NOT_FOUND, "사용자가 없다"));
    }

    /** 사용자 행 락을 잡은 뒤에만 부른다. 옛 키를 폐기하고 새 키를 같은 트랜잭션에 넣는다. */
    private StreamKeyMaterial replaceAlive(Long userId, StreamKey previous, Instant now, String logEvent) {
        if (streamKeyRepository.revokeAlive(userId, now) == 0) {
            // 동시 재발급에 졌다. "내가 폐기한 키는 없다"가 참이므로 키 없음과 같게 다룬다.
            throw new StreamKeyException(
                    StreamKeyFailure.STREAM_KEY_NOT_FOUND, "폐기할 스트림키가 없다");
        }

        StreamKeyMaterial material = newMaterial();
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
            log.info("{} userId={}", logEvent, userId);
        });
        return material;
    }

    private void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private StreamKeyMaterial newMaterial() {
        return new StreamKeyMaterial(CrockfordBase32.random(random, TOKEN_LENGTH), randomPassphrase());
    }

    private String randomPassphrase() {
        byte[] bytes = new byte[PASSPHRASE_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
