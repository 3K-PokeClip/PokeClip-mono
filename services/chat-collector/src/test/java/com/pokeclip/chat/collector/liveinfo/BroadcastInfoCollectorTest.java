package com.pokeclip.chat.collector.liveinfo;

import com.pokeclip.chat.collector.liveinfo.ChzzkLiveInfoClient.LiveEntry;
import com.pokeclip.chat.collector.liveinfo.ChzzkLiveInfoClient.LivePage;
import com.pokeclip.chat.collector.liveinfo.ChzzkLiveInfoClient.LiveSetting;
import com.pokeclip.chat.collector.liveinfo.ChzzkLiveInfoClient.SettingResult;
import com.pokeclip.chat.collector.relay.RelayPayload;
import com.pokeclip.chat.collector.relay.RelaySink;
import com.pokeclip.chat.collector.session.SessionRegistry;
import com.pokeclip.chat.collector.Platform;
import com.pokeclip.chat.collector.session.SessionRegistry.ActiveSession;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BroadcastInfoCollectorTest {

    private static final LiveInfoProperties PROPS =
            new LiveInfoProperties(true, Duration.ofMinutes(1), Duration.ofSeconds(30), "id", "secret", 10);

    private final SessionRegistry registry = mock(SessionRegistry.class);
    private final List<BroadcastInfo> stored = new CopyOnWriteArrayList<>();
    private final List<String> relayed = new CopyOnWriteArrayList<>();
    private final RelaySink relay = (streamId, payload) -> relayed.add(streamId + ":" + payload.kind());
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-13T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private FakeClient client;
    /** clip 플랫폼 알림(POK-397). 받은 (방송, 플랫폼)을 적는다. */
    private final List<String> notified = new CopyOnWriteArrayList<>();
    private final List<java.util.Set<String>> retained = new CopyOnWriteArrayList<>();
    private volatile boolean notifierThrows;
    private final com.pokeclip.chat.collector.platforms.PlatformNotifier notifier =
            new com.pokeclip.chat.collector.platforms.PlatformNotifier() {
                @Override
                public void seen(String streamId, Platform platform, Instant seenAt) {
                    notified.add(streamId + ":" + platform);
                    if (notifierThrows) {
                        throw new IllegalStateException("clip down");
                    }
                }

                @Override
                public void retain(Platform platform, java.util.Set<String> streamIds) {
                    retained.add(java.util.Set.copyOf(streamIds));
                }
            };

    @BeforeEach
    void setUp() {
        client = new FakeClient();
        when(registry.activeSessions()).thenReturn(List.of(
                new ActiveSession(Platform.CHZZK, "s-a", 1L, "c-a", "tok-a"),
                new ActiveSession(Platform.CHZZK, "s-b", 2L, "c-b", "tok-b")));
        when(registry.currentStreamIdOf(1L)).thenReturn("s-a");
        when(registry.currentStreamIdOf(2L)).thenReturn("s-b");
    }

    private BroadcastInfoCollector collector() {
        return new BroadcastInfoCollector(registry, client, stored::add, relay, notifier, PROPS, clock);
    }

    @Test
    void 설정과_시청자_수가_한_줄로_남고_다_찾으면_목록을_그만_읽는다() {
        client.pages = List.of(
                new LivePage(List.of(entry("other", 5000)), "p2"),
                new LivePage(List.of(entry("c-a", 300), entry("c-b", 40)), "p3"),
                new LivePage(List.of(entry("never", 1)), null));
        client.settings.put("tok-a", new SettingResult(new LiveSetting("A 제목", List.of("롤"), "LoL"), 200));
        client.settings.put("tok-b", new SettingResult(new LiveSetting("B 제목", List.of(), "ETC"), 200));

        BroadcastInfoCollector collector = collector();
        collector.tick();

        assertThat(stored).extracting(BroadcastInfo::streamId).containsExactly("s-a", "s-b");
        assertThat(stored.get(0).title()).isEqualTo("A 제목");
        assertThat(stored.get(0).viewers()).isEqualTo(300);
        assertThat(stored.get(1).viewers()).isEqualTo(40);
        assertThat(stored.get(0).observedAt()).isEqualTo(now.get());
        assertThat(client.pagesRead.get()).as("셋째 장은 안 읽는다").isEqualTo(2);
        assertThat(client.cursorsAsked).as("앞 장이 준 커서를 다음 요청에 그대로 넘긴다").containsExactly(null, "p2");
        assertThat(collector.lastPagesScanned()).isEqualTo(2);
        assertThat(relayed).containsExactly("s-a:broadcast-info", "s-b:broadcast-info");
    }

    /**
     * 치지직 목록·설정은 치지직 세션만 묻는다(POK-397). SOOP 세션의 채널이 우연히 치지직 목록의 채널 아이디와 같아도(플랫폼마다 따로
     * 매긴다) 그 세션에는 설정 호출·정보 줄·SSE·clip 알림이 없다. 정보 줄과 SSE에는 치지직이 실린다.
     */
    @Test
    void 치지직_세션만_훑고_SOOP_세션은_건드리지_않는다() {
        when(registry.activeSessions()).thenReturn(List.of(
                new ActiveSession(Platform.SOOP, "s-soop", 3L, "c-a", "tok-soop"),
                new ActiveSession(Platform.CHZZK, "s-a", 1L, "c-a", "tok-a")));
        client.pages = List.of(new LivePage(List.of(entry("c-a", 300)), null));
        client.settings.put("tok-a", new SettingResult(new LiveSetting("A", List.of(), null), 200));
        client.settings.put("tok-soop", new SettingResult(new LiveSetting("숲", List.of(), null), 200));

        List<RelayPayload> payloads = new ArrayList<>();
        new BroadcastInfoCollector(registry, client, stored::add, (streamId, payload) -> payloads.add(payload), notifier,
                PROPS, clock).tick();

        assertThat(client.settingCalls.get()).as("SOOP 토큰으로 치지직 설정을 안 묻는다").isEqualTo(1);
        assertThat(stored).extracting(BroadcastInfo::streamId).containsExactly("s-a");
        assertThat(stored).extracting(BroadcastInfo::platform).containsExactly(Platform.CHZZK);
        assertThat(payloads).as("SSE 방송 정보에도 같은 플랫폼이 실린다(POK-397)").singleElement()
                .isInstanceOfSatisfying(RelayPayload.Info.class, info -> assertThat(info.platform()).isEqualTo(Platform.CHZZK));
        assertThat(notified).containsExactly("s-a:CHZZK");
    }

    /** 치지직 세션이 없고 SOOP 세션만 있으면 치지직 목록을 아예 안 연다(앱 인증 호출을 아낀다). */
    @Test
    void SOOP_세션만_있으면_치지직_목록을_안_연다() {
        when(registry.activeSessions()).thenReturn(List.of(
                new ActiveSession(Platform.SOOP, "s-soop", 3L, "c-soop", "tok-soop")));

        collector().tick();

        assertThat(client.pagesRead.get()).isZero();
        assertThat(client.settingCalls.get()).isZero();
        assertThat(stored).isEmpty();
        assertThat(retained).as("메모는 걷는 치지직 방송만 남긴다").containsExactly(java.util.Set.of());
    }

    /**
     * 목록에서 찾은 치지직 세션만 clip에 「치지직이 켜져 있다」를 알린다. 목록에서 못 찾은 세션은 설정 조회가 돼도 알리지 않는다
     * (목록이 방송 중인 것을 보여 준 증거다). 메모를 걷는 방송으로 줄인다.
     */
    @Test
    void 목록에서_찾은_치지직_세션만_clip에_알린다() {
        client.pages = List.of(new LivePage(List.of(entry("c-a", 300)), null));
        client.settings.put("tok-a", new SettingResult(new LiveSetting("A", List.of(), null), 200));
        client.settings.put("tok-b", new SettingResult(new LiveSetting("B", List.of(), null), 200));

        collector().tick();

        assertThat(notified).containsExactly("s-a:CHZZK");
        assertThat(retained).containsExactly(java.util.Set.of("s-a", "s-b"));
    }

    /**
     * clip이 받고 답을 안 주면 알림마다 시한(최대 1.5초)을 먹는다. 한 틱의 알림 합계가 예산({@link BroadcastInfoCollector#NOTIFY_BUDGET})을
     * 넘으면 남은 알림은 다음 틱으로 미루고, 다음 틱은 미룬 것부터 보낸다(감사 1회차 m3). 안 그러면 방송 100개 × 1.5초가 한 틱에 더해져
     * 뒤 방송의 관측 간격이 「꺼짐」 판정(3분)에 닿는다. 정보 저장·중계는 알림 전에 다 끝난다.
     */
    @Test
    void clip이_매달리면_알림은_예산에서_멈추고_다음_틱에_미룬_것부터_보낸다() {
        List<ActiveSession> sessions = new ArrayList<>();
        List<LiveEntry> entries = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            sessions.add(new ActiveSession(Platform.CHZZK, "s-" + i, i, "c-" + i, "tok-" + i));
            entries.add(entry("c-" + i, 10 * i));
            when(registry.currentStreamIdOf((long) i)).thenReturn("s-" + i);
        }
        when(registry.activeSessions()).thenReturn(sessions);
        List<String> calls = new CopyOnWriteArrayList<>();
        com.pokeclip.chat.collector.platforms.PlatformNotifier slow =
                new com.pokeclip.chat.collector.platforms.PlatformNotifier() {
                    @Override
                    public void seen(String streamId, Platform platform, Instant seenAt) {
                        calls.add(streamId);
                        try {
                            Thread.sleep(1_100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }

                    @Override
                    public void retain(Platform platform, java.util.Set<String> streamIds) {
                    }
                };
        BroadcastInfoCollector collector =
                new BroadcastInfoCollector(registry, client, stored::add, relay, slow, PROPS, clock);

        client.pages = List.of(new LivePage(entries, null));
        long started = System.nanoTime();
        collector.tick();
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(stored).as("저장은 알림과 무관하게 다섯 다").hasSize(5);
        assertThat(calls).as("1.1초 셋이면 3초 예산을 넘는다").containsExactly("s-1", "s-2", "s-3");
        assertThat(took).as("다섯 다 기다리면 5.5초").isLessThan(Duration.ofMillis(4_500));

        calls.clear();
        client.pages = List.of(new LivePage(entries, null));
        client.pagesRead.set(0);
        collector.tick();

        assertThat(calls).as("미룬 둘이 먼저다").startsWith("s-4", "s-5");
    }

    /** clip 알림이 실패(던짐)해도 정보 저장·중계는 이미 끝났고 뒤 방송도 그대로다. */
    @Test
    void clip_알림이_실패해도_정보_저장은_계속한다() {
        notifierThrows = true;
        client.pages = List.of(new LivePage(List.of(entry("c-a", 300), entry("c-b", 40)), null));
        client.settings.put("tok-a", new SettingResult(new LiveSetting("A", List.of(), null), 200));
        client.settings.put("tok-b", new SettingResult(new LiveSetting("B", List.of(), null), 200));

        collector().tick();

        assertThat(stored).extracting(BroadcastInfo::streamId).containsExactly("s-a", "s-b");
        assertThat(relayed).containsExactly("s-a:broadcast-info", "s-b:broadcast-info");
        assertThat(notified).containsExactly("s-a:CHZZK", "s-b:CHZZK");
    }

    @Test
    void 목록이_끝없이_이어져도_상한_장_수에서_멈춘다() {
        // 고친 뒤(POK-325)엔 목록을 끝까지 넘기는 것이 정상이라, 치지직이 다음 장을 계속 주면 이 상한이 끝없는 요청을 막는 유일한 막이다.
        client.endless = true;
        BroadcastInfoCollector collector = collector();

        collector.tick();

        assertThat(client.pagesRead.get()).isEqualTo(PROPS.maxPages());
        assertThat(collector.lastPagesScanned()).isEqualTo(PROPS.maxPages());
        assertThat(stored).as("훑기가 상한에서 멈춘 회차에 못 찾은 세션은 적지 않는다").isEmpty();
    }

    @Test
    void 목록이_중간_장에서_실패하면_앞_장에서_찾은_방송만_남기고_상태_코드를_남긴다() {
        client.pages = List.of(new LivePage(List.of(entry("c-a", 300)), "p2"));
        client.failAt = 1;
        client.failStatus = 400;
        client.settings.put("tok-a", new SettingResult(new LiveSetting("A", List.of(), null), 200));
        client.settings.put("tok-b", new SettingResult(new LiveSetting("B", List.of(), null), 200));

        try (LogCaptor captor = new LogCaptor()) {
            collector().tick();

            assertThat(captor.messages())
                    .as("400(우리 요청이 틀림)과 5xx(기다리면 되는 장애)를 로그로 가른다(POK-325)")
                    .contains("liveinfo.lives_failed pages=1 status=400");
        }
        assertThat(stored).extracting(BroadcastInfo::viewers).containsExactly(300);
        assertThat(client.settingCalls.get()).as("못 찾은 세션은 설정도 안 묻는다").isEqualTo(1);
    }

    /**
     * 🔴 치지직 목록에서 못 찾은 세션은 방송 정보를 적지 않는다(POK-397 재시험). SOOP으로만 방송하는데 치지직 세션이 붙어 있으면(같은 방송 번호,
     * 치지직은 꺼져 있음) 설정 조회는 치지직 방송 설정(제목)을 그대로 준다. 그것을 적으면 SOOP 전용 방송에 치지직 줄·제목·태그가 붙었다.
     * 정보 줄·SSE·clip 알림·설정 조회 모두 없다.
     */
    @Test
    void 목록에_없는_세션은_적지_않고_설정도_안_묻는다() {
        client.pages = List.of(new LivePage(List.of(entry("other", 10)), null));
        client.settings.put("tok-a", new SettingResult(new LiveSetting("A", List.of(), null), 200));
        client.settings.put("tok-b", new SettingResult(new LiveSetting("B", List.of(), null), 200));

        collector().tick();

        assertThat(stored).isEmpty();
        assertThat(relayed).isEmpty();
        assertThat(notified).isEmpty();
        assertThat(client.settingCalls.get()).isZero();
    }

    @Test
    void 설정이_거부되면_목록의_제목으로_채우고_틱은_계속된다() {
        client.pages = List.of(new LivePage(List.of(
                new LiveEntry("c-a", "목록 제목", List.of("t"), "LoL", 77)), null));
        client.settings.put("tok-a", new SettingResult(null, 403));
        client.settings.put("tok-b", new SettingResult(new LiveSetting("B", List.of(), null), 200));

        collector().tick();

        assertThat(stored).as("목록에 있는 s-a만").hasSize(1);
        assertThat(stored.get(0).title()).isEqualTo("목록 제목");
        assertThat(stored.get(0).tags()).containsExactly("t");
        assertThat(stored.get(0).viewers()).isEqualTo(77);
    }

    @Test
    void 목록이_429면_이번엔_아무것도_안_남기고_주기만큼_다음_틱을_건너뛴다() {
        client.throttle = true;
        client.settings.put("tok-a", new SettingResult(new LiveSetting("A", List.of(), null), 200));
        client.settings.put("tok-b", new SettingResult(new LiveSetting("B", List.of(), null), 200));
        BroadcastInfoCollector collector = collector();

        collector.tick();
        assertThat(stored).as("훑기가 실패한 회차는 적지 않는다").isEmpty();
        assertThat(client.cursorsAsked).hasSize(1);

        now.set(now.get().plusSeconds(59));
        collector.tick();
        assertThat(client.cursorsAsked).as("백오프 안이면 아무것도 안 한다").hasSize(1);

        now.set(now.get().plusSeconds(2));
        collector.tick();
        assertThat(client.cursorsAsked).as("백오프가 지나면 다시 돈다").hasSize(2);
        assertThat(stored).isEmpty();
    }

    @Test
    void 한_방송의_저장이_던져도_뒤_방송은_남고_틱은_안_던진다() {
        client.pages = List.of(new LivePage(List.of(entry("c-a", 1), entry("c-b", 2)), null));
        client.settings.put("tok-a", new SettingResult(new LiveSetting("A", List.of(), null), 200));
        client.settings.put("tok-b", new SettingResult(new LiveSetting("B", List.of(), null), 200));
        List<BroadcastInfo> kept = new ArrayList<>();
        BroadcastInfoCollector collector = new BroadcastInfoCollector(registry, client, info -> {
            if (info.streamId().equals("s-a")) {
                throw new IllegalStateException("db down");
            }
            kept.add(info);
        }, relay, notifier, PROPS, clock);

        collector.tick();

        assertThat(kept).extracting(BroadcastInfo::streamId).containsExactly("s-b");
        assertThat(relayed).as("저장이 실패해도 화면에는 민다(PR #180 codex)")
                .containsExactly("s-a:broadcast-info", "s-b:broadcast-info");
    }

    /** PR #180 codex — 묻는 사이 방송이 바뀌었거나 닫혔으면 끝난 방송에 안 민다(카운터를 되살리지 않는다). 저장은 한다. */
    @Test
    void 묻는_사이_방송이_바뀌거나_닫히면_중계하지_않는다() {
        client.pages = List.of(new LivePage(List.of(entry("c-a", 1), entry("c-b", 2)), null));
        client.settings.put("tok-a", new SettingResult(new LiveSetting("A", List.of(), null), 200));
        client.settings.put("tok-b", new SettingResult(new LiveSetting("B", List.of(), null), 200));
        when(registry.currentStreamIdOf(1L)).thenReturn("s-a-next");
        when(registry.currentStreamIdOf(2L)).thenReturn(null);

        collector().tick();

        assertThat(stored).hasSize(2);
        assertThat(relayed).isEmpty();
    }

    @Test
    void 주기가_0이면_부팅을_거부한다() {
        LiveInfoProperties zero = new LiveInfoProperties(true, Duration.ZERO, Duration.ofSeconds(30), "id", "secret", 10);
        org.assertj.core.api.Assertions.assertThatThrownBy(zero::validate)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("interval");
        PROPS.validate();
    }

    @Test
    void 등록부가_Error를_던져도_틱은_안_던진다() {
        when(registry.activeSessions()).thenThrow(new LinkageError("boom"));

        collector().tick();

        assertThat(stored).isEmpty();
    }

    @Test
    void 걷는_방송이_없으면_치지직을_안_부른다() {
        when(registry.activeSessions()).thenReturn(List.of());

        collector().tick();

        assertThat(client.pagesRead.get()).isZero();
        assertThat(client.settingCalls.get()).isZero();
    }

    private static LiveEntry entry(String channelId, int viewers) {
        return new LiveEntry(channelId, "t", List.of(), null, viewers);
    }

    private static final class FakeClient extends ChzzkLiveInfoClient {
        List<LivePage> pages = List.of();
        boolean throttle;
        /**
         * 참이면 다음 장을 계속 준다(상한 시험). 1,000장째에서는 Error를 던진다. 수집기에 상한이 없으면 시험이 끝없이 도는
         * 대신 그 Error로 끝나 빨간불이 된다(JUnit {@code @Timeout}은 쉬지 않고 도는 반복을 못 끊는다).
         */
        boolean endless;
        /** 이 순번의 장에서 {@link ListFailedException}을 던진다. -1이면 안 던진다. */
        int failAt = -1;
        int failStatus;
        final Map<String, SettingResult> settings = new java.util.concurrent.ConcurrentHashMap<>();
        final AtomicInteger pagesRead = new AtomicInteger();
        final AtomicInteger settingCalls = new AtomicInteger();
        /** 받은 커서를 순서대로 적는다. 첫 장은 null이다. */
        final List<String> cursorsAsked = new CopyOnWriteArrayList<>();

        FakeClient() {
            super(null, "http://unused", "id", "secret");
        }

        @Override
        public SettingResult setting(String accessToken) {
            settingCalls.incrementAndGet();
            return settings.getOrDefault(accessToken, new SettingResult(null, 0));
        }

        @Override
        public LivePage lives(String next) {
            cursorsAsked.add(next);
            if (throttle) {
                throw new TooManyRequestsException();
            }
            int index = pagesRead.getAndIncrement();
            if (index == failAt) {
                throw new ListFailedException(failStatus);
            }
            if (endless) {
                if (index >= 1_000) {
                    throw new AssertionError("1,000장을 넘겼다: 수집기에 페이지 상한이 없다");
                }
                return new LivePage(List.of(entry("other", 1)), "p" + (index + 2));
            }
            return pages.get(index);
        }
    }
}
