package com.pokeclip.auth.chzzk;

import com.pokeclip.auth.streamkey.secret.SecretStore;
import com.pokeclip.auth.user.ActiveUserGuard;
import com.pokeclip.auth.user.UserRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 커밋 뒤 정리 잡의 순서 — secrets 삭제가 던져도(SecretStore가 원격 구현으로 바뀌면 흔하다) 옛 토큰 revoke는
 * 반드시 시도한다. 통합으로는 SecretStore를 실패시킬 수 없어 잡 본문을 단위로 잰다.
 */
class ChzzkLinkWriterTest {

    private final SecretStore secretStore = mock(SecretStore.class);
    private final ChzzkTokenDiscarder discarder = mock(ChzzkTokenDiscarder.class);
    private final ChzzkLinkWriter writer = new ChzzkLinkWriter(mock(ChzzkChannelLinkRepository.class), secretStore,
            mock(UserRepository.class), mock(ActiveUserGuard.class), discarder,
            mock(ChzzkCleanupExecutor.class));

    @Test
    void secrets_삭제가_던져도_옛_토큰_revoke는_시도하고_예외는_올린다() {
        doThrow(new IllegalStateException("저장소 장애")).when(secretStore).delete(anyString());

        assertThatThrownBy(() -> writer.cleanupOld(7L, "ref-a", "ref-r", "at-old", "rt-old", "auth.chzzk.link.unlinked"))
                .isInstanceOf(IllegalStateException.class);
        verify(discarder).discard(7L, "at-old", "rt-old");
    }
}
