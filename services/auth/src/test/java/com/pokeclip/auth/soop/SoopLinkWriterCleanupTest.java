package com.pokeclip.auth.soop;

import com.pokeclip.auth.streamkey.secret.SecretStore;
import com.pokeclip.auth.user.ActiveUserGuard;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 커밋 뒤 정리 잡의 본문. 통합으로는 SecretStore를 실패시킬 수 없어 단위로 잰다
 * ({@code YoutubeLinkWriterCleanupTest}의 쌍둥이, 감사 1회차 중대 1).
 *
 * <p>운영 경로를 그대로 탄다: 해제 → 닫기 → afterCommit 제출 → 실제 {@link SoopCleanupExecutor} 스레드.
 * 트랜잭션 동기화를 손으로 열고 afterCommit을 직접 발화한다(스프링 컨텍스트 없음).
 */
class SoopLinkWriterCleanupTest {

    private final SoopChannelLinkRepository links = mock(SoopChannelLinkRepository.class);
    private final SecretStore secretStore = mock(SecretStore.class);
    private final UserRepository users = mock(UserRepository.class);
    private final SoopCleanupExecutor cleanup = new SoopCleanupExecutor();
    private final SoopLinkWriter writer = new SoopLinkWriter(links, secretStore, users, mock(ActiveUserGuard.class), cleanup);

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        cleanup.shutdown();
    }

    /** 살아 있는 연동 하나(ref-a·ref-r)를 둔 채 해제하고, 커밋을 흉내 내 정리 잡을 돌린 뒤 끝날 때까지 기다린다. */
    private void unlinkAndCommit() {
        when(users.findByIdForUpdate(7L)).thenReturn(Optional.of(mock(User.class)));
        when(links.findByUserIdAndRevokedAtIsNull(7L)).thenReturn(Optional.of(SoopChannelLink.of(
                7L, "soop-x", "닉네임", null, "ref-a", "ref-r", Instant.now().plus(Duration.ofHours(8)), Instant.now())));
        TransactionSynchronizationManager.initSynchronization();
        writer.revoke(7L, Instant.now());
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCommit();
        }
        assertThat(cleanup.awaitIdle(Duration.ofSeconds(5))).isTrue();
    }

    /**
     * 🔴 access 삭제가 던져도 refresh 삭제를 <b>시도한다</b>. 한 흐름으로 두면 첫 실패가 둘째를 건너뛰어 refresh
     * 원문(가장 오래 사는 비밀)이 secrets에 영구히 남는다. 잡은 예외를 로그로만 남기고 재시도가 없다.
     */
    @Test
    void access_삭제가_던져도_refresh_삭제를_건너뛰지_않는다() {
        doThrow(new IllegalStateException("저장소 장애")).when(secretStore).delete("ref-a");

        unlinkAndCommit();

        verify(secretStore).delete("ref-a");
        verify(secretStore).delete("ref-r");   // ← 건너뛰면 refresh 원문이 남는다
    }

    /** 정상 갈래: 둘 다 지운다. */
    @Test
    void 둘_다_지운다() {
        unlinkAndCommit();

        verify(secretStore).delete("ref-a");
        verify(secretStore).delete("ref-r");
    }
}
