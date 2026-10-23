package com.pokeclip.auth.soop;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * resolve의 NOT_LINKED 갈래는 갱신기가 <b>락 안에서</b> 잡은 마지막 행 상태를 그대로 쓴다(PR #329 봇 지적).
 * 락 밖에서 다시 읽으면 그 사이 재연동·해제가 끼어 「방금 살아난 연동을 UNLINKED로」 같은 틀린 답이 나간다.
 * 저장소를 아무것도 안 하는 mock으로 두고 상호작용이 0인지 본다(치지직 쌍둥이는 이번 범위 밖).
 */
class SoopLinkServiceResolveTest {

    private final SoopTokenRefresher refresher = mock(SoopTokenRefresher.class);
    private final SoopChannelLinkRepository links = mock(SoopChannelLinkRepository.class);
    private final SoopLinkService service = new SoopLinkService(SoopTokenRefreshSchedulerTest.testProps(),
            mock(SoopLinkStateCodec.class), mock(SoopOAuthClient.class), mock(SoopLinkWriter.class), refresher, links);

    private SoopResolveResult resolveWith(LinkStatus last) {
        when(refresher.refreshIfExpiringWithin(eq(7L), any(Duration.class)))
                .thenReturn(RefreshResult.notLinked(last));
        return service.resolve(7L);
    }

    @Test
    void 마지막_행이_BROKEN이면_BROKEN() {
        assertThat(resolveWith(LinkStatus.BROKEN).reason()).isEqualTo("BROKEN");
        verifyNoInteractions(links);
    }

    @Test
    void 마지막_행이_UNLINKED면_UNLINKED() {
        assertThat(resolveWith(LinkStatus.UNLINKED).reason()).isEqualTo("UNLINKED");
        verifyNoInteractions(links);
    }

    @Test
    void 행이_없으면_NOT_LINKED() {
        assertThat(resolveWith(null).reason()).isEqualTo("NOT_LINKED");
        verifyNoInteractions(links);
    }
}
