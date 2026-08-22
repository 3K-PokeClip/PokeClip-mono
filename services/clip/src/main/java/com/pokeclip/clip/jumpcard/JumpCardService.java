package com.pokeclip.clip.jumpcard;

import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.jumpcard.JumpCardErrors.BroadcastNotFoundException;
import com.pokeclip.clip.jumpcard.api.HighlightRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

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

    JumpCardService(JumpCardRepository cards, BroadcastRepository broadcasts,
                    JumpCardProperties properties, ObjectMapper mapper) {
        this.cards = cards;
        this.broadcasts = broadcasts;
        this.properties = properties;
        this.mapper = mapper;
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

    @Transactional(readOnly = true)
    public List<JumpCardSnapshot> snapshotsOf(String streamId) {
        return cards.findAllByStreamIdOrderByEventSeqAsc(streamId).stream().map(this::snapshot).toList();
    }

    private JumpCardSnapshot snapshot(JumpCard card) {
        return JumpCardSnapshot.of(card, properties.claimTtl(), mapper);
    }

    /** 카드가 생기거나 바뀌었다고 알리는 유일한 자리. 태스크 10이 커밋 뒤 제출로 채운다. */
    private void publishAfterCommit(JumpCardSnapshot snapshot) {
    }
}
