package com.pokeclip.chat.collector.broadcast.reattach;

import com.pokeclip.chat.collector.broadcast.BroadcastSessions;
import com.pokeclip.chat.collector.broadcast.EndedStreamStore;
import com.pokeclip.chat.collector.broadcast.ProcessResult;
import com.pokeclip.chat.collector.broadcast.StreamerId;
import com.pokeclip.chat.collector.broadcast.attach.LaneKey;
import com.pokeclip.chat.collector.broadcast.attach.StreamerSerialExecutor;
import com.pokeclip.chat.collector.session.SessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * clip에 「지금 방송 중인 것」을 물어, <b>붙어 있어야 하는데 안 붙은 방송</b>에 붙는다.
 *
 * <p><b>왜 필요한가</b>: 수집을 다시 시작하는 트리거가 지금까지 <b>일회용 시작 알림뿐</b>이었다.
 * 처리하면 지워지고 복구 경로가 없어서, 재배포·재시작 뒤 그 방송은 <b>끝날 때까지 안 걷힌다</b>
 * ({@code CLAUDE.md}「다음 카드로 넘긴 것」). 이 부품이 그 구멍을 메운다.
 *
 * <h2>🔴 알림 경로와 <b>같은 줄</b>에 넣는다 — 이 설계의 기둥이다</h2>
 *
 * 줄 이름은 {@link LaneKey#of}이고, {@code SqsIntakeRunner}가 쓰는 것과 <b>같은 함수</b>다.
 * 그래서 같은 스트리머의 알림 처리와 재부착은 <b>절대 겹치지 않고 제출 순서대로</b> 돈다.
 * 여기서 나오는 것이 셋이다.
 *
 * <ol>
 *   <li><b>{@code SessionRegistry.retargetOrSkip}의 비원자성이 지금도 안 열린다.</b>
 *       그 {@code SEAT_STOPPING} 주석이 <b>「지금 도달 경로가 없는 이유는 상태가 아니라
 *       스레드 수다 … 수립을 워커로 빼는 날 이 자리를 다시 본다」</b>고 예고했고(계획 검증 I4),
 *       태스크 3이 수립을 줄로 빼고 태스크 7이 <b>둘째 제출자</b>를 붙인 지금이 그 날이다.
 *       <b>판정: 열리지 않는다.</b> 근거가 「스레드가 하나」에서
 *       <b>「같은 스트리머는 같은 줄이고 줄 안은 직렬」</b>로 바뀌었을 뿐이다 —
 *       {@code registry.open}을 부르는 자리가 {@code LinkedSessionStarter} 하나뿐이고, 두 경로
 *       모두 {@code StreamerId.parse}가 성공한 것만 그 문에 넣으므로 줄 이름이 언제나
 *       {@code Long.toString(streamerId)}(= 자리 열쇠)와 <b>일대일</b>이다.
 *       {@code 재부착은_알림_경로와_같은_줄에_들어간다}가 {@code "007"} 대 {@code "7"}로 잰다.</li>
 *   <li><b>아래 재확인 둘이 「사이에 아무 일도 안 일어난다」를 보장받는다.</b> 목록 조회는
 *       스케줄러 스레드에서 도는데, 줄에 들어온 뒤로는 같은 스트리머의 ENDED·STARTED가
 *       끼어들 수 없다. <b>재확인이 필요한 이유와 재확인으로 충분한 이유가 서로 다르다</b> —
 *       필요한 이유는 목록 조회가 줄 밖이라서, 충분한 이유는 줄이 직렬이라서다.</li>
 *   <li><b>{@code dropPending}을 여기서 부르지 않는다.</b> 아래 {@link #attachOne} 참고.</li>
 * </ol>
 *
 * <h2>이 부품이 여는 손실 경로 하나 — 백프레셔를 알림 경로와 나눠 쓴다</h2>
 *
 * {@code StreamerSerialExecutor}의 상한은 <b>프로세스에 하나</b>다. 재부착이 방송 여럿을
 * 한꺼번에 제출하면 {@code saturated()}가 서고, {@code SqsIntakeRunner}가 그것을 보고
 * <b>큐를 두드리는 것 자체를 쉰다.</b> <b>알림은 큐에 그대로 남으므로 유실이 아니라 지연이다</b>
 * (그리고 그 지연은 health의 {@code letterStalled}가 2분 뒤 드러낸다).
 * 반대로 재부착이 밀리는 것은 {@code chat.reattach.deferred}가 남긴다.
 */
public class Reattacher {

    private static final Logger log = LoggerFactory.getLogger(Reattacher.class);

    private final LiveBroadcastClient client;
    private final SessionRegistry registry;
    private final EndedStreamStore store;
    private final GapMeasurer measurer;
    private final StreamerSerialExecutor lanes;
    private final BroadcastSessions sessions;
    private final Supplier<Instant> clock;

    /**
     * 숫자로 못 읽은 스트리머 식별자의 수. <b>1번이 식별자 체계를 바꾸면 모든 방송이 이 길이다</b> —
     * 로그만으로는 「한 건 이상했다」와 구분이 안 된다. 판정기의 같은 이름 카운터와 짝이다.
     */
    private final AtomicLong unreadableStreamerIds = new AtomicLong();

    public Reattacher(LiveBroadcastClient client, SessionRegistry registry, EndedStreamStore store,
                      GapMeasurer measurer, StreamerSerialExecutor lanes,
                      BroadcastSessions sessions, Supplier<Instant> clock) {
        this.client = client;
        this.registry = registry;
        this.store = store;
        this.measurer = measurer;
        this.lanes = lanes;
        this.sessions = sessions;
        this.clock = clock;
    }

    /**
     * 한 바퀴. <b>던지지 않는다</b> — {@code @Scheduled}는 태스크가 한 번이라도 던지면 그 뒤
     * 주기가 안 돈다. 재부착이 영영 멈추는데 아무 신호도 없다({@code EndedStreamSweeper}와
     * 같은 이유이자 같은 폭).
     *
     * <p><b>대신 성패를 돌려준다.</b> 삼키기만 하면 clip에 몇 시간을 못 닿아도 밖에서는
     * 아무 차이가 없다 — 이 카드가 새로 만든 사각이라 {@code ReattachScheduler}가 이 값을
     * {@link ReattachStatus}에 옮기고 health가 그것을 드러낸다. <b>여기서 직접 안 쓰는 이유</b>는
     * 이 부품이 「무엇을 줍나」만 알아야 해서다(검사 열셋이 이 생성자를 직접 부른다).
     *
     * @return 한 바퀴가 통째로 돌았으면 {@code true}. 개별 방송의 붙이기 실패는 여기 안 실린다 —
     *         그것은 {@code chat.reattach.attach_failed}가 방송 번호와 함께 남긴다
     */
    public boolean sweep() {
        try {
            sweepOnce();
            return true;
        } catch (Throwable t) {
            // clip 주소·내부 토큰이 예외 메시지에 실릴 수 있다. 타입 이름만 남긴다.
            // <b>예외 객체를 인자로 넘기지 마라</b> — SLF4J가 throwable로 인식해 메시지와
            // 스택트레이스를 통째로 렌더한다(이 서버가 실제로 데인 자리, codex P2).
            log.warn("chat.reattach.failed causeType={}", t.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * <b>거르는 순서가 뜻을 가른다 — 싼 것부터다.</b> 이미 붙어 있나(메모리) → 메모가 있나
     * (DB 한 번) → 스트리머를 읽을 수 있나(파싱). 평상시에는 목록의 거의 전부가 첫 거름망에서
     * 빠지므로, 순서를 뒤집으면 매 주기 DB를 방송 수만큼 두드린다.
     *
     * <p><b>거름망이 두 겹이다</b>(여기 + {@link #attachOne}). 겹치는 것이 낭비가 아니라
     * 역할이 다르다 — 여기는 <b>비용</b>을 아끼고, 저기는 <b>줄에 들어간 뒤 벌어진 일</b>을 본다.
     */
    private void sweepOnce() {
        LiveBroadcasts live = client.list();
        Set<String> attached = Set.copyOf(registry.activeStreamIds());
        List<LiveBroadcasts.Item> candidates = live.broadcasts().stream()
                .filter(item -> !attached.contains(item.streamId()))
                .toList();
        // 메모 조회는 한 번이다 — 낱개로 물으면 방송 수만큼 왕복한다.
        Set<String> remembered = store.findAllIds(
                candidates.stream().map(LiveBroadcasts.Item::streamId).toList());

        int submitted = 0;
        int deferred = 0;
        for (LiveBroadcasts.Item item : candidates) {
            if (remembered.contains(item.streamId())) {
                continue;   // 끝났거나 포기한 방송이다
            }
            StreamerId streamer = StreamerId.parse(item.streamerId());
            if (!streamer.valid()) {
                log.warn("chat.reattach.streamer_id_unreadable stream={}", item.streamId());
                unreadableStreamerIds.incrementAndGet();
                continue;
            }
            // 🔴 시각이 없으면 EPOCH다. 갈아끼움은 「더 늦게 시작한 방송만」이라 EPOCH는
            // 절대 못 이긴다 — 자리가 비었을 때만 붙고 살아 있는 세션을 안 뺏는다.
            Instant startedAt = item.startedAt() != null ? item.startedAt() : Instant.EPOCH;
            // 🔴 원문이 아니라 LaneKey다. 알림 경로와 같은 줄에 들어가는 것이 이 설계의 기둥이고,
            // 표기가 갈리는 여지가 바로 여기다 — 두 발행자가 다른 시스템이다(1번의 SQS 봉투 vs
            // clip 명부의 칸). 클래스 javadoc 1번 항목이 무엇이 걸려 있는지를 적어 뒀다.
            String lane = LaneKey.of(item.streamerId());
            if (lanes.submit(lane, () -> attachOne(item.streamId(), streamer, startedAt))) {
                submitted++;
            } else {
                deferred++;
            }
        }
        if (deferred > 0) {
            // <b>버린 것이 아니라 미룬 것이다.</b> 다음 회차가 같은 목록을 다시 받는다 —
            // 재부착은 상태가 없어서 「어디까지 했다」를 기억할 필요가 없다.
            log.warn("chat.reattach.deferred count={} reason=LANES_FULL", deferred);
        }
        log.info("chat.reattach.swept received={} candidates={} submitted={} deferred={}",
                live.broadcasts().size(), candidates.size(), submitted, deferred);
    }

    /**
     * 줄 안에서 도는 본체. <b>이 안에서는 같은 스트리머의 알림 처리가 끼어들 수 없다</b>
     * (클래스 javadoc 2번 항목).
     *
     * <p>🔴 <b>여기서 {@link StreamerSerialExecutor#dropPending}을 부르지 않는다.</b>
     * 그 줄의 대기열에는 <b>알림 경로의 배치</b>가 들어 있을 수 있고, 버리면 그 알림들은
     * 지워지지 않은 채 가시성 시한이 지나야 다시 온다. 재부착 하나가 실패했다는 사실은
     * 그 알림들도 실패한다는 근거가 <b>아니므로</b> 얻는 것 없이 늦추기만 한다.
     * (알림 경로가 부르는 것은 정반대 이유다 — 거기서는 <b>앞엣것이 실패한 채로 뒤엣것이
     * 처리·삭제되면 영구 유실</b>이라 버리는 쪽이 싸다.)
     *
     * <p><b>반대 방향은 해롭지 않다</b>: 알림 배치가 실패해 {@code dropPending}이 대기 중인
     * 재부착 작업을 같이 버려도, 재부착은 상태가 없고 주기적이라 다음 회차가 같은 목록을
     * 다시 받는다. 그래서 이 부품은 「제출했다」를 기억하지 않는다.
     *
     * <p><b>예외를 삼키고 이름을 남긴다.</b> 안 잡아도 실행기의 {@code catch (Throwable)}이
     * 받아 줄은 안 멈추지만, 그 로그에는 <b>줄 이름만 있고 방송 번호가 없다</b> —
     * 「어느 방송이 안 붙었나」가 이 부품의 유일한 진단이다.
     */
    private void attachOne(String streamId, StreamerId streamer, Instant startedAt) {
        try {
            attachOnce(streamId, streamer, startedAt);
        } catch (Throwable t) {
            log.warn("chat.reattach.attach_failed stream={} causeType={}",
                    streamId, t.getClass().getSimpleName());
        }
    }

    private void attachOnce(String streamId, StreamerId streamer, Instant startedAt) {
        // 🔴 <b>메모를 여기서 다시 본다</b>(계획 검증 M2). 위 sweepOnce의 findAllIds는 줄에
        // 넣기 <b>전</b>에, 스케줄러 스레드에서 돌았다. 그 사이 ENDED 알림이 처리되면
        // <b>끝난 방송에 세션이 선다.</b>
        //
        // 그리고 그것을 막을 층이 아래에 없다 — LinkedSessionStarter는 EndedStreamStore를
        // 안 본다. 그 검사는 BroadcastEventProcessor.handleStarted에만 있고
        // <b>재부착은 그 층을 안 탄다.</b>
        //
        // 닫을 트리거도 없다: 그 방송의 ENDED는 이미 소비돼 큐에 없다. 계정당 세 자리 중
        // 하나를 프로세스가 끝날 때까지 먹고, 창구는 그 방송에 collecting을 답한다.
        if (store.find(streamId).isPresent()) {
            log.info("chat.reattach.skipped_late stream={} reason=MEMO_APPEARED", streamId);
            return;
        }
        // 🔴 <b>그 사이에 붙었는지도 다시 본다</b>(계획 검증 T5). sessions.start는
        // 「이미 걷고 있음」과 「새로 열었음」에 <b>똑같이 PROCESSED</b>를 준다
        // (LinkedSessionStarter의 첫 갈래). 안 가르면 gap_measured가 <b>붙지도 않은
        // 재부착의 공백</b>을 찍어, 나중에 그 로그를 세면 유실이 실제보다 많아 보인다.
        if (streamId.equals(registry.currentStreamIdOf(streamer.value()))) {
            log.info("chat.reattach.skipped_late stream={} reason=ALREADY_ATTACHED", streamId);
            return;
        }
        // <b>재는 것이 붙기보다 먼저다.</b> 붙고 나면 새 채팅이 들어와 마지막 채팅 시각이 바뀐다.
        Gap gap = measurer.measure(streamId, startedAt, clock.get());
        ProcessResult result = sessions.start(streamId, streamer, startedAt);
        if (result != ProcessResult.PROCESSED) {
            // 살아 있는 뒤 방송을 못 이긴 것(IGNORED_STALE)과 auth가 아픈 것(RETRY_LATER)이
            // 여기로 온다. <b>둘 다 다음 회차가 같은 목록을 다시 받으므로 여기서 재시도하지
            // 않는다</b> — 여기서 돌면 그 줄이 그동안 남의 알림을 막는다.
            log.info("chat.reattach.not_attached stream={} result={}", streamId, result);
            return;
        }
        log.info("chat.reattach.gap_measured stream={} basis={} since={} gapMs={}",
                streamId, gap.basis(), gap.since(), gap.gapMs());
    }

    public long unreadableStreamerIds() {
        return unreadableStreamerIds.get();
    }
}
