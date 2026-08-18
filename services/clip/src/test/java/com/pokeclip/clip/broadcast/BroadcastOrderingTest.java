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

    /**
     * {@code applyStarted}의 ENDED 보호 분기를 실행하는 유일한 시험이다(감사 1차 지적 1).
     *
     * <p>낮은 번호로는 이 분기에 못 닿는다 — 앞의 {@code sequence <= lastSequence}에서
     * 걸러진다. <b>번호가 더 높은</b> 시작이 와야 반영 경로로 들어가고, 그때 시작 시각은
     * 채우되 상태는 ENDED로 남아야 한다. 끝난 방송이 다시 LIVE가 되면 그 뒤 파이프라인이
     * 살아 있는 방송으로 오해한다.
     */
    @Test
    void 종료된_방송에_더_높은_번호의_시작이_와도_상태가_되돌아가지_않는다() {
        processor.process(Envelopes.ended("e1", "s5", 5L));

        assertThat(processor.process(Envelopes.started("e2", "s5", 7L)))
                .as("번호가 더 높으니 무시가 아니라 반영이다")
                .isEqualTo(ProcessResult.PROCESSED);

        assertThat(broadcasts.findByStreamId("s5")).get()
                .satisfies(b -> {
                    assertThat(b.getStatus())
                            .as("끝난 방송이 다시 LIVE가 되면 안 된다")
                            .isEqualTo(BroadcastStatus.ENDED);
                    assertThat(b.getStartedAt())
                            .as("뒤늦게 받은 시작 시각은 채운다")
                            .isNotNull();
                    assertThat(b.getLastSequence()).isEqualTo(7L);
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
