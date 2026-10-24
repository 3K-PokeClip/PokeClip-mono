package com.pokeclip.chat.collector.soop.chat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 화면 없는 Chromium 하나로 SOOP 공식 Chat SDK를 돌린다(POK-397 PR4, ADR-105 사용자 결정). 방송마다 브라우저 컨텍스트(탭) 하나다.
 *
 * <p>🔴 <b>Playwright 객체는 스레드를 나눠 쓸 수 없고, 페이지가 부르는 콜백도 Playwright를 부르는 그 스레드가 펌프해야 온다.</b> 그래서
 * 전용 스레드 하나({@code soop-chat-browser})가 브라우저를 쥐고, 밖에서는 열기 · 닫기 명령을 줄에 넣기만 한다. 탭이 하나라도 있으면 그
 * 스레드는 {@code waitForTimeout}으로 펌프하며 사이사이 명령을 꺼낸다.
 *
 * <p>Chromium은 첫 탭을 열 때 띄운다. 채팅을 켰어도 방송이 없으면 브라우저 프로세스가 없다.
 *
 * <p>🔴 토큰 · 앱 비밀값은 페이지에 인자로만 넘기고(HTML 글자로 박지 않는다) 로그 · 예외 메시지에 안 싣는다.
 */
public class SoopChatBrowser implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SoopChatBrowser.class);

    /** 페이지가 자바를 부르는 이름. 이음 스크립트와 같은 이름이어야 한다. */
    static final String BINDING = "__pokeclipSoop";

    /** 탭이 있을 때 한 번에 펌프하는 시간. 명령(열기 · 닫기)이 늦게 처리되는 최대치이기도 하다. */
    private static final double PUMP_MILLIS = 100;

    /**
     * 페이지가 알리는 일. 브라우저 스레드에서 불린다: 넣기만 하고 막지 않는다. {@code generation}은 그 일을 낸 탭을 열 때 받은 세대다
     * (듣는 쪽이 지난 탭의 늦은 일을 거른다).
     */
    public interface Listener {
        void onMessage(String streamId, long generation, SoopChatMessage message);

        /** 받는 후원 여섯 중 하나(POK-431). */
        void onDonation(String streamId, long generation, SoopChatDonation donation);

        /** 채팅 서버 연결이 닫혔다({@code handleChatClosed}). */
        void onClosed(String streamId, long generation);

        /** SDK 오류({@code handleError}) 또는 스크립트를 못 불러옴 · 연결 실패. 코드만 온다. */
        void onError(String streamId, long generation, String code);
    }

    /** 열린 탭 하나: 브라우저 컨텍스트와 열 때 받은 세대. */
    private record Tab(BrowserContext context, long generation) { }

    private final SoopChatProperties properties;
    private final Listener listener;
    private final BlockingQueue<Runnable> commands = new LinkedBlockingQueue<>();
    private final Thread thread;
    private volatile boolean running = true;

    // 아래는 브라우저 스레드만 만진다
    private Playwright playwright;
    private Browser browser;
    private final Map<String, Tab> tabs = new LinkedHashMap<>();

    public SoopChatBrowser(SoopChatProperties properties, Listener listener) {
        this.properties = properties;
        this.listener = listener;
        this.thread = Thread.ofPlatform().name("soop-chat-browser").daemon(true).start(this::loop);
    }

    /** 이 방송의 탭을 연다. 이미 있으면 닫고 새로 연다(다시 붙기). 그 탭이 내는 일에는 {@code generation}이 실린다. */
    public void open(String streamId, String token, long generation) {
        commands.add(() -> doOpen(streamId, token, generation));
    }

    /** 이 방송의 탭을 닫는다. 없으면 무시한다. */
    public void close(String streamId) {
        commands.add(() -> doClose(streamId));
    }

    /** 지금 열린 탭 수(시험 · 진단). 브라우저 스레드가 아닌 곳에서 읽으면 근사값이다. */
    public int openTabs() {
        return tabs.size();
    }

    private void loop() {
        while (running) {
            try {
                Runnable command = tabs.isEmpty() ? commands.poll(200, TimeUnit.MILLISECONDS) : commands.poll();
                if (command != null) {
                    command.run();
                } else if (!tabs.isEmpty()) {
                    pump();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // 한 번 던졌다고 스레드가 죽으면 모든 방송의 채팅이 멈춘다. 종류만 남긴다(메시지에 토큰이 섞일 수 있다)
                log.warn("soop.chat.browser_failed causeType={}", e.getClass().getSimpleName());
            }
        }
        shutdownBrowser();
    }

    /**
     * 펌프: 페이지가 부른 콜백이 이 호출 안에서 처리된다. 첫 탭에서 기다리지만 다른 탭의 콜백도 같이 온다(같은 연결이다).
     * 🔴 그 탭의 페이지가 닫혔으면 매번 던진다. 그대로 두면 쉬지 않고 경고만 찍고 그 방송은 영영 안 붙는다. 탭을 버리고 오류로 알려
     * 다시 붙기 줄에 태운다(렌더러 충돌은 던지지 않고 {@link #onPageCrashed}로 온다).
     */
    private void pump() {
        Map.Entry<String, Tab> first = tabs.entrySet().iterator().next();
        Tab tab = first.getValue();
        try {
            tab.context().pages().getFirst().waitForTimeout(PUMP_MILLIS);
        } catch (RuntimeException e) {
            if (tabs.get(first.getKey()) != tab) {
                return; // 펌프 중에 브라우저를 잃어 이미 알렸다
            }
            log.warn("soop.chat.page_lost stream={} causeType={}", first.getKey(), e.getClass().getSimpleName());
            loseTab(first.getKey(), tab);
        }
    }

    /**
     * 이 탭의 렌더러가 죽었다(메모리 · 충돌, 브라우저 스레드). 🔴 깨진 페이지에서도 {@code waitForTimeout}은 그냥 기다려서 펌프로는 모른다
     * (시험 실측). 사건으로 받아 그 탭만 버리고 다시 붙는다. 🔴 사건이 그 탭을 갈아 낀 뒤에 오면(옛 페이지의 늦은 사건) 무시한다. 방송
     * 번호만 보면 막 연 새 탭을 닫고, 옛 세대로 알린 오류는 세션이 버려 다시 붙기도 없다(계획 검증 경미).
     */
    private void onPageCrashed(String streamId, BrowserContext context) {
        Tab tab = tabs.get(streamId);
        if (tab == null || tab.context() != context) {
            return;
        }
        log.warn("soop.chat.page_crashed stream={}", streamId);
        loseTab(streamId, tab);
    }

    private void loseTab(String streamId, Tab tab) {
        doClose(streamId);
        listener.onError(streamId, tab.generation(), "PAGE_LOST");
    }

    private void doOpen(String streamId, String token, long generation) {
        doClose(streamId);
        try {
            openTab(streamId, token, generation);
        } catch (RuntimeException e) {
            // 브라우저를 못 띄웠거나 탭을 못 만들었다(막 끊긴 브라우저 등). 이대로 두면 세션은 남는데 탭이 없어 영영 안 붙는다
            log.warn("soop.chat.tab_open_failed stream={} causeType={}", streamId, e.getClass().getSimpleName());
            doClose(streamId);
            listener.onError(streamId, generation, "OPEN");
        }
    }

    private void openTab(String streamId, String token, long generation) {
        if (browser != null && !browser.isConnected()) {
            // 끊긴 브라우저를 계속 쓰면 newContext가 영영 던진다(메모리 상한에 죽은 경우 등). 버리고 새로 띄운다
            discardBrowser();
        }
        if (browser == null) {
            playwright = Playwright.create();
            BrowserType.LaunchOptions options = new BrowserType.LaunchOptions().setHeadless(true);
            try {
                browser = playwright.chromium().launch(options);
            } catch (RuntimeException e) {
                // 못 띄웠으면 Playwright(드라이버 프로세스)도 치운다. 안 치우면 다시 붙기마다 드라이버가 하나씩 쌓인다
                discardBrowser();
                throw e;
            }
            // 🔴 Chromium이 죽으면(메모리 상한 · 충돌) 모든 탭이 조용히 멈춘다. 탭마다 오류로 알려 다시 붙기 줄에 태우고 브라우저를 버린다
            browser.onDisconnected(gone -> onBrowserLost());
            log.info("soop.chat.browser_started");
        }
        BrowserContext context = browser.newContext();
        tabs.put(streamId, new Tab(context, generation));
        // 세대 · 컨텍스트는 열 때 캡처해 일을 낸 탭의 값을 싣는다. 지금은 옛 탭을 먼저 닫고 새 탭을 맵에 넣어 맵의 값과 같지만,
        // 열고 닫는 순서가 바뀌어도 새 탭의 세대가 옛 탭의 일에 실리지 않게 맵에서 찾지 않는다
        context.exposeBinding(BINDING, (source, args) -> {
            onPageEvent(streamId, context, generation, args);
            return null;
        });
        Page page = context.newPage();
        page.onCrash(crashed -> onPageCrashed(streamId, context));
        String origin = properties.pageOrigin().replaceAll("/+$", "");
        page.route(origin + "/soop-chat", route -> route.fulfill(new Route.FulfillOptions()
                .setStatus(200).setContentType("text/html; charset=utf-8").setBody(SoopChatPage.html(properties.sdkUrl()))));
        try {
            page.navigate(origin + "/soop-chat");
            page.evaluate("cfg => window.__pokeclipStart(cfg)",
                    Map.of("clientId", properties.clientId(), "clientSecret", properties.clientSecret(), "token", token));
        } catch (RuntimeException e) {
            // SDK 스크립트를 못 불러왔거나(SOOP 전역 없음) 시작이 던졌다. 오류로 넘겨 다시 붙기 줄에 태운다
            log.warn("soop.chat.page_failed stream={} causeType={}", streamId, e.getClass().getSimpleName());
            listener.onError(streamId, generation, "PAGE");
        }
    }

    /** 브라우저가 끊겼다(브라우저 스레드). 열려 있던 탭마다 오류로 알리고, 다음 열기가 새 브라우저를 띄우게 버린다. */
    private void onBrowserLost() {
        if (!running) {
            return;
        }
        Map<String, Tab> lost = new LinkedHashMap<>(tabs);
        tabs.clear();
        log.warn("soop.chat.browser_lost tabs={}", lost.size());
        lost.forEach((streamId, tab) -> listener.onError(streamId, tab.generation(), "BROWSER_LOST"));
    }

    /** 끊긴 브라우저와 Playwright를 조용히 치운다. 다음 열기가 새로 띄운다. */
    private void discardBrowser() {
        tabs.clear();
        try {
            if (playwright != null) {
                playwright.close();
            }
        } catch (RuntimeException e) {
            log.warn("soop.chat.browser_close_failed causeType={}", e.getClass().getSimpleName());
        }
        browser = null;
        playwright = null;
    }

    /** 시험용: Chromium 프로세스를 실제로 죽인다(CDP {@code Browser.crash}). 정상 닫기와 달리 사건 순서가 실물 충돌과 같다. */
    void crashBrowserForTest() {
        commands.add(() -> {
            if (browser == null) {
                throw new IllegalStateException("crash before launch");
            }
            browser.newBrowserCDPSession().send("Browser.crash");
        });
    }

    /**
     * 시험용: 이 방송 탭의 렌더러만 죽인다({@code chrome://crash}로 이동). 브라우저는 산다. 이동은 충돌로 실패하는 것이 정상이라 삼킨다.
     * 🔴 CDP {@code Page.crash}는 Linux CI에서 응답이 안 와 이 스레드를 30초 붙잡았다(첫 CI 실측).
     */
    void crashPageForTest(String streamId) {
        commands.add(() -> {
            try {
                tabs.get(streamId).context().pages().getFirst().navigate("chrome://crash");
            } catch (RuntimeException expected) {
                // 충돌로 이동이 실패한다
            }
        });
    }

    private void onPageEvent(String streamId, BrowserContext context, long generation, Object[] args) {
        Tab tab = tabs.get(streamId);
        if (args.length == 0 || tab == null || tab.context() != context) {
            return; // 닫았거나 갈아 낀 탭이다
        }
        String kind = String.valueOf(args[0]);
        switch (kind) {
            case "message" -> listener.onMessage(streamId, generation, new SoopChatMessage(str(args, 1), str(args, 2),
                    str(args, 3), str(args, 4), str(args, 5)));
            case "donation" -> listener.onDonation(streamId, generation, SoopChatDonation.of(str(args, 1), str(args, 2),
                    str(args, 3), str(args, 4), str(args, 5), str(args, 6), str(args, 7)));
            case "closed" -> listener.onClosed(streamId, generation);
            case "error" -> listener.onError(streamId, generation, str(args, 1));
            default -> { }
        }
    }

    private static String str(Object[] args, int index) {
        return index < args.length && args[index] != null ? String.valueOf(args[index]) : "";
    }

    private void doClose(String streamId) {
        Tab tab = tabs.remove(streamId);
        if (tab != null) {
            try {
                tab.context().close();
            } catch (RuntimeException e) {
                log.warn("soop.chat.tab_close_failed stream={} causeType={}", streamId, e.getClass().getSimpleName());
            }
        }
    }

    /** 끌 때: 탭(페이지)을 하나씩 닫고 브라우저 · Playwright를 닫는다. 순서를 지켜 SDK가 연결을 스스로 끊을 기회를 준다. */
    private void shutdownBrowser() {
        for (String streamId : java.util.List.copyOf(tabs.keySet())) {
            doClose(streamId);
        }
        try {
            if (browser != null) {
                browser.close();
            }
            if (playwright != null) {
                playwright.close();
            }
        } catch (RuntimeException e) {
            log.warn("soop.chat.browser_close_failed causeType={}", e.getClass().getSimpleName());
        }
    }

    /**
     * 브라우저 스레드에 멈추라고 알리고 <b>기다리지 않는다.</b> 스레드가 탭 → 브라우저 → Playwright 순으로 닫는다.
     *
     * <p>🔴 수집기 종료 예산(접수 join · 줄 비우기 · 세션 닫기 · flush · 후원 flush)이 이미 19초로 유예 20초에 1초만 남는다
     * ({@code ShutdownBudgetTest}). 여기서 몇 초라도 기다리면 그 합에 더해져 유예를 넘고 도커가 프로세스를 죽인다. 기다리지 않아도
     * JVM이 내릴 때 Playwright 드라이버가 입력이 끊겨 Chromium을 함께 내린다. SOOP 채팅은 저장 버퍼로 이미 넘어가 있다.
     */
    @Override
    public void close() {
        running = false;
    }

    /** 시험용: 브라우저 스레드가 끝날 때까지 기다린다. */
    boolean awaitStopped(java.time.Duration wait) throws InterruptedException {
        thread.join(wait.toMillis());
        return stopped();
    }

    /** 브라우저 스레드가 끝났는가(시험). */
    boolean stopped() {
        return !thread.isAlive();
    }
}
