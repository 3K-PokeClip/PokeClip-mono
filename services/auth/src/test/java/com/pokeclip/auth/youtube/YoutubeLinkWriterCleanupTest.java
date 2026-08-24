package com.pokeclip.auth.youtube;

import com.pokeclip.auth.streamkey.secret.SecretStore;
import com.pokeclip.auth.user.UserRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 커밋 뒤 정리 잡의 본문 — 통합으로는 SecretStore를 실패시킬 수 없어 단위로 잰다.
 *
 * <p>여기서 갈리는 것이 계획 2절 결정 8의 두 갈래다: 사용자 해제는 옛 토큰 원문을 들고 와 revoke하고,
 * 재연동은 원문 자체를 안 들고 와서(null) revoke가 없다.
 */
class YoutubeLinkWriterCleanupTest {

    private final SecretStore secretStore = mock(SecretStore.class);
    private final YoutubeTokenDiscarder discarder = mock(YoutubeTokenDiscarder.class);
    private final YoutubeLinkWriter writer = new YoutubeLinkWriter(mock(YoutubeChannelLinkRepository.class),
            secretStore, mock(UserRepository.class), discarder, mock(YoutubeCleanupExecutor.class));

    @Test
    void secrets_삭제가_던져도_옛_토큰_revoke는_시도하고_예외는_올린다() {
        doThrow(new IllegalStateException("저장소 장애")).when(secretStore).delete(anyString());

        assertThatThrownBy(() -> writer.cleanupOld(7L, "ref-a", "ref-r", "at-old", "rt-old",
                "auth.youtube.link.unlinked")).isInstanceOf(IllegalStateException.class);

        verify(discarder).discard(7L, "at-old", "rt-old");
    }

    /**
     * 재연동 갈래 — 옛 토큰 원문이 없으면(null) revoke를 부르지 않는다.
     * 부르면 구글이 <b>방금 저장한 새 토큰까지</b> 죽인다.
     */
    @Test
    void 옛_토큰_원문이_없으면_revoke를_부르지_않는다() {
        writer.cleanupOld(7L, "ref-a", "ref-r", null, null, "auth.youtube.link.relinked");

        verify(secretStore).delete("ref-a");
        verify(secretStore).delete("ref-r");
        verify(discarder, never()).discard(any(), any(), any());
    }
}
