package com.pokeclip.auth.streamername;

import com.pokeclip.auth.chzzk.ChzzkChannelLinkRepository;
import com.pokeclip.auth.soop.SoopChannelLinkRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 번호 N개에 표당 한 번만 묻는가(N+1 아님). auth 시험에는 질의 수를 세는 장치가 없어, 저장소를 목으로 두고
 * 호출 수와 받은 번호를 직접 본다. 스프링 컨텍스트를 띄우지 않는다.
 */
class StreamerChannelNameServiceTest {

    private final ChzzkChannelLinkRepository chzzk = mock(ChzzkChannelLinkRepository.class);
    private final SoopChannelLinkRepository soop = mock(SoopChannelLinkRepository.class);
    private final StreamerChannelNameService service = new StreamerChannelNameService(chzzk, soop);

    @Test
    @SuppressWarnings("unchecked")
    void 번호_셋에_표마다_한_번씩_번호_전부로_묻는다() {
        when(chzzk.findAliveNames(any())).thenReturn(List.of(chzzkName(1L, "가")));
        when(soop.findAliveNames(any())).thenReturn(List.of(soopName(3L, "다")));

        Map<Long, StreamerChannelNames> out = service.namesOf(List.of(1L, 2L, 3L));

        ArgumentCaptor<Collection<Long>> c = ArgumentCaptor.forClass(Collection.class);
        verify(chzzk, times(1)).findAliveNames(c.capture());
        assertThat(c.getValue()).containsExactlyInAnyOrder(1L, 2L, 3L);
        ArgumentCaptor<Collection<Long>> s = ArgumentCaptor.forClass(Collection.class);
        verify(soop, times(1)).findAliveNames(s.capture());
        assertThat(s.getValue()).containsExactlyInAnyOrder(1L, 2L, 3L);

        assertThat(out).containsOnlyKeys(1L, 2L, 3L);
        assertThat(out.get(1L)).isEqualTo(new StreamerChannelNames("가", null));
        assertThat(out.get(2L)).isEqualTo(StreamerChannelNames.NONE);
        assertThat(out.get(3L)).isEqualTo(new StreamerChannelNames(null, "다"));
    }

    /** JPQL `IN ()` 빈 컬렉션 처리를 믿지 않는다: 아예 안 묻는다. */
    @Test
    void 빈_목록이면_묻지_않는다() {
        assertThat(service.namesOf(List.of())).isEmpty();
        verify(chzzk, never()).findAliveNames(any());
        verify(soop, never()).findAliveNames(any());
    }

    private static ChzzkChannelLinkRepository.AliveName chzzkName(Long id, String name) {
        return new ChzzkChannelLinkRepository.AliveName() {
            public Long getUserId() { return id; }
            public String getName() { return name; }
        };
    }

    private static SoopChannelLinkRepository.AliveName soopName(Long id, String name) {
        return new SoopChannelLinkRepository.AliveName() {
            public Long getUserId() { return id; }
            public String getName() { return name; }
        };
    }
}
