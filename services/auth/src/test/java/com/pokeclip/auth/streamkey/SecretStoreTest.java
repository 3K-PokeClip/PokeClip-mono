package com.pokeclip.auth.streamkey;

import com.pokeclip.auth.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SecretStoreTest extends IntegrationTestSupport {

    private final SecretStore secretStore;
    private final JdbcTemplate jdbc;

    SecretStoreTest(SecretStore secretStore, JdbcTemplate jdbc) {
        this.secretStore = secretStore;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM secrets");
    }

    @Test
    void 넣은_값을_그대로_돌려준다() {
        String ref = ref();
        secretStore.put(ref, "passphrase-32chars-여기가-원문이다");

        assertThat(secretStore.get(ref)).contains("passphrase-32chars-여기가-원문이다");
    }

    @Test
    void 없는_참조는_비어_있다() {
        assertThat(secretStore.get(ref())).isEmpty();
    }

    @Test
    void 지우면_사라진다() {
        String ref = ref();
        secretStore.put(ref, "값");
        secretStore.delete(ref);

        assertThat(secretStore.get(ref)).isEmpty();
    }

    /** 없는 참조를 지우는 것은 오류가 아니다. 재발급이 두 번 와도 터지면 안 된다. */
    @Test
    void 없는_참조를_지워도_터지지_않는다() {
        secretStore.delete(ref());
    }

    /** 표에 평문이 남으면 포트를 만든 이유가 사라진다. */
    @Test
    void 표에는_평문이_남지_않는다() {
        String ref = ref();
        String plaintext = "LEAK-" + UUID.randomUUID();
        secretStore.put(ref, plaintext);

        String stored = jdbc.queryForObject(
                "SELECT encode(ciphertext, 'escape') FROM secrets WHERE ref = ?",
                String.class, ref);

        assertThat(stored).doesNotContain(plaintext);
    }

    /**
     * GCM에서 nonce를 재사용하면 키가 사실상 깨진다. 같은 평문을 두 번 넣었을 때
     * 암호문이 같으면 nonce가 고정됐다는 뜻이다.
     */
    @Test
    void 같은_평문을_두_번_넣으면_암호문이_다르다() {
        String first = ref();
        String second = ref();
        secretStore.put(first, "같은 값");
        secretStore.put(second, "같은 값");

        byte[] a = jdbc.queryForObject(
                "SELECT ciphertext FROM secrets WHERE ref = ?", byte[].class, first);
        byte[] b = jdbc.queryForObject(
                "SELECT ciphertext FROM secrets WHERE ref = ?", byte[].class, second);

        assertThat(a).isNotEqualTo(b);
    }

    /** 같은 ref로 다시 넣으면 덮어쓴다. 재발급이 이 동작에 기댄다. */
    @Test
    void 같은_참조에_다시_넣으면_덮어쓴다() {
        String ref = ref();
        secretStore.put(ref, "옛 값");
        secretStore.put(ref, "새 값");

        assertThat(secretStore.get(ref)).contains("새 값");
    }

    private String ref() {
        return "streamkey:" + UUID.randomUUID();
    }
}
