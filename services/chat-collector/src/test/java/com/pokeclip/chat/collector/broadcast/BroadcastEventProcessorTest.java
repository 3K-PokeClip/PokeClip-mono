package com.pokeclip.chat.collector.broadcast;

import ch.qos.logback.classic.Level;
import com.pokeclip.chat.collector.StopReason;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

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

    private final FakeSessions sessions = new FakeSessions();
    /** 판정기가 남긴 포기 메모. 실물은 {@code StoppedStreamRecorder::record}다. */
    private final List<String> 남긴것 = new ArrayList<>();
    private BroadcastEventProcessor processor;

    BroadcastEventProcessorTest(EndedStreamStore store, JdbcTemplate jdbc) {
        this.store = store;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 표를_비우고_판정기를_새로_만든다() {
        jdbc.update("DELETE FROM chat_ended_streams");
        남긴것.clear();
        processor = new BroadcastEventProcessor(store, sessions,
                (streamId, reason) -> 남긴것.add(streamId + "/" + reason));
        sessions.reset();
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
        assertThat(sessions.userIds()).isEmpty();
        // 메모만 남기고 세션을 안 닫으면 끝난 방송에 붙은 채로 남아 계정별 상한 3개 중
        // 한 자리를 영영 먹는다. <b>그 방송 번호로</b> 닫는지까지 본다 — 아무거나 닫으면
        // 남의 방송이 끊긴다.
        assertThat(sessions.stopped()).containsExactly("s1");
    }

    // 문항 4: IGNORED_STALE을 돌려주면서 세션도 여는 구현이 지시서 단언을 통과한다.
    //         그것이 가장 나쁜 결말(끝난 방송에 붙어 상한 한 자리를 영영 먹는다)이라 같이 본다.
    @Test
    void 끝난_방송에_낮은_번호의_시작이_오면_무시한다() {
        processor.process(ended("s1", 5));
        assertThat(processor.process(started("s1", 3))).isEqualTo(ProcessResult.IGNORED_STALE);
        assertThat(sessions.userIds()).isEmpty();
    }

    @Test
    void 끝난_방송에_같은_번호의_시작이_와도_무시한다() {
        processor.process(ended("s1", 5));
        assertThat(processor.process(started("s1", 5))).isEqualTo(ProcessResult.IGNORED_STALE);
        assertThat(sessions.userIds()).isEmpty();
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
            assertThat(sessions.userIds()).isEmpty();

            // 양성 대조 — 메모가 없는 방송은 붙는다. 이 줄이 없으면 "늘 무시"가 초록이다.
            assertThat(processor.process(started("s2", 9))).isEqualTo(ProcessResult.PROCESSED);
            assertThat(sessions.userIds()).containsExactly(42L);
            assertThat(captor.messages().stream()
                    .filter(l -> l.contains("chat.broadcast.started_after_ended")).count()).isEqualTo(1L);
        }
    }

    // 포기 메모(번호 0) 위에 같은 방송의 시작 편지(번호 >= 1)가 다시 오면 — 영원히 도는 편지를 여기서 멈춘다.
    // 문항 2: noneMatch(started_after_ended)는 로그가 한 줄도 없어도 참이다 —
    //         anyMatch(started_after_stopped)를 <b>먼저</b> 단언한다.
    // 문항 5: handleStarted의 갈래를 지우면 started_after_ended가 찍혀 둘째 단언이 빨간불.
    @Test
    void 포기_메모가_있는_방송의_시작_편지는_지워지고_로그가_종료와_갈린다() throws Exception {
        store.rememberStopped("s1", "SESSION_AUTH_REJECTED", Instant.parse("2026-08-22T12:00:00Z"));
        try (LogCaptor captor = new LogCaptor()) {
            assertThat(processor.process(started("s1", 1))).isEqualTo(ProcessResult.IGNORED_STALE);
            assertThat(captor.messages()).anyMatch(m -> m.startsWith("chat.broadcast.started_after_stopped"));
            assertThat(captor.messages()).noneMatch(m -> m.startsWith("chat.broadcast.started_after_ended"));
        }
        assertThat(sessions.userIds()).as("세션을 열지 않았다").isEmpty();
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
        assertThat(sessions.userIds()).isEmpty();

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
        assertThat(sessions.userIds()).isEmpty();

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
        assertThat(sessions.userIds()).containsExactly(42L, 42L);
    }

    /**
     * 🔴 <b>이 갈래가 없으면 그 방송의 큐가 영구히 막힌다.</b> 태스크 6이 실물 DB로 재 봤다 —
     * 종료 편지의 {@code streamId}가 {@code null}이거나 129자면
     * {@code DataIntegrityViolationException}({@code stream_id}는 {@code VARCHAR(128)} PK),
     * {@code occurredAt}이 {@code null}이면 {@code Timestamp.from}에서 {@code NullPointerException}.
     * 셋 다 {@code process()} 밖으로 나가 러너가 「안 지우고 회차 중단」으로 받는데, 재전송돼도
     * 값이 안 바뀌므로 영영 안 풀린다. 폴링은 성공하므로 <b>health는 초록이다.</b>
     *
     * <p>시작 편지는 지금 안 터진다 — {@code find(null)}은 0행이라 그냥 통과해 <b>이름 없는
     * 방송에 세션이 열린다.</b> 터지지 않을 뿐 같은 못 쓸 편지이므로 같이 막는다
     * (태스크 10의 세션 등록부가 {@code streamId}를 열쇠로 쓴다).
     *
     * <p>문항 2: 「늘 {@code UNREADABLE}」인 구현에도 판정 한 줄은 초록이다 — 이 갈래에서
     * 봉투 카운터가 올랐는지, 신원 카운터는 안 올랐는지, 시작 자리를 안 밟았는지를 같이 본다.
     * 양성 대조는 {@code 정상_봉투는…}·{@code 딱_128자인…} 둘이 진다.
     * <p>문항 4: 검증을 시작 자리 <b>뒤</b>에 두는 구현도 판정만 보면 통과한다 — 끝난 방송에
     * 붙인 뒤 {@code UNREADABLE}을 돌려주는 모양이다. 그래서 {@code sessions}를 같이 본다.
     * <p>문항 1·3: 이 판정기는 세션을 열지 않는다. 잴 대상이 없어 해당하지 않는다.
     * <p>문항 5: 봉투 검증을 통째로 빼면 여섯 다 빨간불(확인함) — 종료 셋은 위 두 예외로,
     * 시작 셋은 {@code PROCESSED}로.
     */
    @ParameterizedTest(name = "{1}")
    @MethodSource("표에_못_넣을_봉투들")
    void 표에_못_넣을_봉투는_못_읽음으로_버린다(LifecycleEnvelope 못_쓸_편지, String 왜) {
        assertThat(processor.process(못_쓸_편지)).as(왜).isEqualTo(ProcessResult.UNREADABLE);
        assertThat(processor.counters().malformedEnvelopes()).as(왜).isEqualTo(1L);
        assertThat(processor.counters().unreadableStreamerIds()).as(왜).isZero();
        assertThat(sessions.userIds()).as(왜).isEmpty();
    }

    static Stream<Arguments> 표에_못_넣을_봉투들() {
        // 129자는 표의 폭(V303 VARCHAR(128))보다 딱 한 자 길다.
        // 반대쪽 경계는 「딱_128자인_방송_번호는_표에_들어간다」가 잡는다.
        String 한_자_긴_번호 = "s".repeat(129);
        return Stream.of(
                arguments(ended(null, 1), "종료 — 방송 번호가 없다"),
                arguments(ended(한_자_긴_번호, 1), "종료 — 방송 번호가 128자를 넘는다"),
                arguments(endedWithoutOccurredAt("s1"), "종료 — 종료 시각이 없다"),
                arguments(started(null, "42", 1), "시작 — 방송 번호가 없다"),
                arguments(started("", "42", 1), "시작 — 방송 번호가 비었다"),
                arguments(started(한_자_긴_번호, "42", 1), "시작 — 방송 번호가 128자를 넘는다"));
    }

    // 양성 대조. 위 갈래들은 「늘 UNREADABLE」인 구현에도 전부 초록이다(문항 2).
    @Test
    void 정상_봉투는_그_검증에_안_걸린다() {
        assertThat(processor.process(started("s1", "42", 1))).isEqualTo(ProcessResult.PROCESSED);
        assertThat(processor.counters().malformedEnvelopes()).isZero();
        assertThat(sessions.userIds()).containsExactly(42L);
    }

    /**
     * 양성 대조이면서 <b>상수를 표의 폭에 묶는 자리다.</b> 딱 128자를 실제로 표에 넣어 본다 —
     * 상수가 {@code V303}의 {@code VARCHAR(128)}보다 커지면 위 129자 갈래가, 작아지면
     * 여기가 빨간불이 된다. 양쪽이 다 닫힌다.
     *
     * <p>문항 4: 129자만 재면 {@code >=}로 쓴 오프바이원이 초록이다 — <b>멀쩡한 방송이
     * 조용히 버려지는데</b> 카운터만 오르고 아무도 원인을 모른다.
     * <p>문항 5: 상수를 127로 낮추면 빨간불(확인함).
     */
    @Test
    void 딱_128자인_방송_번호는_표에_들어간다() {
        String 폭에_꼭_맞는_번호 = "s".repeat(128);

        assertThat(processor.process(ended(폭에_꼭_맞는_번호, 5))).isEqualTo(ProcessResult.PROCESSED);
        assertThat(store.find(폭에_꼭_맞는_번호).orElseThrow().lastSequence()).isEqualTo(5L);
        assertThat(processor.counters().malformedEnvelopes()).isZero();
    }

    /**
     * 1번이 고칠 자리가 다르다 — 「식별자 체계를 바꿨다」와 「봉투가 깨졌다」는 다른 사건이다.
     * 한 값으로 합치면 어느 쪽인지 모른 채 health만 아프다.
     *
     * <p>문항 4: 두 카운터를 다 올리는 구현도 「1 이상」 단언은 통과한다 — 정확히 1인지 본다.
     * <p>문항 5: 봉투 검증을 신원 검사 뒤로 옮기면 마지막 두 줄이 빨간불(확인함).
     */
    @Test
    void 못_쓸_편지_카운터는_신원_불량과_따로_센다() {
        processor.process(started(null, "42", 1));
        processor.process(started("s1", "uuid-form", 1));
        assertThat(processor.counters().malformedEnvelopes()).isEqualTo(1L);
        assertThat(processor.counters().unreadableStreamerIds()).isEqualTo(1L);

        // 둘 다 깨졌으면 봉투로 센다 — 검사 순서를 그렇게 정했다. 신원 경고는 streamId를
        // 찍는데 봉투가 깨졌으면 그 값 자체가 못 믿을 것이라, 「식별자 체계가 바뀌었다」로
        // 읽히면 1번이 엉뚱한 곳을 판다.
        processor.process(started(null, "uuid-form", 1));
        assertThat(processor.counters().malformedEnvelopes()).isEqualTo(2L);
        assertThat(processor.counters().unreadableStreamerIds()).isEqualTo(1L);
    }

    /**
     * 모르는 종류는 봉투를 안 본다. 우리가 안 쓰는 종류라 {@code streamId}·{@code occurredAt}이
     * 비어 있어도 이상하지 않고, 그것을 봉투 불량으로 세면 <b>「1번이 새 종류를 보내기
     * 시작했다」는 진짜 신호가 봉투 카운터에 묻힌다.</b>
     *
     * <p>문항 5: 봉투 검증을 종류 검사 앞으로 옮기면 빨간불(확인함).
     */
    @Test
    void 모르는_종류는_봉투가_깨져도_종류로_센다() {
        assertThat(processor.process(envelope("broadcast.paused", null, "42", 1)))
                .isEqualTo(ProcessResult.UNREADABLE);
        assertThat(processor.counters().unknownTypes()).isEqualTo(1L);
        assertThat(processor.counters().malformedEnvelopes()).isZero();
    }

    /**
     * 🔴 <b>지우기 전에 남긴다.</b> 편지가 그 방송의 유일한 트리거라, 메모 없이 지우면
     * 창구가 그 방송에 <b>영원히</b> {@code unknown}을 답한다 — 배너를 끄는 값이라
     * 「가장 나쁜 상태가 가장 안전한 답으로 보이는」 틈이고 여기서는 그 틈이 영구다.
     *
     * <p><b>이 자리가 붙이기 문이 아니라 여기인 이유</b>: 문은 재부착도 쓰는데 재부착에는
     * 지울 편지가 없다. 문에 두면 재부착이 만든 메모가 재부착 자신을 24시간 막는다
     * (POK-219 감사 라운드 3, {@code ReattacherTest}가 반대편을 잰다).
     *
     * <p>판정값을 {@code PROCESSED}로 바꿔 돌려주지 않는다 — 러너는 둘 다 지우지만
     * {@code broadcast.intake.handled}의 {@code result=}가 「왜 지웠나」를 잃는다.
     */
    @Test
    void 연동이_영구히_거절되면_포기_메모를_남기고_판정값을_그대로_돌려준다() {
        sessions.willReturn(ProcessResult.LINK_REFUSED);

        assertThat(processor.process(started("s1", 1))).isEqualTo(ProcessResult.LINK_REFUSED);

        assertThat(남긴것).containsExactly("s1/" + StopReason.LINK_UNAVAILABLE);
    }

    /**
     * 문항 2 — 위 검사는 「늘 메모를 남기는」 구현에서도 초록이다. 반대쪽을 같이 둔다:
     * 붙은 방송({@code PROCESSED})·다시 물을 방송({@code RETRY_LATER})에 메모를 남기면
     * <b>멀쩡한 방송이 24시간 안 걷힌다.</b>
     */
    @Test
    void 붙었거나_다시_물을_방송에는_포기_메모를_안_남긴다() {
        sessions.willReturn(ProcessResult.PROCESSED);
        assertThat(processor.process(started("s1", 1))).isEqualTo(ProcessResult.PROCESSED);

        sessions.willReturn(ProcessResult.RETRY_LATER);
        assertThat(processor.process(started("s2", 1))).isEqualTo(ProcessResult.RETRY_LATER);

        assertThat(남긴것).isEmpty();
    }

    private void givenLinkUnreachable() {
        sessions.willReturn(ProcessResult.RETRY_LATER);
    }

    private void givenLinkResolves() {
        sessions.willReturn(ProcessResult.PROCESSED);
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
        return envelope(eventType, streamId, streamerId, sequence, 종료시각);
    }

    private static LifecycleEnvelope endedWithoutOccurredAt(String streamId) {
        return envelope("broadcast.ended", streamId, "42", 1, null);
    }

    private static LifecycleEnvelope envelope(String eventType, String streamId, String streamerId,
                                              long sequence, Instant occurredAt) {
        return new LifecycleEnvelope(1, "evt-" + eventType + "-" + streamId + "-" + sequence, eventType,
                occurredAt, streamId, streamerId, sequence, "trace-1", null);
    }

    /**
     * 실물은 {@code LinkedSessionStarter}(열쇠 조회 + 세션 등록부)다. 여기서는 판정 결과를
     * 미리 정해 두고, 받은 회원 번호와 <b>닫으라고 한 방송 번호</b>를 기록한다.
     *
     * <p>배선이 실제로 붙는지는 {@code BroadcastSessionWiringTest}가 진짜 빈으로 잰다 —
     * <b>여기서 초록인 것이 「편지가 세션을 연다」의 증거는 아니다.</b>
     */
    private static final class FakeSessions implements BroadcastSessions {

        private final List<Long> userIds = new ArrayList<>();
        private final List<String> stopped = new ArrayList<>();
        private ProcessResult next = ProcessResult.PROCESSED;

        @Override
        public ProcessResult start(String streamId, StreamerId streamer, Instant startedAt) {
            userIds.add(streamer.value());
            return next;
        }

        @Override
        public boolean stop(String streamId) {
            stopped.add(streamId);
            return true;
        }

        void willReturn(ProcessResult result) {
            next = result;
        }

        List<Long> userIds() {
            return List.copyOf(userIds);
        }

        List<String> stopped() {
            return List.copyOf(stopped);
        }

        void reset() {
            userIds.clear();
            stopped.clear();
            next = ProcessResult.PROCESSED;
        }
    }
}
