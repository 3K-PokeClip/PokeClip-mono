package com.pokeclip.clip.broadcast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * 편지 하나를 처리한다. 편지 기록과 명부 갱신이 <b>한 트랜잭션</b>에 들어간다 —
 * 갈라 두면 "기록은 남았는데 명부는 안 바뀐" 줄이 생기고, 그 편지가 재전송돼도
 * 중복으로 걸러져 영영 반영되지 않는다.
 *
 * <p><b>중복을 예외로 가르지 않는다.</b> ON CONFLICT의 영향 행 수로 가른다.
 * 예외로 가르면 저장 실패까지 중복으로 삼켜 러너가 메시지를 지운다.
 * 여기서 예외가 올라간다는 것은 <b>진짜 실패</b>라는 뜻이고, 러너는 그때
 * 메시지를 남겨 재전송받아야 한다.
 *
 * <p>클래스와 메서드에 final을 붙이지 않는다 — 태스크 7의 러너 테스트가 상속해
 * 스텁을 만든다.
 */
@Component
public class BroadcastEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(BroadcastEventProcessor.class);

    private final BroadcastRepository broadcasts;
    private final BroadcastEventRepository events;

    public BroadcastEventProcessor(BroadcastRepository broadcasts, BroadcastEventRepository events) {
        this.broadcasts = broadcasts;
        this.events = events;
    }

    @Transactional
    public ProcessResult process(LifecycleEnvelope envelope) {
        int inserted = events.insertIfAbsent(envelope.eventId(), envelope.streamId(),
                envelope.type().name(), envelope.sequence(), Instant.now());
        if (inserted == 0) {
            log.info("broadcast.event.duplicate_skipped eventId={} streamId={} type={}",
                    envelope.eventId(), envelope.streamId(), envelope.eventType());
            return ProcessResult.DUPLICATE;
        }
        return applyToBroadcast(envelope);
    }

    private ProcessResult applyToBroadcast(LifecycleEnvelope envelope) {
        Optional<Broadcast> existing = broadcasts.findByStreamIdForUpdate(envelope.streamId());

        if (existing.isEmpty()) {
            // 줄이 없다. 동시에 둘이 만들려 하면 stream_id UNIQUE가 하나만 통과시키고,
            // 진 쪽은 트랜잭션이 통째로 되감긴 뒤 재전송으로 다시 온다.
            Broadcast created = switch (envelope.type()) {
                case BROADCAST_STARTED -> Broadcast.startedNow(envelope.streamId(),
                        envelope.streamerId(), envelope.sequence(), envelope.occurredAt(),
                        envelope.trackManifestJson());
                case BROADCAST_ENDED -> {
                    // ADR-016의 ended placeholder. 서버를 죽이지 않고 흔적을 남긴다.
                    log.warn("broadcast.ended_before_started streamId={} eventId={} sequence={}",
                            envelope.streamId(), envelope.eventId(), envelope.sequence());
                    yield Broadcast.endedPlaceholder(envelope.streamId(), envelope.streamerId(),
                            envelope.sequence(), envelope.occurredAt());
                }
            };
            broadcasts.save(created);
            return ProcessResult.PROCESSED;
        }

        Broadcast broadcast = existing.get();
        boolean applied = switch (envelope.type()) {
            case BROADCAST_STARTED -> broadcast.applyStarted(envelope.sequence(),
                    envelope.occurredAt(), envelope.trackManifestJson());
            case BROADCAST_ENDED -> broadcast.applyEnded(envelope.sequence(), envelope.occurredAt());
        };

        if (!applied) {
            log.warn("broadcast.event.stale_ignored streamId={} eventId={} sequence={} lastSequence={}",
                    envelope.streamId(), envelope.eventId(), envelope.sequence(),
                    broadcast.getLastSequence());
            return ProcessResult.IGNORED_STALE;
        }
        return ProcessResult.PROCESSED;
    }
}
