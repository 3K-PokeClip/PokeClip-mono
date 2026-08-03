package com.pokeclip.auth.streamkey;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class PostgresSecretStore implements SecretStore {

    private final SecretRepository secretRepository;
    private final AesGcmCipher cipher;

    /**
     * delete와 달리 REQUIRED로 남긴다(일부러다). {@code StreamKeyCreator.create}가
     * "삽입이 유니크 위반으로 깨지면 secret도 같이 롤백돼 고아가 안 남는다"에
     * 기대고 있어서다.
     *
     * <p>다만 <b>그 보증은 이 PG 구현에서만 참이고 Secrets Manager로 갈아타면
     * 사라진다</b> — 원격 호출에는 롤백이 안 따라온다. StreamKeyCreator의 주석이
     * 그 대가를 이미 적어뒀다("최악이 아무도 참조하지 않는 고아 secret 하나").
     */
    @Override
    @Transactional
    public void put(String ref, String value) {
        byte[] sealed = cipher.encrypt(value);

        secretRepository.findById(ref)
                .ifPresentOrElse(
                        existing -> existing.replaceCiphertext(sealed),
                        () -> secretRepository.save(Secret.of(ref, sealed, Instant.now())));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> get(String ref) {
        return secretRepository.findById(ref)
                .map(secret -> cipher.decrypt(secret.getCiphertext()));
    }

    /**
     * <b>REQUIRES_NEW가 아니면 조용히 안 지워진다.</b> 유일한 운영 호출부가
     * {@code StreamKeyService.rotate}의 afterCommit인데, afterCommit이 도는 시점에는
     * 트랜잭션 자원이 아직 바인딩돼 있고 동기화도 살아 있다. 그래서 REQUIRED면
     * <b>이미 커밋이 끝난 트랜잭션에 합류하고 이 DELETE는 영영 커밋되지 않는다</b> —
     * 예외도 경고도 없다. POK-68의 "이전 passphrase도 정리한다"가 초록인 채로
     * 거짓이 되는 경로였고, {@code 이전_secret이_지워진다}가 그것을 잡았다.
     *
     * <p><b>이 REQUIRES_NEW는 앞의 셋과 이유가 다르다.</b> 값이 같다고 이유가 같지 않다 —
     * {@code StreamKeyCreator.create}는 오염된 세션을 피하려고,
     * {@code PairingAttemptRecorder}는 롤백돼도 기록이 살아남으려고,
     * 여기는 <b>커밋 뒤 작업이 개념상 그 트랜잭션 바깥이라서</b>다.
     *
     * <p>그리고 이것이 포트로서도 옳다. <b>구현체끼리 트랜잭션 의미론이 다르면 그 차이는
     * 교체하는 날 터진다</b> — REQUIRED로 두면 "롤백되면 delete도 취소된다"에 기대는
     * 코드가 자연히 자라는데, Secrets Manager 구현은 원격 호출이라 롤백이 안 따라와
     * 갈아타는 순간 그 가정들이 한꺼번에 무너진다. REQUIRES_NEW면 PG 대역이 진짜와
     * 같게 행동해서 그런 가정이 애초에 자라지 못하고, 문제가 있으면 지금 드러난다.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void delete(String ref) {
        secretRepository.deleteById(ref);
    }
}
