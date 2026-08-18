package com.pokeclip.chat.collector.broadcast;

import ch.qos.logback.classic.Level;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 편지 하나를 어떻게 판정하는지를 잰다. <b>판정값 넷의 뜻은 러너의 삭제 기준이다</b> —
 * {@code PROCESSED}·{@code IGNORED_STALE}·{@code UNREADABLE}은 지우고
 * {@code RETRY_LATER}만 큐에 남긴다. 그래서 "이 편지를 다시 받아야 하는가"가 판정 기준이다.
 *
 * <p><b>끝난 방송 표는 진짜 PostgreSQL로 잰다.</b> 종료 편지의 멱등이 {@code ON CONFLICT}에
 * 걸려 있어서다 — 가짜 저장소로 바꾸면 그 방어선이 검사에서 사라진다.
 *
 * <p><b>다중 세션 문항(multi-session-test-reality)</b> — 이 판정기는 세션을 열지 않는다
 * (배선은 태스크 10). 문항 1(세션 하나로 돌려도 통과하는가)·문항 3(의도한 동시성이 환경에
 * 막히는가)은 <b>잴 대상이 없어</b> 해당하지 않는다. 재 보지 않은 것이 아니다.
 * 문항 2·4·5는 검사마다 주석으로 답을 남겼다.
 */
@SpringBootTest
@ActiveProfiles("test")
class BroadcastEventProcessorTest extends IntegrationTestSupport {

    private static final Instant 종료시각 = Instant.parse("2026-08-18T10:00:00Z");

    private final EndedStreamStore store;
    private final JdbcTemplate jdbc;

    private final FakeStarter starter = new FakeStarter();
    private BroadcastEventProcessor processor;

    BroadcastEventProcessorTest(EndedStreamStore store, JdbcTemplate jdbc) {
        this.store = store;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 표를_비우고_판정기를_새로_만든다() {
        jdbc.update("DELETE FROM chat_ended_streams");
        processor = new BroadcastEventProcessor(store, starter);
        starter.reset();
    }

    // 문항 4: 번호만 옮기고 종료 시각은 지금 시각으로 넣는 구현도 지시서 단언을 통과한다 —
    //         봉투의 occurredAt이 실제로 표에 들어갔는지 같이 본다.
    //         종료 편지가 세션을 여는 구현도 통과한다 — 시작 자리를 안 밟았는지도 본다.
    // 문항 5: handleEnded가 remember를 안 부르게 하면 orElseThrow에서 빨간불(확인함).
    @Test
    void 종료가_먼저_오면_메모를_남기고_처리로_끝낸다() {
        ProcessResult result = processor.process(ended("s1", 5));
        assertThat(result).isEqualTo(ProcessResult.PROCESSED);
        assertThat(store.find("s1").orElseThrow().lastSequence()).isEqualTo(5L);
        assertThat(store.find("s1").orElseThrow().endedAt()).isEqualTo(종료시각);
        assertThat(starter.userIds()).isEmpty();
    }

    // 문항 4: IGNORED_STALE을 돌려주면서 세션도 여는 구현이 지시서 단언을 통과한다.
    //         그것이 가장 나쁜 결말(끝난 방송에 붙어 상한 한 자리를 영영 먹는다)이라 같이 본다.
    @Test
    void 끝난_방송에_낮은_번호의_시작이_오면_무시한다() {
        processor.process(ended("s1", 5));
        assertThat(processor.process(started("s1", 3))).isEqualTo(ProcessResult.IGNORED_STALE);
        assertThat(starter.userIds()).isEmpty();
    }

    @Test
    void 끝난_방송에_같은_번호의_시작이_와도_무시한다() {
        processor.process(ended("s1", 5));
        assertThat(processor.process(started("s1", 5))).isEqualTo(ProcessResult.IGNORED_STALE);
        assertThat(starter.userIds()).isEmpty();
    }

    // streamId가 방송마다 새로 발급된다는 가정 아래, 이것은 정상이 아니라 이상 상황이다.
    // clip의 Broadcast.applyStarted가 ENDED를 LIVE로 되돌리지 않는 것과 같은 쪽이다.
    //
    // 문항 4: 판정이 늘 IGNORED_STALE이고 경고를 늘 찍는 구현도 지시서 단언을 통과한다.
    //         그래서 같은 검사 안에 양성 대조를 둔다 — 끝나지 않은 방송의 시작은 붙고,
    //         그때는 이 경고가 안 나간다.
    // 문항 5: handleStarted에서 표 조회를 빼면 이 갈래가 빨간불(확인함).
    @Test
    void 끝난_방송에_더_높은_번호의_시작이_와도_붙지_않고_경고한다() {
        try (LogCaptor captor = new LogCaptor()) {
            processor.process(ended("s1", 5));
            assertThat(processor.process(started("s1", 9))).isEqualTo(ProcessResult.IGNORED_STALE);
            assertThat(captor.messages()).anyMatch(l -> l.contains("chat.broadcast.started_after_ended"));
            assertThat(captor.levelOf("chat.broadcast.started_after_ended")).isEqualTo(Level.WARN);
            assertThat(starter.userIds()).isEmpty();

            // 양성 대조 — 메모가 없는 방송은 붙는다. 이 줄이 없으면 "늘 무시"가 초록이다.
            assertThat(processor.process(started("s2", 9))).isEqualTo(ProcessResult.PROCESSED);
            assertThat(starter.userIds()).containsExactly(42L);
            assertThat(captor.messages().stream()
                    .filter(l -> l.contains("chat.broadcast.started_after_ended")).count()).isEqualTo(1L);
        }
    }

    // 문항 2: 「카운터가 늘 1」인 구현도 한 줄짜리 단언은 통과한다 — 부르기 전 0을 먼저 보고,
    //         두 번째 불량 편지에서 2가 되는지도 본다(계획 검증 S6: 편지마다 올린다.
    //         한 번만 올리면 「1번이 식별자 체계를 바꿨다」와 「한 건 이상했다」가 구분되지 않는다).
    //         정상 편지에서는 안 오르는 것까지 봐야 「늘 오른다」가 걸린다.
    // 문항 5: streamer.valid() 검사를 빼면 빨간불(확인함).
    @Test
    void 신원을_못_읽으면_카운터가_오르고_편지는_버려진다() {
        assertThat(processor.counters().unreadableStreamerIds()).isZero();

        assertThat(processor.process(started("s1", "uuid-form", 1)))
                .isEqualTo(ProcessResult.UNREADABLE);
        assertThat(processor.counters().unreadableStreamerIds()).isEqualTo(1L);

        assertThat(processor.process(started("s2", "uuid-form", 1)))
                .isEqualTo(ProcessResult.UNREADABLE);
        assertThat(processor.counters().unreadableStreamerIds()).isEqualTo(2L);

        // 신원을 못 읽었으면 세션도 안 연다. 열쇠를 누구 것으로 받을지 모르기 때문이다.
        assertThat(starter.userIds()).isEmpty();

        // 정상 편지는 카운터를 안 올린다.
        assertThat(processor.process(started("s3", 1))).isEqualTo(ProcessResult.PROCESSED);
        assertThat(processor.counters().unreadableStreamerIds()).isEqualTo(2L);
    }

    // 재시도해도 계속 실패하는데 안 지우면 FIFO라 같은 방송의 뒤 편지가 전부 막힌다.
    //
    // 문항 2: 판정만 보는 한 줄은 「늘 UNREADABLE」에도 초록이다 — 카운터와
    //         정상 편지 양성 대조를 같이 둔다.
    // 문항 5: UNKNOWN 분기를 빼면 switch의 IllegalStateException으로 빨간불(확인함).
    @Test
    void 모르는_종류의_편지는_못_읽음으로_버린다() {
        assertThat(processor.process(ofType("broadcast.paused"))).isEqualTo(ProcessResult.UNREADABLE);
        assertThat(processor.counters().unknownTypes()).isEqualTo(1L);
        assertThat(starter.userIds()).isEmpty();

        assertThat(processor.process(started("s1", 1))).isEqualTo(ProcessResult.PROCESSED);
        assertThat(processor.counters().unknownTypes()).isEqualTo(1L);
    }

    // SQS는 at-least-once라 같은 편지가 두 번 오는 것이 **정상이다.**
    // 두 번째는 표를 안 바꾸지만(remember()가 false) 그것도 「처리됨」이다 —
    // 실패로 분류하면 정상 중복이 재시도 대상이 된다(계획 검증 S4).
    //
    // 문항 5: remember()의 false를 RETRY_LATER로 돌리면 둘째 줄이 빨간불(확인함).
    @Test
    void 같은_종료_편지가_두_번_와도_메모는_한_번만_바뀐다() {
        LifecycleEnvelope same = ended("s1", 5);
        assertThat(processor.process(same)).isEqualTo(ProcessResult.PROCESSED);
        assertThat(processor.process(same)).isEqualTo(ProcessResult.PROCESSED);
        assertThat(store.find("s1").orElseThrow().lastSequence()).isEqualTo(5L);
    }

    /**
     * 🔴 <b>이 갈래가 계획 검증 C1이 되살아나는 것을 막는 유일한 방어선이다.</b>
     *
     * <p>초안은 처리한 {@code eventId}를 맵에 기억하고 <b>판정 앞에서</b> 기록했다. 그러면
     * 열쇠를 못 받아 {@code RETRY_LATER}가 났을 때 편지는 큐에 남는데 기록은 이미 남아,
     * 재전송된 2회차가 「이미 봤다」로 걸러지고 러너가 지운다 — 그 방송의 세션은 영영 안 열린다.
     *
     * <p>문항 4: 판정 두 줄만 보면 「2회차를 안 부르고 PROCESSED를 돌려주는」 구현도 통과한다.
     * 그래서 시작 자리가 <b>두 번</b> 불렸는지를 같이 본다 — 맵을 되살리면 1회에 머문다.
     * <p>문항 5: {@code process} 앞에 {@code seenEventIds.add(eventId)} 게이트를 되살리면 빨간불(확인함).
     */
    @Test
    void 나중에_다시_시도하기로_한_편지가_재전송되면_이번엔_처리된다() {
        LifecycleEnvelope 같은_편지 = started("s1", "42", 1);

        // 1회차: auth가 죽어 있다 → 편지를 안 지운다
        givenLinkUnreachable();
        assertThat(processor.process(같은_편지)).isEqualTo(ProcessResult.RETRY_LATER);

        // 2회차: auth가 살아났고 SQS가 같은 편지를 다시 줬다 → 이번엔 붙어야 한다
        givenLinkResolves();
        assertThat(processor.process(같은_편지)).isEqualTo(ProcessResult.PROCESSED);
        assertThat(starter.userIds()).containsExactly(42L, 42L);
    }

    private void givenLinkUnreachable() {
        starter.willReturn(ProcessResult.RETRY_LATER);
    }

    private void givenLinkResolves() {
        starter.willReturn(ProcessResult.PROCESSED);
    }

    private static LifecycleEnvelope ended(String streamId, long sequence) {
        return envelope("broadcast.ended", streamId, "42", sequence);
    }

    private static LifecycleEnvelope started(String streamId, long sequence) {
        return envelope("broadcast.started", streamId, "42", sequence);
    }

    private static LifecycleEnvelope started(String streamId, String streamerId, long sequence) {
        return envelope("broadcast.started", streamId, streamerId, sequence);
    }

    private static LifecycleEnvelope ofType(String eventType) {
        return envelope(eventType, "s1", "42", 1);
    }

    /**
     * {@code eventId}를 내용에서 뽑는다 — 재전송된 같은 편지는 같은 값이어야
     * 「eventId를 기억하는 맵」 결함 주입이 실제로 그 갈래를 막는다.
     *
     * <p><b>종류까지 넣는 이유가 있다.</b> 처음엔 {@code streamId}·{@code sequence}만 썼는데,
     * 그러면 같은 방송의 종료 편지와 시작 편지가 같은 값이 된다 — 실물에서는 다른 사건이라
     * 다른 값이다. 맵 결함을 주입했을 때 {@code 끝난_방송에_같은_번호의_시작이_와도_무시한다}가
     * 그 우연 때문에 같이 빨간불이 됐다(확인함). 방어선이 어느 갈래인지 흐려지므로 갈랐다.
     */
    private static LifecycleEnvelope envelope(String eventType, String streamId, String streamerId, long sequence) {
        return new LifecycleEnvelope(1, "evt-" + eventType + "-" + streamId + "-" + sequence, eventType,
                종료시각, streamId, streamerId, sequence, "trace-1", null);
    }

    /**
     * 태스크 10에서 열쇠 조회(태스크 7)와 세션 등록부(태스크 9)가 들어올 자리.
     * 여기서는 판정 결과를 미리 정해 두고, 받은 회원 번호를 기록한다.
     */
    private static final class FakeStarter implements BroadcastStarter {

        private final List<Long> userIds = new ArrayList<>();
        private ProcessResult next = ProcessResult.PROCESSED;

        @Override
        public ProcessResult start(LifecycleEnvelope envelope, StreamerId streamer) {
            userIds.add(streamer.value());
            return next;
        }

        void willReturn(ProcessResult result) {
            next = result;
        }

        List<Long> userIds() {
            return List.copyOf(userIds);
        }

        void reset() {
            userIds.clear();
            next = ProcessResult.PROCESSED;
        }
    }
}
