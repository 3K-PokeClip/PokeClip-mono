package com.pokeclip.chat.collector.soop.chat;

import com.pokeclip.chat.collector.Platform;
import com.pokeclip.chat.collector.archive.ChatArchive;
import com.pokeclip.chat.collector.persist.ChatBuffer;
import com.pokeclip.chat.collector.persist.DonationBuffer;
import com.pokeclip.chat.collector.persist.PersistableChat;
import com.pokeclip.chat.collector.persist.PersistableDonation;
import com.pokeclip.chat.collector.relay.RelayPayload;
import com.pokeclip.chat.collector.relay.RelaySink;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 붙이기 · 떼기 · 다시 붙기의 줄 세우기(POK-397 PR4, 봇 2판). 브라우저 없이 탭 명령만 적는다.
 */
class SoopChatSessionsTest {

    private final List<String> calls = new CopyOnWriteArrayList<>();
    private SoopChatSessions sessions;

    @AfterEach
    void 끈다() {
        sessions.shutdown();
    }

    /**
     * 🔴 탭 열기 · 닫기는 세션 자물쇠 안에서 넣는다. 다시 붙기 예약이 「아직 그 세션인가」를 본 직후 떼기가 끼면 닫기 뒤에 열기가 들어가
     * 명부에 없는 방송의 탭이 남는다. 다시 붙기(예약 스레드)까지 같은 자물쇠인지 본다
     */
    @Test
    void 탭_명령은_떼기와_같은_자물쇠_안에서_넣는다() throws Exception {
        SoopChatProperties properties = new SoopChatProperties(true, "i", "s", "u", "o", 10, Duration.ofMillis(10),
                Duration.ofMillis(10));
        sessions = new SoopChatSessions(properties, new ChatBuffer(), new DonationBuffer(), ChatArchive.NONE, RelaySink.NONE,
                System::currentTimeMillis);
        sessions.attach(new SoopChatSessions.Tabs() {
            @Override
            public void open(String streamId, String token, long generation) {
                calls.add("open:" + Thread.holdsLock(sessions));
            }

            @Override
            public void close(String streamId) {
                calls.add("close:" + Thread.holdsLock(sessions));
            }
        });

        sessions.ensure("s-1", "bj-1", "t");
        sessions.onClosed("s-1", 1);
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (calls.size() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        sessions.close("s-1");

        assertThat(calls).containsExactly("open:true", "open:true", "close:true");
    }

    /** 끈 뒤에 지켜보기 틱이 붙이기 · 닫힘 알림을 보내도 탭을 안 열고 던지지도 않는다(닫히는 브라우저 · 멈춘 예약기) */
    @Test
    void 끈_뒤에는_붙이기도_다시_붙기도_안_한다() {
        SoopChatProperties properties = new SoopChatProperties(true, "i", "s", "u", "o", 10, Duration.ofMillis(10),
                Duration.ofMillis(10));
        sessions = new SoopChatSessions(properties, new ChatBuffer(), new DonationBuffer(), ChatArchive.NONE, RelaySink.NONE,
                System::currentTimeMillis);
        sessions.attach(new SoopChatSessions.Tabs() {
            @Override
            public void open(String streamId, String token, long generation) {
                calls.add("open:" + streamId);
            }

            @Override
            public void close(String streamId) {
                calls.add("close:" + streamId);
            }
        });
        sessions.ensure("s-1", "bj-1", "t");

        sessions.shutdown();
        sessions.ensure("s-2", "bj-2", "t");
        sessions.onClosed("s-1", 1);

        assertThat(calls).containsExactly("open:s-1");
        assertThat(sessions.size()).isZero();
    }

    // 세대 번호(POK-431, PR4 봇 codex P2): 브라우저 없이 세대를 직접 넣는다. 브라우저가 정말 그 탭의 세대를 싣는지는 SoopChatBrowserTest가 잰다

    private final List<Long> opened = new CopyOnWriteArrayList<>();
    private final ChatBuffer buffer = new ChatBuffer();
    private final DonationBuffer donations = new DonationBuffer();
    private final List<RelayPayload> relayed = new CopyOnWriteArrayList<>();
    private volatile long clock = 1_761_300_000_000L;

    /** 다시 붙기 간격 10ms부터(상한 1초): 로그의 delayMs로 실패 셈이 올랐는지 본다. */
    private void startRecording() {
        SoopChatProperties properties = new SoopChatProperties(true, "i", "s", "u", "o", 10, Duration.ofMillis(10),
                Duration.ofSeconds(1));
        sessions = new SoopChatSessions(properties, buffer, donations, ChatArchive.NONE,
                (streamId, payload) -> relayed.add(payload), () -> clock);
        sessions.attach(new SoopChatSessions.Tabs() {
            @Override
            public void open(String streamId, String token, long generation) {
                opened.add(generation);
            }

            @Override
            public void close(String streamId) {
            }
        });
    }

    private static SoopChatMessage chat(String text) {
        return new SoopChatMessage("MESSAGE", "v", "n", text, "{}");
    }

    @Test
    void 탭을_열_때마다_새_세대다() {
        startRecording();
        sessions.ensure("s-1", "bj-1", "t");
        sessions.ensure("s-2", "bj-2", "t");
        sessions.onClosed("s-1", opened.get(0));
        await(() -> opened.size() >= 3);

        assertThat(opened).doesNotHaveDuplicates();
    }

    @Test
    void 채널이_바뀐_뒤_옛_세대_채팅은_새_채널로_저장되지_않는다() {
        startRecording();
        sessions.ensure("s-1", "bj-old", "t");
        long old = opened.getLast();
        sessions.ensure("s-1", "bj-new", "t2");
        long current = opened.getLast();

        sessions.onMessage("s-1", old, chat("옛"));
        sessions.onMessage("s-1", current, chat("새"));

        List<PersistableChat> chats = buffer.drain(100);
        assertThat(chats).extracting(c -> c.channelId() + ":" + c.content()).containsExactly("bj-new:새");
        assertThat(sessions.staleEvents()).isEqualTo(1);
    }

    /** 오류로 다시 붙은 뒤 옛 탭의 늦은 닫힘이 와도 막 연 탭을 또 열지 않고, 다음 대기도 늘지 않는다(계획 검증 중대 1) */
    @Test
    void 다시_붙은_뒤_옛_세대_닫힘은_다시_열지_않고_대기도_늘리지_않는다() throws Exception {
        try (LogCaptor captor = new LogCaptor()) {
            startRecording();
            sessions.ensure("s-1", "bj-1", "t");
            sessions.onError("s-1", opened.getLast(), "E");
            await(() -> opened.size() >= 2);

            sessions.onClosed("s-1", opened.getFirst());
            Thread.sleep(200);
            assertThat(opened).as("옛 세대 닫힘은 다시 붙기를 안 건다").hasSize(2);

            sessions.onError("s-1", opened.getLast(), "E");
            await(() -> opened.size() >= 3);
            assertThat(captor.messages()).filteredOn(m -> m.startsWith("soop.chat.retrying"))
                    .as("둘째 실패의 대기는 두 배 한 번(셈이 옛 닫힘으로 안 올랐다)")
                    .containsExactly("soop.chat.retrying stream=s-1 reason=error delayMs=10",
                            "soop.chat.retrying stream=s-1 reason=error delayMs=20");
        }
    }

    /** 옛 탭의 채팅은 저장되지 않고, 실패 셈도 되돌리지 않는다(되돌리면 새 탭이 실패를 거듭해도 늘 1초로 재시도한다) */
    @Test
    void 옛_세대_채팅은_대기를_되돌리지_않는다() throws Exception {
        try (LogCaptor captor = new LogCaptor()) {
            startRecording();
            sessions.ensure("s-1", "bj-1", "t");
            sessions.onError("s-1", opened.getLast(), "E");
            await(() -> opened.size() >= 2);

            sessions.onMessage("s-1", opened.getFirst(), chat("옛"));
            sessions.onError("s-1", opened.getLast(), "E");
            await(() -> opened.size() >= 3);

            assertThat(buffer.drain(100)).isEmpty();
            assertThat(captor.messages()).filteredOn(m -> m.startsWith("soop.chat.retrying"))
                    .containsExactly("soop.chat.retrying stream=s-1 reason=error delayMs=10",
                            "soop.chat.retrying stream=s-1 reason=error delayMs=20");
            assertThat(captor.messages()).filteredOn(m -> m.startsWith("soop.chat.stale_event"))
                    .as("첫 번만 INFO, 아이디 · 본문 없이").containsExactly("soop.chat.stale_event stream=s-1");
        }
    }

    @Test
    void 같은_세대는_지금처럼_받고_다시_붙는다() {
        startRecording();
        sessions.ensure("s-1", "bj-1", "t");
        sessions.onMessage("s-1", opened.getLast(), chat("하나"));
        sessions.onClosed("s-1", opened.getLast());
        await(() -> opened.size() >= 2);

        assertThat(buffer.drain(100)).extracting(PersistableChat::content).containsExactly("하나");
        assertThat(relayed).as("치지직 채팅과 한 줄기로 섞여 화면이 줄마다 플랫폼을 보인다(POK-466)")
                .singleElement().isInstanceOfSatisfying(RelayPayload.Chat.class, c -> {
                    assertThat(c.platform()).isEqualTo(Platform.SOOP);
                    assertThat(c.text()).isEqualTo("하나");
                });
        assertThat(sessions.staleEvents()).isZero();
    }

    // 후원(POK-431)

    private static SoopChatDonation gift(String action, String count, String fromVod, String fromStation, String relaysBroad) {
        return SoopChatDonation.of(action, "fan-1", "팬", count, fromVod, fromStation, relaysBroad);
    }

    @Test
    void 후원은_SOOP_줄로_개수와_종류와_빈_문구로_표와_중계에_간다() {
        startRecording();
        sessions.ensure("s-1", "bj-1", "t");

        sessions.onDonation("s-1", opened.getLast(), gift("BALLOON_GIFTED", "10", "false", "false", "false"));

        assertThat(donations.drain(10)).singleElement().satisfies(d -> {
            assertThat(d.platform()).isEqualTo(Platform.SOOP);
            assertThat(d.streamId()).isEqualTo("s-1");
            assertThat(d.channelId()).as("스트리머 SOOP 아이디").isEqualTo("bj-1");
            assertThat(d.donatorChannelId()).isEqualTo("fan-1");
            assertThat(d.donatorNickname()).isEqualTo("팬");
            assertThat(d.donationType()).isEqualTo("BALLOON_GIFTED");
            assertThat(d.payAmount()).as("SOOP은 원이 아니라 개수").isEqualTo(10L);
            assertThat(d.donationText()).isEmpty();
            assertThat(d.receivedAtMillis()).isEqualTo(clock);
        });
        assertThat(relayed).singleElement().isEqualTo(new RelayPayload.Donation(Platform.SOOP, java.time.Instant.ofEpochMilli(clock),
                "팬", "fan-1", 10L, "BALLOON_GIFTED", ""));
        assertThat(buffer.drain(10)).as("채팅 바구니에는 안 간다").isEmpty();
    }

    /** 다시보기 · 방송국에서 보낸 후원은 이 방송의 반응이 아니라 버리고, 칸 뜻을 잘못 읽었을 때 보이게 닫을 때 합계를 남긴다(결정 1) */
    @Test
    void 다시보기와_방송국_후원은_버리고_세어_닫을_때_합계를_남긴다() {
        try (LogCaptor captor = new LogCaptor()) {
            startRecording();
            sessions.ensure("s-1", "bj-1", "t");

            sessions.onDonation("s-1", opened.getLast(), gift("BALLOON_GIFTED", "1", "true", "false", "false"));
            sessions.onDonation("s-1", opened.getLast(), gift("BALLOON_GIFTED", "1", "false", "true", "false"));
            sessions.close("s-1");

            assertThat(donations.drain(10)).isEmpty();
            assertThat(relayed).isEmpty();
            assertThat(captor.messages()).filteredOn(m -> m.startsWith("soop.chat.closed")).containsExactly(
                    "soop.chat.closed stream=s-1 received=0 donations=0 relayDonationsDropped=0 vodStationDonationsDropped=2");
        }
    }

    /** 중계방에서 온 후원은 버리고 세어, 첫 번 INFO와 닫을 때 합계로 남긴다(결정 3). 아이디 · 닉네임은 안 싣는다 */
    @Test
    void 중계방_후원은_버리고_세어_닫을_때_합계를_남긴다() {
        try (LogCaptor captor = new LogCaptor()) {
            startRecording();
            sessions.ensure("s-1", "bj-1", "t");
            for (int i = 0; i < 3; i++) {
                sessions.onDonation("s-1", opened.getLast(), gift("BALLOON_GIFTED", "1", "false", "false", "true"));
            }
            sessions.close("s-1");

            assertThat(donations.drain(10)).isEmpty();
            assertThat(relayed).isEmpty();
            assertThat(captor.messages()).filteredOn(m -> m.startsWith("soop.chat.donation_relay_dropped"))
                    .containsExactly("soop.chat.donation_relay_dropped stream=s-1");
            assertThat(captor.messages()).filteredOn(m -> m.startsWith("soop.chat.closed"))
                    .containsExactly("soop.chat.closed stream=s-1 received=0 donations=0 relayDonationsDropped=3 vodStationDonationsDropped=0");
            assertThat(String.join("\n", captor.messages())).doesNotContain("fan-1").doesNotContain("팬");
        }
    }

    @Test
    void 개수가_숫자가_아니면_null로_남긴다() {
        startRecording();
        sessions.ensure("s-1", "bj-1", "t");

        sessions.onDonation("s-1", opened.getLast(), gift("STICKER_GIFTED", "undefined", "false", "false", "false"));

        assertThat(donations.drain(10)).singleElement().extracting(PersistableDonation::payAmount).isNull();
    }

    @Test
    void 옛_세대_후원은_버린다() {
        startRecording();
        sessions.ensure("s-1", "bj-old", "t");
        long old = opened.getLast();
        sessions.ensure("s-1", "bj-new", "t2");

        sessions.onDonation("s-1", old, gift("BALLOON_GIFTED", "1", "false", "false", "false"));

        assertThat(donations.drain(10)).isEmpty();
        assertThat(relayed).isEmpty();
    }

    /**
     * 🔴 순번은 음수로, 프로세스 하나에서 이어진다(계획 검증 중대 2). 치지직 순번(세션마다 1부터, 양수)과 같은 방송 번호 · 같은 밀리초에서
     * 겹치면 중복 열쇠가 SOOP 후원을 조용히 접는다. 채널이 바뀌어 세션이 새로 서도 다시 -1부터 세지 않는다
     */
    @Test
    void 순번은_음수로_세션이_바뀌어도_이어진다() {
        startRecording();
        sessions.ensure("s-1", "bj-old", "t");
        sessions.onDonation("s-1", opened.getLast(), gift("BALLOON_GIFTED", "1", "false", "false", "false"));
        sessions.onDonation("s-1", opened.getLast(), gift("BALLOON_GIFTED", "1", "false", "false", "false"));
        sessions.ensure("s-1", "bj-new", "t2");
        sessions.onDonation("s-1", opened.getLast(), gift("BALLOON_GIFTED", "1", "false", "false", "false"));

        assertThat(donations.drain(10)).extracting(PersistableDonation::receivedSeq).containsExactly(-1L, -2L, -3L);
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("5초 안에 조건이 안 섰다");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
