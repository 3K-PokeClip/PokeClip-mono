package com.pokeclip.chat.collector.soop.chat;

import com.pokeclip.chat.collector.ChatLogLeakTest;
import com.pokeclip.chat.collector.Platform;
import com.pokeclip.chat.collector.archive.ArchivableChat;
import com.pokeclip.chat.collector.archive.ArchiveCounters;
import com.pokeclip.chat.collector.archive.ChatArchive;
import com.pokeclip.chat.collector.persist.ChatBuffer;
import com.pokeclip.chat.collector.persist.PersistableChat;
import com.pokeclip.chat.collector.relay.RelayPayload;
import com.pokeclip.chat.collector.relay.RelaySink;
import com.pokeclip.chat.collector.soop.chat.FakeSoopSdkServer.Step;
import com.pokeclip.web.support.LogCaptor;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 진짜 Chromium(Playwright)으로 SOOP 채팅 받기를 잰다(POK-397 PR4). SDK만 같은 모양의 가짜다({@link FakeSoopSdkServer}): 진짜 SOOP은
 * 실방송 · 실계정이 있어야 해서 아침 실방송 시험이 맡는다. 여기서는 브라우저 스레드 · 바인딩 · 다시 붙기 · 떼기 · 세 길 · 비밀값 유출을 본다.
 *
 * <p>{@code browser} 태그: Chromium을 내려받아 띄우므로 따로 돌릴 수 있게 갈라 둔다.
 */
@Tag("browser")
class SoopChatBrowserTest {

    private static final String TOKEN = "soop-token-" + System.nanoTime();
    private static final String SECRET = "soop-secret-" + System.nanoTime();

    private FakeSoopSdkServer sdk;
    private ChatBuffer buffer;
    private final List<ArchivableChat> archived = new CopyOnWriteArrayList<>();
    private final List<String> relayed = new CopyOnWriteArrayList<>();
    private final List<RelayPayload> relayPayloads = new CopyOnWriteArrayList<>();
    private SoopChatSessions sessions;
    private SoopChatBrowser browser;

    /**
     * Chromium을 한 번 미리 띄운다. 처음 띄울 때 Playwright가 브라우저를 내려받는데(로컬 · CI, 수십 초), 그것이 첫 시험의 30초 기다림을
     * 먹어 첫 시험만 떨어졌다(첫 실행 실측). 이미지는 미리 구워 두므로 운영에는 없는 시간이다.
     */
    @BeforeAll
    static void 브라우저를_미리_받는다() {
        try (Playwright playwright = Playwright.create()) {
            playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true)).close();
        }
    }

    @BeforeEach
    void 띄운다() throws Exception {
        sdk = new FakeSoopSdkServer();
        buffer = new ChatBuffer();
    }

    @AfterEach
    void 끈다() throws Exception {
        if (sessions != null) {
            sessions.shutdown();
        }
        if (browser != null) {
            browser.close();
        }
        sdk.close();
    }

    private void start(int maxSessions) {
        SoopChatProperties properties = new SoopChatProperties(true, "client-1", SECRET, sdk.sdkUrl(), "http://localhost",
                maxSessions, Duration.ofMillis(100), Duration.ofMillis(400));
        RelaySink relay = (streamId, payload) -> {
            relayed.add(streamId);
            relayPayloads.add(payload);
        };
        ChatArchive archive = new ChatArchive() {
            @Override
            public void offer(ArchivableChat chat) {
                archived.add(chat);
            }

            @Override
            public void beginClose() {
            }

            @Override
            public void awaitClosed(Duration budget) {
            }

            @Override
            public ArchiveCounters counters() {
                return ArchiveCounters.NONE;
            }
        };
        sessions = new SoopChatSessions(properties, buffer, archive, relay, System::currentTimeMillis);
        browser = new SoopChatBrowser(properties, sessions);
        sessions.attach(new SoopChatSessions.Tabs() {
            @Override
            public void open(String streamId, String token) {
                browser.open(streamId, token);
            }

            @Override
            public void close(String streamId) {
                browser.close(streamId);
            }
        });
    }

    @Test
    void 붙으면_채팅_둘만_세_길로_platform_SOOP으로_간다() {
        sdk.script(Step.message("MESSAGE", "viewer-1", "닉1", "안녕"), Step.other("IN"),
                Step.message("MANAGER_MESSAGE", "manager-1", "매니저", "공지 아님"), Step.other("ADMIN_NOTICE"));
        start(10);

        sessions.ensure("s-1", "soop-bj", TOKEN);

        List<PersistableChat> chats = new ArrayList<>();
        await(() -> {
            chats.addAll(buffer.drain(100));
            return chats.size() >= 2;
        });
        assertThat(sdk.connects).singleElement().satisfies(c -> {
            assertThat(c.clientId()).isEqualTo("client-1");
            assertThat(c.clientSecret()).as("SDK 생성자가 받는 비밀값이 페이지에 갔다").isEqualTo(SECRET);
            assertThat(c.token()).isEqualTo(TOKEN);
        });
        assertThat(chats).extracting(PersistableChat::platform).containsOnly(Platform.SOOP);
        assertThat(chats).extracting(PersistableChat::streamId).containsOnly("s-1");
        assertThat(chats).extracting(PersistableChat::channelId).as("채널은 스트리머의 SOOP 아이디").containsOnly("soop-bj");
        assertThat(chats).extracting(PersistableChat::senderChannelId).containsExactly("viewer-1", "manager-1");
        assertThat(chats).extracting(PersistableChat::content).containsExactly("안녕", "공지 아님");
        assertThat(chats).extracting(PersistableChat::nickname).containsExactly("닉1", "매니저");
        assertThat(chats).extracting(PersistableChat::userRole).containsExactly(null, "manager");
        assertThat(chats).allSatisfy(c -> assertThat(c.messageTimeMillis()).as("SOOP은 시각을 안 줘서 받은 시각이다")
                .isEqualTo(c.receivedAtMillis()));
        await(() -> archived.size() >= 2 && relayed.size() >= 2);
        assertThat(archived).extracting(ArchivableChat::platform).containsOnly(Platform.SOOP);
        assertThat(archived.getFirst().raw()).contains("\"message\":\"안녕\"");
        assertThat(relayPayloads).allSatisfy(p -> assertThat(p).isInstanceOf(RelayPayload.Chat.class));
        // 입장 · 공지는 어디에도 안 간다(잠깐 더 기다려도)
        sleep(300);
        assertThat(buffer.drain(100)).isEmpty();
        assertThat(archived).hasSize(2);
    }

    /** 펌프는 한 탭에서만 기다리지만 다른 탭의 채팅도 온다(탭 셋, 방송마다 제 번호로) */
    @Test
    void 탭이_여럿이어도_탭마다_제_방송으로_받는다() {
        sdk.script(Step.message("MESSAGE", "v1", "n1", "하나"));
        sdk.script(Step.message("MESSAGE", "v2", "n2", "둘"));
        sdk.script(Step.message("MESSAGE", "v3", "n3", "셋"));
        start(10);

        sessions.ensure("s-1", "bj-1", TOKEN);
        sessions.ensure("s-2", "bj-2", TOKEN);
        sessions.ensure("s-3", "bj-3", TOKEN);

        List<PersistableChat> chats = new ArrayList<>();
        await(() -> {
            chats.addAll(buffer.drain(100));
            return chats.size() >= 3;
        });
        assertThat(chats).extracting(c -> c.streamId() + ":" + c.channelId())
                .containsExactlyInAnyOrder("s-1:bj-1", "s-2:bj-2", "s-3:bj-3");
    }

    /** Chromium이 죽으면 탭마다 오류로 알려 새 브라우저로 다시 붙는다(메모리 상한에 죽은 경우) */
    @Test
    void 브라우저가_죽으면_새로_띄워_다시_붙는다() {
        start(10);
        sessions.ensure("s-1", "bj-1", TOKEN);
        sessions.ensure("s-2", "bj-2", TOKEN);
        await(() -> sdk.connects.size() >= 2);

        browser.crashForTest();

        await(() -> sdk.connects.size() >= 4);
        assertThat(sessions.size()).isEqualTo(2);
    }

    @Test
    void 연결이_닫히면_다시_붙고_다음_채팅을_받는다() {
        sdk.script(Step.message("MESSAGE", "v1", "n1", "첫째"), Step.closed());
        sdk.script(Step.message("MESSAGE", "v2", "n2", "둘째"));
        start(10);

        sessions.ensure("s-1", "soop-bj", TOKEN);

        List<PersistableChat> chats = new ArrayList<>();
        await(() -> {
            chats.addAll(buffer.drain(100));
            return chats.size() >= 2;
        });
        assertThat(chats).extracting(PersistableChat::content).containsExactly("첫째", "둘째");
        assertThat(sdk.connects).hasSize(2);
    }

    @Test
    void 다시_붙을_때는_새_토큰을_쓴다() {
        sdk.script(Step.closed());
        start(10);

        sessions.ensure("s-1", "soop-bj", TOKEN);
        sessions.ensure("s-1", "soop-bj", TOKEN + "-new");

        await(() -> sdk.connects.size() >= 2);
        assertThat(sdk.connects).extracting(FakeSoopSdkServer.Connect::token).containsExactly(TOKEN, TOKEN + "-new");
    }

    @Test
    void 떼면_탭을_닫고_다시_안_붙는다() {
        sdk.script(Step.message("MESSAGE", "v1", "n1", "하나"), Step.closed());
        start(10);

        sessions.ensure("s-1", "soop-bj", TOKEN);
        await(() -> !buffer.drain(100).isEmpty());
        sessions.close("s-1");

        sleep(800);
        assertThat(sdk.connects).as("뗀 뒤 닫힘 알림이 와도 다시 안 붙는다").hasSize(1);
        assertThat(browser.openTabs()).isZero();
        assertThat(sessions.size()).isZero();
    }

    @Test
    void SDK를_못_불러오면_오류로_보고_다시_시도한다() {
        sdk.sdkMissing(true);
        start(10);

        sessions.ensure("s-1", "soop-bj", TOKEN);

        await(() -> sdk.sdkLoads.size() >= 3);
        assertThat(sdk.connects).isEmpty();
    }

    @Test
    void 상한을_넘으면_더_안_연다() {
        start(1);

        sessions.ensure("s-1", "bj-1", TOKEN);
        sessions.ensure("s-2", "bj-2", TOKEN);

        await(() -> sdk.connects.size() >= 1);
        sleep(300);
        assertThat(sdk.connects).hasSize(1);
        assertThat(sessions.size()).isEqualTo(1);
    }

    /** 끄는 순서(세션 떼기 → 탭 닫기 → 브라우저 닫기)가 수집기 종료 유예(20초) 안에, 상한 5초 안에 끝난다. 탭 셋이 열린 채로 */
    @Test
    void 끌_때_탭을_닫고_브라우저를_닫는_것이_유예_안에_끝난다() throws Exception {
        start(10);
        sessions.ensure("s-1", "bj-1", TOKEN);
        sessions.ensure("s-2", "bj-2", TOKEN);
        sessions.ensure("s-3", "bj-3", TOKEN);
        await(() -> sdk.connects.size() >= 3);
        SoopChatConfiguration.SoopChatRuntime runtime = new SoopChatConfiguration.SoopChatRuntime(sessions, browser);

        long started = System.nanoTime();
        runtime.destroy();
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(took).isLessThan(SoopChatBrowser.CLOSE_BUDGET);
        assertThat(browser.stopped()).as("브라우저 스레드가 끝나야 프로세스가 내린다").isTrue();
        assertThat(sessions.size()).isZero();
        browser = null;
        sessions = null;
    }

    /** 토큰 · 앱 비밀값 · 본문 · 아이디 · 닉네임이 어느 로그 줄에도 없다(오류 · 다시 붙기 갈래까지 태운다) */
    @Test
    void 비밀값과_채팅이_로그에_안_남는다() {
        sdk.script(Step.message("MESSAGE", "viewer-leak", "닉-leak", "본문-leak"), Step.error("ERR-1"), Step.closed());
        sdk.script();
        try (LogCaptor captor = new LogCaptor()) {
            start(10);
            sessions.ensure("s-1", "bj-leak", TOKEN);
            await(() -> sdk.connects.size() >= 2);
            sessions.close("s-1");
            ChatLogLeakTest.assertNoSecretsIn(ChatLogLeakTest.renderAll(captor),
                    List.of(TOKEN, SECRET, "viewer-leak", "닉-leak", "본문-leak", "bj-leak"));
            assertThat(captor.messages()).anySatisfy(m -> assertThat(m).startsWith("soop.chat.retrying stream=s-1"));
        }
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("30초 안에 조건이 안 섰다");
            }
            sleep(50);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
