package com.pokeclip.chat.collector.broadcast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 방송 생명주기 편지 하나를 판정한다.
 *
 * <p>🔴 <b>처리한 {@code eventId}를 기억하지 않는다</b>(계획 검증 C1). 판정 앞에 「이미 봤다」
 * 게이트를 두면, 열쇠를 못 받아 {@code RETRY_LATER}가 났을 때 편지는 큐에 남는데 기록은 남아
 * 재전송된 2회차가 걸러지고 러너가 지운다 — <b>그 방송의 세션은 영영 안 열린다.</b>
 *
 * <p>멱등은 상태가 진다. 종료는 {@code chat_ended_streams} 표가, 시작은 세션 등록부가 막는다
 * (태스크 10). 메모리 상태가 판정에 관여하면 껐다 켠 뒤 동작이 달라지기도 한다.
 */
public class BroadcastEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(BroadcastEventProcessor.class);

    /**
     * <b>{@code chat_ended_streams.stream_id}의 폭이다({@code V303}의 {@code VARCHAR(128)}).</b>
     * clip의 {@code broadcasts.stream_id}({@code V201})도 같은 폭이다.
     *
     * <p><b>어긋나면 조용히 깨진다.</b> 이 값이 크면 표에 못 들어가는 편지가 판정을 통과해
     * 저장에서 터지고(아래 참고), 작으면 멀쩡한 방송이 버려지는데 카운터만 오른다.
     * 마이그레이션을 고칠 때 여기도 같이 고친다 — {@code 딱_128자인_방송_번호는_표에_들어간다}가
     * 실제로 128자를 넣어 보므로 어긋나면 검사가 잡는다.
     */
    private static final int STREAM_ID_MAX_LENGTH = 128;

    private final EndedStreamStore store;
    private final BroadcastSessions sessions;

    private final AtomicLong unreadableStreamerIds = new AtomicLong();
    private final AtomicLong unknownTypes = new AtomicLong();
    private final AtomicLong malformedEnvelopes = new AtomicLong();

    public BroadcastEventProcessor(EndedStreamStore store, BroadcastSessions sessions) {
        this.store = store;
        this.sessions = sessions;
    }

    public ProcessResult process(LifecycleEnvelope envelope) {
        LifecycleEventType type = envelope.type();
        if (type == LifecycleEventType.UNKNOWN) {
            // 재시도해도 계속 실패하는데 안 지우면 FIFO라 같은 방송의 뒤 편지가 전부 막힌다.
            log.warn("chat.broadcast.unknown_type_dropped eventType={}", envelope.eventType());
            unknownTypes.incrementAndGet();
            return ProcessResult.UNREADABLE;
        }
        if (!readableEnvelope(envelope)) {
            // 원문 값은 안 찍는다 — 129자짜리가 그대로 로그에 박히면 읽을 수 없다.
            log.warn("chat.broadcast.malformed_envelope_dropped eventId={} eventType={} "
                            + "streamIdLength={} occurredAtMissing={}",
                    envelope.eventId(), envelope.eventType(),
                    envelope.streamId() == null ? -1 : envelope.streamId().length(),
                    envelope.occurredAt() == null);
            malformedEnvelopes.incrementAndGet();
            return ProcessResult.UNREADABLE;
        }
        StreamerId streamer = StreamerId.parse(envelope.streamerId());
        if (!streamer.valid()) {
            // 1번이 다른 식별자 체계로 바꾸면 모든 방송이 조용히 안 걷힌다.
            // 로그만으로는 안 보이므로 카운터로 올려 health가 드러낸다.
            // 편지마다 올린다 — 한 번만 올리면 「체계가 바뀌었다」와 「한 건 이상했다」가
            // 구분되지 않는다(계획 검증 S6).
            log.warn("chat.broadcast.streamer_id_unreadable streamId={}", envelope.streamId());
            unreadableStreamerIds.incrementAndGet();
            return ProcessResult.UNREADABLE;
        }
        return switch (type) {
            case ENDED -> handleEnded(envelope);
            case STARTED -> handleStarted(envelope, streamer);
            case UNKNOWN -> throw new IllegalStateException("위에서 걸렀다");
        };
    }

    public BroadcastCounters counters() {
        return new BroadcastCounters(unreadableStreamerIds.get(), unknownTypes.get(), malformedEnvelopes.get());
    }

    /**
     * 표에 넣을 수 있는 봉투인가. <b>「이 편지를 읽을 수 있는가」는 이미 이 판정기가 지는
     * 질문이다</b> — 모르는 종류도 못 읽는 {@code streamerId}도 같은 자리에서
     * {@code UNREADABLE}로 나간다. {@code streamId}가 없거나 너무 긴 것도 같은 질문이다.
     *
     * <p>🔴 <b>안 거르면 그 방송의 큐가 영구히 막힌다.</b> 태스크 6이 실물 DB로 쟀다 —
     * 종료 편지의 {@code streamId}가 {@code null}이거나 129자면
     * {@code DataIntegrityViolationException}, {@code occurredAt}이 {@code null}이면
     * {@code Timestamp.from}에서 {@code NullPointerException}이다. 셋 다 {@code process()}
     * 밖으로 나가 러너가 「안 지우고 회차 중단」으로 받는데, <b>재전송돼도 값이 안 바뀌므로
     * 영영 안 풀린다.</b> {@code MessageGroupId}가 {@code streamId}라 그 방송의 뒤 편지가
     * 전부 막히고, 폴링 자체는 성공하므로 health는 초록이다.
     *
     * <p>시작 편지는 지금 안 터진다 — {@code find(null)}이 0행이라 통과해서 <b>이름 없는 방송에
     * 세션이 열린다.</b> 터지지 않을 뿐 같은 못 쓸 편지이므로 같이 막는다(태스크 10의 세션
     * 등록부가 {@code streamId}를 열쇠로 쓴다).
     *
     * <p>clip도 PR #89에서 같은 자리를 막았지만 <b>거긴 러너다</b> — 그쪽
     * {@code LifecycleEventType.from}이 예외를 던지는 설계라 러너가 분류를 맡았다.
     * 우리 {@code from}은 {@code UNKNOWN}을 값으로 돌려주므로 전제가 다르다.
     *
     * <p>{@code streamerId}는 여기서 안 본다. 바로 아래 {@link StreamerId#parse}가 막는다 —
     * 두 곳에서 보면 어느 쪽이 막았는지가 카운터로 안 갈린다.
     *
     * <p>{@code occurredAt}은 종류를 안 가리고 본다. 지금 쓰는 것은 ENDED뿐이지만, 종류마다
     * 다른 규칙을 두면 「읽을 수 있는 봉투」의 뜻이 갈린다. 계약9는 모든 종류에 이 칸을 요구한다.
     */
    private static boolean readableEnvelope(LifecycleEnvelope envelope) {
        String streamId = envelope.streamId();
        return streamId != null
                && !streamId.isBlank()
                && streamId.length() <= STREAM_ID_MAX_LENGTH
                && envelope.occurredAt() != null;
    }

    /**
     * <b>{@code remember()}의 false를 실패로 읽지 않는다</b>(계획 검증 S4). SQS는 at-least-once라
     * 같은 종료 편지가 두 번 오는 것이 정상이고, 그때 표는 안 바뀐다. 그것을 {@code RETRY_LATER}로
     * 돌리면 정상 중복이 영원히 재시도된다.
     *
     * <p>메모의 종료 시각은 봉투의 {@code occurredAt}이다 — 우리가 받은 시각이 아니다.
     * 편지가 늦게 오면 둘이 벌어지고, 그때 받은 시각을 쓰면 순서 판정이 어긋난다.
     */
    private ProcessResult handleEnded(LifecycleEnvelope envelope) {
        store.remember(envelope.streamId(), envelope.sequence(), envelope.occurredAt());
        // <b>메모가 먼저다.</b> 닫고 나서 메모를 남기면 그 사이에 재전송된 시작 편지가
        // 세션을 다시 열고, 뒤이어 들어온 메모는 이미 열린 세션을 막지 못한다 — 끝난
        // 방송에 붙은 채로 남아 계정별 상한 3개 중 한 자리를 영영 먹는다. 지금은 편지를
        // 꺼내는 스레드가 하나라 그 끼어듦이 없지만, 순서가 지키는 것을 스레드 수에
        // 기대게 두지 않는다.
        //
        // <b>닫을 세션이 없어도 PROCESSED다.</b> 종료 편지는 재전송으로 두 번 오고, 우리가
        // 뜨기 전에 시작한 방송은 애초에 연 적이 없다. 그것을 RETRY_LATER로 돌리면
        // 영원히 다시 받는다.
        sessions.stop(envelope.streamId());
        return ProcessResult.PROCESSED;
    }

    /**
     * 끝난 방송에는 붙지 않는다. streamId가 방송마다 새로 발급된다는 가정 아래
     * 더 높은 번호의 시작은 정상이 아니라 이상 상황이므로 경고를 남긴다
     * (clip의 {@code Broadcast.applyStarted}가 ENDED를 LIVE로 안 되돌리는 것과 같은 쪽이다).
     */
    private ProcessResult handleStarted(LifecycleEnvelope envelope, StreamerId streamer) {
        Optional<EndedStream> ended = store.find(envelope.streamId());
        if (ended.isPresent()) {
            if (envelope.sequence() > ended.get().lastSequence()) {
                if (ended.get().stopped()) {
                    // 포기 메모(번호 0) 위의 시작 — 재전송이거나, 재연동 전에 다시 켠 것이다. 같은 토큰이면
                    // 또 401이라 열 이유가 없고, 여는 트리거 자체가 없다(POK-127 미결). 내부 로그라 사유 이름을 찍는다.
                    log.warn("chat.broadcast.started_after_stopped streamId={} sequence={} stopReason={}",
                            envelope.streamId(), envelope.sequence(), ended.get().stopReason());
                } else {
                    log.warn("chat.broadcast.started_after_ended streamId={} sequence={} endedSequence={}",
                            envelope.streamId(), envelope.sequence(), ended.get().lastSequence());
                }
            }
            return ProcessResult.IGNORED_STALE;
        }
        return sessions.start(envelope, streamer);
    }
}
