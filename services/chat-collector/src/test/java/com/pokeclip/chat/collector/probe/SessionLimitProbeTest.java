package com.pokeclip.chat.collector.probe;

import com.pokeclip.chat.collector.chzzk.SessionUrl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 실계정으로 치지직을 직접 재는 프로브 셋. 다중 스트리머(POK-127)의 설계 근거가 된다.
 *
 * <p><b>단언이 하나도 없다. 일부러다.</b> 이것은 "이래야 한다"를 지키는 검사가 아니라
 * "실제로 어떤가"를 적는 계측기다. 단언을 넣으면 예상 밖의 값이 나온 순간 빨간불로
 * 끝나고, 정작 <b>무엇이 나왔는지</b>를 못 본다. 값은 전부 로그로 남기고 항상 통과한다.
 * 판정은 사람이 로그를 읽어서 한다.
 *
 * <p>로그는 {@code [P1]}·{@code [P2]}·{@code [P3]} 접두어로 시작한다. 결과는
 * {@code build/test-results/test/*.xml}의 {@code system-out}에 담기므로 거기서 grep한다.
 *
 * <p><b>기본은 SKIP이다.</b> {@code CHZZK_LIVE_PROBE=true}와 {@code CHZZK_ACCESS_TOKEN}이
 * 둘 다 있어야 돈다 — LiveProbeTest와 같은 게이트다. 토큰이 24시간짜리라 존재만으로
 * 게이트를 삼으면 <b>토큰을 가진 사람의 빌드가 매일 빨간불</b>이 되고, 그러면 사람이
 * 그 줄을 무시하기 시작해 진짜 회귀도 같이 묻힌다.
 *
 * <p>계정 둘이 필요한 것은 첫 검사뿐이라 {@code CHZZK_ACCESS_TOKEN_B} 게이트는
 * 그 메서드에만 붙였다. 계정이 하나뿐이면 나머지 둘은 그대로 돈다.
 *
 * <pre>
 * cd services &amp;&amp; CHZZK_LIVE_PROBE=true CHZZK_ACCESS_TOKEN=$TOKEN_A CHZZK_ACCESS_TOKEN_B=$TOKEN_B \
 *   ./gradlew :chat-collector:test --tests "*ProbeTest" --no-daemon
 * </pre>
 *
 * <p><b>순서가 있다.</b> 셋 다 계정 A의 연결 상한(3개)을 통째로 먹으므로, 앞 검사가
 * 자리를 놓아주기 전에 뒤가 시작되면 뒤는 "상한에 막힌 것"을 "핸드셰이크 실패"로
 * 잘못 적는다. {@code @AfterEach}가 전부 반납하고 {@link #SLOT_SETTLE}만큼 기다린다.
 *
 * <p><b>{@code java.net.http.HttpClient}를 직접 쓴다 — RestClient가 아니다.</b> 두 가지
 * 이유다. ① 운영의 {@code spring.http.clients.imperative.factory=jdk}가 감싸는 것이
 * 정확히 이 클래스라, 커넥션 풀 동작(=반납 셋이 겹치는가)이 같다. ② <b>응답의 HTTP
 * 버전을 읽을 수 있는 유일한 길이다</b> — RestClient의 응답에는 그 값을 꺼내는 공개
 * 통로가 없다. HTTP/2면 커넥션 하나에 다 몰리므로, 버전 없이는 소요 시간을 해석할 수 없다.
 */
@EnabledIfEnvironmentVariable(named = "CHZZK_LIVE_PROBE", matches = "true")
@EnabledIfEnvironmentVariable(named = "CHZZK_ACCESS_TOKEN", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SessionLimitProbeTest {

    private static final Logger log = LoggerFactory.getLogger(SessionLimitProbeTest.class);

    private static final String BASE = "https://openapi.chzzk.naver.com";

    /** 공식 문서의 연결 상한. 이 값이 스트리머당인지가 첫 검사의 질문이다. */
    private static final int LIMIT = 3;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    /**
     * 운영값(read 5초)보다 길다. 여기서 재는 것은 시한이 걸리는가가 아니라 왕복이
     * 얼마나 걸리는가라, 운영 시한을 그대로 쓰면 <b>측정 자체가 잘려</b> 배치인지
     * 겹치는지를 못 가른다. 시한 검증은 HttpTimeoutTest가 한다.
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration CONNECTED_TIMEOUT = Duration.ofSeconds(15);

    /**
     * 앞 검사가 놓은 자리를 서버가 회수할 때까지 기다리는 시간.
     * 우아하게 끊으면 반납이 약 1초, 급사하면 10초~4분 42초로 튄다(CLAUDE.md 실측).
     * 우리는 반납까지 보내고 끊으므로 앞쪽이고, 5초면 다섯 배 여유다.
     */
    private static final Duration SLOT_SETTLE = Duration.ofSeconds(5);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    /** 이번 검사가 연 것 전부. 예외로 빠져나가도 @AfterEach가 여기서 걷어 간다. */
    private final List<ProbeSession> opened = new ArrayList<>();

    @Test
    @Order(1)
    @EnabledIfEnvironmentVariable(named = "CHZZK_ACCESS_TOKEN_B", matches = ".+")
    void 연결_상한이_유저별인지_잰다() {
        String tokenA = System.getenv("CHZZK_ACCESS_TOKEN");
        String tokenB = System.getenv("CHZZK_ACCESS_TOKEN_B");

        log.info("[P1] 붙기 전 A 세션 목록 = {}", sessions(tokenA));
        log.info("[P1] 붙기 전 B 세션 목록 = {}", sessions(tokenB));

        int aConnected = 0;
        for (int i = 1; i <= LIMIT; i++) {
            ProbeSession a = openAndSubscribe(tokenA, "A#" + i);
            if (a.connectedArrived) {
                aConnected++;
            }
            log.info("[P1] A#{} connected={} authStatus={} 실패={}",
                    i, a.connectedArrived, a.authStatus, a.failure);
        }

        ProbeSession b = openAndSubscribe(tokenB, "B#1");
        log.info("[P1] B#1 connected={} authStatus={} authVersion={} 실패={}",
                b.connectedArrived, b.authStatus, b.authVersion, b.failure);
        log.info("[P1] B#1 발급 응답 봉투 = {}", b.authBody);

        log.info("[P1] 결과 — A로 {}개를 붙인 상태에서 B가 붙었나 = {}", aConnected, b.connectedArrived);
        log.info("[P1] 읽는 법 — B가 붙으면 상한이 유저별, 거부되면 앱 단위다. "
                + "앱 단위면 태스크 9의 동시 세션 수에 상한을 걸어야 한다");
        log.info("[P1] 🔴 A가 {}개를 못 채웠으면(지금 {}개) 상한을 건드린 적이 없어 위 줄은 아무 뜻이 없다",
                LIMIT, aConnected);
    }

    @Test
    @Order(2)
    void 상한을_넘겼을_때_오는_응답을_적는다() {
        String tokenA = System.getenv("CHZZK_ACCESS_TOKEN");

        int connected = 0;
        for (int i = 1; i <= LIMIT; i++) {
            if (openAndSubscribe(tokenA, "A#" + i).connectedArrived) {
                connected++;
            }
        }
        log.info("[P2] 상한까지 채운 세션 = {}/{}", connected, LIMIT);

        ProbeSession over = openAndSubscribe(tokenA, "A#" + (LIMIT + 1));
        log.info("[P2] {}번째 발급 — status={} version={} 본문={}",
                LIMIT + 1, over.authStatus, over.authVersion, over.authBody);
        log.info("[P2] {}번째 접속 — 예외={} connected도착={} sessionKey받음={}",
                LIMIT + 1, over.failure, over.connectedArrived, over.sessionKey != null);

        log.info("[P2] 읽는 법 — 상한 초과가 재시도로 풀리는 사유면 ReconnectPolicy.retriable에 넣는다");
        log.info("[P2] 🔴 429(Quota 제한 초과)가 여기 찍히면 요청 속도 제한이 실재하는 것이다. "
                + "치지직 문서에 코드만 있고 수치가 없어 이 줄이 유일한 근거가 된다");
        log.info("[P2] 🔴 위 connected가 전부 왔으면(={}/{}) 상한을 안 넘긴 것이라 이 검사는 아무것도 안 쟀다",
                connected, LIMIT);
    }

    @Test
    @Order(3)
    void 치지직을_상대로_반납_셋이_겹치는지_잰다() throws Exception {
        String tokenA = System.getenv("CHZZK_ACCESS_TOKEN");

        List<ProbeSession> live = new ArrayList<>();
        for (int i = 1; i <= LIMIT; i++) {
            ProbeSession s = openAndSubscribe(tokenA, "A#" + i);
            if (s.sessionKey != null) {
                live.add(s);
            }
        }
        log.info("[P3] 반납할 세션 = {}개 (상한이 {}이라 실물로 잴 수 있는 최대치다)", live.size(), LIMIT);
        if (live.isEmpty()) {
            log.info("[P3] 🔴 붙은 세션이 0이라 아무것도 못 쟀다. 앞 검사의 자리가 안 풀렸는지 본다");
            return;
        }

        ExecutorService pool = Executors.newFixedThreadPool(live.size());
        CountDownLatch ready = new CountDownLatch(live.size());
        CountDownLatch go = new CountDownLatch(1);
        AtomicLong goAt = new AtomicLong();
        List<Future<String>> results = new ArrayList<>();

        for (ProbeSession s : live) {
            results.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                long sentOffsetMillis = (System.nanoTime() - goAt.get()) / 1_000_000;
                ReleaseOutcome outcome = release(s);
                return s.label + " 출발오프셋=" + sentOffsetMillis + "ms " + outcome;
            }));
        }

        // 스레드가 전부 출발선에 선 뒤에 총을 쏜다. 안 그러면 스레드 기동 시간이
        // 소요 시간에 섞여, 배치인지 겹치는지를 가르는 자가 무뎌진다.
        ready.await(10, TimeUnit.SECONDS);
        goAt.set(System.nanoTime());
        go.countDown();

        for (Future<String> result : results) {
            log.info("[P3] {}", result.get(60, TimeUnit.SECONDS));
        }
        long totalMillis = (System.nanoTime() - goAt.get()) / 1_000_000;
        pool.shutdown();

        log.info("[P3] 반납 {}건 총 소요 = {}ms", live.size(), totalMillis);
        log.info("[P3] 읽는 법 — 반납 왕복 실측이 약 1초다. 총 ~1000ms면 겹치는 것(커넥션 하나에 "
                + "몰리거나 풀이 {}개를 함께 낸다), ~{}ms면 배치다(한 번에 하나씩 나간다)",
                live.size(), live.size() * 1000);
        log.info("[P3] 🔴 이 값이 태스크 11의 판정 근거다. ~{}ms(배치)면 세션별 클라이언트나 "
                + "반납 전용 풀이 필요하다", live.size() * 1000);
        log.info("[P3] 🔴 위 version이 HTTP_2면 커넥션 하나에 다 몰린 것이다 — 총 소요의 해석 근거다");
        log.info("[P3] 🔴 이 값은 종료만의 것이 아니다. 100명이 한꺼번에 끊기면 재연결 REST 왕복도 "
                + "한꺼번에 나간다. 배치면 스레드를 몇 개 만들든 무의미하고(실제 상한이 커넥션 쪽), "
                + "겹치면 치지직에 순간 부하를 몰아주므로 요청 속도 제한을 확인해야 한다");
    }

    /**
     * 이번 검사가 연 자리를 전부 놓는다. <b>반납까지 보내고 끊는다</b> — 소켓만 닫으면
     * 서버가 죽은 전송을 알아챌 때까지 자리가 남고(10초~4분 42초 실측), 다음 검사가
     * 그것을 "핸드셰이크 실패"로 잘못 적는다.
     */
    @AfterEach
    void 열어_둔_세션을_전부_놓는다() throws Exception {
        if (opened.isEmpty()) {
            return;
        }
        for (ProbeSession s : opened) {
            ReleaseOutcome outcome = release(s);
            log.info("[프로브] {} 정리 반납 = {}", s.label, outcome);
            closeSocket(s);
        }
        String token = opened.getFirst().token;
        opened.clear();
        Thread.sleep(SLOT_SETTLE.toMillis());
        log.info("[프로브] 정리 뒤 세션 목록 = {}", sessions(token));
        log.info("[프로브] 🔴 totalCount는 끊긴 세션까지 센다(90일 보관). 자리 점유는 "
                + "disconnectedDate가 빈 것만 세야 한다");
    }

    // --- 이하 프로브 배관. 어떤 실패도 던지지 않고 사유를 기록만 한다 ---

    /**
     * ①발급 → ②접속 → ③connected → ④구독. 실패해도 던지지 않고 사유를
     * {@link ProbeSession#failure}에 적는다 — 프로브는 빨간불로 끝나면 안 된다.
     */
    private ProbeSession openAndSubscribe(String token, String label) {
        ProbeSession session = open(token, label);
        if (session.sessionKey != null) {
            subscribe(session);
        }
        return session;
    }

    private ProbeSession open(String token, String label) {
        ProbeSession session = new ProbeSession(label, token);
        opened.add(session);

        String url;
        try {
            HttpResponse<String> response = http.send(
                    get(BASE + "/open/v1/sessions/auth", token), HttpResponse.BodyHandlers.ofString());
            session.authStatus = response.statusCode();
            session.authVersion = response.version().name();
            session.authBody = redact(response.body());
            if (response.statusCode() != 200) {
                session.failure = "발급 거부 status=" + response.statusCode();
                return session;
            }
            url = extractUrl(response.body());
        } catch (Exception e) {
            session.failure = describeChain("발급", e);
            return session;
        }

        CountDownLatch connected = new CountDownLatch(1);
        // 운영과 같은 변환기를 쓴다. 경로(/socket.io/) 부착과 getRawQuery는 여기 한 곳에만
        // 있어야 한다 — 프로브가 자기 사본을 들면 실물과 다른 URL을 재게 된다.
        URI socketUri = SessionUrl.toWebSocketUri(url);
        try {
            session.socket = http.newWebSocketBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .buildAsync(socketUri, new WebSocket.Listener() {

                        private final StringBuilder buffer = new StringBuilder();

                        @Override
                        public void onOpen(WebSocket ws) {
                            // 기본 구현이 하는 일이다. 오버라이드하면서 안 부르면
                            // 연결은 되고 메시지만 영영 안 온다 — 오류도 없다.
                            ws.request(1);
                        }

                        @Override
                        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                            buffer.append(data);
                            if (last) {
                                String raw = buffer.toString();
                                buffer.setLength(0);
                                if (raw.contains("connected")) {
                                    session.sessionKey = crudeSessionKey(raw);
                                    session.connectedArrived = true;
                                    connected.countDown();
                                }
                            }
                            ws.request(1);
                            return null;
                        }

                        @Override
                        public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
                            log.info("[프로브] {} 소켓 닫힘 code={} reason={}", label, code, reason);
                            return null;
                        }

                        @Override
                        public void onError(WebSocket ws, Throwable error) {
                            log.info("[프로브] {} 전송 오류 = {}", label, error.toString());
                        }
                    })
                    .join();
        } catch (RuntimeException e) {
            // 겉면(CompletionException)만 보면 "상한에 막힌 것"과 "경로·헤더가 틀린 것"이
            // 구분되지 않는다. 원인 체인을 통째로 남긴다.
            session.failure = describeChain("접속", e);
            return session;
        }

        try {
            if (!connected.await(CONNECTED_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                session.failure = "connected가 " + CONNECTED_TIMEOUT.toSeconds() + "초 안에 안 왔다";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            session.failure = "connected 대기 중 인터럽트";
        }
        return session;
    }

    /** sessionKey는 POST여도 쿼리 파라미터다. Body JSON은 미지원. */
    private void subscribe(ProbeSession session) {
        try {
            HttpResponse<String> response = http.send(
                    post(BASE + "/open/v1/sessions/events/subscribe/chat?sessionKey=" + session.sessionKey,
                            session.token),
                    HttpResponse.BodyHandlers.ofString());
            log.info("[프로브] {} 구독 status={} version={}",
                    session.label, response.statusCode(), response.version());
        } catch (Exception e) {
            log.info("[프로브] {} 구독 실패 = {}", session.label, e.getClass().getSimpleName());
        }
    }

    /**
     * 구독 반납 한 건. <b>{@code released}로 한 번만 나간다</b> — P3가 직접 반납한 것을
     * {@code @AfterEach}가 또 보내면 두 번째는 이미 없는 구독을 지우는 요청이라
     * 소요 시간이 첫 번째와 다르고, 그 값이 측정에 섞인다.
     */
    private ReleaseOutcome release(ProbeSession session) {
        if (session.sessionKey == null || !session.released.compareAndSet(false, true)) {
            return new ReleaseOutcome(-1, "생략", "-", "sessionKey가 없거나 이미 반납했다");
        }
        long startedAt = System.nanoTime();
        try {
            HttpResponse<String> response = http.send(
                    post(BASE + "/open/v1/sessions/events/unsubscribe/chat?sessionKey=" + session.sessionKey,
                            session.token),
                    HttpResponse.BodyHandlers.ofString());
            return new ReleaseOutcome(elapsedMillis(startedAt),
                    String.valueOf(response.statusCode()), response.version().name(), null);
        } catch (Exception e) {
            return new ReleaseOutcome(elapsedMillis(startedAt), "예외", "-", describeChain("반납", e));
        }
    }

    private void closeSocket(ProbeSession session) {
        WebSocket socket = session.socket;
        if (socket == null) {
            return;
        }
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "probe done")
                .orTimeout(5, TimeUnit.SECONDS)
                .exceptionally(e -> null)
                .join();
        socket.abort();
    }

    private String sessions(String token) {
        try {
            return redact(http.send(get(BASE + "/open/v1/sessions?size=10&page=0", token),
                    HttpResponse.BodyHandlers.ofString()).body());
        } catch (Exception e) {
            return "조회 실패: " + e.getClass().getSimpleName();
        }
    }

    private static HttpRequest get(String uri, String token) {
        return HttpRequest.newBuilder(URI.create(uri))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
    }

    private static HttpRequest post(String uri, String token) {
        return HttpRequest.newBuilder(URI.create(uri))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private static String describeChain(String what, Throwable error) {
        StringBuilder chain = new StringBuilder(what);
        for (Throwable t = error; t != null; t = t.getCause()) {
            chain.append(" <- ").append(t.getClass().getName()).append(": ").append(t.getMessage());
        }
        // 예외 메시지에 세션 URL이 통째로 실려 오는 경로가 있다. auth 파라미터가 자격증명이다.
        return redact(chain.toString());
    }

    /**
     * 공개 저장소다. 세션 URL의 auth 파라미터와 sessionKey는 자격증명이므로 로그에
     * 원문으로 남기지 않는다. 모양만 남으면 판정에 충분하다. LiveProbeTest와 같은 규칙이다.
     */
    private static String redact(String text) {
        if (text == null) {
            return "null";
        }
        return text
                .replaceAll("(auth=)[^&\"\\\\]+", "$1***")
                .replaceAll("(sessionKey\\\\?\":\\\\?\")[^\"\\\\]+", "$1***")
                .replaceAll("(sessionKey=)[^&\"\\\\]+", "$1***");
    }

    private static String extractUrl(String body) {
        int at = body.indexOf("\"url\"");
        int start = body.indexOf('"', body.indexOf(':', at)) + 1;
        return body.substring(start, body.indexOf('"', start));
    }

    /** 프로브 전용이다. 정식 파싱은 ChatEventDecoder가 한다. */
    private static String crudeSessionKey(String frame) {
        String marker = "sessionKey\\\":\\\"";
        int start = frame.indexOf(marker);
        if (start < 0) {
            return null;
        }
        start += marker.length();
        return frame.substring(start, frame.indexOf("\\", start));
    }

    private static final class ProbeSession {

        private final String label;
        private final String token;
        private final AtomicBoolean released = new AtomicBoolean();

        private volatile WebSocket socket;
        // 수신 콜백 스레드가 쓰고 검사 스레드가 읽는다.
        private volatile String sessionKey;
        private volatile boolean connectedArrived;
        private volatile String failure;
        private volatile int authStatus;
        private volatile String authVersion;
        private volatile String authBody;

        private ProbeSession(String label, String token) {
            this.label = label;
            this.token = token;
        }
    }

    private record ReleaseOutcome(long millis, String status, String version, String failure) {

        @Override
        public String toString() {
            return "소요=" + millis + "ms status=" + status + " version=" + version
                    + (failure == null ? "" : " 실패=" + failure);
        }
    }
}
