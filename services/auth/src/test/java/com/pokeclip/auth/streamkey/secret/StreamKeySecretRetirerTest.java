package com.pokeclip.auth.streamkey.secret;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 폐기 정리기의 이름 셋과 실패 규칙(POK-272). 저장소 동작은 각 저장소 시험이 잰다. 여기는 부르는 쪽이다. */
class StreamKeySecretRetirerTest {

    private static final String PREFIX = "pokeclip/test/stream-key/";
    private static final String UUID = "0b6c0b6c-0b6c-0b6c-0b6c-0b6c0b6c0b6c";

    @Test
    void 옛_이름_행은_세_이름을_모두_지운다() {
        RecordingStore store = new RecordingStore();
        new StreamKeySecretRetirer(List.of(store), PREFIX).retire("streamkey:" + UUID);

        assertThat(store.deleted).containsExactly("streamkey:" + UUID, PREFIX + UUID);
    }

    @Test
    void 새_이름_행도_옛_이름_사본까지_지운다() {
        RecordingStore store = new RecordingStore();
        new StreamKeySecretRetirer(List.of(store), PREFIX).retire(PREFIX + UUID);

        assertThat(store.deleted).containsExactly(PREFIX + UUID, "streamkey:" + UUID);
    }

    @Test
    void uuid를_못_뽑으면_그_이름만_지운다() {
        RecordingStore store = new RecordingStore();
        new StreamKeySecretRetirer(List.of(store), PREFIX).retire("이상한-이름");

        assertThat(store.deleted).containsExactly("이상한-이름");
    }

    @Test
    void 모든_저장소에서_지운다() {
        RecordingStore pg = new RecordingStore();
        RecordingStore sm = new RecordingStore();
        pg.values.put(PREFIX + UUID, "v");
        sm.values.put(PREFIX + UUID, "v");

        new StreamKeySecretRetirer(List.of(pg, sm), PREFIX).retire(PREFIX + UUID);

        assertThat(pg.values).isEmpty();
        assertThat(sm.values).isEmpty();
    }

    /** 앞 저장소가 던져도 뒤 저장소를 지우고, 마지막에 처음 실패를 다시 던진다. */
    @Test
    void 한_저장소_실패가_다른_저장소_삭제를_막지_않는다() {
        RecordingStore failing = new RecordingStore();
        failing.fail = new IllegalStateException("첫 실패");
        RecordingStore healthy = new RecordingStore();
        healthy.values.put(PREFIX + UUID, "v");

        assertThatThrownBy(() -> new StreamKeySecretRetirer(List.of(failing, healthy), PREFIX).retire(PREFIX + UUID))
                .hasMessage("첫 실패");
        assertThat(healthy.values).isEmpty();
        assertThat(failing.deleted).hasSize(2);
    }

    private static final class RecordingStore implements SecretStore {
        final Map<String, String> values = new HashMap<>();
        final List<String> deleted = new ArrayList<>();
        RuntimeException fail;

        @Override
        public void put(String ref, String value) {
            values.put(ref, value);
        }

        @Override
        public Optional<String> get(String ref) {
            return Optional.ofNullable(values.get(ref));
        }

        @Override
        public void delete(String ref) {
            deleted.add(ref);
            if (fail != null) {
                throw fail;
            }
            values.remove(ref);
        }
    }
}
