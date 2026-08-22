package com.pokeclip.clip.jumpcard;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.jumpcard.JumpCardErrors.BroadcastNotFoundException;
import com.pokeclip.clip.jumpcard.JumpCardService.RecordResult;
import com.pokeclip.clip.jumpcard.api.HighlightRequest;
import com.pokeclip.clip.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 저장의 규칙을 잰다 — 처음이면 만들고, 같은 창이 다시 오면 기존 카드를 돌려준다.
 *
 * <p>중복 판정을 DB가 한다는 것이 이 설계의 핵심이다. 조회 후 삽입이면
 * {@code 같은_창이_동시에…}가 깨진다.
 */
class JumpCardServiceTest extends IntegrationTestSupport {

    // static 메서드(auto)가 쓰므로 인스턴스 필드로 두면 컴파일되지 않는다.
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JumpCardService service;
    private final JumpCardRepository cards;
    private final BroadcastRepository broadcasts;
    private final JdbcTemplate jdbc;

    JumpCardServiceTest(JumpCardService service, JumpCardRepository cards,
                        BroadcastRepository broadcasts, JdbcTemplate jdbc) {
        this.service = service;
        this.cards = cards;
        this.broadcasts = broadcasts;
        this.jdbc = jdbc;
    }

    /** 카드가 broadcasts의 자식이라 카드를 먼저 지운다. 안 지우면 다음 정리가 FK로 죽는다. */
    @BeforeEach
    void 정리() {
        jdbc.update("DELETE FROM jump_cards");
        broadcasts.deleteAllInBatch();
        broadcasts.save(Broadcast.startedNow("s-1", "u-1", 1L, Instant.now(), null));
    }

    private static HighlightRequest auto(String eventId, long start) {
        return new HighlightRequest(eventId, "auto", start + 23_000L,
                new HighlightRequest.Window(start, start + 42_000L), 97,
                MAPPER.readTree("{\"multiplier\":4.2}"));
    }

    @Test
    void 처음_보면_만들고_created가_참이다() {
        RecordResult result = service.record("s-1", auto("evt-1", 5_020_000L));

        assertThat(result.created()).isTrue();
        assertThat(result.card().eventSeq()).isPositive();
        assertThat(result.card().source()).isEqualTo(JumpCardSource.AUTO);
        assertThat(result.card().streamTimestampMs()).isEqualTo(5_043_000L);
    }

    @Test
    void 같은_창이_다시_오면_기존_카드를_돌려주고_행은_하나다() {
        RecordResult first = service.record("s-1", auto("evt-1", 5_020_000L));
        RecordResult again = service.record("s-1", auto("evt-재전송", 5_020_000L));

        assertThat(again.created()).isFalse();
        assertThat(again.card().id()).isEqualTo(first.card().id());
        assertThat(cards.findAll()).as("정확히 한 줄이어야 한다").hasSize(1);
    }

    /**
     * 조회 후 삽입 방식이면 둘 다 "아직 없다"로 통과해 여기서 깨진다.
     *
     * <p><b>이 단언은 두 스레드가 직렬로 돌아도 통과한다</b>(POK-82와 같은 한계).
     * 겹쳤다는 증거는 결함 주입뿐이고 그 기록은 progress에 있다.
     */
    @Test
    void 같은_창이_동시에_와도_하나만_만들어지고_둘_다_정상으로_끝난다() throws Exception {
        List<RecordResult> results = 동시에(
                () -> service.record("s-1", auto("a", 5_020_000L)),
                () -> service.record("s-1", auto("b", 5_020_000L)));

        assertThat(results).extracting(RecordResult::created).containsExactlyInAnyOrder(true, false);
        assertThat(cards.findAll()).hasSize(1);
    }

    /** FK 위반이 아니라 404가 나가야 판별기가 재시도 상한을 셀 수 있다. */
    @Test
    void 없는_방송이면_BroadcastNotFound다() {
        assertThatThrownBy(() -> service.record("s-없음", auto("e", 1_000L)))
                .isInstanceOf(BroadcastNotFoundException.class);
        assertThat(cards.findAll()).isEmpty();
    }

    /** 종료 이벤트가 먼저 도착해도 판별기의 마지막 카드가 버려지면 안 된다. */
    @Test
    void 끝난_방송에도_들어간다() {
        broadcasts.save(Broadcast.endedPlaceholder("s-ended", "u-1", 9L, Instant.now()));

        assertThat(service.record("s-ended", auto("e", 1_000L)).created()).isTrue();
    }

    @Test
    void evidence와_score가_없어도_들어간다() {
        RecordResult result = service.record("s-1", new HighlightRequest("e", "auto", 1_500L,
                new HighlightRequest.Window(1_000L, 2_000L), null, null));

        assertThat(result.created()).isTrue();
        assertThat(result.card().score()).isNull();
        assertThat(result.card().evidence()).isNull();
    }

    @Test
    void snapshotsOf는_순번_순이고_숨긴_것도_포함한다() {
        long a = service.record("s-1", auto("a", 1_000L)).card().id();
        long b = service.record("s-1", auto("b", 9_000L)).card().id();
        // a가 뒤에 바뀌었으니 순번이 b보다 크다 — 정렬 기준이 id가 아니라 event_seq임을 잰다.
        jdbc.update("UPDATE jump_cards SET hidden_at = now(), hidden_by = 'u-2' WHERE id = ?", a);

        List<JumpCardSnapshot> snapshots = service.snapshotsOf("s-1");

        assertThat(snapshots).extracting(JumpCardSnapshot::id).containsExactly(b, a);
        assertThat(snapshots.get(1).hidden()).isTrue();
        assertThat(snapshots.get(1).hiddenBy()).isEqualTo("u-2");
    }

    /**
     * 둘을 같은 출발선에 세워 동시에 보낸다. 예외도 결과로 모은다 — 던져 버리면
     * "누가 이겼나"를 못 보고 시험이 원인 대신 증상만 남긴다.
     */
    @SafeVarargs
    private <T> List<T> 동시에(Callable<T>... tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.length);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    gate.await();
                    return task.call();
                }));
            }
            gate.countDown();
            List<T> results = Collections.synchronizedList(new ArrayList<>());
            for (Future<T> future : futures) {
                results.add(future.get(10, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
