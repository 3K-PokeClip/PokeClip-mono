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
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

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
            browser.awaitStopped(Duration.ofSeconds(5));
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
            public void open(String streamId, String token, long generation) {
                browser.open(streamId, token, generation);
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

        sdk.script(Step.message("MESSAGE", "v1", "n1", "살아남"));
        browser.crashBrowserForTest();

        await(() -> sdk.connects.size() >= 4);
        assertThat(sessions.size()).isEqualTo(2);
        List<PersistableChat> chats = new ArrayList<>();
        await(() -> {
            chats.addAll(buffer.drain(100));
            return !chats.isEmpty();
        });
        assertThat(chats).extracting(PersistableChat::content).containsExactly("살아남");
    }

    /**
     * 첫 탭의 렌더러만 죽으면 그 탭을 버리고 다시 붙는다. 펌프가 깨진 페이지에 매여 헛돌지 않는다.
     *
     * <p>🔴 GitHub Actions에서는 건너뛴다. 러너가 Ubuntu 26이라 Playwright 1.52가 지원하지 않고(설치 때 경고) 브라우저 시스템 꾸러미가
     * 빠져 있어, 렌더러가 죽어도 충돌 사건이 안 왔다(CDP {@code Page.crash} · {@code chrome://crash} 둘 다 30초 시한, CI 두 번 실측).
     * 운영 이미지와 같은 조건(Ubuntu 24.04 + {@code --with-deps}, Playwright 공식 이미지 {@code playwright/java:v1.52.0-noble})에서는
     * 통과한다(로컬 컨테이너 실측). 다른 브라우저 시험 10건은 CI에서도 돈다.
     */
    @Test
    @DisabledIfEnvironmentVariable(named = "GITHUB_ACTIONS", matches = "true",
            disabledReason = "CI 러너(Ubuntu 26)에서 렌더러 충돌 사건이 안 온다. 운영과 같은 noble+deps 컨테이너에서 통과")
    void 페이지가_깨지면_그_탭만_다시_붙고_채팅을_받는다() {
        // 앞 두 연결 몫의 빈 대본을 먼저 쌓는다. 안 쌓으면 둘째 연결이 붙은 뒤(connects 셈) 대본을 받으러 가기 전에 아래 「다시」가 쌓여
        // s-2가 그것을 가져가 흘린다(대본은 연결 순서대로 나간다, 감사 1회차 경미 4)
        sdk.script();
        sdk.script();
        start(10);
        sessions.ensure("s-1", "bj-1", TOKEN);
        await(() -> sdk.connects.size() >= 1);
        sessions.ensure("s-2", "bj-2", TOKEN);
        await(() -> sdk.connects.size() >= 2);

        sdk.script(Step.message("MESSAGE", "v1", "n1", "다시"));
        browser.crashPageForTest("s-1");

        List<PersistableChat> chats = new ArrayList<>();
        await(() -> {
            chats.addAll(buffer.drain(100));
            return !chats.isEmpty();
        });
        assertThat(chats).extracting(PersistableChat::streamId).containsExactly("s-1");
        assertThat(sdk.connects).hasSize(3);
    }

    /** 방송 중에 다른 SOOP 계정으로 다시 연동하면 탭을 갈아 새 채널로 저장한다(토큰만 바꾸면 옛 채널로 저장된다) */
    @Test
    void 채널이_바뀌면_새로_붙어_새_채널로_저장한다() {
        // 첫 연결 몫의 빈 대본을 먼저 쌓는다. 안 쌓으면 첫 연결이 대본을 받으러 가기 전에 「새 계정」이 쌓여 옛 탭이 그것을 흘리고, 세대 번호가
        // 그 줄을 버려 둘째 연결은 빈 대본을 받는다(30초 시한, 클래스 10회 중 1회 실측). 옛 코드는 그 줄을 새 채널로 저장해 통과했다
        sdk.script();
        start(10);
        sessions.ensure("s-1", "bj-old", TOKEN);
        await(() -> sdk.connects.size() >= 1);

        sdk.script(Step.message("MESSAGE", "v1", "n1", "새 계정"));
        sessions.ensure("s-1", "bj-new", "token-new");

        List<PersistableChat> chats = new ArrayList<>();
        await(() -> {
            chats.addAll(buffer.drain(100));
            return !chats.isEmpty();
        });
        assertThat(chats).extracting(PersistableChat::channelId).containsExactly("bj-new");
        assertThat(sdk.connects).extracting(FakeSoopSdkServer.Connect::token).containsExactly(TOKEN, "token-new");
        assertThat(sessions.size()).isEqualTo(1);
    }

    /**
     * 🔴 채널이 바뀐 직후, 브라우저가 옛 탭을 닫기 전(펌프 한 번, 최대 0.1초)에 옛 탭이 보낸 채팅이 새 채널 아이디로 저장되면 안 된다
     * (PR4 봇 codex P2). 연결마다 20ms 간격으로 「연결 번호-순번」을 흘리고 채널을 다섯 번 바꿔, 줄마다 그 연결의 채널로만 저장됐는지 본다.
     * 틈에 걸려 버려진 줄이 실제로 있었는지도 본다(없으면 이 시험은 아무것도 안 잰 것이다. 한 번 바꿀 때 걸리는 줄 수는 펌프 시점에
     * 따라 0~5줄이라 다섯 번 바꾼다).
     */
    @Test
    void 채널이_바뀌는_틈에_온_옛_탭_채팅은_새_채널로_저장되지_않는다() {
        int switches = 5;
        for (int k = 0; k <= switches; k++) {
            Step[] steps = new Step[200];
            for (int i = 0; i < steps.length; i++) {
                steps[i] = Step.message("MESSAGE", "v", "n", k + "-" + i);
            }
            sdk.script(steps);
        }
        start(10);
        List<PersistableChat> chats = new ArrayList<>();
        for (int k = 0; k <= switches; k++) {
            String prefix = k + "-";
            sessions.ensure("s-1", "bj-" + k, "token-" + k);
            await(() -> {
                chats.addAll(buffer.drain(100));
                return chats.stream().filter(c -> c.content().startsWith(prefix)).count() >= 3;
            });
        }

        assertThat(chats).allSatisfy(c -> assertThat(c.channelId())
                .as("연결 %s의 채팅", c.content()).isEqualTo("bj-" + c.content().substring(0, c.content().indexOf('-'))));
        assertThat(sessions.staleEvents()).as("틈에 걸린 옛 탭 채팅이 있었고 버려졌다").isPositive();
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
    void 끌_때_기다리지_않고_뒤에서_탭과_브라우저를_닫는다() throws Exception {
        start(10);
        sessions.ensure("s-1", "bj-1", TOKEN);
        sessions.ensure("s-2", "bj-2", TOKEN);
        sessions.ensure("s-3", "bj-3", TOKEN);
        await(() -> sdk.connects.size() >= 3);
        SoopChatConfiguration.SoopChatRuntime runtime = new SoopChatConfiguration.SoopChatRuntime(sessions, browser);

        long started = System.nanoTime();
        runtime.destroy();
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        assertThat(took).as("종료 예산(유예 20초에 1초 여유)에 더하지 않게 기다리지 않는다").isLessThan(Duration.ofMillis(200));
        assertThat(browser.awaitStopped(Duration.ofSeconds(5))).as("브라우저 스레드는 뒤에서 탭 · 브라우저를 닫고 끝난다").isTrue();
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
