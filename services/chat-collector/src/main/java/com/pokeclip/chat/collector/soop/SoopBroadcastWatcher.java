package com.pokeclip.chat.collector.soop;

import com.pokeclip.chat.collector.Platform;
import com.pokeclip.chat.collector.liveinfo.BroadcastInfo;
import com.pokeclip.chat.collector.liveinfo.BroadcastInfoCollector;
import com.pokeclip.chat.collector.platforms.PlatformNotifier;
import com.pokeclip.chat.collector.relay.RelayPayload;
import com.pokeclip.chat.collector.relay.RelaySink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * SOOP 연동 스트리머의 방송을 지켜본다(POK-397). 시작 편지·재부착이 넣고 종료 편지·재부착 떼기·탈퇴 정리가 뺀다. 1분마다 방송마다
 * SOOP 방송 여부를 묻고, 방송 중이면 방송 정보 한 줄(platform SOOP, 채널 = SOOP 아이디)을 남기고 화면에 밀고 clip 묶음에 SOOP을 알린다.
 *
 * <p><b>채팅 세션 자리(등록부)를 안 쓴다.</b> SOOP 채팅 수집은 PR4다. 그래서 탈퇴 정리·중계 번호 카운터를 등록부가 대신해 주지 않는다:
 * 정리는 {@link #unwatchChannel}로, 카운터는 뺄 때 등록부에 그 방송 세션이 없으면 직접 치운다(계획 검증 M3·L6).
 *
 * <p><b>넣고 빼기는 I/O가 없다.</b> 편지 스레드가 부른다. 연동 조회와 상태 확인은 {@link #tick()}이 스케줄러 한 스레드에서 한다.
 * 한 틱은 {@link #TICK_BUDGET}에서 멈추고 남은 방송은 다음 틱에 먼저 본다. SOOP 시한은 접속 1초·읽기 2초({@link SoopWatchConfiguration}).
 *
 * <p>스트리머마다 하나다. 같은 스트리머의 방송이 또 들어오면 더 늦게 시작한 쪽이 갈아낀다(등록부와 같은 규칙, L7): 종료 편지를 놓친
 * 옛 방송에 새 방송의 SOOP 값이 적히지 않게 한다.
 *
 * <p>🔴 토큰·SOOP 아이디는 로그에 안 싣는다. 방송 번호·회원 번호·사유만.
 */
public class SoopBroadcastWatcher implements SoopWatch {

    private static final Logger log = LoggerFactory.getLogger(SoopBroadcastWatcher.class);

    /** 토큰 만료가 이만큼 남으면 다시 받는다. auth는 2시간 전부터 갈므로 그 사이 새 토큰이 와 있다. */
    static final Duration REFRESH_BEFORE = Duration.ofMinutes(10);

    /**
     * 한 틱의 시간 예산(감사 2회차 n2). 방송마다 auth·SOOP·clip을 부르므로 SOOP이 매달리면 방송 100개 × 몇 초가 한 틱에 더해진다. 넘으면 남은 방송은
     * 다음 틱으로 미루고 다음 틱은 미룬 것부터 본다(치지직 방송 정보의 알림 예산과 같은 꼴). 주기 1분보다 짧게 둔다.
     */
    static final Duration TICK_BUDGET = Duration.ofSeconds(45);

    /** 같은 토큰이 이만큼 이어서 거부되면 멈춘다(감사 2회차 n4). auth가 아직 안 간 토큰을 다시 주는 동안은 기다린다. */
    static final int MAX_SAME_TOKEN_REJECTIONS = 3;

    private final LongFunction<SoopLink> links;
    private final Function<String, SoopLiveStatus> live;
    private final BroadcastInfoCollector.Store store;
    private final RelaySink relay;
    private final PlatformNotifier platforms;
    private final Supplier<Collection<String>> sessionStreams;
    private final Function<Collection<String>, Set<String>> endedStreams;
    private final int maxWatched;
    private final Clock clock;
    private final Duration tickBudget;
    /** 지난 틱에 예산이 다해 못 본 방송 번호. 틱 스레드만 만진다. */
    private final Set<String> deferred = new java.util.LinkedHashSet<>();

    /** 스트리머 번호 → 지켜보기. 고치는 쪽(넣고 빼기)은 {@code this}로 잠근다, 틱은 사본을 돈다. */
    private final Map<Long, Watch> byStreamer = new ConcurrentHashMap<>();

    /** 한 방송의 지켜보기. 연동 칸은 틱 스레드만 고친다. */
    private static final class Watch {
        final String streamId;
        final long streamerId;
        final Instant startedAt;
        volatile String soopUserId;
        String accessToken;
        Instant expiresAt;
        /** 영구 거절(연동 없음·해제·끊김) 또는 새 토큰도 거부. 그 방송 동안 다시 안 묻는다. 상한 판정(다른 스레드)이 읽는다. */
        volatile boolean stopped;
        /** 다시 받아도 같은 토큰이 거부된 횟수(n4). 받아들여지면 0. */
        int sameTokenRejections;

        Watch(String streamId, long streamerId, Instant startedAt) {
            this.streamId = streamId;
            this.streamerId = streamerId;
            this.startedAt = startedAt;
        }
    }

    /**
     * @param links          회원 번호 → SOOP 연동(운영 {@code SoopLinkClient::resolve}). 던지지 않는다
     * @param live           토큰 → 방송 여부(운영 {@code SoopLiveClient::check}). 던지지 않는다
     * @param sessionStreams 등록부가 지금 걷는 방송 번호(운영 {@code SessionRegistry::activeStreamIds}). 카운터를 치울지 가른다
     */
    public SoopBroadcastWatcher(LongFunction<SoopLink> links, Function<String, SoopLiveStatus> live,
                                BroadcastInfoCollector.Store store, RelaySink relay, PlatformNotifier platforms,
                                Supplier<Collection<String>> sessionStreams, int maxWatched, Clock clock) {
        this(links, live, store, relay, platforms, sessionStreams, ids -> Set.of(), maxWatched, clock, TICK_BUDGET);
    }

    /**
     * @param endedStreams 방송 번호 묶음 → 그중 종료 메모가 있는 것(운영 {@code EndedStreamStore.findAll}에서 정상 종료만). 틱마다 지켜보는 방송을
     *                     한 번에 묻는다(n5)
     * @param tickBudget   한 틱 시간 예산. 운영은 {@link #TICK_BUDGET}, 시험은 짧게 준다
     */
    public SoopBroadcastWatcher(LongFunction<SoopLink> links, Function<String, SoopLiveStatus> live,
                                BroadcastInfoCollector.Store store, RelaySink relay, PlatformNotifier platforms,
                                Supplier<Collection<String>> sessionStreams,
                                Function<Collection<String>, Set<String>> endedStreams, int maxWatched, Clock clock,
                                Duration tickBudget) {
        this.endedStreams = endedStreams;
        this.tickBudget = tickBudget;
        this.links = links;
        this.live = live;
        this.store = store;
        this.relay = relay;
        this.platforms = platforms;
        this.sessionStreams = sessionStreams;
        this.maxWatched = maxWatched;
        this.clock = clock;
    }

    @Override
    public synchronized void watch(String streamId, long streamerId, Instant startedAt) {
        Watch current = byStreamer.get(streamerId);
        if (current != null) {
            if (current.streamId.equals(streamId) || !startedAt.isAfter(current.startedAt)) {
                return;
            }
            byStreamer.put(streamerId, new Watch(streamId, streamerId, startedAt));
            forgetCounter(current.streamId);
            log.info("soop.watch.replaced stream={} previous={}", streamId, current.streamId);
            return;
        }
        // 상한은 확인 중인 것만 센다(n1). 재부착이 명부의 모든 방송을 넣으므로 SOOP을 연동 안 한 스트리머도 멈춘 채 한 자리씩 남는다.
        if (byStreamer.values().stream().filter(watch -> !watch.stopped).count() >= maxWatched) {
            log.warn("soop.watch.full stream={} max={}", streamId, maxWatched);
            return;
        }
        byStreamer.put(streamerId, new Watch(streamId, streamerId, startedAt));
        log.info("soop.watch.added stream={} userId={}", streamId, streamerId);
    }

    @Override
    public synchronized void unwatch(String streamId) {
        removeIf(watch -> watch.streamId.equals(streamId), "ended");
    }

    @Override
    public synchronized void unwatchChannel(Platform platform, String channelId, Instant startedBefore) {
        if (platform != Platform.SOOP) {
            return;
        }
        removeIf(watch -> channelId.equals(watch.soopUserId) && watch.startedAt.isBefore(startedBefore), "purged");
    }

    @Override
    public synchronized void retainListed(Set<String> listedStreamIds, Instant startedBefore) {
        removeIf(watch -> !listedStreamIds.contains(watch.streamId) && watch.startedAt.isBefore(startedBefore), "unlisted");
    }

    private void removeIf(java.util.function.Predicate<Watch> match, String reason) {
        List<Watch> removed = byStreamer.values().stream().filter(match).toList();
        for (Watch watch : removed) {
            byStreamer.remove(watch.streamerId, watch);
            forgetCounter(watch.streamId);
            log.info("soop.watch.removed stream={} reason={}", watch.streamId, reason);
        }
    }

    /** 등록부에 그 방송 세션이 있으면 카운터는 등록부가 닫을 때 치운다. 없으면(SOOP 단독 방송) 여기서 치운다(L6). */
    private void forgetCounter(String streamId) {
        if (!sessionStreams.get().contains(streamId)) {
            relay.forget(streamId);
        }
    }

    /** 스케줄러 한 스레드에서만 불린다. 던지지 않는다({@code @Scheduled}는 한 번 던지면 주기가 죽는다). */
    public void tick() {
        try {
            dropEnded();
            List<Watch> snapshot = new ArrayList<>(byStreamer.values());
            platforms.retain(Platform.SOOP, snapshot.stream().map(watch -> watch.streamId).collect(Collectors.toSet()));
            List<Watch> order = new ArrayList<>();
            snapshot.stream().filter(watch -> deferred.contains(watch.streamId)).forEach(order::add);
            snapshot.stream().filter(watch -> !deferred.contains(watch.streamId)).forEach(order::add);
            deferred.clear();
            long started = System.nanoTime();
            for (Watch watch : order) {
                if (System.nanoTime() - started >= tickBudget.toNanos()) {
                    deferred.add(watch.streamId);
                    continue;
                }
                try {
                    checkOnce(watch);
                } catch (RuntimeException e) {
                    log.warn("soop.watch.failed stream={} causeType={}", watch.streamId, e.getClass().getSimpleName());
                }
            }
            if (!deferred.isEmpty()) {
                log.warn("soop.watch.deferred count={}", deferred.size());
            }
        } catch (Throwable e) {
            log.warn("soop.watch.tick_failed causeType={}", e.getClass().getSimpleName());
        }
    }

    /**
     * 종료 메모가 생긴 방송을 뺀다(n5). 재부착 넣기가 종료 편지와 엇갈리거나 종료 편지를 놓친 방송이 떼기 꺼진 배포에서 남으면, 스트리머가 SOOP에서
     * 바로 방송할 때 끝난 방송 번호에 SOOP 값이 쌓인다. 실패하면 이번 틱은 그냥 지난다(다음 틱에 다시 본다).
     */
    private void dropEnded() {
        Set<String> watched = byStreamer.values().stream().map(watch -> watch.streamId).collect(Collectors.toSet());
        if (watched.isEmpty()) {
            return;
        }
        Set<String> ended;
        try {
            ended = endedStreams.apply(watched);
        } catch (RuntimeException e) {
            log.warn("soop.watch.ended_lookup_failed causeType={}", e.getClass().getSimpleName());
            return;
        }
        if (!ended.isEmpty()) {
            synchronized (this) {
                removeIf(watch -> ended.contains(watch.streamId), "ended_memo");
            }
        }
    }

    private void checkOnce(Watch watch) {
        if (watch.stopped) {
            return;
        }
        Instant now = clock.instant();
        if (watch.accessToken == null || !now.isBefore(watch.expiresAt.minus(REFRESH_BEFORE))) {
            if (!resolve(watch)) {
                return;
            }
        }
        SoopLiveStatus status = live.apply(watch.accessToken);
        if (status.outcome() == SoopLiveStatus.Outcome.REJECTED) {
            // auth가 그 사이 토큰을 갈았을 수 있다(만료 2시간 전·마지막 확인 24시간 경과 때 간다). 한 번 다시 받아 묻는다(M5).
            String rejected = watch.accessToken;
            if (!resolve(watch)) {
                return;
            }
            if (rejected.equals(watch.accessToken)) {
                // 같은 토큰이다(n4): auth가 아직 안 갈았다. 같은 것으로 또 묻지 않고 일시 실패로 다음 틱에 다시 받는다. 이어서 여러 번이면 멈춘다.
                watch.sameTokenRejections++;
                watch.accessToken = null;
                watch.expiresAt = null;
                if (watch.sameTokenRejections >= MAX_SAME_TOKEN_REJECTIONS) {
                    watch.stopped = true;
                    log.warn("soop.watch.stopped stream={} reason=SAME_TOKEN_REJECTED", watch.streamId);
                }
                return;
            }
            status = live.apply(watch.accessToken);
            if (status.outcome() == SoopLiveStatus.Outcome.REJECTED) {
                watch.stopped = true;
                log.warn("soop.watch.stopped stream={} reason=REJECTED", watch.streamId);
                return;
            }
        }
        watch.sameTokenRejections = 0;
        if (status.outcome() != SoopLiveStatus.Outcome.ON_AIR) {
            return;
        }
        // 확인하는 사이 종료·정리·갈아낌이 왔으면 남기지 않는다. 끝난 방송에 이벤트가 가고 지운 카운터가 되살아난다(L6).
        // 확인과 남기기 사이의 좁은 창은 남는다(방송 정보 수집과 같은 한계).
        if (!stillWatched(watch)) {
            return;
        }
        Instant observedAt = clock.instant();
        BroadcastInfo info = new BroadcastInfo(Platform.SOOP, watch.streamId, watch.soopUserId, observedAt,
                status.title(), List.of(), status.category(), status.viewers());
        try {
            store.insert(info);
        } catch (RuntimeException e) {
            // 저장과 중계를 따로 시도한다(방송 정보 수집과 같은 원칙).
            log.warn("soop.watch.record_failed stream={} causeType={}", watch.streamId, e.getClass().getSimpleName());
        }
        if (stillWatched(watch)) {
            relay.offer(watch.streamId, new RelayPayload.Info(Platform.SOOP, observedAt, info.title(), info.tags(),
                    info.category(), info.viewers()));
        }
        platforms.seen(watch.streamId, Platform.SOOP, observedAt);
    }

    /** @return 쓸 만한 토큰을 받았으면 {@code true}. 영구 거절이면 그 방송을 멈추고, 일시 실패면 토큰을 비워 다음 틱에 다시 묻는다. */
    private boolean resolve(Watch watch) {
        SoopLink link = links.apply(watch.streamerId);
        if (link.usable()) {
            watch.soopUserId = link.soopUserId();
            watch.accessToken = link.accessToken();
            watch.expiresAt = link.expiresAt();
            return true;
        }
        watch.accessToken = null;
        watch.expiresAt = null;
        if (!link.retryable()) {
            watch.stopped = true;
            log.info("soop.watch.stopped stream={} reason=LINK_REFUSED", watch.streamId);
        }
        return false;
    }

    private boolean stillWatched(Watch watch) {
        return byStreamer.get(watch.streamerId) == watch;
    }
}
