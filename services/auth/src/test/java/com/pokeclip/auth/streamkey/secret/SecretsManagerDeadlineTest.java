package com.pokeclip.auth.streamkey.secret;

import com.pokeclip.auth.support.FakeSecretsManagerServer;
import com.pokeclip.auth.support.FakeSecretsManagerServer.Step;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Secrets Manager 저장소의 <b>공유 시한</b>과 조회 갈래(POK-272, 1번 설계 8-A 7) F3·E7).
 *
 * <p>가짜 서버로 지연·무응답·스로틀을 심는다. 시간 단언은 목표 + 오차 {@link #EPSILON}(0.3초, CI 스케줄 흔들림 몫)
 * 이다. 시험값은 응답이 시한 경계에 걸리지 않게 골랐다(설계 cx 부수). <b>오차를 넘으면 실패로 둔다</b>: 그때는
 * 요청 단위 시한 재정의를 믿을 수 없다는 뜻이라 비동기 클라이언트로 바꾼다(설계 8-A 1) 대체 수단).
 *
 * <p>스프링을 안 띄운다. 운영과 같은 클라이언트({@link SecretsManagerClients#forRequests})를 가짜 주소로 만든다.
 * 시한·재시도 설정이 그 한 곳에 있어 그것을 그대로 재야 한다.
 */
class SecretsManagerDeadlineTest {

    private static final Duration EPSILON = Duration.ofMillis(300);
    private static final String PREFIX = "pokeclip/test/stream-key/";
    private static final String REF = PREFIX + "0b6c0b6c-0b6c-0b6c-0b6c-0b6c0b6c0b6c";

    private static FakeSecretsManagerServer fake;
    private static SecretsManagerSecretStore store;

    @BeforeAll
    static void start() {
        // SDK 표준 체인이 읽는 시스템 프로퍼티 자리. 운영 코드에는 키를 받는 자리가 없다(사진 픽스처와 같다)
        System.setProperty("aws.accessKeyId", "test");
        System.setProperty("aws.secretAccessKey", "test");
        fake = FakeSecretsManagerServer.start();
        StreamKeySecretStoreProperties p = new StreamKeySecretStoreProperties(
                StreamKeySecretStoreProperties.Type.AWS, PREFIX, Set.of(StreamKeySecretStoreProperties.Type.AWS),
                fake.endpoint(), "ap-northeast-2");
        store = new SecretsManagerSecretStore(SecretsManagerClients.forRequests(p), PREFIX, Clock.systemUTC());
    }

    /** 부를 때마다 다음 값(시작 시각에서 얼마나 지났나)을 주고, 끝나면 마지막 값을 되풀이하는 시계. */
    private static SecretsManagerSecretStore storeWithClock(Duration... elapsed) {
        Instant t0 = Instant.parse("2026-10-06T00:00:00Z");
        Deque<Duration> ticks = new ArrayDeque<>(List.of(elapsed));
        Clock clock = new Clock() {
            private Duration last = Duration.ZERO;

            @Override
            public synchronized Instant instant() {
                if (!ticks.isEmpty()) {
                    last = ticks.pollFirst();
                }
                return t0.plus(last);
            }

            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }
        };
        StreamKeySecretStoreProperties p = new StreamKeySecretStoreProperties(
                StreamKeySecretStoreProperties.Type.AWS, PREFIX, Set.of(StreamKeySecretStoreProperties.Type.AWS),
                fake.endpoint(), "ap-northeast-2");
        return new SecretsManagerSecretStore(SecretsManagerClients.forRequests(p), PREFIX, clock);
    }

    @AfterAll
    static void stop() {
        store.close();
        fake.close();
    }

    @BeforeEach
    @AfterEach
    void reset() {
        fake.reset();
    }

    @Test
    void 처음_두_번은_없고_셋째에_보이면_값을_준다() {
        fake.seed(REF, "token:pass");
        fake.script("GetSecretValue", Step.notFoundAfter(Duration.ZERO), Step.notFoundAfter(Duration.ZERO));

        assertThat(store.get(REF)).contains("token:pass");
        assertThat(fake.count("GetSecretValue")).isEqualTo(3);
    }

    @Test
    void 끝내_없으면_네_번_읽고_빈손이며_경고에_시도_수가_남는다() {
        try (LogCaptor logs = new LogCaptor()) {
            assertThat(store.get(REF)).isEmpty();

            assertThat(fake.count("GetSecretValue")).isEqualTo(4);
            assertThat(logs.messages()).contains("auth.streamkey.secret.not_visible attempts=4");
            assertThat(logs.messages()).noneMatch(m -> m.contains(REF));
        }
    }

    /**
     * 「각 응답은 1초 미만이지만 재조회 넷을 다 하면 3초 초과(4 × 0.8 + 0.7 = 3.9초)」인 경우가 시한 안에서 끝난다.
     * 시도는 0 · 0.9 · 1.9초에 시작한 셋이고, 셋째가 2.7초에 끝난 뒤 남은 0.3초가 다음 백오프 0.4초 + 최소 시도
     * 0.1초보다 짧아 멈춘다.
     */
    @Test
    void 매번_0점8초_뒤_없음이면_세_번_시도_뒤_시한_안에_빈손이다() {
        Duration slow = Duration.ofMillis(800);
        fake.script("GetSecretValue", Step.notFoundAfter(slow), Step.notFoundAfter(slow),
                Step.notFoundAfter(slow), Step.notFoundAfter(slow));

        try (LogCaptor logs = new LogCaptor()) {
            long started = System.nanoTime();
            Optional<String> value = store.get(REF);
            Duration took = Duration.ofNanos(System.nanoTime() - started);

            assertThat(value).isEmpty();
            assertThat(fake.count("GetSecretValue")).isEqualTo(3);
            assertThat(logs.messages()).contains("auth.streamkey.secret.not_visible attempts=3");
            assertThat(took).isLessThanOrEqualTo(Duration.ofMillis(2700).plus(EPSILON));
        }
    }

    /** 시간 초과는 「없음」이 아니라 판단 불가다. 다시 읽지 않고 1초 시도 시한에 끊긴다. */
    @Test
    void 응답이_없으면_1초_안에_저장소_장애로_끊긴다() {
        fake.script("GetSecretValue", Step.hang(), Step.hang(), Step.hang());

        long started = System.nanoTime();
        assertThatThrownBy(() -> store.get(REF)).isInstanceOf(SecretStoreUnavailableException.class);
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(took).isLessThanOrEqualTo(SecretsManagerSecretStore.ATTEMPT_TIMEOUT.plus(EPSILON));
    }

    /**
     * CreateSecret이 0.9초 뒤 「이미 있음」, PutSecretValue는 무응답. 두 호출이 시한을 나눠 쓴다.
     * PutSecretValue가 실제로 나갔고(요청 둘) 총 1.9초 + 오차 안에 끝난다.
     */
    @Test
    void put의_두_호출이_시한을_나눠_쓴다() {
        fake.script("CreateSecret", Step.existsAfter(Duration.ofMillis(900)));
        fake.script("PutSecretValue", Step.hang(), Step.hang(), Step.hang());

        long started = System.nanoTime();
        assertThatThrownBy(() -> store.put(REF, "v")).isInstanceOf(SecretStoreUnavailableException.class);
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(fake.requests()).startsWith("CreateSecret", "PutSecretValue");
        assertThat(took).isLessThanOrEqualTo(Duration.ofMillis(1900).plus(EPSILON));
    }

    /**
     * 남은 시간이 「다음 백오프 + 최소 시도」보다 짧으면 다시 읽지 않는다. 시계를 끌어 올려 남은 시간을 0.15초로
     * 만든다(다음 백오프 0.1초 + 최소 시도 0.1초 = 0.2초보다 짧다). 실제로 3초를 기다리지 않고 경계를 잰다.
     */
    @Test
    void 남은_시간이_모자라면_다시_읽지_않는다() {
        SecretsManagerSecretStore jumpy = storeWithClock(Duration.ZERO, Duration.ZERO, Duration.ofMillis(2850));

        assertThat(jumpy.get(REF)).isEmpty();
        assertThat(fake.count("GetSecretValue")).isEqualTo(1);
    }

    /** put의 둘째 호출도 같다. 「이미 있음」 뒤 남은 시간이 최소 시도보다 짧으면 PutSecretValue를 안 보낸다. */
    @Test
    void 남은_시간이_최소_시도보다_짧으면_덮어쓰기를_보내지_않는다() {
        fake.seed(REF, "old");
        SecretsManagerSecretStore jumpy = storeWithClock(Duration.ZERO, Duration.ZERO, Duration.ofMillis(2950));

        assertThatThrownBy(() -> jumpy.put(REF, "new")).isInstanceOf(SecretStoreUnavailableException.class);
        assertThat(fake.count("PutSecretValue")).isZero();
    }

    /** 스로틀·5xx는 SDK가 남은 시간 안에서 다시 보내고, 끝내 실패하면 판단 불가다. 빈손이 아니다. */
    @Test
    void 스로틀이면_저장소_장애다() {
        fake.script("GetSecretValue", Step.error("ThrottlingException", 400), Step.error("ThrottlingException", 400),
                Step.error("ThrottlingException", 400), Step.error("ThrottlingException", 400));

        assertThatThrownBy(() -> store.get(REF)).isInstanceOf(SecretStoreUnavailableException.class);
    }

    @Test
    void 서버_오류면_저장소_장애다() {
        fake.script("GetSecretValue", Step.error("InternalServiceError", 500), Step.error("InternalServiceError", 500),
                Step.error("InternalServiceError", 500), Step.error("InternalServiceError", 500));

        assertThatThrownBy(() -> store.get(REF)).isInstanceOf(SecretStoreUnavailableException.class);
    }

    /** 권한 거부 메시지에는 비밀 이름이 실려 온다. 예외 메시지에 원인 본문이 따라오면 안 된다. */
    @Test
    void 권한_거부는_저장소_장애이고_이름이_메시지에_없다() {
        fake.script("GetSecretValue", Step.error("AccessDeniedException", 400));

        assertThatThrownBy(() -> store.get(REF))
                .isInstanceOf(SecretStoreUnavailableException.class)
                .hasNoCause()
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(REF).contains("causeType="));
    }

    @Test
    void 삭제_예약된_비밀은_빈손이고_경고가_남는다() {
        fake.script("GetSecretValue", Step.error("InvalidRequestException", 400));

        try (LogCaptor logs = new LogCaptor()) {
            assertThat(store.get(REF)).isEmpty();
            assertThat(fake.count("GetSecretValue")).isEqualTo(1);
            assertThat(logs.messages()).contains("auth.streamkey.secret.scheduled_for_deletion");
        }
    }

    /** 옛 이름({@code streamkey:<uuid>})은 Secrets Manager에 있을 수 없다. 묻지 않는다. */
    @Test
    void 접두_밖_이름은_원격_호출이_없다() {
        String legacy = "streamkey:0b6c0b6c-0b6c-0b6c-0b6c-0b6c0b6c0b6c";

        assertThat(store.get(legacy)).isEmpty();
        store.delete(legacy);

        assertThat(fake.requests()).isEmpty();
        assertThatThrownBy(() -> store.put(legacy, "v")).isInstanceOf(IllegalArgumentException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(legacy));
        assertThat(fake.requests()).isEmpty();
    }

    @Test
    void 지우는_시간_초과도_저장소_장애다() {
        fake.script("DeleteSecret", Step.hang(), Step.hang(), Step.hang());

        assertThatThrownBy(() -> store.delete(REF)).isInstanceOf(SecretStoreUnavailableException.class);
    }
}
