package com.pokeclip.clip.broadcast;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

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

    /** 태스크 5에서 규칙을 채운다. 지금은 없으면 만들기만 한다. */
    private ProcessResult applyToBroadcast(LifecycleEnvelope envelope) {
        if (broadcasts.findByStreamId(envelope.streamId()).isEmpty()) {
            broadcasts.save(Broadcast.startedNow(envelope.streamId(), envelope.streamerId(),
                    envelope.sequence(), envelope.occurredAt(), envelope.trackManifestJson()));
        }
        return ProcessResult.PROCESSED;
    }
}
