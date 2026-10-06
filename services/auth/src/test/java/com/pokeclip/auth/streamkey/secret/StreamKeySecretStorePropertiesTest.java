package com.pokeclip.auth.streamkey.secret;

import com.pokeclip.auth.streamkey.secret.StreamKeySecretStoreProperties.Type;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 설정이 틀리면 부팅이 선다(POK-272, 1번 설계 8-A 7) 「프로퍼티 검사 → 부팅 실패」). */
class StreamKeySecretStorePropertiesTest {

    @Test
    void 기본값과_dev_값은_통과한다() {
        StreamKeySecretStoreProperties local = bind(Map.of());
        assertThat(local.type()).isEqualTo(Type.POSTGRES);
        assertThat(local.retireFrom()).containsExactly(Type.POSTGRES);
        assertThat(local.usesAws()).isFalse();

        StreamKeySecretStoreProperties dev = bind(Map.of(
                "type", "aws", "retire-from", "postgres,aws", "ref-prefix", "pokeclip/dev/stream-key/"));
        assertThat(dev.type()).isEqualTo(Type.AWS);
        assertThat(dev.retireFrom()).containsExactlyInAnyOrder(Type.POSTGRES, Type.AWS);
        assertThat(dev.usesAws()).isTrue();
    }

    @Test
    void 병행_중_postgres_상태도_aws를_만든다() {
        assertThat(bind(Map.of("retire-from", "postgres,aws")).usesAws()).isTrue();
    }

    @Test
    void 저장소_종류가_틀리면_선다() {
        assertThatThrownBy(() -> bind(Map.of("type", "redis"))).isInstanceOf(BindException.class);
    }

    @Test
    void 접두가_슬래시로_안_끝나면_선다() {
        assertThatThrownBy(() -> props("pokeclip/dev/stream-key", Set.of(Type.POSTGRES)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ref-prefix");
    }

    /** 콜론은 Secrets Manager 이름에 못 쓴다. 옛 이름 모양({@code streamkey:})을 접두로 두면 안 된다. */
    @Test
    void 접두에_이름_규칙_밖_글자가_있으면_선다() {
        assertThatThrownBy(() -> props("streamkey:/", Set.of(Type.POSTGRES)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ref-prefix");
        assertThatThrownBy(() -> props("pokeclip/dev stream/", Set.of(Type.POSTGRES)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 접두가_219자를_넘으면_선다() {
        String ok = "a".repeat(218) + "/";
        String tooLong = "a".repeat(219) + "/";

        assertThat(props(ok, Set.of(Type.POSTGRES)).refPrefix()).hasSize(219);
        assertThatThrownBy(() -> props(tooLong, Set.of(Type.POSTGRES)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("219");
    }

    @Test
    void 정리_대상이_비었거나_저장소_종류를_빠뜨리면_선다() {
        assertThatThrownBy(() -> props("p/", Set.of()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("retire-from");
        assertThatThrownBy(() -> new StreamKeySecretStoreProperties(Type.AWS, "p/", Set.of(Type.POSTGRES), null, "r"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("retire-from");
    }

    private static StreamKeySecretStoreProperties props(String prefix, Set<Type> retireFrom) {
        return new StreamKeySecretStoreProperties(Type.POSTGRES, prefix, retireFrom, null, "ap-northeast-2");
    }

    /** yml 기본값과 같은 값을 깔고 덮어쓴다. 바인딩 경로(소문자 enum·쉼표 목록)까지 잰다. */
    private static StreamKeySecretStoreProperties bind(Map<String, String> overrides) {
        Map<String, String> values = new java.util.HashMap<>(Map.of(
                "type", "postgres", "retire-from", "postgres", "ref-prefix", "pokeclip/local/stream-key/",
                "region", "ap-northeast-2"));
        values.putAll(overrides);
        Map<String, String> prefixed = new java.util.HashMap<>();
        values.forEach((k, v) -> prefixed.put("pokeclip.stream-key-secret-store." + k, v));
        return new Binder(new MapConfigurationPropertySource(prefixed))
                .bindOrCreate("pokeclip.stream-key-secret-store", StreamKeySecretStoreProperties.class);
    }
}
