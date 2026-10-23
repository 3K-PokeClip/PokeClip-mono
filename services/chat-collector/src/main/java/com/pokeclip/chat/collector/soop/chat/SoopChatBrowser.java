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

    /** 페이지가 알리는 일. 브라우저 스레드에서 불린다: 넣기만 하고 막지 않는다. */
    public interface Listener {
        void onMessage(String streamId, SoopChatMessage message);

        /** 채팅 서버 연결이 닫혔다({@code handleChatClosed}). */
        void onClosed(String streamId);

        /** SDK 오류({@code handleError}) 또는 스크립트를 못 불러옴 · 연결 실패. 코드만 온다. */
        void onError(String streamId, String code);
    }

    private final SoopChatProperties properties;
    private final Listener listener;
    private final BlockingQueue<Runnable> commands = new LinkedBlockingQueue<>();
    private final Thread thread;
    private volatile boolean running = true;

    // 아래는 브라우저 스레드만 만진다
    private Playwright playwright;
    private Browser browser;
    private final Map<String, BrowserContext> tabs = new LinkedHashMap<>();

    public SoopChatBrowser(SoopChatProperties properties, Listener listener) {
        this.properties = properties;
        this.listener = listener;
        this.thread = Thread.ofPlatform().name("soop-chat-browser").daemon(true).start(this::loop);
    }

    /** 이 방송의 탭을 연다. 이미 있으면 닫고 새로 연다(다시 붙기). */
    public void open(String streamId, String token) {
        commands.add(() -> doOpen(streamId, token));
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
        Map.Entry<String, BrowserContext> first = tabs.entrySet().iterator().next();
        try {
            first.getValue().pages().getFirst().waitForTimeout(PUMP_MILLIS);
        } catch (RuntimeException e) {
            if (!tabs.containsKey(first.getKey())) {
                return; // 펌프 중에 브라우저를 잃어 이미 알렸다
            }
            log.warn("soop.chat.page_lost stream={} causeType={}", first.getKey(), e.getClass().getSimpleName());
            loseTab(first.getKey());
        }
    }

    /**
     * 이 탭의 렌더러가 죽었다(메모리 · 충돌, 브라우저 스레드). 🔴 깨진 페이지에서도 {@code waitForTimeout}은 그냥 기다려서 펌프로는 모른다
     * (시험 실측). 사건으로 받아 그 탭만 버리고 다시 붙는다.
     */
    private void onPageCrashed(String streamId) {
        if (!tabs.containsKey(streamId)) {
            return;
        }
        log.warn("soop.chat.page_crashed stream={}", streamId);
        loseTab(streamId);
    }

    private void loseTab(String streamId) {
        doClose(streamId);
        listener.onError(streamId, "PAGE_LOST");
    }

    private void doOpen(String streamId, String token) {
        doClose(streamId);
        try {
            openTab(streamId, token);
        } catch (RuntimeException e) {
            // 브라우저를 못 띄웠거나 탭을 못 만들었다(막 끊긴 브라우저 등). 이대로 두면 세션은 남는데 탭이 없어 영영 안 붙는다
            log.warn("soop.chat.tab_open_failed stream={} causeType={}", streamId, e.getClass().getSimpleName());
            doClose(streamId);
            listener.onError(streamId, "OPEN");
        }
    }

    private void openTab(String streamId, String token) {
        if (browser != null && !browser.isConnected()) {
            // 끊긴 브라우저를 계속 쓰면 newContext가 영영 던진다(메모리 상한에 죽은 경우 등). 버리고 새로 띄운다
            discardBrowser();
        }
        if (browser == null) {
            playwright = Playwright.create();
            BrowserType.LaunchOptions options = new BrowserType.LaunchOptions().setHeadless(true);
            browser = playwright.chromium().launch(options);
            // 🔴 Chromium이 죽으면(메모리 상한 · 충돌) 모든 탭이 조용히 멈춘다. 탭마다 오류로 알려 다시 붙기 줄에 태우고 브라우저를 버린다
            browser.onDisconnected(gone -> onBrowserLost());
            log.info("soop.chat.browser_started");
        }
        BrowserContext context = browser.newContext();
        tabs.put(streamId, context);
        context.exposeBinding(BINDING, (source, args) -> {
            onPageEvent(streamId, args);
            return null;
        });
        Page page = context.newPage();
        page.onCrash(crashed -> onPageCrashed(streamId));
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
            listener.onError(streamId, "PAGE");
        }
    }

    /** 브라우저가 끊겼다(브라우저 스레드). 열려 있던 탭마다 오류로 알리고, 다음 열기가 새 브라우저를 띄우게 버린다. */
    private void onBrowserLost() {
        if (!running) {
            return;
        }
        java.util.List<String> lost = java.util.List.copyOf(tabs.keySet());
        tabs.clear();
        log.warn("soop.chat.browser_lost tabs={}", lost.size());
        for (String streamId : lost) {
            listener.onError(streamId, "BROWSER_LOST");
        }
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
        commands.add(() -> browser.newBrowserCDPSession().send("Browser.crash"));
    }

    /** 시험용: 이 방송 탭의 렌더러만 죽인다(CDP {@code Page.crash}). 브라우저는 산다. */
    void crashPageForTest(String streamId) {
        commands.add(() -> {
            BrowserContext context = tabs.get(streamId);
            Page page = context.pages().getFirst();
            context.newCDPSession(page).send("Page.crash");
        });
    }

    private void onPageEvent(String streamId, Object[] args) {
        if (args.length == 0 || !tabs.containsKey(streamId)) {
            return;
        }
        String kind = String.valueOf(args[0]);
        switch (kind) {
            case "message" -> listener.onMessage(streamId, new SoopChatMessage(str(args, 1), str(args, 2), str(args, 3),
                    str(args, 4), str(args, 5)));
            case "closed" -> listener.onClosed(streamId);
            case "error" -> listener.onError(streamId, str(args, 1));
            default -> { }
        }
    }

    private static String str(Object[] args, int index) {
        return index < args.length && args[index] != null ? String.valueOf(args[index]) : "";
    }

    private void doClose(String streamId) {
        BrowserContext context = tabs.remove(streamId);
        if (context != null) {
            try {
                context.close();
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
     * 브라우저 스레드를 멈추고 기다린다. 수집기 종료 유예(20초, docker-compose stop_grace_period) 안에 끝나야 해서 기다림 상한을
     * {@link #CLOSE_BUDGET}으로 둔다. 넘으면 기다리지 않고 넘어간다(그 뒤는 JVM이 내리며 정리한다).
     */
    @Override
    public void close() throws InterruptedException {
        running = false;
        thread.join(CLOSE_BUDGET.toMillis());
    }

    /** 끌 때 브라우저를 기다리는 상한. */
    static final java.time.Duration CLOSE_BUDGET = java.time.Duration.ofSeconds(5);

    /** 브라우저 스레드가 끝났는가(시험). */
    boolean stopped() {
        return !thread.isAlive();
    }
}
