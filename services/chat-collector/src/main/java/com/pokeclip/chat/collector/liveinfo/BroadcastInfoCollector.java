package com.pokeclip.chat.collector.liveinfo;

import com.pokeclip.chat.collector.Platform;
import com.pokeclip.chat.collector.liveinfo.ChzzkLiveInfoClient.LiveEntry;
import com.pokeclip.chat.collector.liveinfo.ChzzkLiveInfoClient.LivePage;
import com.pokeclip.chat.collector.liveinfo.ChzzkLiveInfoClient.SettingResult;
import com.pokeclip.chat.collector.platforms.PlatformNotifier;
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
 * <p><b>치지직 세션만 본다</b>(POK-397). 목록·설정이 치지직 창구라 SOOP 세션을 넣으면 SOOP 토큰으로 치지직을 묻고, 채널 아이디가
 * 플랫폼마다 따로 매겨져 남의 치지직 방송 시청자 수가 SOOP 방송에 적힐 수 있다. SOOP 방송 정보는 SOOP 지켜보기가 남긴다.
 * 목록에서 찾은 세션은 clip에 「치지직이 켜져 있다」를 알린다({@link PlatformNotifier}, 받을 때까지 한 번).
 *
 * <p>🔴 {@link #tick()}은 <b>던지지 않는다.</b> {@code @Scheduled}는 한 번 던지면 그 뒤 주기가 죽는다.
 * 🔴 제목·태그를 로그에 싣지 않는다 — 건수·방송 번호·원인 타입만.
 */
public class BroadcastInfoCollector {

    private static final Logger log = LoggerFactory.getLogger(BroadcastInfoCollector.class);
    static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    /**
     * 한 틱의 clip 알림 합계 상한(감사 1회차 m3). 알림 하나는 시한으로 최대 1.5초(접속 500ms + 읽기 1초)라, clip이 받고 답을 안 주면
     * 방송 100개에서 한 틱이 2분 넘게 늘어 뒤 방송의 관측 간격이 「꺼짐」 판정(3분)에 닿는다. 넘으면 남은 알림은 다음 틱으로 미룬다.
     */
    static final Duration NOTIFY_BUDGET = Duration.ofSeconds(3);

    private final SessionRegistry registry;
    private final ChzzkLiveInfoClient client;
    private final Store store;
    private final RelaySink relay;
    private final PlatformNotifier platforms;
    private final LiveInfoProperties properties;
    private final Clock clock;

    private Instant pausedUntil = Instant.EPOCH;
    private Duration backoff = Duration.ZERO;
    private volatile int lastPagesScanned;
    /** 지난 틱에 예산이 다해 못 보낸 알림의 방송 번호. 다음 틱에 먼저 보낸다(안 그러면 앞 방송이 늘 예산을 먹어 뒤 방송은 영영 못 간다). */
    private final java.util.Set<String> deferredNotices = new java.util.LinkedHashSet<>();

    /** 표 쓰기. 시험이 DB 없이 돌 수 있게 한 줄짜리 타입으로 받는다. */
    @FunctionalInterface
    public interface Store {
        void insert(BroadcastInfo info);
    }

    public BroadcastInfoCollector(SessionRegistry registry, ChzzkLiveInfoClient client, Store store,
                                  RelaySink relay, PlatformNotifier platforms, LiveInfoProperties properties, Clock clock) {
        this.registry = registry;
        this.client = client;
        this.store = store;
        this.relay = relay;
        this.platforms = platforms;
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
        List<ActiveSession> active = registry.activeSessions().stream()
                .filter(session -> session.platform() == Platform.CHZZK)
                .toList();
        platforms.retain(Platform.CHZZK, active.stream().map(ActiveSession::streamId).collect(Collectors.toSet()));
        if (active.isEmpty()) {
            return;
        }
        Map<String, LiveEntry> found = scanLives(active.stream().map(ActiveSession::channelId)
                .collect(Collectors.toSet()), now);

        int refused = 0;
        Map<String, Instant> notices = new java.util.LinkedHashMap<>();
        for (ActiveSession session : active) {
            // 🔴 치지직 라이브 목록에서 실제로 찾은 세션만 적는다(POK-397 재시험으로 POK-234의 「못 찾아도 설정 제목은 남긴다」를 뒤집었다).
            // 치지직 방송이 꺼져 있어도 방송 설정은 제목을 그대로 주므로, SOOP으로만 방송하는데 치지직 세션이 붙은 방송에 치지직 줄·제목이
            // 붙어 홈에 치지직 태그가 떴다. 목록에 있다는 것이 치지직에서 지금 방송 중이라는 유일한 증거다.
            // 대가: 목록 훑기가 실패·중단(429·4xx·5xx·장 상한)한 회차에 못 찾은 방송은 그 분의 치지직 정보가 빈다(다음 회차에 다시 찾는다).
            LiveEntry live = found.get(session.channelId());
            if (live == null) {
                continue;
            }
            try {
                SettingResult result = client.setting(session.accessToken());
                if (result.refused()) {
                    refused++;
                }
                BroadcastInfo info = merge(session, result, live, clock.instant());
                // 🔴 저장과 중계를 따로 시도한다(PR #180 codex) — 채팅 중계와 같은 원칙이다. DB가 아프다고
                // 화면이 방송 정보를 못 받으면 안 된다.
                try {
                    store.insert(info);
                } catch (RuntimeException e) {
                    log.warn("liveinfo.record_failed stream={} causeType={}", session.streamId(),
                            e.getClass().getSimpleName());
                }
                // 🔴 목록·설정을 묻는 사이 세션이 닫히거나 방송이 바뀌었으면 민다(PR #180 codex) — 닫힐 때 등록부가
                // 잊은 번호 카운터를 되살려 끝난 방송에 이벤트가 가고 카운터가 샌다. 확인과 넣기 사이의 좁은 창은 남는다.
                if (session.streamId().equals(registry.currentStreamIdOf(session.streamerId()))) {
                    relay.offer(info.streamId(), new RelayPayload.Info(info.platform(), info.observedAt(), info.title(), info.tags(),
                            info.category(), info.viewers(), info.categoryImageUrl()));
                }
                // 목록에서 찾았다는 것이 그 방송이 치지직에서 켜져 있다는 증거다. 알림은 모든 방송의 저장·중계 뒤에 한 번에 보낸다:
                // clip이 늦어도 정보는 남고 뒤 방송 관측이 안 밀린다.
                notices.put(session.streamId(), info.observedAt());
            } catch (RuntimeException e) {
                // 한 방송의 실패가 뒤 방송을 막지 않는다.
                log.warn("liveinfo.session_failed stream={} causeType={}", session.streamId(),
                        e.getClass().getSimpleName());
            }
        }
        notifyWithinBudget(notices);
        log.info("liveinfo.tick sessions={} found={} pages={} refused={}", active.size(), found.size(),
                lastPagesScanned, refused);
    }

    /**
     * 지난 틱에 미룬 것부터 보낸다. 합계가 {@link #NOTIFY_BUDGET}에 닿으면 남은 것은 미룬다. 받은 (방송, 플랫폼)은 알림 쪽이 바로 건너뛰므로
     * 예산을 먹는 것은 아직 못 받은 것뿐이다. 미룬 목록은 이번 틱에 걷는 방송으로만 남는다(끝난 방송은 저절로 빠진다).
     */
    private void notifyWithinBudget(Map<String, Instant> notices) {
        List<String> order = new java.util.ArrayList<>();
        deferredNotices.stream().filter(notices::containsKey).forEach(order::add);
        notices.keySet().stream().filter(id -> !deferredNotices.contains(id)).forEach(order::add);
        deferredNotices.clear();
        long started = System.nanoTime();
        for (String streamId : order) {
            if (System.nanoTime() - started >= NOTIFY_BUDGET.toNanos()) {
                deferredNotices.add(streamId);
                continue;
            }
            try {
                platforms.seen(streamId, Platform.CHZZK, notices.get(streamId));
            } catch (RuntimeException e) {
                log.warn("liveinfo.notify_failed stream={} causeType={}", streamId, e.getClass().getSimpleName());
            }
        }
        if (!deferredNotices.isEmpty()) {
            log.warn("liveinfo.notify_deferred count={}", deferredNotices.size());
        }
    }

    /**
     * 목록을 넘기며 걷는 채널을 찾는다. 실패하면 그 장 앞에서 찾은 채널만 돌려준다. 못 찾은 채널은 그 회차에 정보를 안 적는다(POK-397 재시험).
     *
     * <p>429면 그 회차 시작부터 백오프(주기, 이어지면 두 배씩, 최대 5분)가 지나기 전 회차를 건너뛴다. 다음 회차는 앞 회차가
     * 끝나고 주기 뒤에 오므로({@code fixedDelay}) 첫 429는 실제로 건너뛰는 회차가 없고, 연속 둘째 429부터 건너뛴다.
     */
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
        } catch (ChzzkLiveInfoClient.ListFailedException e) {
            // 상태 코드를 남긴다. 400(우리 요청이 틀림)과 5xx(기다리면 되는 장애)가 갈린다. causeType만 남기던 때는 둘이
            // 같은 한 줄이라 커서 인코딩 결함이 한 달 숨었다(POK-325).
            log.warn("liveinfo.lives_failed pages={} status={}", pages, e.status());
        } catch (RuntimeException e) {
            log.warn("liveinfo.lives_failed pages={} causeType={}", pages, e.getClass().getSimpleName());
        }
        lastPagesScanned = pages;
        return found;
    }

    /** 설정이 이기고, 없으면 목록 값으로 채운다. 시청자 수는 목록에서만, 카테고리 사진은 설정에서만 온다(목록에 칸이 없다). */
    static BroadcastInfo merge(ActiveSession session, SettingResult result, LiveEntry live, Instant observedAt) {
        ChzzkLiveInfoClient.LiveSetting setting = result.setting();
        String title = setting != null ? setting.title() : live == null ? null : live.liveTitle();
        List<String> tags = setting != null ? setting.tags() : live == null ? List.of() : live.tags();
        String category = setting != null ? setting.category() : live == null ? null : live.category();
        return new BroadcastInfo(session.platform(), session.streamId(), session.channelId(), observedAt, title, tags, category,
                live == null ? null : live.viewers(), setting == null ? null : setting.categoryImageUrl());
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
