package com.pokeclip.clip.broadcast;

import ch.qos.logback.classic.Level;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BroadcastOrderingTest extends IntegrationTestSupport {

    private final BroadcastEventProcessor processor;
    private final BroadcastRepository broadcasts;
    private final BroadcastEventRepository events;

    BroadcastOrderingTest(BroadcastEventProcessor processor, BroadcastRepository broadcasts,
                          BroadcastEventRepository events) {
        this.processor = processor;
        this.broadcasts = broadcasts;
        this.events = events;
    }

    /** 이 클래스의 테스트 넷이 e1·e2를 재사용한다. 정리가 없으면 서로를 막는다. */
    @BeforeEach
    void 앞_테스트의_흔적을_지운다() {
        events.deleteAllInBatch();
        broadcasts.deleteAllInBatch();
    }

    @Test
    void 정상_순서면_live_였다가_ended가_된다() {
        processor.process(Envelopes.started("e1", "s1", 1L));
        assertThat(broadcasts.findByStreamId("s1")).get()
                .satisfies(b -> assertThat(b.getStatus()).isEqualTo(BroadcastStatus.LIVE));

        processor.process(Envelopes.ended("e2", "s1", 2L));
        assertThat(broadcasts.findByStreamId("s1")).get()
                .satisfies(b -> {
                    assertThat(b.getStatus()).isEqualTo(BroadcastStatus.ENDED);
                    assertThat(b.getEndedAt()).isNotNull();
                    assertThat(b.getStartedAt()).isNotNull();
                    assertThat(b.getLastSequence()).isEqualTo(2L);
                });
    }

    @Test
    void 종료가_먼저_와도_죽지_않고_빈_줄이_생기며_경고가_남는다() {
        try (LogCaptor captor = new LogCaptor()) {
            processor.process(Envelopes.ended("e1", "s2", 5L));

            // 실물 API는 messages()·levelOf(String)이다 — forClass()·warnMessages()는 없다.
            // levelOf는 String이 아니라 logback Level을 준다.
            assertThat(captor.messages()).anyMatch(m -> m.contains("ended_before_started"));
            assertThat(captor.levelOf("broadcast.ended_before_started")).isEqualTo(Level.WARN);
        }

        assertThat(broadcasts.findByStreamId("s2")).get()
                .satisfies(b -> {
                    assertThat(b.getStatus()).isEqualTo(BroadcastStatus.ENDED);
                    assertThat(b.getStartedAt()).as("시작 시각을 모르는 placeholder다").isNull();
                    assertThat(b.getEndedAt()).isNotNull();
                    assertThat(b.getLastSequence()).isEqualTo(5L);
                });
    }

    @Test
    void 종료_뒤에_낮은_번호의_시작이_와도_되돌아가지_않는다() {
        processor.process(Envelopes.ended("e1", "s3", 5L));

        assertThat(processor.process(Envelopes.started("e2", "s3", 3L)))
                .isEqualTo(ProcessResult.IGNORED_STALE);

        assertThat(broadcasts.findByStreamId("s3")).get()
                .satisfies(b -> {
                    assertThat(b.getStatus()).isEqualTo(BroadcastStatus.ENDED);
                    assertThat(b.getStartedAt()).isNull();
                    assertThat(b.getLastSequence()).as("낮은 번호로 덮이지 않는다").isEqualTo(5L);
                });
    }

    @Test
    void 낡은_편지여도_받은_기록에는_남는다() {
        processor.process(Envelopes.ended("e1", "s4", 5L));
        processor.process(Envelopes.started("e2", "s4", 3L));

        // 같은 낡은 편지가 또 와도 판정을 다시 하지 않는다.
        assertThat(processor.process(Envelopes.started("e2", "s4", 3L)))
                .isEqualTo(ProcessResult.DUPLICATE);
    }
}
