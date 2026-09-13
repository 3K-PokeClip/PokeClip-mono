package com.pokeclip.chat.collector.liveinfo;

import com.pokeclip.chat.collector.liveinfo.ChzzkLiveInfoClient.LiveEntry;
import com.pokeclip.chat.collector.liveinfo.ChzzkLiveInfoClient.LivePage;
import com.pokeclip.chat.collector.liveinfo.ChzzkLiveInfoClient.LiveSetting;
import com.pokeclip.chat.collector.liveinfo.ChzzkLiveInfoClient.SettingResult;
import com.pokeclip.chat.collector.relay.RelayPayload;
import com.pokeclip.chat.collector.relay.RelaySink;
import com.pokeclip.chat.collector.session.SessionRegistry;
import com.pokeclip.chat.collector.session.SessionRegistry.ActiveSession;
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

    @BeforeEach
    void setUp() {
        client = new FakeClient();
        when(registry.activeSessions()).thenReturn(List.of(
                new ActiveSession("s-a", 1L, "c-a", "tok-a"),
                new ActiveSession("s-b", 2L, "c-b", "tok-b")));
        when(registry.currentStreamIdOf(1L)).thenReturn("s-a");
        when(registry.currentStreamIdOf(2L)).thenReturn("s-b");
    }

    private BroadcastInfoCollector collector() {
        return new BroadcastInfoCollector(registry, client, stored::add, relay, PROPS, clock);
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
        assertThat(collector.lastPagesScanned()).isEqualTo(2);
        assertThat(relayed).containsExactly("s-a:broadcast-info", "s-b:broadcast-info");
    }

    @Test
    void 목록에_없으면_시청자_수는_null이고_0이_아니다() {
        client.pages = List.of(new LivePage(List.of(entry("other", 10)), null));
        client.settings.put("tok-a", new SettingResult(new LiveSetting("A", List.of(), null), 200));
        client.settings.put("tok-b", new SettingResult(new LiveSetting("B", List.of(), null), 200));

        collector().tick();

        assertThat(stored).extracting(BroadcastInfo::viewers).containsExactly(null, null);
    }

    @Test
    void 설정이_거부되면_목록의_제목으로_채우고_틱은_계속된다() {
        client.pages = List.of(new LivePage(List.of(
                new LiveEntry("c-a", "목록 제목", List.of("t"), "LoL", 77)), null));
        client.settings.put("tok-a", new SettingResult(null, 403));
        client.settings.put("tok-b", new SettingResult(new LiveSetting("B", List.of(), null), 200));

        collector().tick();

        assertThat(stored).hasSize(2);
        assertThat(stored.get(0).title()).isEqualTo("목록 제목");
        assertThat(stored.get(0).tags()).containsExactly("t");
        assertThat(stored.get(0).viewers()).isEqualTo(77);
    }

    @Test
    void 목록이_429면_이번엔_시청자_없이_남기고_주기만큼_다음_틱을_건너뛴다() {
        client.throttle = true;
        client.settings.put("tok-a", new SettingResult(new LiveSetting("A", List.of(), null), 200));
        client.settings.put("tok-b", new SettingResult(new LiveSetting("B", List.of(), null), 200));
        BroadcastInfoCollector collector = collector();

        collector.tick();
        assertThat(stored).extracting(BroadcastInfo::viewers).containsExactly(null, null);

        now.set(now.get().plusSeconds(59));
        collector.tick();
        assertThat(stored).as("백오프 안이면 아무것도 안 한다").hasSize(2);

        now.set(now.get().plusSeconds(2));
        collector.tick();
        assertThat(stored).as("백오프가 지나면 다시 돈다").hasSize(4);
    }

    @Test
    void 한_방송의_저장이_던져도_뒤_방송은_남고_틱은_안_던진다() {
        client.pages = List.of(new LivePage(List.of(), null));
        client.settings.put("tok-a", new SettingResult(new LiveSetting("A", List.of(), null), 200));
        client.settings.put("tok-b", new SettingResult(new LiveSetting("B", List.of(), null), 200));
        List<BroadcastInfo> kept = new ArrayList<>();
        BroadcastInfoCollector collector = new BroadcastInfoCollector(registry, client, info -> {
            if (info.streamId().equals("s-a")) {
                throw new IllegalStateException("db down");
            }
            kept.add(info);
        }, relay, PROPS, clock);

        collector.tick();

        assertThat(kept).extracting(BroadcastInfo::streamId).containsExactly("s-b");
        assertThat(relayed).as("저장이 실패해도 화면에는 민다(PR #180 codex)")
                .containsExactly("s-a:broadcast-info", "s-b:broadcast-info");
    }

    /** PR #180 codex — 묻는 사이 방송이 바뀌었거나 닫혔으면 끝난 방송에 안 민다(카운터를 되살리지 않는다). 저장은 한다. */
    @Test
    void 묻는_사이_방송이_바뀌거나_닫히면_중계하지_않는다() {
        client.pages = List.of(new LivePage(List.of(), null));
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
        final Map<String, SettingResult> settings = new java.util.concurrent.ConcurrentHashMap<>();
        final AtomicInteger pagesRead = new AtomicInteger();
        final AtomicInteger settingCalls = new AtomicInteger();

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
            if (throttle) {
                throw new TooManyRequestsException();
            }
            int index = pagesRead.getAndIncrement();
            return pages.get(index);
        }
    }
}
