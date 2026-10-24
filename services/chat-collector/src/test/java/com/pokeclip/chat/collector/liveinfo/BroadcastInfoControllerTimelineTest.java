package com.pokeclip.chat.collector.liveinfo;

import com.pokeclip.chat.collector.Platform;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 구간 창구가 관측 줄 상한에 걸린 것을 {@code truncated}로 넘기는지(POK-462). 상한(2만 줄)을 실 PG에 심기는 무거워 저장소를 가짜로 둔다.
 * 구간 수가 상한 안이어도 줄이 잘렸으면 앞 구간의 from이 진짜 시작이 아니다.
 */
class BroadcastInfoControllerTimelineTest {

    private final BroadcastInfoStore store = mock(BroadcastInfoStore.class);
    private final BroadcastInfo row = new BroadcastInfo(Platform.CHZZK, "s", "c", Instant.parse("2026-10-25T10:00:00Z"), "t",
            List.of(), "LoL", null);

    @Test
    void 관측_줄이_잘렸으면_구간이_적어도_truncated다() {
        when(store.timelineRows("s", BroadcastInfoController.MAX_TIMELINE_ROWS))
                .thenReturn(new BroadcastInfoStore.TimelineRows(List.of(row), true));

        BroadcastTimeline timeline = new BroadcastInfoController(store).timeline("s");

        assertThat(timeline.segments()).hasSize(1);
        assertThat(timeline.truncated()).isTrue();
    }

    @Test
    void 안_잘렸으면_truncated가_아니다() {
        when(store.timelineRows("s", BroadcastInfoController.MAX_TIMELINE_ROWS))
                .thenReturn(new BroadcastInfoStore.TimelineRows(List.of(row), false));

        assertThat(new BroadcastInfoController(store).timeline("s").truncated()).isFalse();
    }
}
