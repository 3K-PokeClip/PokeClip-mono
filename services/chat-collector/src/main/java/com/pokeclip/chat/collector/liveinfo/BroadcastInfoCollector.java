package com.pokeclip.chat.collector.liveinfo;

import com.pokeclip.chat.collector.liveinfo.ChzzkLiveInfoClient.LiveEntry;
import com.pokeclip.chat.collector.liveinfo.ChzzkLiveInfoClient.LivePage;
import com.pokeclip.chat.collector.liveinfo.ChzzkLiveInfoClient.SettingResult;
import com.pokeclip.chat.collector.relay.RelayPayload;
import com.pokeclip.chat.collector.relay.RelaySink;
import com.pokeclip.chat.collector.session.SessionRegistry;
import com.pokeclip.chat.collector.session.SessionRegistry.ActiveSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 걷고 있는 방송마다 제목·태그·카테고리·시청자 수를 한 시점씩 남긴다(POK-234 PR-C).
 *
 * <p>제목·태그·카테고리는 <b>방송 설정</b>(스트리머 토큰)에서, 시청자 수는 <b>전체 라이브 목록</b>(앱 인증)에서
 * 온다 — 채널 하나의 시청자 수를 묻는 공식 창구가 없다. 목록은 시청자 수 내림차순이라 우리 채널을 전부
 * 찾으면 거기서 멈춘다. 설정이 거부(401·403)되면 목록에서 찾은 제목·태그로 채운다.
 *
 * <p>🔴 {@link #tick()}은 <b>던지지 않는다.</b> {@code @Scheduled}는 한 번 던지면 그 뒤 주기가 죽는다.
 * 🔴 제목·태그를 로그에 싣지 않는다 — 건수·방송 번호·원인 타입만.
 */
public class BroadcastInfoCollector {

    private static final Logger log = LoggerFactory.getLogger(BroadcastInfoCollector.class);
    static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    private final SessionRegistry registry;
    private final ChzzkLiveInfoClient client;
    private final Store store;
    private final RelaySink relay;
    private final LiveInfoProperties properties;
    private final Clock clock;

    private Instant pausedUntil = Instant.EPOCH;
    private Duration backoff = Duration.ZERO;
    private volatile int lastPagesScanned;

    /** 표 쓰기. 시험이 DB 없이 돌 수 있게 한 줄짜리 타입으로 받는다. */
    @FunctionalInterface
    public interface Store {
        void insert(BroadcastInfo info);
    }

    public BroadcastInfoCollector(SessionRegistry registry, ChzzkLiveInfoClient client, Store store,
                                  RelaySink relay, LiveInfoProperties properties, Clock clock) {
        this.registry = registry;
        this.client = client;
        this.store = store;
        this.relay = relay;
        this.properties = properties;
        this.clock = clock;
    }

    /** 스케줄러 한 스레드에서만 불린다 — 백오프 칸이 {@code volatile}이 아닌 이유. */
    public void tick() {
        try {
            tickOnce();
        } catch (Throwable e) {
            log.warn("liveinfo.tick_failed causeType={}", e.getClass().getSimpleName());
        }
    }

    int lastPagesScanned() {
        return lastPagesScanned;
    }

    private void tickOnce() {
        Instant now = clock.instant();
        if (now.isBefore(pausedUntil)) {
            return;
        }
        List<ActiveSession> active = registry.activeSessions();
        if (active.isEmpty()) {
            return;
        }
        Map<String, LiveEntry> found = scanLives(active.stream().map(ActiveSession::channelId)
                .collect(Collectors.toSet()), now);

        int refused = 0;
        for (ActiveSession session : active) {
            try {
                SettingResult result = client.setting(session.accessToken());
                if (result.refused()) {
                    refused++;
                }
                BroadcastInfo info = merge(session, result, found.get(session.channelId()), clock.instant());
                store.insert(info);
                relay.offer(info.streamId(), new RelayPayload.Info(info.observedAt(), info.title(), info.tags(),
                        info.category(), info.viewers()));
            } catch (RuntimeException e) {
                // 한 방송의 실패가 뒤 방송을 막지 않는다.
                log.warn("liveinfo.record_failed stream={} causeType={}", session.streamId(),
                        e.getClass().getSimpleName());
            }
        }
        log.info("liveinfo.tick sessions={} found={} pages={} refused={}", active.size(), found.size(),
                lastPagesScanned, refused);
    }

    /** 429면 다음 회차들을 건너뛰고(백오프 두 배, 최대 5분) 이번 회차는 시청자 수 없이 남긴다. */
    private Map<String, LiveEntry> scanLives(Set<String> wanted, Instant now) {
        Map<String, LiveEntry> found = new HashMap<>();
        int pages = 0;
        try {
            String next = null;
            do {
                LivePage page = client.lives(next);
                pages++;
                for (LiveEntry entry : page.entries()) {
                    if (wanted.contains(entry.channelId())) {
                        found.put(entry.channelId(), entry);
                    }
                }
                next = page.next();
            } while (next != null && found.size() < wanted.size() && pages < properties.maxPages());
            backoff = Duration.ZERO;
        } catch (ChzzkLiveInfoClient.TooManyRequestsException e) {
            backoff = backoff.isZero() ? properties.interval() : min(backoff.multipliedBy(2), MAX_BACKOFF);
            pausedUntil = now.plus(backoff);
            log.warn("liveinfo.lives_throttled pages={} backoffMs={}", pages, backoff.toMillis());
        } catch (RuntimeException e) {
            log.warn("liveinfo.lives_failed pages={} causeType={}", pages, e.getClass().getSimpleName());
        }
        lastPagesScanned = pages;
        return found;
    }

    /** 설정이 이기고, 없으면 목록 값으로 채운다. 시청자 수는 목록에서만 온다. */
    static BroadcastInfo merge(ActiveSession session, SettingResult result, LiveEntry live, Instant observedAt) {
        ChzzkLiveInfoClient.LiveSetting setting = result.setting();
        String title = setting != null ? setting.title() : live == null ? null : live.liveTitle();
        List<String> tags = setting != null ? setting.tags() : live == null ? List.of() : live.tags();
        String category = setting != null ? setting.category() : live == null ? null : live.category();
        return new BroadcastInfo(session.streamId(), session.channelId(), observedAt, title, tags, category,
                live == null ? null : live.viewers());
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
