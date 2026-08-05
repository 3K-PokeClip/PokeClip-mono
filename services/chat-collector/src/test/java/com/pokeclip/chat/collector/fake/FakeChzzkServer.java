package com.pokeclip.chat.collector.fake;

import com.pokeclip.chat.collector.engineio.EngineIoFrame;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 치지직 세션 API를 실측대로 흉내 낸다. REST 둘 + Engine.IO 3 소켓.
 *
 * <p><b>ping이 생존 시한 안에 안 오면 오류 프레임 없이 끊는다.</b> 이 동작이
 * 2026-08-01 사고의 본체이고, 이게 없으면 함정 3 테스트가 간격만 재는 것이 된다.
 */
@TestConfiguration
@EnableWebSocket
public class FakeChzzkServer implements WebSocketConfigurer {

    @Bean
    public FakeChzzkBehavior fakeChzzkBehavior() {
        return new FakeChzzkBehavior();
    }

    @Bean
    public FakeSocketHandler fakeSocketHandler(FakeChzzkBehavior behavior) {
        return new FakeSocketHandler(behavior);
    }

    // FakeSessionRest에 @Bean을 두지 않는다. 중첩 @RestController는 @Import된
    // 설정 클래스의 멤버 클래스로 ConfigurationClassParser가 이미 등록한다.
    // 여기서 또 등록하면 "Ambiguous mapping"으로 컨텍스트가 부팅에 실패하고,
    // @Import(FakeChzzkServer)를 쓰는 통합 테스트가 한 줄도 못 돈다.

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // 실서버와 같은 경로다. /fake/... 로 두면 SessionUrl이 "경로가 이미 있는"
        // 분기를 타서, 실서버가 실제로 타는 "경로 없음 → /socket.io/ 부착" 분기를
        // 통합 경로에서 한 번도 안 밟는다. 프로브 시도 #1을 깨뜨린 자리다.
        registry.addHandler(fakeSocketHandler(fakeChzzkBehavior()), "/socket.io/")
                .setAllowedOrigins("*");
    }

    @RestController
    public static class FakeSessionRest {

        private final FakeChzzkBehavior behavior;

        FakeSessionRest(FakeChzzkBehavior behavior) { this.behavior = behavior; }

        /**
         * 실측 url은 {@code https://ssio24.nchat.naver.com:443?auth=***}로
         * <b>경로가 없다.</b> 같은 모양을 돌려줘야 클라이언트가 실서버와 같은
         * 길을 탄다. 요청에서 호스트·포트를 읽으므로 자리표시자가 필요 없다.
         */
        @GetMapping("/open/v1/sessions/auth")
        public ResponseEntity<String> auth(HttpServletRequest request) {
            behavior.countAuthCall();
            if (behavior.authStatus != 200) {
                return ResponseEntity.status(behavior.authStatus)
                        .body("{\"code\":401,\"message\":\"Unauthorized\",\"content\":null}");
            }
            String url = request.getScheme() + "://" + request.getServerName()
                    + ":" + request.getServerPort() + "?auth=FAKE-AUTH";
            // 봉투는 실측 그대로다(01_probe.md) — code·message가 한 겹 더 있다.
            return ResponseEntity.ok(
                    "{\"code\":200,\"message\":null,\"content\":{\"url\":\"" + url + "\"}}");
        }

        /**
         * sessionKey는 POST여도 쿼리 파라미터다. Body JSON은 미지원.
         *
         * <p><b>구독이 성공하면 소켓으로 subscribed를 쏜다.</b> 실측에 오는
         * 프레임이고(01_probe.md), 안 쏘면 ChatSession이 ⑤에서 영영 기다린다.
         */
        @PostMapping("/open/v1/sessions/events/subscribe/chat")
        public ResponseEntity<String> subscribe(@RequestParam String sessionKey) {
            if (behavior.sendSubscribed) {
                behavior.emitSystem("{\"type\":\"subscribed\",\"data\":"
                        + "{\"eventType\":\"CHAT\",\"channelId\":\"FAKE-CHANNEL\"}}");
            }
            return ResponseEntity.ok("{\"code\":200,\"message\":null,\"content\":null}");
        }
    }

    /** 구독 반납. 안 오면 세션이 우리 손으로 안 닫히고 연결 상한을 먹는다. */
    @RestController
    public static class FakeUnsubscribeRest {

        private final FakeChzzkBehavior behavior;

        FakeUnsubscribeRest(FakeChzzkBehavior behavior) { this.behavior = behavior; }

        @PostMapping("/open/v1/sessions/events/unsubscribe/chat")
        public ResponseEntity<String> unsubscribe(@RequestParam String sessionKey) {
            // 실패해도 도착한 사실은 센다. 안 세면 "왔는데 터졌다"와 "아예 안 왔다"가
            // 같아 보인다.
            behavior.countUnsubscribeCall();
            if (behavior.unsubscribeStatus != 200) {
                return ResponseEntity.status(behavior.unsubscribeStatus)
                        .body("{\"code\":500,\"message\":\"Internal Error\",\"content\":null}");
            }
            return ResponseEntity.ok("{\"code\":200,\"message\":null,\"content\":null}");
        }
    }

    public static class FakeSocketHandler extends TextWebSocketHandler {

        private final FakeChzzkBehavior behavior;
        private final ScheduledExecutorService reaper =
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "fake-chzzk-reaper");
                    t.setDaemon(true);
                    return t;
                });
        private final AtomicLong lastPingAt = new AtomicLong();

        FakeSocketHandler(FakeChzzkBehavior behavior) { this.behavior = behavior; }

        /**
         * 접속마다 리퍼를 새로 걸고 <b>끊길 때 취소한다.</b> 안 그러면 앞선 접속의
         * 리퍼가 계속 돌면서 뒤 테스트의 스위치(`disconnectWhenPingMissing`)를 읽고
         * 판단한다 — 테스트 클래스들이 스프링 컨텍스트 하나를 공유하므로 이 서버도
         * 하나뿐이고, 그래서 앞뒤 테스트가 서로의 상태를 밟았다.
         */
        private final java.util.Map<String, java.util.concurrent.ScheduledFuture<?>> reapers =
                new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public void afterConnectionEstablished(WebSocketSession session) throws Exception {
            behavior.rememberQuery(session.getUri() == null ? "" : session.getUri().getQuery());
            behavior.remember(session);
            lastPingAt.set(System.nanoTime());
            behavior.startPingClock();

            // 전송은 전부 behavior.send()를 지난다. 스프링 세션은 동시 전송에
            // 안전하지 않아서, 락을 나눠 쥐면 채팅 홍수 + 하트비트가 겹치는
            // 순간(=T1·T2가 겨냥한 바로 그 상황)에 서버가 스스로 무너진다:
            // IllegalStateException: The remote endpoint was in state [TEXT_PARTIAL_WRITING]
            // upgrades는 실측이 ["websocket"]이다. 빈 배열이 아니다(01_probe.md).
            behavior.send("0{\"sid\":\"fake\",\"upgrades\":[\"websocket\"],"
                    + "\"pingInterval\":" + behavior.pingIntervalMillis + ","
                    + "\"pingTimeout\":" + behavior.pingTimeoutMillis + "}");
            behavior.send("40");

            if (behavior.sendConnected) {
                behavior.emitSystem("{\"type\":\"connected\","
                        + "\"data\":{\"sessionKey\":\"FAKE-KEY\"}}");
            }
            scheduleReaper(session);
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
            String raw = message.getPayload();
            behavior.record(raw);

            if (EngineIoFrame.parse(raw).type() == EngineIoFrame.Type.PING) {
                lastPingAt.set(System.nanoTime());
                behavior.markPingReceived();     // 간격의 지상 진실
                if (behavior.answerPong) {
                    behavior.send("3");          // 같은 락을 탄다
                }
            }
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            java.util.concurrent.ScheduledFuture<?> task = reapers.remove(session.getId());
            if (task != null) {
                task.cancel(false);
            }
            behavior.forget(session);
        }

        /** 오류 프레임 없이 조용히 끊는다. 실측에서 오류 로그가 0줄이었던 이유다. */
        private void scheduleReaper(WebSocketSession session) {
            long deadlineMillis = behavior.pingIntervalMillis + behavior.pingTimeoutMillis;
            reapers.put(session.getId(), reaper.scheduleAtFixedRate(() -> {
                if (!behavior.disconnectWhenPingMissing || !session.isOpen()) {
                    return;
                }
                long idleMillis = (System.nanoTime() - lastPingAt.get()) / 1_000_000;
                if (idleMillis > deadlineMillis) {
                    try {
                        // NO_STATUS_CODE(1005)를 쓰면 안 된다. RFC 6455가 전송을
                        // 금지한 예약 코드라 톰캣이 깨진 프레임을 내보내고,
                        // 클라이언트는 onClose가 아니라 ProtocolException을 받는다.
                        // 실측의 "오류 로그 0줄"과 어긋나고, 깨끗한 close를 받는
                        // 길을 어느 테스트도 안 밟게 된다.
                        session.close(CloseStatus.NORMAL);
                    } catch (Exception ignored) {
                        // 이미 닫힌 세션. 조용히 넘어가는 것이 실측 동작이다.
                    }
                }
            }, deadlineMillis / 4, deadlineMillis / 4, TimeUnit.MILLISECONDS));
        }
    }
}
