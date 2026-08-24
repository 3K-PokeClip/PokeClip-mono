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
import static org.mockito.Mockito.verifyNoInteractions;

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

        assertThatThrownBy(() -> writer.cleanupOld(7L, "UC-a", "ref-a", "ref-r", "at-old", "rt-old",
                "auth.youtube.link.unlinked")).isInstanceOf(IllegalStateException.class);

        // 진입점은 조건부인 쪽이다 — 이 잡이 큐에서 밀린 사이 재연동이 끝났으면 버리면 안 된다(감사 3라운드 중대-1).
        // 채널도 넘긴다 — 그 채널을 남이 쓰고 있으면 같은 구글 계정이라 버리면 남의 연동이 끊긴다(봇 PR #116).
        // 「조건이 실제로 갈린다」는 YoutubeDiscardGuardTest가 실물 배선으로 잰다.
        verify(discarder).discardIfNoLiveLink(7L, "UC-a", "at-old", "rt-old");
        verify(discarder, never()).discard(any(), any(), any());
    }

    /**
     * 🔴 access 삭제가 던져도 refresh 삭제를 <b>시도한다</b>. 한 try로 묶으면 첫 실패가 둘째를 건너뛰어
     * 그 비밀이 secrets에 영구히 남는다 — 잡은 예외는 로그만 남고 재시도가 없다(봇 리뷰 PR #116).
     */
    @Test
    void access_삭제가_던져도_refresh_삭제를_건너뛰지_않는다() {
        doThrow(new IllegalStateException("저장소 장애")).when(secretStore).delete("ref-a");

        assertThatThrownBy(() -> writer.cleanupOld(7L, "UC-a", "ref-a", "ref-r", "at-old", "rt-old",
                "auth.youtube.link.unlinked")).isInstanceOf(IllegalStateException.class);

        verify(secretStore).delete("ref-a");
        verify(secretStore).delete("ref-r");   // ← 건너뛰면 refresh 원문이 남는다
    }

    /**
     * 재연동 갈래 — 옛 토큰 원문이 없으면(null) revoke를 부르지 않는다.
     * 부르면 구글이 <b>방금 저장한 새 토큰까지</b> 죽인다.
     */
    @Test
    void 옛_토큰_원문이_없으면_revoke를_부르지_않는다() {
        writer.cleanupOld(7L, "UC-a", "ref-a", "ref-r", null, null, "auth.youtube.link.relinked");

        verify(secretStore).delete("ref-a");
        verify(secretStore).delete("ref-r");
        verifyNoInteractions(discarder);
    }
}
