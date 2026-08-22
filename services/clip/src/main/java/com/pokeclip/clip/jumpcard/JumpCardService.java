package com.pokeclip.clip.jumpcard;

import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.jumpcard.JumpCardErrors.BroadcastNotFoundException;
import com.pokeclip.clip.jumpcard.JumpCardErrors.ClaimedByOtherException;
import com.pokeclip.clip.jumpcard.JumpCardErrors.JumpCardNotFoundException;
import com.pokeclip.clip.jumpcard.JumpCardErrors.NotClaimOwnerException;
import com.pokeclip.clip.jumpcard.api.HighlightRequest;
import com.pokeclip.clip.jumpcard.stream.CardStreamRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.function.IntSupplier;

/** 카드를 쓰는 유일한 자리. 쓰기는 전부 네이티브 SQL이라 DB 시계와 순번 트리거를 탄다. */
@Service
public class JumpCardService {

    private static final Logger log = LoggerFactory.getLogger(JumpCardService.class);

    /** {@code created}가 거짓이면 같은 창의 카드가 이미 있었다는 뜻이다(200 대 201을 가른다). */
    public record RecordResult(boolean created, JumpCardSnapshot card) {
    }

    private final JumpCardRepository cards;
    private final BroadcastRepository broadcasts;
    private final JumpCardProperties properties;
    private final ObjectMapper mapper;
    private final CardStreamRegistry registry;

    JumpCardService(JumpCardRepository cards, BroadcastRepository broadcasts,
                    JumpCardProperties properties, ObjectMapper mapper, CardStreamRegistry registry) {
        this.cards = cards;
        this.broadcasts = broadcasts;
        this.properties = properties;
        this.mapper = mapper;
        this.registry = registry;
    }

    @Transactional
    public RecordResult record(String streamId, HighlightRequest request) {
        // FK 위반으로 막는 대신 먼저 본다 — FK 위반은 "진짜 실패"로 분류돼 500이 되고,
        // 판별기는 404를 받아야 재시도 상한을 센다.
        broadcasts.findByStreamId(streamId).orElseThrow(() -> new BroadcastNotFoundException(streamId));

        JumpCardSource source = JumpCardSource.fromDbValue(request.source());
        String evidence = request.evidence() == null ? null : mapper.writeValueAsString(request.evidence());

        int inserted = cards.insertIfAbsent(streamId, source.dbValue(), request.eventId(),
                request.streamTimestampMs(), request.window().startMs(), request.window().endMs(),
                request.score(), evidence);

        JumpCard card = cards.findByStreamIdAndSourceAndWindowStartMs(streamId, source, request.window().startMs())
                .orElseThrow();
        JumpCardSnapshot snapshot = snapshot(card);

        if (inserted == 1) {
            publishAfterCommit(snapshot);
        } else {
            log.info("jumpcard.duplicate_skipped streamId={} source={} windowStartMs={} eventId={}",
                    streamId, source, request.window().startMs(), request.eventId());
        }
        return new RecordResult(inserted == 1, snapshot);
    }

    @Transactional
    public JumpCardSnapshot claim(long id, String userId) {
        int updated = cards.claim(id, userId, properties.claimTtl().toSeconds());
        JumpCard card = cards.findById(id).orElseThrow(() -> new JumpCardNotFoundException(id));
        if (updated == 0) {
            // 행은 있는데 못 잡았다 = 남이 잡고 있다. 「없는 카드」는 위 orElseThrow가 이미 갈랐다.
            throw new ClaimedByOtherException(snapshot(card));
        }
        JumpCardSnapshot snapshot = snapshot(card);
        publishAfterCommit(snapshot);
        return snapshot;
    }

    @Transactional
    public void release(long id, String userId) {
        // 먼저 읽는 이유: 영향 행 0이 「없는 카드」인지 「남의 것」인지 갈라야 404와 403이 다르게 나간다.
        JumpCard card = cards.findById(id).orElseThrow(() -> new JumpCardNotFoundException(id));
        // 영향 행 0의 뜻이 둘이다 — 「남이 잡고 있다」와 「아무도 안 잡았다」.
        // 후자는 이미 목표 상태이므로 성공으로 본다(멱등). 전자만 403이다.
        if (cards.release(id, userId) == 0 && card.getClaimedBy() != null) {
            throw new NotClaimOwnerException(id);
        }
        publishAfterCommit(snapshot(cards.findById(id).orElseThrow()));
    }

    @Transactional
    public JumpCardSnapshot hide(long id, String userId) {
        return touch(id, () -> cards.hide(id, userId));
    }

    @Transactional
    public JumpCardSnapshot unhide(long id, String userId) {
        return touch(id, () -> cards.unhide(id));
    }

    private JumpCardSnapshot touch(long id, IntSupplier write) {
        if (write.getAsInt() == 0) {
            throw new JumpCardNotFoundException(id);
        }
        JumpCardSnapshot snapshot = snapshot(cards.findById(id).orElseThrow());
        publishAfterCommit(snapshot);
        return snapshot;
    }

    @Transactional(readOnly = true)
    public List<JumpCardSnapshot> snapshotsOf(String streamId) {
        return cards.findAllByStreamIdOrderByEventSeqAsc(streamId).stream().map(this::snapshot).toList();
    }

    private JumpCardSnapshot snapshot(JumpCard card) {
        return JumpCardSnapshot.of(card, properties.claimTtl(), mapper);
    }

    /**
     * 카드가 생기거나 바뀌었다고 알리는 유일한 자리. <b>커밋 뒤에만</b> 보낸다 —
     * 커밋 전에 보내면 되감긴 카드가 화면에 뜨고, 지울 방법이 없다.
     *
     * <p>{@code afterCommit} 안에서는 <b>제출만</b> 한다. 전송은 {@code CardStreamExecutor}의
     * 전용 스레드가 하므로 요청 스레드가 느린 브라우저에 묶이지 않는다 — 커밋 뒤 처리를 요청
     * 스레드에 이어 붙였다가 커넥션 풀 데드락을 낸 자리가 POK-93이다(ChzzkCleanupExecutor와 같은 분리).
     *
     * <p>트랜잭션이 없으면 {@code IllegalStateException}이 난다. 그게 맞다 — 자기 호출로
     * {@code @Transactional} 프록시를 안 탄 것을 여기서 잡아 준다.
     */
    private void publishAfterCommit(JumpCardSnapshot snapshot) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                registry.publish(snapshot);
            }
        });
    }
}
