package com.pokeclip.auth.streamkey.secret;

import com.pokeclip.auth.support.SecretsLocalStackFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;

import static com.pokeclip.auth.support.SecretsLocalStackFixture.PREFIX;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Secrets Manager 저장소의 기본 동작을 LocalStack에 대고 잰다(POK-272, 1번 설계 8-A 7)). 지연·실패 갈래는
 * {@link SecretsManagerDeadlineTest}가 가짜 서버로 잰다. LocalStack은 그것을 못 만든다.
 *
 * <p>쓴 결과는 저장소가 아니라 픽스처의 직접 클라이언트로 확인한다.
 */
class SecretsManagerSecretStoreTest {

    private static SecretsManagerSecretStore store;

    @BeforeAll
    static void start() {
        StreamKeySecretStoreProperties p = new StreamKeySecretStoreProperties(
                StreamKeySecretStoreProperties.Type.AWS, PREFIX, Set.of(StreamKeySecretStoreProperties.Type.AWS),
                SecretsLocalStackFixture.endpoint(), SecretsLocalStackFixture.region());
        store = new SecretsManagerSecretStore(SecretsManagerClients.forRequests(p), PREFIX, Clock.systemUTC());
    }

    @AfterAll
    static void stop() {
        store.close();
    }

    @Test
    void 새로_넣으면_그_이름의_비밀이_생긴다() {
        String ref = ref();
        store.put(ref, "token:passphrase");

        assertThat(SecretsLocalStackFixture.read(ref)).contains("token:passphrase");
        assertThat(store.get(ref)).contains("token:passphrase");
    }

    @Test
    void 같은_이름에_다시_넣으면_덮어쓴다() {
        String ref = ref();
        store.put(ref, "old");
        store.put(ref, "new");

        assertThat(SecretsLocalStackFixture.read(ref)).contains("new");
    }

    @Test
    void 없는_비밀을_지워도_터지지_않는다() {
        store.delete(ref());
    }

    @Test
    void 지운_뒤에는_빈손이다() {
        String ref = ref();
        store.put(ref, "v");
        store.delete(ref);

        assertThat(store.get(ref)).isEmpty();
        assertThat(SecretsLocalStackFixture.read(ref)).isEmpty();
    }

    @Test
    void 없는_비밀은_빈손이다() {
        assertThat(store.get(ref())).isEmpty();
    }

    private static String ref() {
        return PREFIX + UUID.randomUUID();
    }
}
