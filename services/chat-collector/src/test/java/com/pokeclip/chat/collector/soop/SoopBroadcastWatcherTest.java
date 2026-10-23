package com.pokeclip.chat.collector.soop;

import com.pokeclip.chat.collector.Platform;
import com.pokeclip.chat.collector.liveinfo.BroadcastInfo;
import com.pokeclip.chat.collector.platforms.PlatformNotifier;
import com.pokeclip.chat.collector.relay.RelayPayload;
import com.pokeclip.chat.collector.relay.RelaySink;
import com.pokeclip.chat.collector.soop.chat.SoopChat;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SOOP 방송 지켜보기(POK-397)를 HTTP 없이 잰다. 연동 조회·방송 여부 체크는 대답을 줄로 쌓는 가짜이고, 저장·중계·clip 알림은 받은 것을 적는다.
 * 실제 HTTP 둘은 {@link SoopClientsTest}가, 비밀이 안 새는 것은 {@link SoopSecretLeakTest}가 잰다.
 */
class SoopBroadcastWatcherTest {

    private static final Instant 시작 = Instant.parse("2026-10-24T01:00:00Z");

    private final AtomicReference<Instant> now = new AtomicReference<>(시작.plusSeconds(60));
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };

    private final Deque<SoopLink> linkAnswers = new ArrayDeque<>();
    private final List<Long> resolved = new CopyOnWriteArrayList<>();
    private final Deque<SoopLiveStatus> liveAnswers = new ArrayDeque<>();
    private final List<String> checkedTokens = new CopyOnWriteArrayList<>();
    private final List<BroadcastInfo> stored = new CopyOnWriteArrayList<>();
    private final List<String> relayed = new CopyOnWriteArrayList<>();
    private final List<RelayPayload> payloads = new CopyOnWriteArrayList<>();
    private final List<String> forgotten = new CopyOnWriteArrayList<>();
    private final List<String> notified = new CopyOnWriteArrayList<>();
    private final List<Set<String>> retained = new CopyOnWriteArrayList<>();
    private final Set<String> sessionStreams = new HashSet<>();
    /** 상태 확인 중에 한 번 끼워 넣을 일(지켜보는 사이 종료·정리가 오는 경우). */
    private Runnable duringCheck = () -> { };

    private final RelaySink relay = new RelaySink() {
        @Override
        public void offer(String streamId, RelayPayload payload) {
            relayed.add(streamId);
            payloads.add(payload);
        }

        @Override
        public void forget(String streamId) {
            forgotten.add(streamId);
        }
    };

    private final PlatformNotifier platforms = new PlatformNotifier() {
        @Override
        public void seen(String streamId, Platform platform, Instant seenAt) {
            notified.add(streamId + ":" + platform);
        }

        @Override
        public void retain(Platform platform, Set<String> streamIds) {
            retained.add(Set.copyOf(streamIds));
        }
    };

    /** 종료 메모가 있는 방송(지켜보기 틱이 묻는다, 감사 2회차 n5). */
    private final Set<String> endedMemos = new HashSet<>();

    private SoopBroadcastWatcher watcher(int max) {
        return new SoopBroadcastWatcher(id -> {
            resolved.add(id);
            return linkAnswers.size() > 1 ? linkAnswers.pollFirst() : linkAnswers.peekFirst();
        }, token -> {
            checkedTokens.add(token);
            Runnable once = duringCheck;
            duringCheck = () -> { };
            once.run();
            return liveAnswers.size() > 1 ? liveAnswers.pollFirst() : liveAnswers.peekFirst();
        }, stored::add, relay, platforms, () -> (Collection<String>) Set.copyOf(sessionStreams),
                ids -> ids.stream().filter(endedMemos::contains).collect(java.util.stream.Collectors.toSet()), max, clock,
                SoopBroadcastWatcher.TICK_BUDGET);
    }

    private SoopBroadcastWatcher watcher() {
        return watcher(10);
    }

    private static SoopLink link(String token, Instant expiresAt) {
        return SoopLink.granted("soop-user-1", token, expiresAt);
    }

    private static SoopLiveStatus onAir(String title, Integer viewers) {
        return new SoopLiveStatus(SoopLiveStatus.Outcome.ON_AIR, title, null, viewers);
    }

    private static SoopLiveStatus outcome(SoopLiveStatus.Outcome outcome) {
        return new SoopLiveStatus(outcome, null, null, null);
    }

    @Test
    void 연동이_있고_방송_중이면_정보_줄과_SSE와_clip_알림을_남긴다() {
        linkAnswers.add(link("tok-1", now.get().plus(Duration.ofHours(8))));
        liveAnswers.add(onAir("숲 제목", 321));
        SoopBroadcastWatcher watcher = watcher();

        watcher.watch("s-1", 42L, 시작);
        watcher.tick();

        assertThat(stored).singleElement().satisfies(info -> {
            assertThat(info.platform()).isEqualTo(Platform.SOOP);
            assertThat(info.streamId()).isEqualTo("s-1");
            assertThat(info.channelId()).as("채널은 SOOP 아이디다(탈퇴 정리가 이것으로 지운다)").isEqualTo("soop-user-1");
            assertThat(info.title()).isEqualTo("숲 제목");
            assertThat(info.viewers()).isEqualTo(321);
            assertThat(info.observedAt()).isEqualTo(now.get());
        });
        assertThat(relayed).containsExactly("s-1");
        assertThat(payloads).singleElement().isInstanceOfSatisfying(RelayPayload.Info.class,
                info -> assertThat(info.platform()).isEqualTo(Platform.SOOP));
        assertThat(notified).containsExactly("s-1:SOOP");
        assertThat(resolved).containsExactly(42L);
        assertThat(checkedTokens).containsExactly("tok-1");
        assertThat(retained).containsExactly(Set.of("s-1"));
    }

    // ── 채팅 받기(PR4) ──────────────────────────────────────────

    /** 채팅 붙이기 · 떼기를 적는 가짜. 순서까지 본다. */
    private final List<String> chatCalls = new CopyOnWriteArrayList<>();

    private final SoopChat chat = new SoopChat() {
        @Override
        public void ensure(String streamId, String channelId, String token) {
            chatCalls.add("ensure:" + streamId + ":" + channelId + ":" + token);
        }

        @Override
        public void close(String streamId) {
            chatCalls.add("close:" + streamId);
        }
    };

    private SoopBroadcastWatcher watcherWithChat() {
        SoopBroadcastWatcher watcher = watcher();
        watcher.attachChat(chat);
        return watcher;
    }

    /** 방송 중으로 본 방송에 그 스트리머의 SOOP 아이디 · 토큰으로 채팅을 붙인다. 틱마다 부르되 이미 붙어 있으면 받는 쪽이 토큰만 고친다 */
    @Test
    void 방송_중이면_채팅을_붙인다() {
        linkAnswers.add(link("tok-1", now.get().plus(Duration.ofHours(8))));
        liveAnswers.add(onAir("숲", 1));
        SoopBroadcastWatcher watcher = watcherWithChat();

        watcher.watch("s-1", 42L, 시작);
        watcher.tick();

        assertThat(chatCalls).containsExactly("ensure:s-1:soop-user-1:tok-1");
    }

    /**
     * 붙이기는 지켜보기의 자물쇠 안에서 한다. 「아직 지켜보는가」를 본 직후 다른 스레드가 빼고(close) 나서 붙이면, 이미 명부에서 빠진 방송이라
     * 다음 틱도 못 떼어 영영 붙어 있다(탈퇴 정리 뒤 채팅이 다시 쌓인다). 빼기(unwatch 등)가 같은 자물쇠라 자물쇠 안이면 끼어들 수 없다
     */
    @Test
    void 채팅을_붙이는_것은_지켜보기_자물쇠_안에서_한다() {
        linkAnswers.add(link("tok-1", now.get().plus(Duration.ofHours(8))));
        liveAnswers.add(onAir("숲", 1));
        SoopBroadcastWatcher watcher = watcher();
        List<Boolean> lockHeld = new CopyOnWriteArrayList<>();
        watcher.attachChat(new SoopChat() {
            @Override
            public void ensure(String streamId, String channelId, String token) {
                lockHeld.add(Thread.holdsLock(watcher));
            }

            @Override
            public void close(String streamId) {
            }
        });

        watcher.watch("s-1", 42L, 시작);
        watcher.tick();

        assertThat(lockHeld).containsExactly(true);
    }

    /** SOOP 방송이 끝났으면(streamend) 채팅을 뗀다. 지켜보기는 종료 편지가 뺀다 */
    @Test
    void 방송이_끝나면_채팅을_뗀다() {
        linkAnswers.add(link("tok-1", now.get().plus(Duration.ofHours(8))));
        liveAnswers.add(onAir("숲", 1));
        liveAnswers.add(outcome(SoopLiveStatus.Outcome.OFF_AIR));
        SoopBroadcastWatcher watcher = watcherWithChat();

        watcher.watch("s-1", 42L, 시작);
        watcher.tick();
        watcher.tick();

        assertThat(chatCalls).containsExactly("ensure:s-1:soop-user-1:tok-1", "close:s-1");
    }

    /** 일시 실패(SOOP 응답 없음)는 채팅을 그대로 둔다: 그 사이 채팅은 계속 들어온다 */
    @Test
    void 확인이_일시_실패면_채팅을_그대로_둔다() {
        linkAnswers.add(link("tok-1", now.get().plus(Duration.ofHours(8))));
        liveAnswers.add(onAir("숲", 1));
        liveAnswers.add(outcome(SoopLiveStatus.Outcome.UNAVAILABLE));
        SoopBroadcastWatcher watcher = watcherWithChat();

        watcher.watch("s-1", 42L, 시작);
        watcher.tick();
        watcher.tick();

        assertThat(chatCalls).containsExactly("ensure:s-1:soop-user-1:tok-1");
    }

    /** 지켜보기에서 빠지면(종료 편지 · 갈아낌) 채팅도 뗀다 */
    @Test
    void 지켜보기에서_빠지거나_갈아끼면_채팅을_뗀다() {
        SoopBroadcastWatcher watcher = watcherWithChat();

        watcher.watch("s-1", 42L, 시작);
        watcher.watch("s-2", 42L, 시작.plusSeconds(10));
        watcher.unwatch("s-2");

        assertThat(chatCalls).containsExactly("close:s-1", "close:s-2");
    }

    /** 토큰이 거부돼 멈추면 채팅도 뗀다(새 토큰도 거부) */
    @Test
    void 토큰이_거부돼_멈추면_채팅을_뗀다() {
        linkAnswers.add(link("tok-1", now.get().plus(Duration.ofHours(8))));
        linkAnswers.add(link("tok-2", now.get().plus(Duration.ofHours(8))));
        liveAnswers.add(outcome(SoopLiveStatus.Outcome.REJECTED));
        SoopBroadcastWatcher watcher = watcherWithChat();

        watcher.watch("s-1", 42L, 시작);
        watcher.tick();

        assertThat(chatCalls).containsExactly("close:s-1");
    }

    /** 영구 거절(연동 없음·해제·끊김)은 그 방송 동안 다시 안 묻는다. SOOP에도 안 묻는다. */
    @Test
    void 연동이_없으면_아무것도_안_하고_다시_안_묻는다() {
        linkAnswers.add(SoopLink.refused(false));
        SoopBroadcastWatcher watcher = watcher();

        watcher.watch("s-1", 42L, 시작);
        watcher.tick();
        watcher.tick();

        assertThat(resolved).containsExactly(42L);
        assertThat(checkedTokens).isEmpty();
        assertThat(stored).isEmpty();
        assertThat(notified).isEmpty();
    }

    /** auth 일시 실패(갱신 일시 실패·5xx)는 「대기」로 두고 다음 틱에 다시 묻는다(계획 검증 M4). */
    @Test
    void 연동_조회가_일시_실패면_다음_틱에_다시_묻는다() {
        linkAnswers.add(SoopLink.refused(true));
        linkAnswers.add(link("tok-1", now.get().plus(Duration.ofHours(8))));
        liveAnswers.add(onAir("숲", 1));
        SoopBroadcastWatcher watcher = watcher();

        watcher.watch("s-1", 42L, 시작);
        watcher.tick();
        assertThat(stored).isEmpty();
        watcher.tick();

        assertThat(resolved).containsExactly(42L, 42L);
        assertThat(stored).hasSize(1);
    }

    /** 방송 중이 아니면 저장·알림이 없고, 다음 틱에 다시 본다(거부가 아니다). */
    @Test
    void 방송_중이_아니면_저장하지_않고_계속_지켜본다() {
        linkAnswers.add(link("tok-1", now.get().plus(Duration.ofHours(8))));
        liveAnswers.add(outcome(SoopLiveStatus.Outcome.OFF_AIR));
        liveAnswers.add(onAir("숲", 3));
        SoopBroadcastWatcher watcher = watcher();

        watcher.watch("s-1", 42L, 시작);
        watcher.tick();
        assertThat(stored).isEmpty();
        assertThat(notified).isEmpty();
        watcher.tick();

        assertThat(stored).hasSize(1);
        assertThat(resolved).as("토큰이 살아 있으면 다시 안 묻는다").containsExactly(42L);
    }

    @Test
    void 상태_확인이_일시_실패면_저장하지_않고_다음_틱에_다시_본다() {
        linkAnswers.add(link("tok-1", now.get().plus(Duration.ofHours(8))));
        liveAnswers.add(outcome(SoopLiveStatus.Outcome.UNAVAILABLE));
        liveAnswers.add(onAir("숲", 3));
        SoopBroadcastWatcher watcher = watcher();

        watcher.watch("s-1", 42L, 시작);
        watcher.tick();
        watcher.tick();

        assertThat(checkedTokens).containsExactly("tok-1", "tok-1");
        assertThat(stored).hasSize(1);
    }

    /** 토큰은 만료 10분 전부터 다시 받는다. 그 전에는 다시 안 묻는다. */
    @Test
    void 토큰은_만료_10분_전에_다시_받는다() {
        linkAnswers.add(link("tok-1", now.get().plus(Duration.ofMinutes(30))));
        linkAnswers.add(link("tok-2", now.get().plus(Duration.ofHours(8))));
        liveAnswers.add(onAir("숲", 1));
        SoopBroadcastWatcher watcher = watcher();

        watcher.watch("s-1", 42L, 시작);
        watcher.tick();
        now.set(now.get().plus(Duration.ofMinutes(19)));
        watcher.tick();
        assertThat(resolved).as("만료 11분 전").hasSize(1);
        now.set(now.get().plus(Duration.ofMinutes(2)));
        watcher.tick();

        assertThat(resolved).as("만료 9분 전").hasSize(2);
        assertThat(checkedTokens).containsExactly("tok-1", "tok-1", "tok-2");
    }

    /**
     * 거부(HTTP 200 + result 음수, 400·401·403)면 곧바로 한 번 다시 받아 새 토큰으로 묻는다. auth가 그 사이 토큰을 갈았을 수 있다
     * (계획 검증 M5). 새 토큰이 되면 지켜보기가 이어진다.
     */
    @Test
    void 거부면_한_번_다시_받아_새_토큰으로_묻는다() {
        linkAnswers.add(link("tok-old", now.get().plus(Duration.ofHours(8))));
        linkAnswers.add(link("tok-new", now.get().plus(Duration.ofHours(8))));
        liveAnswers.add(outcome(SoopLiveStatus.Outcome.REJECTED));
        liveAnswers.add(onAir("숲", 5));
        SoopBroadcastWatcher watcher = watcher();

        watcher.watch("s-1", 42L, 시작);
        watcher.tick();

        assertThat(checkedTokens).containsExactly("tok-old", "tok-new");
        assertThat(stored).hasSize(1);
        watcher.tick();
        assertThat(stored).as("다음 틱도 계속").hasSize(2);
    }

    /** 새 토큰도 거부면 그 방송 지켜보기를 멈춘다. 다음 틱에는 아무것도 안 묻는다. */
    @Test
    void 새_토큰도_거부면_그_방송은_멈춘다() {
        linkAnswers.add(link("tok-old", now.get().plus(Duration.ofHours(8))));
        linkAnswers.add(link("tok-new", now.get().plus(Duration.ofHours(8))));
        liveAnswers.add(outcome(SoopLiveStatus.Outcome.REJECTED));
        SoopBroadcastWatcher watcher = watcher();

        watcher.watch("s-1", 42L, 시작);
        watcher.tick();
        watcher.tick();

        assertThat(checkedTokens).containsExactly("tok-old", "tok-new");
        assertThat(resolved).hasSize(2);
        assertThat(stored).isEmpty();
    }

    /** 종료 편지에 멈춘다. 등록부에 그 방송 세션이 없으면(SOOP 단독 방송) 중계 번호 카운터도 치운다(L6). */
    @Test
    void 종료되면_멈추고_세션이_없으면_중계_카운터를_치운다() {
        linkAnswers.add(link("tok-1", now.get().plus(Duration.ofHours(8))));
        liveAnswers.add(onAir("숲", 1));
        SoopBroadcastWatcher watcher = watcher();
        watcher.watch("s-1", 42L, 시작);
        watcher.watch("s-2", 43L, 시작);
        sessionStreams.add("s-2");

        watcher.unwatch("s-1");
        watcher.unwatch("s-2");
        watcher.unwatch("s-none");
        watcher.tick();

        assertThat(resolved).isEmpty();
        assertThat(stored).isEmpty();
        assertThat(forgotten).as("치지직 세션이 있는 방송의 카운터는 등록부 몫이다").containsExactly("s-1");
    }

    /** 확인하는 사이 종료되면 저장·중계·알림을 안 한다: 끝난 방송에 이벤트가 가고 카운터가 되살아난다(L6, PR #180과 같은 자리). */
    @Test
    void 확인하는_사이_종료되면_남기지_않는다() {
        linkAnswers.add(link("tok-1", now.get().plus(Duration.ofHours(8))));
        liveAnswers.add(onAir("숲", 1));
        SoopBroadcastWatcher watcher = watcher();
        watcher.watch("s-1", 42L, 시작);
        duringCheck = () -> watcher.unwatch("s-1");

        watcher.tick();

        assertThat(checkedTokens).hasSize(1);
        assertThat(stored).isEmpty();
        assertThat(relayed).isEmpty();
        assertThat(notified).isEmpty();
    }

    /**
     * 탈퇴 정리는 (SOOP, SOOP 아이디)로 멈춘다(계획 검증 M3). SOOP에는 폐기 API가 없어 토큰이 만료까지 살아 있는데, 그동안 정보 줄을 쓰면
     * 정리 창 뒤 줄이 60일 보관으로만 지워진다. 다른 플랫폼 같은 아이디·탈퇴 뒤 시작한 방송은 안 멈춘다.
     */
    @Test
    void 탈퇴_정리는_SOOP_아이디로_멈춘다() {
        linkAnswers.add(link("tok-1", now.get().plus(Duration.ofHours(8))));
        liveAnswers.add(onAir("숲", 1));
        SoopBroadcastWatcher watcher = watcher();
        watcher.watch("s-1", 42L, 시작);
        watcher.tick();
        assertThat(stored).hasSize(1);

        watcher.unwatchChannel(Platform.CHZZK, "soop-user-1", now.get());
        watcher.tick();
        assertThat(stored).as("치지직 채널 정리는 SOOP 지켜보기와 무관").hasSize(2);
        watcher.unwatchChannel(Platform.SOOP, "soop-user-1", 시작.minusSeconds(1));
        watcher.tick();
        assertThat(stored).as("탈퇴 시각 뒤에 시작한 방송은 새 연동의 것").hasSize(3);

        watcher.unwatchChannel(Platform.SOOP, "soop-user-1", now.get());
        watcher.tick();
        assertThat(stored).hasSize(3);
        assertThat(checkedTokens).hasSize(3);
    }

    /** 스트리머마다 하나다. 더 늦게 시작한 방송이 갈아끼고, 이른 방송(늦게 온 옛 편지·시각 없는 재부착 줄)은 못 갈아낀다(L7). */
    @Test
    void 같은_스트리머는_더_늦게_시작한_방송이_갈아낀다() {
        linkAnswers.add(link("tok-1", now.get().plus(Duration.ofHours(8))));
        liveAnswers.add(onAir("숲", 1));
        SoopBroadcastWatcher watcher = watcher();

        watcher.watch("s-old", 42L, 시작);
        watcher.watch("s-new", 42L, 시작.plusSeconds(30));
        watcher.watch("s-older", 42L, 시작.minusSeconds(30));
        watcher.watch("s-epoch", 42L, Instant.EPOCH);
        watcher.tick();

        assertThat(stored).extracting(BroadcastInfo::streamId).containsExactly("s-new");
        assertThat(forgotten).as("갈아낀 옛 방송의 카운터를 치운다").containsExactly("s-old");
    }

    @Test
    void 이미_지켜보는_방송을_다시_넣어도_하나다() {
        linkAnswers.add(link("tok-1", now.get().plus(Duration.ofHours(8))));
        liveAnswers.add(onAir("숲", 1));
        SoopBroadcastWatcher watcher = watcher();

        watcher.watch("s-1", 42L, 시작);
        watcher.watch("s-1", 42L, Instant.EPOCH);
        watcher.tick();

        assertThat(stored).hasSize(1);
        assertThat(resolved).hasSize(1);
    }

    @Test
    void 상한이_차면_새_스트리머는_안_넣는다() {
        linkAnswers.add(link("tok-1", now.get().plus(Duration.ofHours(8))));
        liveAnswers.add(onAir("숲", 1));
        SoopBroadcastWatcher watcher = watcher(2);

        watcher.watch("s-1", 1L, 시작);
        watcher.watch("s-2", 2L, 시작);
        watcher.watch("s-3", 3L, 시작);
        watcher.tick();

        assertThat(resolved).containsExactlyInAnyOrder(1L, 2L);
    }

    // ── 감사 2회차 ──

    /** n1: 상한은 실제로 확인 중인 지켜보기만 센다. 멈춘 것(연동 없음)은 자리를 안 먹는다. 멈춘 것은 그대로 남아 다시 안 묻는다. */
    @Test
    void 멈춘_지켜보기는_상한에_안_센다() {
        linkAnswers.add(SoopLink.refused(false));
        linkAnswers.add(link("tok-2", now.get().plus(Duration.ofHours(8))));
        liveAnswers.add(onAir("숲", 1));
        SoopBroadcastWatcher watcher = watcher(1);

        watcher.watch("s-1", 1L, 시작);
        watcher.tick();
        watcher.watch("s-2", 2L, 시작);
        watcher.tick();

        assertThat(resolved).containsExactly(1L, 2L);
        assertThat(stored).extracting(BroadcastInfo::streamId).containsExactly("s-2");
    }

    /**
     * n2: 한 틱에 시간 예산이 있다(치지직 방송 정보의 알림 예산과 같은 꼴). 넘으면 남은 방송은 다음 틱으로 미루고, 다음 틱은 미룬 것부터 본다.
     * SOOP이 매달리면 방송 100개 × 최대 몇 초가 한 틱에 더해져 주기가 무너진다. clip 알림도 같은 예산 안이다.
     */
    @Test
    void 틱_예산을_넘으면_남은_방송은_다음_틱에_미룬_것부터_본다() {
        List<String> checked = new CopyOnWriteArrayList<>();
        SoopBroadcastWatcher watcher = new SoopBroadcastWatcher(
                id -> SoopLink.granted("u-" + id, "tok-" + id, now.get().plus(Duration.ofHours(8))),
                token -> {
                    checked.add(token);
                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return onAir("숲", 1);
                }, stored::add, relay, platforms, Set::of, ids -> Set.of(), 10, clock, Duration.ofMillis(400));
        for (long id = 1; id <= 5; id++) {
            watcher.watch("s-" + id, id, 시작);
        }

        long started = System.nanoTime();
        watcher.tick();
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(checked).as("150ms 셋이면 400ms를 넘는다").hasSize(3);
        assertThat(took).isLessThan(Duration.ofMillis(700));
        List<String> first = List.copyOf(checked);
        checked.clear();
        watcher.tick();

        List<String> deferred = new ArrayList<>(List.of("tok-1", "tok-2", "tok-3", "tok-4", "tok-5"));
        deferred.removeAll(first);
        assertThat(checked.subList(0, 2)).as("미룬 둘이 먼저다").containsExactlyInAnyOrderElementsOf(deferred);
    }

    /**
     * n4: 다시 받은 토큰이 같으면(auth가 아직 안 갈았다) 멈추지 않고 다음 틱에 다시 묻는다. 같은 토큰 거부가 {@value SoopBroadcastWatcher#MAX_SAME_TOKEN_REJECTIONS}번
     * 이어지면 그때 멈춘다. 그 사이 한 번이라도 받아들여지면 센 수를 지운다.
     */
    @Test
    void 같은_토큰이_다시_오면_멈추지_않고_다음_틱에_다시_묻는다() {
        linkAnswers.add(link("tok-1", now.get().plus(Duration.ofHours(8))));
        liveAnswers.add(outcome(SoopLiveStatus.Outcome.REJECTED));
        SoopBroadcastWatcher watcher = watcher();
        watcher.watch("s-1", 42L, 시작);

        watcher.tick();
        assertThat(checkedTokens).as("같은 토큰으로 또 묻지 않는다").containsExactly("tok-1");
        watcher.tick();
        watcher.tick();
        watcher.tick();
        watcher.tick();

        assertThat(checkedTokens).as("세 번째 같은 토큰 거부에서 멈춘다").hasSize(3);
        assertThat(resolved).hasSize(6);
    }

    @Test
    void 같은_토큰_거부_뒤_받아들여지면_이어간다() {
        linkAnswers.add(link("tok-1", now.get().plus(Duration.ofHours(8))));
        liveAnswers.add(outcome(SoopLiveStatus.Outcome.REJECTED));
        liveAnswers.add(outcome(SoopLiveStatus.Outcome.REJECTED));
        liveAnswers.add(onAir("숲", 1));
        liveAnswers.add(outcome(SoopLiveStatus.Outcome.REJECTED));
        liveAnswers.add(outcome(SoopLiveStatus.Outcome.REJECTED));
        liveAnswers.add(onAir("숲", 2));
        SoopBroadcastWatcher watcher = watcher();
        watcher.watch("s-1", 42L, 시작);

        for (int i = 0; i < 6; i++) {
            watcher.tick();
        }

        assertThat(stored).as("받아들여진 뒤 센 수가 지워져 멈추지 않는다").extracting(BroadcastInfo::viewers).containsExactly(1, 2);
    }

    /**
     * n5: 종료 메모가 생긴 방송은 틱에서 뺀다. 재부착 넣기가 종료 편지와 엇갈리거나 종료 편지를 놓친 방송이 떼기 꺼진 배포에서 남아, 끝난
     * 방송 번호에 SOOP 값이 쌓이지 않게 한다.
     */
    @Test
    void 종료_메모가_생긴_방송은_틱에서_뺀다() {
        linkAnswers.add(link("tok-1", now.get().plus(Duration.ofHours(8))));
        liveAnswers.add(onAir("숲", 1));
        SoopBroadcastWatcher watcher = watcher();
        watcher.watch("s-1", 1L, 시작);
        watcher.watch("s-2", 2L, 시작);
        endedMemos.add("s-1");

        watcher.tick();
        watcher.tick();

        assertThat(stored).extracting(BroadcastInfo::streamId).containsExactly("s-2", "s-2");
        assertThat(forgotten).containsExactly("s-1");
    }

    /** 재부착의 떼기 갈래: 명부에 없고 유예보다 먼저 시작한 방송은 멈춘다(종료 편지를 놓친 방송이 상한을 채우지 않게, L7). */
    @Test
    void 명부에_없는_오래된_방송은_멈춘다() {
        linkAnswers.add(link("tok-1", now.get().plus(Duration.ofHours(8))));
        liveAnswers.add(onAir("숲", 1));
        SoopBroadcastWatcher watcher = watcher();
        watcher.watch("s-listed", 1L, 시작);
        watcher.watch("s-gone", 2L, 시작);
        watcher.watch("s-fresh", 3L, now.get());

        watcher.retainListed(Set.of("s-listed"), now.get().minusSeconds(1));
        watcher.tick();

        assertThat(stored).extracting(BroadcastInfo::streamId).containsExactlyInAnyOrder("s-listed", "s-fresh");
        assertThat(forgotten).containsExactly("s-gone");
    }

    /** 한 방송의 실패(저장이 던짐)가 뒤 방송을 막지 않고 틱도 안 던진다. 중계는 저장과 따로다. */
    @Test
    void 한_방송의_실패가_뒤를_막지_않는다() {
        linkAnswers.add(link("tok-1", now.get().plus(Duration.ofHours(8))));
        liveAnswers.add(onAir("숲", 1));
        List<String> kept = new ArrayList<>();
        SoopBroadcastWatcher watcher = new SoopBroadcastWatcher(id -> linkAnswers.peekFirst(), token -> liveAnswers.peekFirst(),
                info -> {
                    if (info.streamId().equals("s-1")) {
                        throw new IllegalStateException("db down");
                    }
                    kept.add(info.streamId());
                }, relay, platforms, Set::of, 10, clock);
        watcher.watch("s-1", 1L, 시작);
        watcher.watch("s-2", 2L, 시작);

        watcher.tick();

        assertThat(kept).containsExactly("s-2");
        assertThat(relayed).containsExactlyInAnyOrder("s-1", "s-2");
    }
}
