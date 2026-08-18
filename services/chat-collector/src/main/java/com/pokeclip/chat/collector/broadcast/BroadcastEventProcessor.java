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

    private final EndedStreamStore store;
    private final BroadcastStarter starter;

    private final AtomicLong unreadableStreamerIds = new AtomicLong();
    private final AtomicLong unknownTypes = new AtomicLong();

    public BroadcastEventProcessor(EndedStreamStore store, BroadcastStarter starter) {
        this.store = store;
        this.starter = starter;
    }

    public ProcessResult process(LifecycleEnvelope envelope) {
        LifecycleEventType type = envelope.type();
        if (type == LifecycleEventType.UNKNOWN) {
            // 재시도해도 계속 실패하는데 안 지우면 FIFO라 같은 방송의 뒤 편지가 전부 막힌다.
            log.warn("chat.broadcast.unknown_type_dropped eventType={}", envelope.eventType());
            unknownTypes.incrementAndGet();
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
        return new BroadcastCounters(unreadableStreamerIds.get(), unknownTypes.get());
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
                log.warn("chat.broadcast.started_after_ended streamId={} sequence={} endedSequence={}",
                        envelope.streamId(), envelope.sequence(), ended.get().lastSequence());
            }
            return ProcessResult.IGNORED_STALE;
        }
        return starter.start(envelope, streamer);
    }
}
