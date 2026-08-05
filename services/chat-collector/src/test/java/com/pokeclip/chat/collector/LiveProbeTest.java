package com.pokeclip.chat.collector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 가정 A를 닫는다 — java.net.http.WebSocket이 치지직 Engine.IO 3 핸드셰이크를
 * 그대로 통과하는가. 실측 gist는 socket.io-client 계열만 확인했다.
 *
 * <p><b>방송이 필요 없다.</b> 세션은 방송이 아니라 계정에 붙는다(361초 확인).
 * 그래서 세션 발급 → 연결 → sessionKey → 구독 → subscribed 까지 전부 돈다.
 * 안 오는 것은 42["CHAT"] 하나뿐이고 그건 10분 실측이 본다.
 *
 * <p>토큰이 없으면 SKIP이다. CI에서는 항상 SKIP된다.
 * <b>토큰은 24시간짜리라 개발 중 매일 재발급한다.</b>
 *
 * <p><b>연결 상한은 Access Token당 3개다.</b> 실패했을 때 "상한에 막힌 것"과
 * "핸드셰이크가 안 되는 것"을 구분해야 하므로, 붙기 전에 이미 열려 있는 세션
 * 목록을 먼저 찍고 시도 횟수를 파일에 남긴다. 끝나면 반드시 닫는다.
 */
@EnabledIfEnvironmentVariable(named = "CHZZK_ACCESS_TOKEN", matches = ".+")
class LiveProbeTest {

    private static final Logger log = LoggerFactory.getLogger(LiveProbeTest.class);

    private static final String BASE = "https://openapi.chzzk.naver.com";
    private static final Path ATTEMPTS = Path.of("_workspace", "01_probe_attempts.txt");

    @Test
    void 자바_내장_WebSocket으로_붙어_구독까지_통과한다() throws Exception {
        String token = System.getenv("CHZZK_ACCESS_TOKEN");
        RestClient rest = RestClient.create();

        log.info("=== 시도 #{} ===", nextAttempt());
        log.info("붙기 전 세션 목록(상한 3개) = {}", redact(sessions(rest, token)));

        // 응답 봉투 모양이 문서에 없다. 문자열로 통째로 받아 눈으로 본다.
        String body = rest.get()
                .uri(BASE + "/open/v1/sessions/auth")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(String.class);
        log.info("session.auth 응답 봉투 = {}", redact(body));

        // 치지직이 주는 것은 https이고 경로가 없다. java.net.http.WebSocket은
        // ws/wss만 받고, 소켓 엔드포인트는 /socket.io/ 아래에 있다.
        // socket.io-client가 내부에서 하던 변환이라 라이브러리를 안 쓰면 우리가 진다.
        String httpsUrl = extractUrl(body);
        URI given = URI.create(httpsUrl);
        String query = (given.getQuery() == null || given.getQuery().isBlank())
                ? "EIO=3&transport=websocket"
                : given.getQuery() + "&EIO=3&transport=websocket";
        URI wss = URI.create("wss://" + given.getAuthority() + "/socket.io/?" + query);
        log.info("접속 URI(쿼리 제외) = {}://{}{}", wss.getScheme(), wss.getAuthority(), wss.getPath());

        List<String> frames = new CopyOnWriteArrayList<>();
        AtomicReference<String> sessionKey = new AtomicReference<>();
        CountDownLatch connected = new CountDownLatch(1);
        CountDownLatch subscribed = new CountDownLatch(1);

        WebSocket socket = openOrExplain(wss, new WebSocket.Listener() {

                    private final StringBuilder buffer = new StringBuilder();

                    @Override
                    public void onOpen(WebSocket ws) {
                        // 기본 구현이 하는 일이다. 오버라이드하면 반드시 직접 불러야
                        // 한다 — 안 부르면 연결은 되고 메시지만 영영 안 온다.
                        ws.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        buffer.append(data);
                        if (last) {
                            String raw = buffer.toString();
                            buffer.setLength(0);
                            frames.add(raw);
                            if (raw.contains("connected")) {
                                sessionKey.set(crudeSessionKey(raw));
                                connected.countDown();
                            }
                            if (raw.contains("subscribed")) {
                                subscribed.countDown();
                            }
                        }
                        ws.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
                        log.info("서버가 닫았다. code={} reason={}", code, reason);
                        return null;
                    }

                    @Override
                    public void onError(WebSocket ws, Throwable error) {
                        log.warn("전송 오류 = {}", error.toString());
                    }
                });

        try {
            assertThat(connected.await(15, TimeUnit.SECONDS))
                    .as("connected가 안 왔다. 위의 '붙기 전 세션 목록'이 3개면 상한이고, "
                            + "아니면 핸드셰이크가 막힌 것이라 가정 A가 틀렸다")
                    .isTrue();
            assertThat(sessionKey.get()).isNotBlank();

            // sessionKey는 POST여도 쿼리 파라미터다. Body JSON은 미지원.
            rest.post()
                    .uri(BASE + "/open/v1/sessions/events/subscribe/chat?sessionKey=" + sessionKey.get())
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .toBodilessEntity();

            assertThat(subscribed.await(15, TimeUnit.SECONDS))
                    .as("구독 REST는 성공했는데 subscribed가 안 왔다")
                    .isTrue();

            // 이 로그가 가짜 서버의 사양이 된다.
            // 봉투 모양 · 프레임 순서 · pingInterval · pingTimeout을 여기서 베낀다.
            frames.forEach(f -> log.info("수신 프레임 = {}", redact(f)));
            assertThat(frames.getFirst()).startsWith("0");
        } finally {
            // 반드시 닫는다. 안 닫으면 상한 3개를 먹은 채로 남아
            // 다음 시도가 "핸드셰이크 실패"처럼 보인다.
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "probe done")
                    .orTimeout(5, TimeUnit.SECONDS)
                    .exceptionally(e -> null)
                    .join();
            socket.abort();
            Thread.sleep(1_000);
            log.info("닫은 뒤 세션 목록 = {}", redact(sessions(rest, token)));
        }
    }

    /**
     * 핸드셰이크가 깨지면 CompletionException 겉면만 보고는 아무것도 알 수 없다.
     * 원인 체인을 통째로 찍어야 "상한에 막힌 것"과 "경로·헤더가 틀린 것"이 갈린다.
     */
    private static WebSocket openOrExplain(URI uri, WebSocket.Listener listener) {
        try {
            return HttpClient.newHttpClient().newWebSocketBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .buildAsync(uri, listener)
                    .join();
        } catch (RuntimeException e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                log.error("핸드셰이크 실패 원인 = {}: {}", t.getClass().getName(), t.getMessage());
            }
            throw e;
        }
    }

    private static String sessions(RestClient rest, String token) {
        try {
            return rest.get()
                    .uri(BASE + "/open/v1/sessions?size=10&page=0")
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .body(String.class);
        } catch (RuntimeException e) {
            return "조회 실패: " + e.getClass().getSimpleName();
        }
    }

    private static int nextAttempt() {
        try {
            Files.createDirectories(ATTEMPTS.getParent());
            int n = Files.exists(ATTEMPTS)
                    ? Integer.parseInt(Files.readString(ATTEMPTS).trim()) + 1
                    : 1;
            Files.writeString(ATTEMPTS, String.valueOf(n));
            return n;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 공개 저장소다. 세션 URL의 auth 파라미터와 sessionKey는 자격증명이므로
     * 로그에 원문으로 남기지 않는다. 모양만 남으면 가짜 서버를 짜는 데 충분하다.
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
            return "";
        }
        start += marker.length();
        return frame.substring(start, frame.indexOf("\\", start));
    }
}
