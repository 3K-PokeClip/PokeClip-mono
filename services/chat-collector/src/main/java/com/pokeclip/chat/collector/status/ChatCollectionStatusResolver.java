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
 * <b>답은 언제나 한 장이다</b> — 모르는 방송에도 404가 아니라 unknown을 준다. 부르는 쪽(clip)이
 * 「모름」과 「장애」를 가를 수 있어야 해서다.
 *
 * <p><b>「던지지 않는다」는 아니다 — DB가 죽으면 500이 나간다.</b> 여기 한때 그렇게 적혀 있었는데
 * {@code store.find}가 던지는 갈래를 안 센 것이었다(2026-08-22 감사 재현: 죽은 포트를 문 저장소로
 * 실제 HTTP를 쳐서 500. 본문·로그 어디에도 DB 주소·계정·예외 메시지는 안 실린다 — 전수 0건).
 * <b>고치지 않는다. 500이 옳은 신호다</b> — 예외를 삼켜 unknown을 답하면 장애 중에 배너가 꺼져
 * 가장 나쁜 상태가 가장 안전한 답으로 보인다. 이 창구가 가장 피하려는 방향이 그것이다.
 * clip은 500을 「모름」으로 접지 말고 「수집 서버 장애」로 읽어야 한다 — {@code services/README.md}에
 * 같은 문장이 있다.
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

    /**
     * <b>{@code observedAt}은 갈래마다 답을 만들기 직전에 찍는다. 메서드 첫 줄로 올리지 마라.</b>
     * 거기서 찍으면 {@code store.find}가 느린 동안(반개방이면 최악 10초, {@code socketTimeout}) 그만큼
     * 낡은 시각이 실린다 — 실측: DB를 1·3·9초 재우면 {@code observedAt}이 정확히 1004·3009·9013ms
     * 과거였다(2026-08-23, {@code pg_sleep}으로 진짜 지연). <b>더 나쁜 갈래는 부호가 뒤집히는 것이다</b> —
     * 조회가 도는 동안 그 방송의 포기 메모가 남으면 {@code since}(메모의 {@code created_at})가
     * {@code observedAt}보다 <b>미래</b>가 되어, clip이 {@code observedAt - since}로 재는
     * 「얼마나 오래 멈췄나」가 <b>음수</b>로 나간다(실측 −1501ms). 조회를 마친 뒤에 찍으면 그 시각은
     * 조회가 그 행을 본 시점 이후이므로 두 갈래가 같이 닫힌다.
     *
     * <p>DB를 묻는 갈래 둘({@code fromMemo}·{@code unknown})은 <b>조회 뒤 한 번만</b> 찍는다 —
     * 갈래마다 따로 찍으면 같은 조회 결과에 다른 시각이 붙는다.
     */
    public ChatCollectionStatus resolve(String streamId) {
        if (streamId.length() > MAX_STREAM_ID_LENGTH) {
            return unknown(streamId, clock.get());
        }
        // <b>스냅숏 한 참조로 읽는다.</b> 낱개 getter를 이어 부르면 그 사이 재접속이 성공해
        // 「reconnecting인데 끊긴 시각 없음」이 나온다(CollectionStatus 주석).
        CollectionStatus.Snapshot live = registry.statusOf(streamId);
        if (live != null) {
            // 스냅숏을 읽은 뒤에 찍는다 — reconnecting의 since(disconnectedAt)보다 앞선 observedAt이
            // 안 나가게. 이 갈래에서 now는 observedAt으로만 쓰인다(STOPPED의 since는 null이다).
            return fromLive(streamId, live, clock.get());
        }
        Optional<EndedStream> memo = store.find(streamId);
        Instant now = clock.get();
        return memo.map(m -> fromMemo(streamId, m, now)).orElseGet(() -> unknown(streamId, now));
    }

    private static ChatCollectionStatus fromLive(String streamId, CollectionStatus.Snapshot live, Instant now) {
        CollectionState state = CollectionState.of(live);
        return switch (state) {
            case RECONNECTING -> new ChatCollectionStatus(streamId, state.wireName(),
                    live.disconnectedAt(), live.attempt(), false, now);
            // 등록부가 지우기 직전의 찰나다. <b>포기 시각은 스냅숏에 없으므로 since를 비운다.</b>
            // 여기 now를 실었더니 메모가 남기 전까지(반개방이면 최악 10초) 부를 때마다 since가 그 호출
            // 시각으로 갱신됐다 — clip이 observedAt - since로 「얼마나 오래 멈췄나」를 재면 그 구간 내내
            // 0이다가 메모가 저장된 뒤 첫 호출에서 갑자기 그만큼 뛴다. 모르는 값을 그럴듯하게 지어내지
            // 않는다(attempt도 이 상태에선 null이다). 진짜 값은 메모가 남는 순간 created_at으로 온다.
            case STOPPED -> new ChatCollectionStatus(streamId, state.wireName(),
                    null, null, CollectionState.needsRelink(live.reason()), now);
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
