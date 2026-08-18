package com.pokeclip.clip.broadcast;

import com.pokeclip.clip.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BroadcastEventProcessorTest extends IntegrationTestSupport {

    private final BroadcastEventProcessor processor;
    private final BroadcastRepository broadcasts;
    private final BroadcastEventRepository events;

    BroadcastEventProcessorTest(BroadcastEventProcessor processor,
                                BroadcastRepository broadcasts,
                                BroadcastEventRepository events) {
        this.processor = processor;
        this.broadcasts = broadcasts;
        this.events = events;
    }

    /**
     * 테스트 클래스들이 같은 컨텍스트·같은 DB를 공유하는데 event_id를 겹쳐 쓴다.
     * 정리가 없으면 먼저 도는 테스트가 그 번호를 선점해 뒤 테스트가 DUPLICATE를
     * 받는다 — 단독 실행은 통과하고 모듈 전체에서만 터진다
     * (plan-critic 실측 5건, services/CLAUDE.md가 경고하는 그 모양).
     */
    @BeforeEach
    void 앞_테스트의_흔적을_지운다() {
        events.deleteAllInBatch();
        broadcasts.deleteAllInBatch();
    }

    @Test
    void 같은_편지가_두_번_오면_한_번만_처리한다() {
        LifecycleEnvelope started = Envelopes.started("evt-1", "stream-A", 1L);

        assertThat(processor.process(started)).isEqualTo(ProcessResult.PROCESSED);
        assertThat(processor.process(started)).isEqualTo(ProcessResult.DUPLICATE);

        assertThat(events.findAll()).hasSize(1);
        assertThat(broadcasts.findAll()).hasSize(1);
    }

    /**
     * 이번 설계의 핵심을 지키는 시험이다. 조회 후 삽입 방식이면 둘 다 "아직 없다"로
     * 통과해 여기서 깨진다. 진 쪽이 예외로 튀어나와도 실패다 — 중복은 오류가 아니라
     * 정상 결과여야 한다.
     */
    @Test
    void 같은_편지가_동시에_와도_한_줄만_남고_진_쪽도_정상으로_끝난다() throws Exception {
        LifecycleEnvelope started = Envelopes.started("evt-dup", "stream-B", 1L);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<ProcessResult>> futures = List.of(
                    pool.submit(() -> { gate.await(); return processor.process(started); }),
                    pool.submit(() -> { gate.await(); return processor.process(started); }));
            gate.countDown();

            assertThat(List.of(futures.get(0).get(10, TimeUnit.SECONDS),
                            futures.get(1).get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(ProcessResult.PROCESSED, ProcessResult.DUPLICATE);
        } finally {
            pool.shutdownNow();
        }

        assertThat(events.findAll()).hasSize(1);
        assertThat(broadcasts.findAll()).hasSize(1);
    }

    /**
     * 중복과 저장 실패를 가르는 시험이다. 예외 타입으로 중복을 판정하면 여기서
     * DUPLICATE가 나오고, 러너가 메시지를 지워 방송 이벤트가 영구 유실된다.
     * RuntimeException으로 느슨하게 받지 않는다 — 무엇이 올라와야 하는지를 재야 한다.
     */
    @Test
    void 저장이_실패하면_중복이_아니라_예외로_올라간다() {
        LifecycleEnvelope broken = Envelopes.startedWithoutStreamer("evt-bad", "stream-C", 1L);

        assertThatThrownBy(() -> processor.process(broken))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(events.existsByEventId("evt-bad")).isFalse();
        assertThat(broadcasts.findByStreamId("stream-C")).isEmpty();
    }

    /** jsonb 왕복을 실제로 잰다. 값이 든 봉투가 아니면 이 경로가 한 번도 안 돈다. */
    @Test
    void 트랙_정보가_jsonb로_저장되고_읽힌다() {
        processor.process(Envelopes.startedWithManifest("evt-m", "stream-D", 1L));

        assertThat(broadcasts.findByStreamId("stream-D")).isPresent().get()
                .satisfies(b -> assertThat(b.getTrackManifest()).contains("manifestVersion"));
    }
}
