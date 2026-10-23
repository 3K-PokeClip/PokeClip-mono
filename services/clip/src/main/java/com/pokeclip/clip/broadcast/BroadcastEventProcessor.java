package com.pokeclip.clip.broadcast;

import com.pokeclip.clip.purge.StreamerPurgeStore;
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
    private final StreamerPurgeStore purges;

    public BroadcastEventProcessor(BroadcastRepository broadcasts, BroadcastEventRepository events,
                                   StreamerPurgeStore purges) {
        this.broadcasts = broadcasts;
        this.events = events;
        this.purges = purges;
    }

    @Transactional
    public ProcessResult process(LifecycleEnvelope envelope) {
        // 탈퇴한 스트리머의 편지는 버린다(POK-256). 지운 뒤 늦게 온 시작·종료 편지가 방송 줄을 되살린다.
        // 편지 기록(broadcast_events)도 안 남긴다: 그 표도 탈퇴 때 지운 것이다.
        if (purges.isPurged(envelope.streamerId())) {
            // 편지의 물리 키는 남긴다. 영상 서버가 이미 그 키로 조각을 적었는데 정리가 방송 줄을 못 봤으면, 이 키 말고는
            // 그 조각을 찾을 길이 없다(PR #220 codex 3판). 명부를 다시 열어 정리기가 그 조각을 지우게 한다.
            purges.rememberSegmentKey(envelope.streamerId(),
                    envelope.ingestStreamId() != null ? envelope.ingestStreamId() : envelope.streamId());
            log.info("broadcast.event.purged_streamer_dropped eventId={} streamId={} type={}",
                    envelope.eventId(), envelope.streamId(), envelope.eventType());
            return ProcessResult.IGNORED_PURGED;
        }
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
            // 플랫폼은 줄이 생길 때 한 번만 정한다. 이미 있는 줄에는 안 쓴다(뒤 편지가 다른 값을 실어도 첫 값을 지킨다).
            // 물리 키와 달리 빈 칸이 없어서 칸 없는 편지로 만든 줄은 CHZZK로 굳는다. 덮어쓰기는 수집기용 내부 창구(POK-397)만 한다(Broadcast.platform)
            String platform = platformOf(envelope);
            Broadcast created = switch (envelope.type()) {
                case BROADCAST_STARTED -> Broadcast.startedNow(envelope.streamId(),
                        envelope.streamerId(), envelope.ingestStreamId(), envelope.sequence(), envelope.occurredAt(),
                        envelope.trackManifestJson(), platform);
                case BROADCAST_ENDED -> {
                    // ADR-016의 ended placeholder. 서버를 죽이지 않고 흔적을 남긴다.
                    log.warn("broadcast.ended_before_started streamId={} eventId={} sequence={}",
                            envelope.streamId(), envelope.eventId(), envelope.sequence());
                    yield Broadcast.endedPlaceholder(envelope.streamId(), envelope.streamerId(),
                            envelope.ingestStreamId(), envelope.sequence(), envelope.occurredAt(), platform);
                }
            };
            broadcasts.save(created);
            return ProcessResult.PROCESSED;
        }

        Broadcast broadcast = existing.get();
        // 순서와 무관하게 채운다. 낡은 편지라도 같은 회차의 경로를 싣고, 빈 칸으로 두면 조각을 streamId로 찾아 못 찾는다
        broadcast.fillIngestStreamId(envelope.ingestStreamId());
        boolean applied = switch (envelope.type()) {
            case BROADCAST_STARTED -> broadcast.applyStarted(envelope.sequence(),
                    envelope.occurredAt(), envelope.trackManifestJson());
            case BROADCAST_ENDED -> broadcast.applyEnded(envelope.sequence(), envelope.occurredAt());
        };

        if (!applied) {
            // 낡은 시작이라도 종료가 먼저 와 빈 시작 시각은 채운다(POK-327). 상태·순서 번호는 그대로다
            if (envelope.type() == LifecycleEventType.BROADCAST_STARTED
                    && broadcast.fillStartOfPlaceholder(envelope.occurredAt(), envelope.trackManifestJson())) {
                log.info("broadcast.placeholder.started_filled streamId={} eventId={} sequence={} lastSequence={} startedAt={}",
                        envelope.streamId(), envelope.eventId(), envelope.sequence(),
                        broadcast.getLastSequence(), envelope.occurredAt());
                return ProcessResult.PROCESSED;
            }
            log.warn("broadcast.event.stale_ignored streamId={} eventId={} sequence={} lastSequence={}",
                    envelope.streamId(), envelope.eventId(), envelope.sequence(),
                    broadcast.getLastSequence());
            return ProcessResult.IGNORED_STALE;
        }
        return ProcessResult.PROCESSED;
    }

    /**
     * 편지의 플랫폼 칸(POK-398). 안 실었거나 비었으면 치지직이다(칸이 생기기 전 편지, 1번이 아직 안 싣는 편지).
     * 형식 밖이면 편지를 버리지 않고 치지직으로 저장한다: 버리면 방송 줄이 안 생겨 그 방송이 통째로 사라진다.
     * WARN에는 값 원문 대신 길이만 찍는다. 큐로 받은 값이라 개행이 섞이면 로그 한 줄을 위조할 수 있다.
     */
    private static String platformOf(LifecycleEnvelope envelope) {
        String raw = envelope.platform();
        if (raw == null || raw.isBlank()) {
            return Platform.DEFAULT;
        }
        if (Platform.isWellFormed(raw)) {
            return raw;
        }
        log.warn("broadcast.platform_invalid streamId={} eventId={} length={} stored={}",
                envelope.streamId(), envelope.eventId(), raw.length(), Platform.DEFAULT);
        return Platform.DEFAULT;
    }
}
