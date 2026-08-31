package com.pokeclip.auth.youtube;

import com.pokeclip.auth.streamkey.secret.SecretStore;
import com.pokeclip.auth.user.ActiveUserGuard;
import com.pokeclip.auth.user.UserRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 커밋 뒤 정리 잡의 본문 — 통합으로는 SecretStore를 실패시킬 수 없어 단위로 잰다.
 *
 * <p>여기서 갈리는 것이 계획 2절 결정 8의 두 갈래다: 사용자 해제는 옛 토큰 원문을 들고 와 revoke하고,
 * 재연동은 원문 자체를 안 들고 와서(null) revoke가 없다.
 */
class YoutubeLinkWriterCleanupTest {

    private final SecretStore secretStore = mock(SecretStore.class);
    private final YoutubeLinkWriter writer = new YoutubeLinkWriter(mock(YoutubeChannelLinkRepository.class),
            secretStore, mock(UserRepository.class), mock(ActiveUserGuard.class),
            mock(YoutubeCleanupExecutor.class));

    /**
     * 🔴 access 삭제가 던져도 refresh 삭제를 <b>시도한다</b>. 한 try로 묶으면 첫 실패가 둘째를 건너뛰어
     * 그 비밀이 secrets에 영구히 남는다 — 잡은 예외는 로그만 남고 재시도가 없다(봇 리뷰 PR #116).
     *
     * <p>구글 호출은 여기서 재지 않는다 — <b>이 잡은 이제 구글에 아무것도 보내지 않는다</b>
     * (해제도 revoke를 안 한다, {@code YoutubeLinkWriter.closeAlive} javadoc).
     */
    @Test
    void access_삭제가_던져도_refresh_삭제를_건너뛰지_않는다() {
        doThrow(new IllegalStateException("저장소 장애")).when(secretStore).delete("ref-a");

        assertThatThrownBy(() -> writer.cleanupOld(7L, "ref-a", "ref-r", "auth.youtube.link.unlinked"))
                .isInstanceOf(IllegalStateException.class);

        verify(secretStore).delete("ref-a");
        verify(secretStore).delete("ref-r");   // ← 건너뛰면 refresh 원문이 남는다
    }

    /** 정상 갈래 — 둘 다 지우고 「정리까지 끝났다」 로그를 남긴다. */
    @Test
    void 둘_다_지우면_정리_로그를_남긴다() {
        writer.cleanupOld(7L, "ref-a", "ref-r", "auth.youtube.link.relinked");

        verify(secretStore).delete("ref-a");
        verify(secretStore).delete("ref-r");
    }
}
