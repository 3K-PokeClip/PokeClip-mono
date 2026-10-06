package com.pokeclip.auth.streamkey.secret;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.InvalidRequestException;
import software.amazon.awssdk.services.secretsmanager.model.ResourceExistsException;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 스트림키 암호를 AWS Secrets Manager에 둔다(POK-272, ADR-018, 1번 설계 8-A 1)). {@code @Component}가 아니다.
 * {@link StreamKeySecretStoreConfig}가 설정을 보고 만든다. 그리고 {@link SecretStore} 타입 빈으로 노출하지 않아
 * 표지 없는 주입(유튜브·치지직 토큰)이 모호해지지 않는다.
 *
 * <p><b>호출은 DB 연결을 쥔 채 일어난다.</b> 페어링 발급·교환은 {@code @Transactional} 안에서 {@code ensureKey}를
 * 부르고, 재발급은 회원 행 잠금 안에서 put을 부른다. 그래서 논리 연산 하나(get·put·delete)마다 <b>공유 시한</b>
 * {@link #OPERATION_DEADLINE}을 잡고, 그 안의 모든 API 호출이 남은 시간을 나눠 쓴다. 호출마다 3초씩 주면 재조회
 * 넷과 백오프가 쌓여 4초를 넘긴다(설계 r4 정정).
 *
 * <p>연결을 쥐는 시간은 두 값으로 적는다:
 * <ul>
 *   <li><b>목표 대기 합 약 5초</b> = get 3초 + put 2초(호출 둘 × {@link #ATTEMPT_TIMEOUT}). 한 트랜잭션에서
 *       get과 put이 이어지는 갈래가 실제로 있다. {@code ensureKey}가 새 키를 만들다가 동시 요청에 져서
 *       ({@code DataIntegrityViolationException}) 다시 읽을 때다. 다만 그 put은 {@code StreamKeyCreator.create}의
 *       별도 트랜잭션이라 바깥 연결과 안쪽 연결을 하나씩 쥔다.</li>
 *   <li><b>시한 합 6초</b> = 연산마다 3초씩. 시간 시험의 허용 오차 0.3초는 연산마다 따로 붙는다.</li>
 * </ul>
 *
 * <p><b>조회의 갈래는 넷이다.</b> 성공은 값. 없음({@code ResourceNotFoundException})은 방금 만든 비밀이 아직 안
 * 보이는 것일 수 있어(최종 일관성) 100·200·400ms 뒤 최대 세 번 다시 읽고, 그래도 없거나 시한이 다하면 빈손 +
 * WARN이다. 삭제 예약({@code InvalidRequestException})은 빈손 + WARN이다. 그 밖(스로틀·5xx·시간 초과·권한 거부)은
 * {@link SecretStoreUnavailableException}이다. <b>시간 초과는 다시 읽지 않는다</b>: 「없음」이 아니라 판단 불가다.
 *
 * <p>접두 밖 이름(옛 {@code streamkey:<uuid>})은 원격 호출 없이 get은 빈손, delete는 무시다. Secrets Manager 이름에
 * {@code :}가 없어 그런 비밀은 애초에 있을 수 없다.
 *
 * <p>로그 줄에 이름·값을 싣지 않는다.
 */
final class SecretsManagerSecretStore implements SecretStore, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SecretsManagerSecretStore.class);

    /** 논리 연산 하나의 공유 목표 시한. 보장이 아니라 목표다. SDK의 중단이 늦을 수 있어 시험이 오차 0.3초로 잰다. */
    static final Duration OPERATION_DEADLINE = Duration.ofSeconds(3);

    /** API 호출 한 번의 상한. 남은 시간이 더 짧으면 남은 시간이다. */
    static final Duration ATTEMPT_TIMEOUT = Duration.ofSeconds(1);

    /** 남은 시간이 이보다 짧으면 더 부르지 않는다. 그 짧은 호출은 거의 언제나 시간 초과로 끝난다. */
    static final Duration MIN_ATTEMPT = Duration.ofMillis(100);

    /** 없음일 때 다시 읽기 전 기다리는 시간. 길이가 곧 재조회 횟수다(첫 시도 + 셋 = 최대 넷). */
    static final List<Duration> NOT_FOUND_BACKOFF =
            List.of(Duration.ofMillis(100), Duration.ofMillis(200), Duration.ofMillis(400));

    private final SecretsManagerClient client;
    private final String refPrefix;
    private final Clock clock;

    SecretsManagerSecretStore(SecretsManagerClient client, String refPrefix, Clock clock) {
        this.client = client;
        this.refPrefix = refPrefix;
        this.clock = clock;
    }

    /**
     * CreateSecret, 이미 있으면 PutSecretValue(포트의 「덮어쓴다」 의미를 지킨다). 스트림키는 언제나 새 이름이라
     * 운영에서 덮어쓰기는 이행 실행기의 재시도에서만 생긴다. 두 호출이 같은 시한을 나눠 쓴다.
     *
     * <p>원격 호출이라 DB 롤백이 따라오지 않는다. 비밀 먼저 → 행 순서({@code StreamKeyCreator})라 최악이
     * 아무 행도 가리키지 않는 고아 비밀 하나다(월 $0.40, 송출에는 무해).
     */
    @Override
    public void put(String ref, String value) {
        requireOwned(ref);
        Instant deadline = clock.instant().plus(OPERATION_DEADLINE);
        try {
            client.createSecret(b -> b.name(ref).secretString(value)
                    .overrideConfiguration(timeoutsUntil(deadline)));
        } catch (ResourceExistsException exists) {
            if (remaining(deadline).compareTo(MIN_ATTEMPT) < 0) {
                throw new SecretStoreUnavailableException("put", null);
            }
            try {
                client.putSecretValue(b -> b.secretId(ref).secretString(value)
                        .overrideConfiguration(timeoutsUntil(deadline)));
            } catch (SdkException e) {
                throw new SecretStoreUnavailableException("put", e);
            }
        } catch (SdkException e) {
            throw new SecretStoreUnavailableException("put", e);
        }
    }

    @Override
    public Optional<String> get(String ref) {
        if (!owns(ref)) {
            return Optional.empty();
        }
        Instant deadline = clock.instant().plus(OPERATION_DEADLINE);
        int attempts = 0;
        while (true) {
            attempts++;
            try {
                return Optional.of(client.getSecretValue(b -> b.secretId(ref)
                        .overrideConfiguration(timeoutsUntil(deadline))).secretString());
            } catch (ResourceNotFoundException notFound) {
                if (!backOffBeforeRetry(attempts, deadline)) {
                    log.warn("auth.streamkey.secret.not_visible attempts={}", attempts);
                    return Optional.empty();
                }
            } catch (InvalidRequestException scheduled) {
                log.warn("auth.streamkey.secret.scheduled_for_deletion");
                return Optional.empty();
            } catch (SdkException e) {
                throw new SecretStoreUnavailableException("get", e);
            }
        }
    }

    /**
     * 복구 창 없이 지운다({@code ForceDeleteWithoutRecovery}). ADR-019 결정 2 「재발급 시 지운다」를 30일 복구 창 없이
     * 지키려고. 영구 삭제는 뒤에서 비동기로 끝난다. 없는 비밀은 성공으로 본다.
     */
    @Override
    public void delete(String ref) {
        if (!owns(ref)) {
            return;
        }
        Instant deadline = clock.instant().plus(OPERATION_DEADLINE);
        try {
            client.deleteSecret(b -> b.secretId(ref).forceDeleteWithoutRecovery(true)
                    .overrideConfiguration(timeoutsUntil(deadline)));
        } catch (ResourceNotFoundException alreadyGone) {
            // 멱등이다. 재발급 정리가 두 번 와도, 이행 실행기의 폐기 행 정리가 다시 돌아도 같다
        } catch (SdkException e) {
            throw new SecretStoreUnavailableException("delete", e);
        }
    }

    @Override
    public void close() {
        client.close();
    }

    /**
     * 시도 수가 남았고, 기다린 뒤에도 최소 한 번 부를 시간이 남으면 기다리고 참이다. 아니면 거짓이다.
     * {@code attempts}는 방금 끝낸 시도를 포함한 수다.
     */
    private boolean backOffBeforeRetry(int attempts, Instant deadline) {
        if (attempts > NOT_FOUND_BACKOFF.size()) {
            return false;
        }
        Duration backoff = NOT_FOUND_BACKOFF.get(attempts - 1);
        if (remaining(deadline).compareTo(backoff.plus(MIN_ATTEMPT)) <= 0) {
            return false;
        }
        try {
            Thread.sleep(backoff);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return true;
    }

    /**
     * 이 호출 하나의 시한 = {@code min(ATTEMPT_TIMEOUT, 남은 시간)}. 시도 시한과 호출 시한을 <b>같은 값</b>으로 둔다.
     * 그러면 시도 하나가 시한을 넘긴 뒤의 SDK 재시도는 일어나지 않고, 빨리 실패한 시도(스로틀·5xx)만 남은 시간
     * 안에서 SDK가 다시 보낸다(1번 10-05 정정 1).
     */
    private AwsRequestOverrideConfiguration timeoutsUntil(Instant deadline) {
        Duration remaining = remaining(deadline);
        Duration timeout = remaining.compareTo(ATTEMPT_TIMEOUT) < 0 ? remaining : ATTEMPT_TIMEOUT;
        if (timeout.isNegative() || timeout.isZero()) {
            timeout = Duration.ofMillis(1);
        }
        return AwsRequestOverrideConfiguration.builder()
                .apiCallTimeout(timeout)
                .apiCallAttemptTimeout(timeout)
                .build();
    }

    private Duration remaining(Instant deadline) {
        return Duration.between(clock.instant(), deadline);
    }

    private boolean owns(String ref) {
        return ref.startsWith(refPrefix);
    }

    private void requireOwned(String ref) {
        if (!owns(ref)) {
            // 새 이름은 언제나 PassphraseRefIssuer가 짓는다. 여기 오면 코드 결함이라 이름 대신 길이만 남긴다
            throw new IllegalArgumentException("접두 밖 이름으로 비밀을 만들려 했다 length=" + ref.length());
        }
    }
}
