package com.pokeclip.chat.collector;

import com.pokeclip.chat.collector.chzzk.ChatEventDecoder;
import com.pokeclip.chat.collector.chzzk.ChatMessage;
import com.pokeclip.chat.collector.chzzk.ChatSession;
import com.pokeclip.chat.collector.chzzk.ChzzkSessionClient;
import com.pokeclip.chat.collector.chzzk.SessionEstablishException;
import com.pokeclip.chat.collector.chzzk.SystemEvent;
import com.pokeclip.chat.collector.engineio.EngineIoFrame;
import com.pokeclip.chat.collector.engineio.PingFailure;
import com.pokeclip.chat.collector.observe.CollectionMetrics;
import com.pokeclip.chat.collector.observe.Heartbeat;
import com.pokeclip.chat.collector.observe.HeartbeatListener;
import com.pokeclip.chat.collector.observe.SummaryLogger;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 부팅 시 수집을 시작하고 종료 시 역순으로 닫는다.
 *
 * <p><b>재시도하지 않는다.</b> 실패는 사유를 로그와 health에 남기고 멈춘다 —
 * 재연결은 POK-86이고, 여기서 넣으면 두 카드가 같은 코드를 두 번 짠다.
 */
@Component
public class CollectorRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CollectorRunner.class);

    /** 실측 30초. 요약 주기는 하트비트와 무관하다 — 얹으면 8/1이 재현된다. */
    private static final Duration SUMMARY_PERIOD = Duration.ofSeconds(30);

    private final ChzzkProperties properties;
    private final CollectionStatus status;

    /**
     * <b>{@code RestClient.create()}로 만들지 않는다.</b> 그러면 자동 설정을 우회해
     * {@code spring.http.clients.*}의 타임아웃이 어디에도 안 걸린다. 그때
     * {@code createSession()}은 {@code establishTimeout}으로 못 끊는 동기 호출이라
     * <b>부팅이 안 끝나고</b>, 같은 클라이언트가 종료 시 구독 반납에도 쓰여
     * <b>종료도 안 끝난다.</b> 치지직이 연결만 받고 답을 안 주면 그대로 멈춘다.
     */
    private final RestClient restClient;

    private final CollectionMetrics metrics = new CollectionMetrics();

    /**
     * 한 세션이 소유한 것 전부. <b>필드로 흩어 놓지 않는다.</b>
     *
     * <p>가드만 세션 단위로 갈고 나머지를 필드로 두면, 뒷정리가 반납 왕복(실서버
     * 약 1초)에 갇힌 사이에 다음 세션이 시작될 때 <b>깨어난 앞 뒷정리가 새 세션의
     * 필드를 지운다.</b> 그러면 새 세션이 끊겨도 구독 반납도 소켓 닫기도 안 나가고,
     * 상한이 3개라 반납이 새면 금방 못 붙게 된다.
     *
     * <p><b>필드마다 세대를 검사하는 것으로는 안 된다.</b> 검사와 검사 사이에 세대가
     * 바뀌면 일부만 지워진 상태가 남는다 — 창이 좁아지지 사라지지 않는다.
     * 덩어리 하나를 참조 하나로 들면 <b>남의 세션을 만질 길 자체가 없다.</b>
     *
     * <p>{@code heartbeat}·{@code summaryLogger}·{@code collectingSince}가 홀더인
     * 이유는 소켓이 있어야 만들 수 있어 {@code open()} 뒤에야 채워지기 때문이다.
     * <b>그때 레코드를 새로 만들어 갈아 끼우면 안 된다</b> — 덩어리의 정체가 바뀌어
     * 뒷정리의 {@code compareAndSet(내것, null)}이 실패하고, 자리가 소모된 가드를
     * 든 채 영영 안 비워져 다음 세션이 영영 못 선다. 홀더는 이 세션의 것이므로
     * 누가 언제 읽어도 남의 세션에 닿지 않는다.
     *
     * @param no            이 프로세스에서 몇 번째 세션인가. 재연결이 붙으면 판정·반납
     *                      줄이 여러 번 나가는데, 번호가 없으면 운영자가 N번째와
     *                      N+1번째를 못 가른다. <b>자리를 잡은 뒤에 채운다</b> —
     *                      거절된 {@code start()}가 번호를 먹으면 로그에 구멍이 생기고,
     *                      사람은 그 구멍을 "판정 줄을 잃어버렸다"로 읽는다
     * @param cleanedUp     뒷정리 가드. 종료 경로가 둘(전송 절단·프로세스 종료)이라
     *                      같은 세션에서 두 번 도는 길이 있다
     * @param verdictLogged 판정 가드. <b>지금은 발화하지 않는다</b> — {@code logVerdictOnce}를
     *                      부르는 곳이 {@code cleanedUp} CAS를 통과한 뒤 한 군데뿐이라
     *                      진입 시 true일 수 없다(떼어도 전체가 초록이다). 판정 시점이
     *                      {@code cleanUpOnce} 밖으로 나가면 그때 살아난다
     */
    private record SessionScope(
            ChatSession chat,
            AtomicLong no,
            AtomicBoolean cleanedUp,
            AtomicBoolean verdictLogged,
            AtomicReference<Heartbeat> heartbeat,
            AtomicReference<SummaryLogger> summaryLogger,
            AtomicReference<Instant> collectingSince) {

        static SessionScope opening(ChatSession chat) {
            return new SessionScope(chat, new AtomicLong(), new AtomicBoolean(), new AtomicBoolean(),
                    new AtomicReference<>(), new AtomicReference<>(), new AtomicReference<>());
        }
    }

    /** 지금 자리를 잡고 있는 세션. 비어 있을 때만 새 세션이 들어올 수 있다. */
    private final AtomicReference<SessionScope> activeSession = new AtomicReference<>();

    /**
     * 자리를 잡은 세션에만 번호를 준다. <b>프로세스 안에서 유일하다</b> —
     * 그래서 static이다.
     *
     * <p>러너마다 1부터 세면 번호가 "이 러너의 몇 번째"가 되고, 러너가 둘 이상인
     * 순간(스트리머별 수집 · 검사) <b>서로 다른 세션이 같은 번호로 로그에 나간다.</b>
     * 그러면 {@code session=N}으로 줄을 고르는 사람도 도구도 남의 세션을 자기 것으로
     * 집는다 — 실제로 검사에서 앞 테스트의 낙오 반납 줄이 뒤 테스트의 줄 수 단언에
     * 섞여 들어갔다(실측: 기대 1, 실제 2).
     */
    private static final AtomicLong SESSION_SEQ = new AtomicLong();

    /**
     * 이 러너가 마지막으로 자리를 준 세션의 번호. 자리가 비워진 뒤에도 남는다.
     *
     * <p><b>검사가 자기 러너의 줄만 고르는 열쇠다.</b> {@code LogCaptor}는 JVM 전역
     * 루트 로거에 붙어 있어 남의 러너가 늦게 찍은 줄까지 담는다. 번호를 모르면
     * 줄 수 단언이 그 낙오까지 세고, 판정이 0줄이 되는 진짜 결함을 낙오 한 건이
     * 메워 준다.
     */
    private final AtomicLong lastSessionNo = new AtomicLong();

    long lastSessionNo() {
        return lastSessionNo.get();
    }

    public CollectorRunner(ChzzkProperties properties, CollectionStatus status,
                           RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.status = status;
        // 빌더는 프로토타입 빈이다. 한 번만 build()해서 들고 있는다.
        this.restClient = restClientBuilder.build();
    }

    @Override
    public void run(ApplicationArguments args) {
        start();
    }

    /**
     * 지금까지의 관측값. 유출 검사가 "바늘이 실제로 코드 안을 지나갔나"를
     * 확인하는 데 쓴다 — 그 대조가 없으면 아무것도 안 흘렀을 때
     * "안 샜다"가 자동으로 참이 된다.
     */
    public CollectionMetrics metrics() {
        return metrics;
    }

    public void start() {
        if (!properties.enabled()) {
            // 붙지 않는다. 로그 한 줄은 남긴다 — "왜 채팅이 안 들어오지"의
            // 첫 번째 답이 대개 이것이다.
            log.info("chat.collector.disabled");
            status.disabled();
            return;
        }

        ChatSession opening = new ChatSession(new ChzzkSessionClient(
                restClient, properties.baseUrl(), properties.accessToken()));
        SessionScope scope = SessionScope.opening(opening);
        if (!activeSession.compareAndSet(null, scope)) {
            // 앞 세션이 아직 자리를 들고 있다. 덮어쓰면 그 세션의 소켓도 스케줄러도
            // 아무도 안 닫아 ping이 죽은 소켓에 계속 나가고, 구독은 서버에 남아
            // 상한 3개를 먹는다. 조용히 돌아가지 않는다 — 재연결 루프가 왜
            // 안 붙었는지 알 길이 없으면 그것이 곧 조용한 실패다.
            log.warn("chat.session.start_skipped reason=ALREADY_ACTIVE");
            return;
        }
        // 자리를 잡은 뒤라 번호를 두 세션이 나눠 갖는 길이 없고, 콜백을 아직
        // 걸지 않아 이 값을 우리보다 먼저 읽는 스레드도 없다.
        long no = SESSION_SEQ.incrementAndGet();
        scope.no().set(no);
        lastSessionNo.set(no);
        opening.onFrame(frame -> handleFrame(scope, frame));
        // 필드가 아니라 이 덩어리를 캡처해서 넘긴다. 콜백이 나중에 필드를 다시
        // 읽으면, 그때 자리에 있는 것은 이미 다음 세션일 수 있다.
        opening.onClosed(() -> handleClosed(scope));
        status.establishing();

        try {
            ChatSession.Established established = opening.open(properties.establishTimeout());

            // 수립을 마치는 사이에 WS 스레드가 절단을 처리했으면 정리가 이미
            // 끝났다. 그 위에 스케줄러를 올리면 닫힌 소켓에 대고 ping을 쏘고,
            // 상태까지 COLLECTING으로 되돌리면 health는 UP인 채로 수집만 죽는다.
            // 정리 가드는 이미 소모돼 stop()도 아무것도 못 한다.
            if (scope.cleanedUp().get()) {
                return;
            }

            Heartbeat beat = Heartbeat.start(established.socket(), established.handshake(),
                    heartbeatListener());
            // 값이 아니라 읽는 길을 넘긴다 — 삼킨 예외 수는 계속 늘어난다.
            // 세션은 이 덩어리의 것이라 바뀌지 않으므로 그쪽을 직접 읽는다.
            SummaryLogger logger = SummaryLogger.start(metrics, beat, SUMMARY_PERIOD,
                    opening::sinkFailureCount);
            // 상태 전이보다 먼저 보인다. 이 뒤에 절단이 오면 cleanUpOnce가 여기서
            // 읽어 둘 다 닫는다 — 반대 순서면 정리가 실행기를 못 보고 지나친다.
            scope.heartbeat().set(beat);
            scope.summaryLogger().set(logger);
            scope.collectingSince().set(Instant.now());

            if (!status.collectingIfEstablishing()) {
                // 검사와 전이 사이에 절단이 들어왔다. 우리가 올린 것은 우리가 내린다 —
                // cleanUpOnce가 이미 지나갔다면 아무도 안 내려 준다.
                beat.close();
                logger.close();
                scope.heartbeat().set(null);
                scope.summaryLogger().set(null);
                return;
            }
            log.info("chat.session.collecting pingIntervalMs={} sendPeriodMs={}",
                    established.handshake().pingInterval().toMillis(),
                    established.handshake().sendPeriod().toMillis());
        } catch (SessionEstablishException e) {
            // URL·응답 본문·토큰을 안 찍는다. detail에 들어가는 것은 상태 코드·예외
            // 단순 이름·단계 이름뿐이라(던지는 자리 7곳 전수 확인) 토큰이 실릴 길이 없다.
            //
            // <b>detail이 없으면 열거값 아래 한 단계가 통째로 사라진다.</b>
            // SESSION_AUTH_FAILED는 500인지 타임아웃인지를, CONNECT_REFUSED는
            // DNS인지 TLS인지 연결 거부인지를 말하지 못한다. 재연결이 반복 실패할 때
            // 사람은 같은 줄만 보고 엉뚱한 곳을 판다.
            log.warn("chat.session.stopped stage={} reason={} detail={}",
                    e.stage(), e.reason(), e.getMessage());
            status.stopped(e.reason());
            // 사유는 status에서 읽는다. 절단이 먼저였다면 그것이 원인이고
            // 여기서 잡은 시한 만료는 그 결과다 — 결과가 원인을 덮으면 추적이 끊긴다.
            cleanUpOnce(scope, status.reason());
        }
    }

    /**
     * 하트비트가 알리는 두 사건을 운영 로그로 옮긴다. <b>태스크 5·6이 운영에
     * 붙는 유일한 지점이다.</b>
     *
     * <p><b>익명 클래스로 {@code start()} 안에 묻어 두지 않는다.</b> 거기 있는 동안은
     * 두 메서드를 빈 몸통으로 바꿔도 전 테스트가 초록이었다(CP2 실측) — 좀비 판정도
     * 송신 실패 분류도 통째로 사라질 수 있고 아무도 몰랐다.
     *
     * <p>좀비 쪽은 가짜 서버가 pong을 끊으면 러너를 통째로 지나 재현되지만,
     * <b>송신 실패 쪽은 루프백으로 못 만든다</b> — 전송이 끊기면 우리 뒷정리가
     * 하트비트를 먼저 닫으므로 "쓰기는 실패하는데 읽기 통지는 아직 안 왔다"는
     * 실제 망 사고의 순서가 같은 기계 안에서는 만들어지지 않는다. 이름을 준 것은
     * 그 한 갈래를 검사가 지나갈 수 있게 하기 위해서다.
     */
    HeartbeatListener heartbeatListener() {
        return new HeartbeatListener() {
            @Override
            public void onSendFailed(PingFailure.Cause cause) {
                log.warn("chat.session.ping_send_failed cause={}", cause);
            }

            @Override
            public void onPongTimeout(Duration gap) {
                log.warn("chat.session.pong_timeout gapMs={}", gap.toMillis());
            }
        };
    }

    /**
     * 서버가 조용히 끊었을 때. 상태를 COLLECTING으로 두면 수집이 죽었는데
     * health가 UP인 상태가 되는데, 그게 2026-08-01의 마지막 장면이다.
     *
     * <p><b>뒷정리를 종료 훅까지 미루지 않는다.</b> 프로세스는 계속 살아 있으므로,
     * 미루면 죽은 세션에 ping을 계속 쏘고({@code ping_send_failed}가 쌓인다)
     * 30초 요약도 계속 찍고 실행기·세션 자원이 프로세스가 죽을 때까지 남는다.
     *
     * <p><b>사유를 안 덮는 것과 뒷정리를 안 하는 것은 다른 일이다.</b> 한 번의 절단에
     * 신호가 둘 들어오는 길이 있고(좀비 판정 · 전송 절단 콜백), 먼저 온 쪽이 이미
     * STOPPED를 찍었다고 여기서 통째로 돌아가면 <b>이 세션이 자리를 문 채 남는다.</b>
     * 그러면 구독 반납도 판정도 안 나가고 다음 세션이 영영 못 선다 —
     * 재연결 루프가 붙어도 {@code start_skipped}만 반복한다.
     */
    private void handleClosed(SessionScope scope) {
        if (status.state() == CollectionStatus.State.STOPPED) {
            // 사유는 먼저 온 쪽의 것을 그대로 쓴다. 여기서 TRANSPORT_CLOSED를 얹으면
            // 진짜 원인이 그 결과로 덮인다. 뒷정리는 가드가 멱등이라 두 번 들어와도 안전하다.
            cleanUpOnce(scope, status.reason());
            return;
        }
        log.warn("chat.session.closed reason={}", StopReason.TRANSPORT_CLOSED);
        status.stopped(StopReason.TRANSPORT_CLOSED);
        cleanUpOnce(scope, StopReason.TRANSPORT_CLOSED);
    }

    /**
     * 수집이 끝났을 때 딱 한 번. 두 줄이 나가면 어느 것이 진짜 끝인지 흐려진다.
     *
     * <p><b>지금 두 줄을 막고 있는 것은 이 가드가 아니라 {@code cleanedUp}이다.</b>
     * 부르는 곳이 그 CAS 뒤 한 군데뿐이라 여기 들어올 때 {@code verdictLogged}는
     * 항상 false다 — 이 CAS를 떼도 전체가 초록이다(CP1b 실측).
     * <b>그래도 지우지 않는다:</b> 판정 시점을 {@code cleanUpOnce} 밖으로 옮기는
     * 순간(재연결 루프의 비재시도 분기 · {@code stop()}) 부르는 곳이 둘이 되고,
     * 그때 이 가드가 유일한 방어가 된다. 없는 결함을 찾지 않도록 여기 적어 둔다.
     */
    private void logVerdictOnce(SessionScope scope, StopReason reason) {
        if (!scope.verdictLogged().compareAndSet(false, true)) {
            return;
        }
        // 세션의 값을 여기서 하나도 안 읽는다. 세션이 끝날 때 metrics가 전부
        // 걷어 두므로, 여기서 이 세션 것을 다시 읽으면 그 항만 앞 세션 값이 지워진다.
        SummaryLogger.logFinalVerdict(scope.no().get(), metrics.verdict(), reason);
    }

    /**
     * <b>개별 메시지를 로그로 남기지 않는다.</b> 세기만 한다.
     * 예외를 밖으로 내지 않는다 — 여기는 WS 수신 콜백 안이다.
     */
    private void handleFrame(SessionScope scope, EngineIoFrame frame) {
        if (frame.type() == EngineIoFrame.Type.PONG) {
            // 이 프레임을 받은 소켓의 하트비트에 적는다. 자리에서 다시 읽으면
            // 앞 세션의 pong이 새 세션의 공백을 메워 준 것처럼 보인다.
            Heartbeat beat = scope.heartbeat().get();
            if (beat != null) {
                beat.recordPong();
            }
            return;
        }
        if (frame.type() != EngineIoFrame.Type.EVENT) {
            return;
        }

        ChatMessage message = ChatEventDecoder.decodeChat(frame.payload());
        if (message != null) {
            metrics.recordMessage(message, System.currentTimeMillis());
            return;
        }

        SystemEvent event = ChatEventDecoder.decodeSystem(frame.payload());
        if (event != null) {
            metrics.recordSystemEvent(event.type());
            if ("revoked".equals(event.type())) {
                // 대응은 POK-93이다. 여기서는 온 사실만 남긴다.
                log.warn("chat.session.revoked");
            }
            return;
        }
        // CHAT도 SYSTEM도 아니게 깨진 것. 본문을 붙이지 않는다 — 깨진 JSON이 곧 본문이다.
        metrics.recordDecodeFailure();
    }

    @PreDestroy
    public void stop() {
        SessionScope scope = activeSession.get();
        if (scope != null) {
            cleanUpOnce(scope, status.reason());
        }
    }

    /**
     * 두 종료 경로(전송 절단 · 프로세스 종료)가 공유하는 정리 절차.
     *
     * <p><b>세션마다 한 번만 돈다.</b> 절단이 먼저 정리하고 나면 뒤이은 {@code stop()}은
     * 아무것도 안 한다. 다음 세션은 자기 가드를 갖고 오므로 여기를 다시 지난다.
     *
     * <p>가드가 막는 것은 <b>판정 두 줄</b>이다. 구독 반납은 이 가드가 아니라
     * {@code ChatSession.releaseAndClose()}의 세션 키 {@code getAndSet(null)}이
     * 독립적으로 막는다 — 두 스레드가 동시에 들어와도 둘째는 {@code SKIPPED}다.
     * <b>가드를 지워도 반납은 여전히 한 번이다</b>(CP1 실측). 반납 중복을 이 가드의
     * 존재 이유로 적어 두면 다음 사람이 없는 결함을 찾는다.
     *
     * @param scope  <b>부른 쪽이 들고 있던 덩어리.</b> 자리에서 다시 읽지 않는다 —
     *               반납 왕복 동안 자리의 주인이 바뀌었을 수 있다
     * @param reason 판정 라인에 남길 사유. null이면 정상 종료(SHUTDOWN)다
     */
    private void cleanUpOnce(SessionScope scope, StopReason reason) {
        if (!scope.cleanedUp().compareAndSet(false, true)) {
            return;
        }
        // 자리부터 비운다. 반납은 실서버에서 약 1초 걸리는 왕복인데, 그것을 끝낸
        // 뒤에 비우면 그 사이에 시작한 다음 세션을 우리가 지운다 — 그 세션은
        // 끊겨도 반납도 소켓 닫기도 못 하고 상한 3개를 먹는다.
        // compareAndSet이라 이미 남의 세션이 들어와 있으면 아무것도 안 한다.
        activeSession.compareAndSet(scope, null);

        // 만든 역순으로 닫는다. 요약이 먼저 멈춰야 닫히는 중인 지표를 안 읽는다.
        SummaryLogger logger = scope.summaryLogger().getAndSet(null);
        if (logger != null) logger.close();

        // 닫기 전에 걷는다. "닫힌 것에서 읽는다"를 코드로 남기지 않는다.
        // 하트비트도 삼킨 프레임 수도 세션과 함께 사라지므로, 여기서 안 걷으면
        // 판정 줄에 마지막 세션 값만 남고 앞 세션에서 ping이 막혔던 사실도
        // 프레임을 삼킨 사실도 사라진다 — 그 값이 POK-85가 정한 실패 조건이라
        // 합격선이 조용히 무력해진다.
        //
        // 하트비트가 없는 갈래(수립 실패)에서도 걷는다. 삼킨 프레임은 수립 도중에도
        // 늘 수 있어, 하트비트가 설 때까지 기다리면 그 세션 값이 통째로 사라진다.
        Heartbeat beat = scope.heartbeat().getAndSet(null);
        Instant since = beat == null ? null : scope.collectingSince().get();
        metrics.recordSessionEnd(
                since == null ? Duration.ZERO : Duration.between(since, Instant.now()),
                beat == null ? Duration.ZERO : beat.maxPingGap(),
                beat == null ? Duration.ZERO : beat.maxPongGap(),
                beat == null ? 0L : beat.sendFailureCount(),
                beat == null ? 0L : beat.callbackFailureCount(),
                scope.chat().sinkFailureCount());
        if (beat != null) {
            beat.close();
        }

        // 판정 라인이 먼저다. 소켓을 닫는 도중 예외가 나도 판정은 남아야 한다.
        if (status.state() != CollectionStatus.State.DISABLED) {
            logVerdictOnce(scope, reason);
        }

        // 구독을 반납하고 끊는다고 알린다. 안 하면 세션 반납이 우리가 아니라
        // 서버가 죽은 전송을 알아채는 때에 달리고, 실측에서 10초와 4분 42초로
        // 갈렸다. 연결 상한이 3개라 짧은 간격의 재시작 세 번이면 막힌다.
        ChatSession.Release released = scope.chat().releaseAndClose();
        log.info("chat.session.released session={} subscription={}", scope.no().get(), released);
    }
}
