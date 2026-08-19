package com.pokeclip.chat.collector;

import com.pokeclip.chat.collector.archive.ChatArchive;
import com.pokeclip.chat.collector.chzzk.ChatSession;
import com.pokeclip.chat.collector.chzzk.ChzzkSessionClient;
import com.pokeclip.chat.collector.observe.CollectionMetrics;
import com.pokeclip.chat.collector.observe.HeartbeatListener;
import com.pokeclip.chat.collector.observe.SummaryLogger;
import com.pokeclip.chat.collector.persist.ChatBuffer;
import com.pokeclip.chat.collector.persist.ChatPersister;
import com.pokeclip.chat.collector.session.SessionKey;
import com.pokeclip.chat.collector.session.StreamSession;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.web.client.RestClient;

import com.pokeclip.chat.collector.reconnect.ReconnectPolicy;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 부팅 시 수집을 시작하고, 종료 시 역순으로 닫는다. <b>세션 하나의 살림은
 * {@link StreamSession}이 한다</b> — 여기 남은 것은 프로세스 단위의 일뿐이다
 * (종료 신호 · 수신 게이트 · 싱크 닫기 · 최종 판정 · 프로세스 종료).
 *
 * <p><b>재시도가 안 통하는 사유는 401·403과 {@code REVOKED}뿐이다.</b> 그 밖은
 * 포기하지 않는다 — 끊겨 있는 동안의 채팅은 되돌릴 수 없고, 백필이 되는지도 모른다.
 * <b>포기하면 판정을 내고 프로세스를 내린다(exit 1)</b> — 수집이 영영 안 되는
 * STOPPED로 살아 있을 이유가 없다.
 *
 * <p><b>🔴 그 「프로세스를 내린다」가 세션 안에 있으면 안 된다</b>(POK-127 C3).
 * 세션이 여럿이 되는 순간 한 스트리머의 동의 철회가 나머지 전원의 수집을 끊기 때문이다.
 * 그래서 {@code exitAction}은 여기 있고 세션은 {@link #onPermanentStop}으로만 알린다.
 *
 * <p><b>여기 남은 것은 옛 경로(설정으로 한 채널만 붙이는 길)의 세션 하나뿐이다.</b>
 * 그 하나가 영구 정지하면 이 프로세스가 수집할 것이 남지 않으므로 판정 뒤 내리는 것이
 * 여전히 맞다. <b>스트리머 여럿은 {@code SessionRegistry}가 든다</b> — 거기서는 같은
 * 사건이 「그 세션만 닫고 등록부에서 지운다」이고, 등록부는 exit 손잡이를 아예 받지 않는다
 * ({@code SessionRegistry.stopOne}, 태스크 9).
 */
public class CollectorRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CollectorRunner.class);

    /** 종료가 재연결 스레드를 기다리는 기본 시한. 구독 반납 왕복이 실서버 약 1초다. */
    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(2);

    /**
     * 반납이 나가 있을 때만 더 기다리는 시한. <b>반납 REST의 상한</b>이다 —
     * 접속 2초 + 읽기 5초({@code spring.http.clients}). 그 뒤에는 REST가 스스로
     * 끝나므로 더 기다릴 이유가 없다.
     */
    private static final Duration RELEASE_WAIT = Duration.ofSeconds(7);

    /**
     * 영구 정지 뒤 프로세스를 내리기 전에 잔량 저장(DB 회복)을 기다리는 시한.
     * 종료 유예와 무관하다 — 우리가 스스로 내리는 길이라 SIGTERM이 온 것이 아니고,
     * 그 사이 SIGTERM이 오면 stop()이 이 대기를 깨운다. 30초는 재연결 백오프 상한과
     * 같은 크기의 "잠깐의 장애" 기준이다 — 그보다 긴 장애면 잔량은 로그로만 남긴다.
     */
    private static final Duration EXIT_DRAIN_WAIT = Duration.ofSeconds(30);

    /**
     * 종료 시 아카이브의 마지막 flush를 기다리는 시한. persister.close()의 5초와 같다 —
     * <b>나란히</b> 닫으므로 종료 예산이 늘지 않는다(closeSinks 주석).
     */
    static final Duration ARCHIVE_CLOSE_WAIT = Duration.ofSeconds(5);

    /** 아카이브 닫기 예산의 시한(nanoTime). 첫 closeSinks가 CAS로 정하고 둘째는 남은 만큼만 기다린다 — 0은 "아직 안 정함". 이유는 closeSinks 주석. */
    private final AtomicLong sinksCloseDeadlineNanos = new AtomicLong();

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

    /** 수신 스레드가 넣기만 하는 바구니. 저장은 {@code ChatPersister}의 스레드가 한다. */
    private final ChatBuffer buffer;
    /** 요약에 persisted·conflicts를 싣기 위해서만 든다 — 저장 지시는 하지 않는다. */
    private final ChatPersister persister;
    /** 수신 스레드가 offer만 하는 원본 아카이브. 꺼져 있으면 {@link ChatArchive#NONE} — 러너는 모른다. */
    private final ChatArchive archive;

    /**
     * 이 러너가 여는 세션. <b>하나뿐이다 — 옛 경로(설정으로 한 채널만 붙이는 길)의 것이다.</b>
     * 스트리머별로 여러 개를 여는 것은 {@code SessionRegistry}가 한다.
     */
    private final StreamSession session;

    /**
     * 재연결 전용 스레드. <b>WS 수신 콜백이나 ping 스케줄러에서 재수립하지 않는다</b> —
     * 앞의 것은 소켓의 스레드라 자기가 닫을 소켓 위에서 도는 꼴이고, 뒤의 것은
     * 그 스레드가 재수립에 붙들려 <b>ping이 안 나가는</b> 2026-08-01 사고를 재현한다.
     *
     * <p>단일 스레드다. 둘 이상이면 서로 다른 시도가 동시에 자리를 노리고, 진 쪽이
     * 연 소켓은 아무도 안 닫는다.
     */
    private final ExecutorService reconnector = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "chzzk-reconnect");
        t.setDaemon(true);
        return t;
    });

    /**
     * 최종 판정 가드. <b>세션이 아니라 프로세스에 하나다.</b>
     *
     * <p>판정은 "수집이 영영 끝났다"는 뜻이라 나가는 자리가 둘뿐이다 —
     * 영구 정지와 프로세스 종료. 세션마다 두면 재연결할 때마다 "최종" 판정이 쌓이고
     * 그중 어느 것도 최종이 아니다.
     */
    private final AtomicBoolean verdictLogged = new AtomicBoolean();

    /**
     * <b>우리가 멈추는 중이라는 신호. 수립 대기가 이걸 본다.</b>
     *
     * <p>없으면 {@code stop()}이 둘 중 하나를 골라야 한다 — 수립 시한(운영 15초)만큼
     * 기다려 종료 유예를 넘기거나, 짧게 기다리고 {@code shutdownNow()}로 뒷정리 중인
     * 스레드를 인터럽트하거나. 뒤쪽이면 구독 반납 REST가 즉시 실패하고 우아한 닫기도
     * 못 돌아 <b>급사 경로</b>가 된다. 그때 서버가 세션을 놓아주는 데 10초~4분 42초가
     * 걸리고(실측) 상한이 3개라, 이 카드가 없애려던 것을 우리가 만드는 셈이 된다.
     */
    private final CountDownLatch stopSignal = new CountDownLatch(1);

    /**
     * <b>수신 게이트. 내려가면 CHAT 프레임을 세지도 담지도 않는다.</b> {@code stop()}이
     * 퍼시스터를 닫기 <i>직전에</i> 내린다.
     *
     * <p>없으면 마무리 flush 도중에도 소켓이 살아 새 채팅이 계속 바구니에 들어온다 —
     * flush는 "진전이 있는 동안 계속"이라 바쁜 방송 + 느린 DB에서는 끝을 못 보고
     * 5초 예산을 넘겨 잔량을 버리고, 판정 줄은 아직 오는 채팅 때문에 등식
     * received = persisted + conflicts + poisoned + dropped가 안 닫힌다(PR #53 P1,
     * 실측 received=818 · persisted=782). <b>수신을 먼저 끊고 나서 close</b>여야 한다.
     *
     * <p><b>소켓을 먼저 닫는 것으로 끊지 않는다.</b> 그러면 ① 구독 반납이 닫힌 소켓
     * 뒤에 나가 반납-후-닫기 순서(서버가 세션을 정리하는 중이면 반납이 무의미해질 수
     * 있다 — {@code 소켓을_닫기_전에_반납을_보낸다}가 지킨다)가 뒤집히고, ② 닫힘 콜백이
     * 이 세션의 절단 사유를 {@code TRANSPORT_CLOSED}로 세워 우아한 종료가 절단으로
     * 기록된다. 그래서 전송은 그대로 두고 <b>수신 콜백에서 받아들이기만 멈춘다</b> —
     * 소켓·반납은 기존 자리({@code cleanUpOnce})에서 그대로 닫는다.
     *
     * <p>{@code stopSignal}과 따로 두는 이유: 그쪽은 {@code stop()} 첫 줄에 내려가고 뒤에
     * 재연결 스레드 대기(최대 9초)가 있다. 그 대기 동안 온 채팅은 아직 실을 수 있으므로
     * 게이트는 close 직전에 내려 유실 창을 그만큼 좁힌다.
     *
     * <p>남는 창은 「게이트 읽기 → offer」의 몇 인스트럭션이다. 그 사이에 마지막 flush의
     * <i>빈 큐 확인</i>까지 통째로 지나가야 한 건이 어긋나는데, flush는 스레드 제출·기상을
     * 거치므로 실질적으로 안 겹친다 — 재현 못 함. "원리적으로 불가능"이 아니다.
     */
    private final AtomicBoolean intakeClosed = new AtomicBoolean();

    /**
     * <b>지금 나가 있는 구독 반납 왕복의 수.</b> {@code stop()}이 인터럽트하기 전에 본다.
     *
     * <p>재연결 스레드를 인터럽트해도 대부분은 손해가 없다 — 수립에 매달린
     * 스레드는 버려도 되고(뒤늦게 성립하는 소켓은 가드에 막힌 정리가 닫는다)
     * 백오프 대기는 중단 신호로 스스로 깨어난다. <b>비싼 자리는 하나뿐이다:</b>
     * 반납 REST가 나간 뒤. 거기서 인터럽트하면 세션 키는 이미 소모돼
     * <b>아무도 다시 못 보내고</b>, 서버 쪽 자리가 10초~4분 42초 남는다(실측).
     *
     * <p>그래서 시한을 통째로 늘리지 않고 이 자리만 더 기다린다. 늘리면
     * 치지직이 아파서 수립이 매달릴 때마다 종료가 이유 없이 몇 초씩 길어진다.
     *
     * <p><b>불리언이 아니라 수다.</b> 한 세션에 두 스레드가 동시에 들어오는 길이
     * 있다(가드를 얻은 쪽과 {@code releaseLate}로 빠지는 쪽). 그때 먼저 끝난
     * 쪽이 플래그를 내리면 <b>아직 나가 있는 왕복이 무방비가 된다</b> —
     * 이 필드가 막으려던 그 인터럽트가 그대로 들어온다.
     */
    private final AtomicInteger releasesInFlight = new AtomicInteger();

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

    /**
     * 영구 정지 판정 뒤 프로세스를 내리는 손잡이. 운영은 {@code System.exit(1)}
     * ({@code CollectorApplication}의 빈 정의), 검사는 가짜다 — 실제 exit은 테스트
     * JVM을 죽인다.
     */
    private final Runnable exitAction;

    /**
     * <b>검사용 — exit이 없다.</b> 러너를 직접 만드는 검사가 스무 곳이 넘고, 그중
     * 여럿이 REVOKED·401을 일부러 만든다. 기본이 {@code System.exit}이면 그 검사가
     * 테스트 JVM을 통째로 내린다. 패키지 밖에서는 안 보인다 — 운영 배선은 아래
     * 공개 생성자로 exit을 <b>명시적으로</b> 준다.
     */
    CollectorRunner(ChzzkProperties properties, CollectionStatus status,
                    RestClient.Builder restClientBuilder,
                    ChatBuffer buffer, ChatPersister persister) {
        this(properties, status, restClientBuilder, buffer, persister, ChatArchive.NONE, () -> { });
    }

    public CollectorRunner(ChzzkProperties properties, CollectionStatus status,
                           RestClient.Builder restClientBuilder,
                           ChatBuffer buffer, ChatPersister persister,
                           ChatArchive archive, Runnable exitAction) {
        this.properties = properties;
        this.status = status;
        // 빌더는 프로토타입 빈이다. 한 번만 build()해서 들고 있는다.
        this.restClient = restClientBuilder.build();
        this.buffer = buffer;
        this.persister = persister;
        this.archive = archive;
        this.exitAction = exitAction;
        // <b>세션은 여기서 하나만 만든다.</b> 스트리머별로 여러 개를 여는 것은
        // SessionRegistry가 한다 — 여기를 늘리면 두 곳이 같은 일을 하게 된다.
        //
        // <b>메서드 참조 둘({@code newSession}·{@code heartbeatListener})은 검사가 상속으로
        // 갈아 끼우는 손잡이다.</b> 세션이 직접 {@code new ChatSession(...)}을 하면 그 손잡이가
        // 죽는다. 참조를 생성자에서 만들어도 실제 호출은 수립 시점이라, 하위 클래스의
        // 필드가 아직 안 채워진 상태를 보지 않는다.
        this.session = new StreamSession(
                // 옛 경로다 — 편지가 없어 방송 번호도 채널도 모른다. 태스크 10이 편지에서
                // 온 진짜 열쇠를 넣는다.
                SessionKey.legacy(), properties.accessToken(),
                properties, status, metrics,
                new ReconnectPolicy(properties.reconnectFirstDelay(), properties.reconnectMaxDelay()),
                restClient, buffer, persister, archive,
                reconnector, stopSignal, intakeClosed, releasesInFlight, lastSessionNo,
                this::newSession, this::heartbeatListener, this::onPermanentStop);
    }

    /**
     * 부팅 경로. 세션이 첫 수립 실패까지 스스로 처리한다
     * ({@link StreamSession#openFromBoot()}에 그 이유가 있다).
     */
    @Override
    public void run(ApplicationArguments args) {
        session.openFromBoot();
    }

    /**
     * 지금까지의 관측값. 유출 검사가 "바늘이 실제로 코드 안을 지나갔나"를
     * 확인하는 데 쓴다 — 그 대조가 없으면 아무것도 안 흘렀을 때
     * "안 샜다"가 자동으로 참이 된다.
     */
    public CollectionMetrics metrics() {
        return metrics;
    }

    /**
     * 세션 하나를 연다. <b>검사가 직접 부르는 자리라 남겨 둔다</b> — 무엇을 하는지는
     * {@link StreamSession#open()}에 있다.
     *
     * <p><b>{@code enabled}를 보는 쪽({@link StreamSession#openIfEnabled()})으로 부른다.</b>
     * 여기는 옛 경로다 — 설정만 보고 한 채널에 붙는 길이라 그 스위치가 그대로 뜻을 갖는다.
     * 편지로 여는 등록부는 그 값을 안 본다.
     */
    public boolean start() {
        return session.openIfEnabled();
    }

    /**
     * 세션 객체를 만든다. <b>검사가 수립 안쪽의 한 지점을 고정하려고 갈아 끼우는
     * 자리다</b> — {@link ChatSession#beforeSessionKey()}는 수립 스레드 위에 있어
     * 가짜 서버 쪽 손잡이로는 못 짚는다. {@link #heartbeatListener(long)}와 같은 이유다.
     */
    ChatSession newSession(ChzzkSessionClient client) {
        return new ChatSession(client);
    }

    /**
     * 하트비트가 알리는 두 사건을 운영 로그로 옮긴다. <b>본체는
     * {@link StreamSession#heartbeatListener(long)}이고 여기는 위임뿐이다.</b>
     *
     * <p><b>이 자리를 없애지 마라.</b> 검사가 러너를 상속해 여기를 갈아 끼워
     * 「가드를 본 뒤 · 상태를 올리기 전」의 창을 결정적으로 벌린다 — 그 창 안에서
     * 불리는 유일한 우리 코드가 이것이다({@code EstablishCutCleanupTest}).
     * 세션은 이 메서드를 참조로 받아 부르므로 오버라이드가 그대로 먹는다.
     */
    HeartbeatListener heartbeatListener(long sessionNo) {
        return session.heartbeatListener(sessionNo);
    }

    /**
     * 버퍼가 비거나 시한이 찰 때까지 기다린다. 저장은 하지 않는다 — 퍼시스터의
     * 스케줄러가 1초마다 재시도하는 것을 <b>지켜볼 뿐</b>이다. stop()이 시작되면
     * (shutdownNow의 인터럽트) 즉시 돌아온다 — 그쪽이 close·판정을 맡는다.
     */
    private void awaitBufferDrained(Duration budget) {
        long deadline = System.nanoTime() + budget.toNanos();
        while (buffer.size() > 0 && stopSignal.getCount() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (buffer.size() > 0) {
            // 유실은 수용하되 관측은 남긴다 — 건수만. 잔량은 뒤이은 close가 한 번 더 시도한다.
            log.warn("chat.collector.exit_drain_left size={}", buffer.size());
        }
    }

    /**
     * <b>수집이 영영 끝났으면 프로세스도 끝낸다.</b> STOPPED로 살아 있으면 health는
     * DOWN인데 아무 일도 안 하는 프로세스가 남고, 버퍼에 남은 채팅은 다음 재시작이
     * 아니라 프로세스가 죽는 순간 사라진다. exit 1로 내려 restart 정책이 새 프로세스를
     * 띄우게 한다 — 토큰이 잘못된 채 배포하면 재시작 루프가 되는데, 그것이 "조용히
     * STOPPED로 살아 있는" 것보다 낫다: 판정 줄의 reason이 매 재시작마다 남는다.
     *
     * <p><b>stop()이 이미 시작됐으면 안 부른다.</b> 종료 시퀀스가 도는 중의 두 번째
     * {@code System.exit}은 JDK가 훅을 기다리지 않고 <b>즉시 halt</b>한다 — 구독 반납이
     * 나가기 전에 프로세스가 사라진다. 검사와 호출 사이의 창(SIGTERM이 그 몇
     * 인스트럭션 사이에 도착)은 못 닫았다 — 그때는 급사 경로다.
     */
    private void exitAfterVerdict(StopReason reason) {
        if (stopSignal.getCount() == 0) {
            return;                       // 종료 중이다. 내리는 것은 그쪽이 한다
        }
        log.warn("chat.collector.exit reason={} code=1", reason);
        exitAction.run();
    }

    /**
     * 세션이 <b>재시도로 안 풀리는 사유</b>를 확정했다. 그 뒤는 프로세스 단위의 일이다.
     *
     * <p><b>이 자리가 세션 밖인 것이 요점이다</b>(POK-127 C3). 세션 안에 프로세스를 내리는
     * 손잡이가 있으면 세션이 여럿이 되는 순간 한 스트리머의 동의 철회가 나머지 전원의 수집을
     * 끊는다. 아래 세 줄은 {@code reconnectLoop}의 비재시도 갈래에 있던 것을 순서까지
     * 그대로 가져온 것이다.
     *
     * <p><b>여기가 여전히 프로세스를 내리는 이유는 이 러너의 세션이 하나뿐이기 때문이다</b> —
     * 그 하나가 영영 못 붙으면 이 프로세스가 수집할 것이 없다. <b>같은 사건을 스트리머
     * 여럿에서 어떻게 다루는지는 {@code SessionRegistry.stopOne}에 있다</b>(그 세션만 닫는다).
     * 두 자리의 결말이 다른 것은 실수가 아니다.
     */
    private void onPermanentStop(StopReason reason) {
        // <b>내리기 전 마지막 회수 — 닫기 전에 기다린다.</b> 여기서 곧장
        // 판정(=close)으로 가면 DB 장애 중일 때 마지막 flush가 실패로 잔량을
        // 복원한 채 스케줄러를 끄고, 그 뒤로는 아무도 다시 저장하지 않는다
        // (PR #55 P1 ②). 퍼시스터의 주기 flush가 아직 살아 있으므로 그것이
        // 회복을 물게 두고, 비거나 시한이 차면 넘어간다. 세션은 이미
        // 치워졌고 STOPPED라 새 채팅은 안 들어온다 — 버퍼는 줄기만 한다.
        awaitBufferDrained(EXIT_DRAIN_WAIT);
        // 퍼시스터 닫기는 logVerdictOnce 안이다 — 판정 경로마다 두면 빼먹는다.
        logVerdictOnce(reason);
        exitAfterVerdict(reason);
    }

    /**
     * <b>수집이 영영 끝났을 때 딱 한 번.</b> 부르는 곳이 둘이다 — 재연결 루프의
     * 비재시도 분기(영구 정지)와 {@code stop()}(프로세스 종료). 영구 정지는 곧이어
     * 스스로 exit하며 종료 훅이 stop()을 부르므로 <b>언제나</b> 두 경로를 다 지난다 —
     * 이 가드가 유일한 방어다.
     *
     * <p>절단에서는 안 부른다. 재연결이 붙은 뒤로 절단은 끝이 아니고, 거기서
     * 판정을 내면 <b>최종이 아닌 최종 판정</b>이 세션 수만큼 쌓인다.
     */
    private void logVerdictOnce(StopReason reason) {
        if (!verdictLogged.compareAndSet(false, true)) {
            return;
        }
        // <b>판정보다 먼저 퍼시스터를 닫는다.</b> 파괴 순서상 퍼시스터의 @PreDestroy는
        // 러너보다 뒤라, 안 닫고 판정을 찍으면 마지막 flush분이 persisted에서 빠져
        // 등식 received = persisted + conflicts + poisoned + dropped가 안 닫힌다.
        // 호출부(영구 정지·stop())마다 두지 않고 <b>가드 통과 직후 여기 한 줄</b>에
        // 둔다 — 세 번째 판정 경로가 생겨도 빼먹을 수 없다. close는 완료-대기
        // 멱등이라 스프링 파괴가 또 불러도, 둘이 겹쳐도 안전하다.
        //
        // 남는 창 하나는 정직하게 적는다: stop()의 shutdownNow가 영구 정지 close
        // 진행 중인 재연결 스레드를 인터럽트하면 그 close는 flush 미완으로 끝날 수
        // 있다(chat.persist.close_interrupted가 단서다). stop() 쪽 판정을
        // shutdownNow 앞으로 옮기는 것은 안 된다 — 판정은 cleanUpOnce(세션 값
        // 걷기) 뒤여야 하고, cleanUpOnce는 낙오 세션 정리를 위해 shutdownNow
        // 뒤가 안전하다.
        closeSinks();
        // <b>열려 있는 절단 구간을 여기서 닫는다.</b> 닫는 자리가 원래 재접속
        // 성공 하나뿐이라, 다시 못 붙고 끝나면 판정 줄이 "outage=0ms
        // lastOutageFrom=none"이라고 말했다 — 그 절단 이후로 계속 못 받고 있는데도.
        // 판정을 내는 두 자리(영구 정지 · 프로세스 종료)가 둘 다 이 가드를 지나므로
        // 여기 두면 양쪽이 한 번에 덮인다.
        //
        // <b>recordOutage가 아니다.</b> 그쪽은 reconnects를 같이 올리는데, 다시 붙은
        // 적이 없으므로 그러면 한 번도 못 붙은 프로세스가 재연결 1회로 보고된다.
        // <b>{@code disconnectedAt}은 세션별 상태라 세션 안에 있다.</b> 걷어 가는 것은
        // 여전히 {@code getAndSet(null)}이라 한 번만 이긴다 — 접근자가 하나 생겼을 뿐
        // 동작은 옮기기 전과 같다.
        Instant openSince = session.takeOpenOutage();
        if (openSince != null) {
            metrics.recordUnrecoveredOutage(openSince, Instant.now());
        }
        // 세션의 값을 여기서 하나도 안 읽는다. 세션이 끝날 때 metrics가 전부
        // 걷어 두므로, 여기서 마지막 세션 것을 다시 읽으면 그 항만 앞 세션 값이 지워진다.
        //
        // 번호는 이 프로세스가 마지막으로 연 세션의 것이다. 판정은 프로세스 전체의
        // 누계라 "몇 번째까지 갔나"를 그 번호가 말한다.
        SummaryLogger.logFinalVerdict(lastSessionNo.get(), metrics.verdict(), reason,
                persister, buffer.droppedCount(), archive.counters());
    }

    /**
     * DB 저장기와 아카이브를 <b>나란히</b> 닫는다 — 아카이브는 마지막 flush를 자기 스레드에 제출만
     * 하고(beginClose) 돌아오므로, persister.close()가 5초를 기다리는 동안 저쪽도 돈다. 그 뒤
     * awaitClosed는 대개 즉시 풀린다. 직렬로 두면 종료 예산이 5초 늘어 유예 20초를 넘긴다
     * (stop 9 + close 5 + 반납 7 = 21). 두 close 모두 완료-대기 멱등이라 두 자리에서 불려도 안전하다.
     *
     * <p><b>예산의 시한은 첫 호출이 정하고({@code sinksCloseDeadlineNanos}) 둘째는 남은 만큼만 기다린다</b> —
     * stop()은 closeSinks를 두 번 지나는데(stop 본문 · 판정 직전), 호출마다 5초를 새로 주면 첫 대기가 시한까지
     * 매달린 뒤 둘째가 또 5초를 기다려 종료가 10초가 된다(가짜로 10.03초 실측). 영구 정지의 재연결 스레드와
     * stop() 호출자가 겹치면 둘 다 여기 올 수 있어 CAS로 첫 승자가 정한다 — 져도 같은 시한을 읽을 뿐이다.
     */
    private void closeSinks() {
        sinksCloseDeadlineNanos.compareAndSet(0, System.nanoTime() + ARCHIVE_CLOSE_WAIT.toNanos());
        archive.beginClose();
        // persister.close()는 모든 갈래를 잡게 짜여 있지만 그 계약이 강제되진 않는다 — 여기서 새면
        // 아래 대기를 통째로 건너뛰어 마지막 flush를 아무도 안 기다리고, 잃은 파일의 단서(close_timeout)도
        // 안 남는다. finally로 묶어 그 갈림을 없앤다.
        try {
            persister.close();
        } finally {
            // 시한까지 남은 만큼만 기다린다 — persister가 5초를 다 썼으면 아카이브 대기는 0에 가깝다(둘 다 시한을
            // 채우는 DB 반개방 + S3 사망 겹침에서 직렬 10초가 되던 것을 막는다, plan-critic 사소-3). 둘째 호출도
            // 같은 시한을 본다(위 주석).
            Duration remaining = Duration.ofNanos(sinksCloseDeadlineNanos.get() - System.nanoTime());
            archive.awaitClosed(remaining.isNegative() ? Duration.ZERO : remaining);
        }
    }

    @PreDestroy
    public void stop() {
        // 먼저 신호를 내린다. 뒷정리보다 뒤에 두면 수립 중인 스레드가 그동안
        // 시한을 계속 쓰고, 그 시간이 그대로 종료 시간에 얹힌다.
        // 백오프 대기도 이 신호를 보고 즉시 깨어난다 — 안 깨우면 종료가 대기
        // 간격만큼 매달리고, 컨테이너 유예를 넘기면 SIGKILL이 와서 구독 반납이
        // 안 나간다. 이 카드가 없애려던 좀비를 우리가 만드는 것이다.
        stopSignal.countDown();
        reconnector.shutdown();
        try {
            // 짧게 기다린다. 여기서 시한을 다 쓰면 shutdownNow()가 재연결 스레드를
            // 뒷정리 도중 인터럽트하고, 그때 구독 반납 REST와 우아한 소켓 닫기가
            // 통째로 실패해 abort만 남는다 = 우리가 없애려던 급사 경로.
            //
            // <b>2초는 수립 최악값이 아니다.</b> 수립 다섯 단계 중 ②③⑤는 조각마다
            // 중단 신호를 보고 100ms 안에 끊기지만, ①④는 <b>이미 나간 REST라 못 끊는다</b> —
            // 접속 2초 + 읽기 5초까지 간다. 그 스레드를 여기서 안 기다리고 버리는데,
            // <b>버려도 새는 것이 없기 때문이다</b>: ①④가 돌아오면 다음 단계 앞에서
            // 중단 신호에 걸리고(ChatSession.abortIfStopping), 그래도 소켓이 늦게
            // 성립하면 cleanUpOnce가 가드에 막힌 채로 그것을 닫는다.
            //
            // 2초는 <b>뒷정리 중인 스레드</b>를 위한 값이다 — 구독 반납 왕복이 실서버
            // 약 1초다. 그쪽을 인터럽트하는 것이 여기서 유일하게 비싼 실수다.
            //
            // <b>그런데 2초는 반납의 최악값이 아니다.</b> 반납 REST도 접속 2초 +
            // 읽기 5초까지 가고, <b>수립과 달리 중단 신호를 안 본다</b> — 이미 나간
            // 요청이라 볼 자리가 없다. 치지직이 느리게 답하는 배포 순간에 정확히
            // 여기 걸려, 인터럽트되면 세션 키가 이미 소모된 뒤라 아무도 다시 못
            // 보내고 자리가 서버에 10초~4분 42초 남는다(실측).
            //
            // <b>시한을 통째로 늘리지 않고 그 자리만 더 기다린다.</b> 늘리면 수립에
            // 매달린 스레드까지 같이 기다리게 되는데, 그쪽은 버려도 새는 것이 없다
            // (위 문단). 아파서 못 붙는 중일수록 종료가 길어지는 것은 손해다.
            if (!reconnector.awaitTermination(SHUTDOWN_WAIT.toMillis(), TimeUnit.MILLISECONDS)
                    && releasesInFlight.get() > 0) {
                reconnector.awaitTermination(RELEASE_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // <b>수신을 먼저 끊고 나서 close.</b> 게이트가 열린 채 close하면 마무리 flush
        // 도중에도 채팅이 계속 들어와 flush가 끝을 못 보고(바쁜 방송 + 느린 DB) 예산을
        // 넘긴다 — 그 뒤 판정 등식이 안 닫힌다. 소켓이 아니라 게이트로 끊는 이유는
        // intakeClosed 주석에 있다. 종료 순서: 게이트 내림 →
        // [archive.beginClose ‖ persister.close ‖ archive.awaitClosed] →
        // shutdownNow → cleanUpOnce(반납·소켓 닫기) → 판정.
        intakeClosed.set(true);
        // <b>인터럽트 전에 퍼시스터 닫기를 선점한다.</b> 재연결 스레드가 영구 정지
        // 판정 안에서 close를 들고 있을 수 있는데, 아래 shutdownNow가 그 스레드를
        // flush 도중 인터럽트하면 마지막 배치가 끊긴다. close는 완료-대기 멱등이라
        // — 진행 중이면 이 호출이 그 완료를 기다리고, 아직이면 여기서 직접 비운다 —
        // 어느 쪽이든 이 줄이 돌아온 시점에는 인터럽트할 flush가 없다.
        // 판정 자체는 아래 logVerdictOnce 자리 그대로다(cleanUpOnce 뒤여야 한다).
        // 아카이브도 여기서 같이 닫는다 — 같은 이유로, 인터럽트 전에 마지막 flush를 제출한다.
        closeSinks();
        reconnector.shutdownNow();
        // 세션의 뒷정리(반납 → 소켓 닫기)다. <b>이 자리를 옮기지 마라</b> — 위의
        // 게이트 내림·싱크 닫기·shutdownNow 뒤여야 하고, 아래 판정 줄보다 앞이어야 한다.
        session.close();
        // 판정이 나가야 할 두 시점 중 둘째. 영구 정지에서 이미 나갔으면 가드가 막는다.
        // <b>꺼져 있으면 안 낸다</b> — 원래 cleanUpOnce의 판정 호출이 그 검사를 달고
        // 있었고, 여기로 옮기면서 빠뜨리면 꺼진 서버가 종료마다 received=0 판정을 뱉는다.
        if (status.state() != CollectionStatus.State.DISABLED) {
            // 퍼시스터 닫기는 logVerdictOnce 안이다 — 수신은 위에서 이미 끊겼으므로
            // 그 시점의 버퍼가 이 프로세스의 전부고, 판정 직전의 close가 그것을 싣는다.
            // DISABLED로 여길 안 지나는 경우의 닫기는 스프링 @PreDestroy가 덮는다.
            logVerdictOnce(status.reason());
        }
    }
}
