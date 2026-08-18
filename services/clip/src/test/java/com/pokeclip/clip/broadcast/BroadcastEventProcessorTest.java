package com.pokeclip.clip.broadcast;

import com.pokeclip.clip.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.ArrayList;
import java.util.Collections;
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
     * 같은 방송에 <b>다른</b> 편지 둘이 동시에 온다. event_id가 달라 ON CONFLICT는 둘 다
     * 통과시키므로, 여기서 줄이 둘 생기는 것을 막는 방어선은 {@code uq_broadcasts_stream_id}
     * 하나다 — {@code findByStreamIdForUpdate}는 행이 없으면 잠글 대상이 없다.
     * PRD가 명시한 방어인데 재는 시험이 없었다(감사 1차 지적 1).
     *
     * <p>겹치느냐 아니냐로 결과가 갈리므로 <b>양쪽에서 성립하는 것</b>만 단언한다.
     * 겹치면 한쪽이 UNIQUE 위반으로 지고, 안 겹치면 둘 다 성공한다. 어느 쪽이든
     * 명부는 한 줄이고, <b>편지 기록 수는 성공한 처리 수와 같아야 한다</b> —
     * 진 쪽 기록이 함께 되감기지 않으면 "기록은 남았는데 명부엔 반영 안 된" 줄이
     * 생기고, 재전송이 와도 중복으로 걸러져 영영 반영되지 않는다.
     *
     * <p>진 쪽 예외가 {@code DataIntegrityViolationException}인 것까지 못 박는다 —
     * 러너가 그것을 "처리 실패"로 보고 메시지를 남기는 것이
     * {@code SqsIntakeRunnerTest.처리에_실패하면_편지를_지우지_않는다}에서 닫힌다.
     */
    @Test
    void 같은_방송에_다른_편지_둘이_동시에_와도_명부는_한_줄이고_기록이_어긋나지_않는다() throws Exception {
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<?>> futures = List.of(
                    pool.submit(() -> process(gate, Envelopes.started("evt-A", "stream-X", 1L), failures)),
                    pool.submit(() -> process(gate, Envelopes.ended("evt-B", "stream-X", 2L), failures)));
            gate.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(failures)
                .as("설계가 예상한 패배는 stream_id UNIQUE 위반뿐이다")
                .allSatisfy(e -> assertThat(e).isInstanceOf(DataIntegrityViolationException.class));
        assertThat(broadcasts.findAll()).as("같은 방송에 줄이 둘 생기면 안 된다").hasSize(1);
        assertThat(events.findAll())
                .as("진 쪽 편지 기록이 명부와 함께 되감기지 않았다")
                .hasSize(2 - failures.size());
    }

    private void process(CountDownLatch gate, LifecycleEnvelope envelope, List<Throwable> failures) {
        try {
            gate.await();
            processor.process(envelope);
        } catch (RuntimeException e) {
            failures.add(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
                .satisfies(b -> assertThat(b.getTrackManifest())
                        .contains("manifestVersion")
                        .contains("tracks")
                        // contains만으로는 봉투 payload를 통째로 담아도 통과한다(감사 1차 지적 3).
                        // 저장돼야 하는 것은 trackManifest "안쪽"이라 그 키 자체는 없어야 한다.
                        .as("payload를 통째로 담았다 — trackManifest 노드 안쪽만 담아야 한다")
                        .doesNotContain("trackManifest"));
    }
}
