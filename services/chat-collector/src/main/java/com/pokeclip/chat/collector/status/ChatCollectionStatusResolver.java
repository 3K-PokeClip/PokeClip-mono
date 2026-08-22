package com.pokeclip.chat.collector.status;

import com.pokeclip.chat.collector.CollectionStatus;
import com.pokeclip.chat.collector.broadcast.EndedStream;
import com.pokeclip.chat.collector.broadcast.EndedStreamStore;
import com.pokeclip.chat.collector.session.SessionRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 방송 번호 하나의 수집 상태를 답한다. <b>등록부 → 메모 → unknown</b> 순서다.
 *
 * <p>등록부에 있으면 지금 걷고 있는(또는 방금 포기한) 방송이다. 없으면 메모를 본다 —
 * 포기 메모면 stopped, 종료 메모면 ended. 둘 다 없으면 모르는 방송이다(24시간 지난 것 포함).
 * <b>답은 언제나 한 장이고 던지지 않는다.</b> 부르는 쪽(clip)이 「모름」과 「장애」를 가를 수
 * 있어야 해서다.
 */
@Component
public class ChatCollectionStatusResolver {

    /** {@code chat_ended_streams.stream_id}·{@code broadcasts.stream_id}의 폭. 넘는 번호는 표에 있을 수 없다. */
    static final int MAX_STREAM_ID_LENGTH = 128;

    private final SessionRegistry registry;
    private final EndedStreamStore store;
    private final Supplier<Instant> clock;

    @Autowired
    public ChatCollectionStatusResolver(SessionRegistry registry, EndedStreamStore store) {
        this(registry, store, Instant::now);
    }

    ChatCollectionStatusResolver(SessionRegistry registry, EndedStreamStore store, Supplier<Instant> clock) {
        this.registry = registry;
        this.store = store;
        this.clock = clock;
    }

    public ChatCollectionStatus resolve(String streamId) {
        Instant now = clock.get();
        if (streamId.length() > MAX_STREAM_ID_LENGTH) {
            return unknown(streamId, now);
        }
        // <b>스냅숏 한 참조로 읽는다.</b> 낱개 getter를 이어 부르면 그 사이 재접속이 성공해
        // 「reconnecting인데 끊긴 시각 없음」이 나온다(CollectionStatus 주석).
        CollectionStatus.Snapshot live = registry.statusOf(streamId);
        if (live != null) {
            return fromLive(streamId, live, now);
        }
        Optional<EndedStream> memo = store.find(streamId);
        return memo.map(m -> fromMemo(streamId, m, now)).orElseGet(() -> unknown(streamId, now));
    }

    private static ChatCollectionStatus fromLive(String streamId, CollectionStatus.Snapshot live, Instant now) {
        CollectionState state = CollectionState.of(live);
        return switch (state) {
            case RECONNECTING -> new ChatCollectionStatus(streamId, state.wireName(),
                    live.disconnectedAt(), live.attempt(), false, now);
            // 등록부가 지우기 직전의 찰나다. 포기 시각은 스냅숏에 없으니 지금으로 — 메모가 남으면 created_at이 이긴다.
            case STOPPED -> new ChatCollectionStatus(streamId, state.wireName(),
                    now, null, CollectionState.needsRelink(live.reason()), now);
            default -> new ChatCollectionStatus(streamId, state.wireName(), null, null, false, now);
        };
    }

    private static ChatCollectionStatus fromMemo(String streamId, EndedStream memo, Instant now) {
        if (memo.stopped()) {
            return new ChatCollectionStatus(streamId, CollectionState.STOPPED.wireName(),
                    memo.createdAt(), null, CollectionState.needsRelink(memo.stopReason()), now);
        }
        return new ChatCollectionStatus(streamId, CollectionState.ENDED.wireName(),
                memo.endedAt(), null, false, now);
    }

    private static ChatCollectionStatus unknown(String streamId, Instant now) {
        return new ChatCollectionStatus(streamId, CollectionState.UNKNOWN.wireName(), null, null, false, now);
    }
}
