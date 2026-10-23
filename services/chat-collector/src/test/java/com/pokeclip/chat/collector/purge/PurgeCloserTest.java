package com.pokeclip.chat.collector.purge;

import com.pokeclip.chat.collector.Platform;
import com.pokeclip.chat.collector.session.SessionRegistry;
import com.pokeclip.chat.collector.soop.SoopWatch;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 탈퇴 정리의 「걷기 멈추기」가 세션 등록부와 SOOP 지켜보기 <b>둘 다</b> 닫는다(계획 검증 M3). 지켜보기는 세션 자리 밖이라 등록부만 닫으면
 * 탈퇴 뒤에도 SOOP 토큰이 만료(약 8시간)까지 살아 정보 줄을 쓴다(SOOP에는 폐기 API가 없다).
 */
class PurgeCloserTest {

    @Test
    void 등록부와_SOOP_지켜보기를_같이_닫는다() {
        SessionRegistry registry = mock(SessionRegistry.class);
        List<String> closed = new ArrayList<>();
        SoopWatch soop = new SoopWatch() {
            @Override
            public void watch(String streamId, long streamerId, Instant startedAt) {
            }

            @Override
            public void unwatch(String streamId) {
            }

            @Override
            public void unwatchChannel(Platform platform, String channelId, Instant startedBefore) {
                closed.add(platform + ":" + channelId + ":" + startedBefore);
            }

            @Override
            public void retainListed(Set<String> listedStreamIds, Instant startedBefore) {
            }
        };
        Instant before = Instant.parse("2026-10-24T00:00:00Z");

        ChatPurgeConfiguration.stopCollecting(registry, soop).close(Platform.SOOP, "soop-id", before);

        verify(registry).closeChannel(Platform.SOOP, "soop-id", before);
        assertThat(closed).containsExactly("SOOP:soop-id:" + before);
    }
}
