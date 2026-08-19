package com.pokeclip.chat.collector.session;

import com.pokeclip.chat.collector.ChzzkProperties;
import com.pokeclip.chat.collector.CollectionStatus;
import com.pokeclip.chat.collector.StopReason;
import com.pokeclip.chat.collector.archive.ArchivableChat;
import com.pokeclip.chat.collector.archive.ChatArchive;
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
import com.pokeclip.chat.collector.persist.ChatBuffer;
import com.pokeclip.chat.collector.persist.ChatPersister;
import com.pokeclip.chat.collector.persist.PersistableChat;
import com.pokeclip.chat.collector.reconnect.ReconnectPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongFunction;

/**
 * 스트리머 한 명의 수집 — 세션을 열고, 끊기면 세션 발급부터 다시 타고, 닫을 때 역순으로 닫는다.
 *
 * <p><b>{@code CollectorRunner}에서 통째로 옮겨 온 것이다(POK-127 태스크 8).</b>
 * 재연결 실행기는 세션이 만들지 않고 생성자로 받는다 — 넣는 쪽이 {@code SessionRegistry}면
 * 가상 스레드를 태스크마다 새로 만드는 실행기이고, 옛 경로의 러너면 플랫폼 스레드 하나다.
 *
 * <p><b>🔴 프로세스를 내리는 손잡이가 여기 없다.</b> 재시도 불가 판정({@code REVOKED}·401·403)
 * 뒤에 {@code System.exit(1)}을 부르던 자리가 {@code onPermanentStop}으로 나갔다. 세션이
 * 여럿이면 한 스트리머의 동의 철회가 나머지 전원의 수집을 끊기 때문이다 — 손잡이를 안 두는
 * 것이 그것을 구조적으로 막는 유일한 방법이다. <b>다시 들이지 마라.</b>
 * (그 콜백의 구현은 넣는 쪽이 정한다. {@code SessionRegistry}는 「그 세션만 닫고 등록부에서
 * 지운다」이고, 옛 경로의 {@code CollectorRunner}는 세션이 하나뿐이라 여전히 프로세스를 내린다.)
 *
 * <p><b>여기 붙은 주석은 이 파일에서 가장 비싼 자산이다</b> — 락 경계 · 순서 뒤집기 금지 ·
 * 「떼도 초록이다」 실측 기록. 지우기 전에 반드시 읽어라.
 */
public class StreamSession {

    private static final Logger log = LoggerFactory.getLogger(StreamSession.class);

    /** 실측 30초. 요약 주기는 하트비트와 무관하다 — 얹으면 8/1이 재현된다. */
    private static final Duration SUMMARY_PERIOD = Duration.ofSeconds(30);

    /**
     * 이 세션이 누구의 <b>어느 방송</b>인가.
     *
     * <p><b>스트리머는 그대로인데 방송 번호만 바뀐다</b>(방송을 껐다 켜면 새 번호가 나온다).
     * 그때 세션·소켓은 그대로 두고 이 값만 갈아낀다 — {@link #retarget(SessionKey)}.
     *
     * <p><b>그래서 final이 아니다.</b> 갈아끼우지 않으면 새 방송의 채팅이 <b>끝난 방송 번호로</b>
     * 기록된다 — 치지직 구독은 「토큰 주인의 채팅」이라 방송을 못 고르므로 소켓은 하나인 채
     * 새 방송의 채팅이 계속 들어오기 때문이다.
     */
    private volatile SessionKey key;

    /**
     * 이 세션이 쓰는 치지직 유저 토큰. <b>{@code properties.accessToken()}을 직접 안 읽는다</b> —
     * 세션이 여럿이 되면 그 값은 스트리머마다 다르고, 설정에서 읽는 코드가 하나라도 남아 있으면
     * 그 세션만 남의 토큰으로 붙는다. 지금은 러너가 설정값을 그대로 넘기므로 동작이 같다.
     */
    private final String accessToken;

    private final ChzzkProperties properties;
    private final CollectionStatus status;
    private final CollectionMetrics metrics;
    private final ReconnectPolicy policy;

    /**
     * <b>러너가 {@code RestClient.Builder}로 만들어 넘긴 것이다.</b> 여기서
     * {@code RestClient.create()}로 새로 만들지 마라 — 자동 설정을 우회해
     * {@code spring.http.clients.*}의 타임아웃이 어디에도 안 걸린다. 그러면 수립도 구독 반납도
     * 치지직이 연결만 받고 답을 안 줄 때 무기한 매달린다. 만드는 자리는 러너 하나다.
     */
    private final RestClient restClient;

    /** 수신 스레드가 넣기만 하는 바구니. 저장은 {@code ChatPersister}의 스레드가 한다. */
    private final ChatBuffer buffer;
    /** 요약에 persisted·conflicts를 싣기 위해서만 든다 — 저장 지시는 하지 않는다. */
    private final ChatPersister persister;
    /** 수신 스레드가 offer만 하는 원본 아카이브. 꺼져 있으면 {@link ChatArchive#NONE} — 러너는 모른다. */
    private final ChatArchive archive;

    /**
     * <b>재연결 전용 실행기. 세션이 만들지 않는다</b> — 만든 쪽이 닫는다.
     *
     * <p>넣는 쪽이 둘이고 <b>모양이 다르다:</b>
     *
     * <ul>
     *   <li><b>등록부</b>({@code SessionRegistry}) — 가상 스레드를 태스크마다 새로 만드는
     *       실행기 하나를 세션 전부가 나눠 쓴다. 제출한 만큼 스레드가 생기므로 세션이
     *       몇이든 <b>줄을 서지 않는다</b>. 운영 경로다
     *   <li><b>러너</b>({@code CollectorRunner}) — 플랫폼 스레드 하나. 옛 경로는 세션이
     *       하나뿐이라 줄을 설 상대가 없다
     * </ul>
     *
     * <p><b>크기를 정한 풀로 바꾸지 마라.</b> 풀이 N이면 N+1번째 세션은 재연결 루프에
     * 들어가지도 못하고 앞선 것들의 백오프(상한 60초)를 기다린다 — 그동안 그 스트리머는
     * 소켓이 없어 채팅이 <b>버퍼에 쌓이는 것이 아니라 아예 안 온다</b>(실시간 푸시라
     * 늦게 붙으면 그 구간을 되받을 방법이 없다). <b>복구 지연이 곧 유실이다.</b>
     *
     * <p><b>한 세션에 루프가 둘 도는 것은 실행기가 아니라 {@link #reconnectInFlight}가 막는다.</b>
     * 실행기를 여러 스레드로 바꿔도 그 불변식은 그대로다 — 옛 「단일 스레드여야 한다」는
     * 주석을 그 근거로 읽지 마라.
     */
    private final ExecutorService reconnector;

    /** 프로세스가 멈추는 중이라는 신호. 종료는 전역이라 러너의 것을 그대로 본다. */
    private final CountDownLatch stopSignal;

    /** 수신 게이트. 프로세스 종료가 내린다 — 러너의 것을 그대로 본다. */
    private final AtomicBoolean intakeClosed;

    /** 지금 나가 있는 구독 반납 왕복의 수. {@code stop()}이 인터럽트 전에 이 값을 본다. */
    private final AtomicInteger releasesInFlight;

    /** 러너가 마지막으로 자리를 준 세션의 번호. 검사가 자기 러너의 줄만 고르는 열쇠다. */
    private final AtomicLong lastSessionNo;

    /**
     * {@code ChatSession}을 만드는 자리. <b>러너의 {@code newSession}을 가리킨다</b> —
     * 검사가 러너를 상속해 그것을 갈아 끼워 수립 안쪽의 한 지점을 고정한다
     * ({@code ChatSession.beforeSessionKey()}는 수립 스레드 위라 가짜 서버로는 못 짚는다).
     * 여기서 직접 {@code new ChatSession(...)}을 하면 그 손잡이가 죽는다.
     */
    private final Function<ChzzkSessionClient, ChatSession> sessionFactory;

    /**
     * 하트비트 리스너를 만드는 자리. <b>러너의 {@code heartbeatListener}를 가리킨다</b> —
     * 위와 같은 이유로 검사가 갈아 끼운다. 본체는 아래 {@link #heartbeatListener(long)}이고
     * 러너는 그리로 위임만 한다.
     */
    private final LongFunction<HeartbeatListener> heartbeatListenerFactory;

    /**
     * 재시도로 안 풀리는 사유가 확정됐을 때 부른다. <b>세션은 그 뒤에 무엇을 할지 모른다.</b>
     * 지금 구현(러너)은 옮기기 전과 같이 잔량을 기다리고 판정을 내고 프로세스를 내린다.
     */
    private final Consumer<StopReason> onPermanentStop;

    /**
     * 등록부가 여는 세션({@code SessionRegistry}). <b>검사용 손잡이 둘을 안 받는다</b> —
     * 그 둘은 러너를 <i>상속해서</i> 갈아 끼우는 옛 검사 전용이고, 등록부 경로에는
     * 상속할 러너가 없다. 자기 것으로 채운다.
     */
    public StreamSession(SessionKey key, String accessToken,
                         ChzzkProperties properties, CollectionStatus status,
                         CollectionMetrics metrics, ReconnectPolicy policy,
                         RestClient restClient,
                         ChatBuffer buffer, ChatPersister persister, ChatArchive archive,
                         ExecutorService reconnector, CountDownLatch stopSignal,
                         AtomicBoolean intakeClosed, AtomicInteger releasesInFlight,
                         AtomicLong lastSessionNo,
                         Consumer<StopReason> onPermanentStop) {
        this(key, accessToken, properties, status, metrics, policy, restClient,
                buffer, persister, archive, reconnector, stopSignal, intakeClosed,
                releasesInFlight, lastSessionNo, null, null, onPermanentStop);
    }

    /**
     * @param sessionFactory           null이면 {@link ChatSession}을 직접 만든다
     * @param heartbeatListenerFactory null이면 이 세션의 {@link #heartbeatListener(long)}를 쓴다
     */
    public StreamSession(SessionKey key, String accessToken,
                         ChzzkProperties properties, CollectionStatus status,
                         CollectionMetrics metrics, ReconnectPolicy policy,
                         RestClient restClient,
                         ChatBuffer buffer, ChatPersister persister, ChatArchive archive,
                         ExecutorService reconnector, CountDownLatch stopSignal,
                         AtomicBoolean intakeClosed, AtomicInteger releasesInFlight,
                         AtomicLong lastSessionNo,
                         Function<ChzzkSessionClient, ChatSession> sessionFactory,
                         LongFunction<HeartbeatListener> heartbeatListenerFactory,
                         Consumer<StopReason> onPermanentStop) {
        this.key = key;
        this.accessToken = accessToken;
        this.properties = properties;
        this.status = status;
        this.metrics = metrics;
        this.policy = policy;
        this.restClient = restClient;
        this.buffer = buffer;
        this.persister = persister;
        this.archive = archive;
        this.reconnector = reconnector;
        this.stopSignal = stopSignal;
        this.intakeClosed = intakeClosed;
        this.releasesInFlight = releasesInFlight;
        this.lastSessionNo = lastSessionNo;
        this.sessionFactory = sessionFactory != null ? sessionFactory : ChatSession::new;
        this.heartbeatListenerFactory = heartbeatListenerFactory != null
                ? heartbeatListenerFactory : this::heartbeatListener;
        this.onPermanentStop = onPermanentStop;
    }

    /** 이 세션이 누구의 어느 방송인가. 등록부가 {@code streamId}로 찾는다. */
    public SessionKey key() {
        return key;
    }

    /**
     * <b>같은 스트리머의 새 방송으로 갈아낀다. 세션도 소켓도 그대로다.</b>
     *
     * <p>닫았다 새로 여는 것이 아니라 번호만 바꾸는 이유는 <b>그 사이의 채팅이 유실되기
     * 때문</b>이다 — 채팅은 실시간 푸시라 늦게 붙으면 그 구간을 되받을 방법이 없다.
     * 게다가 치지직 구독은 「토큰 주인의 채팅」이라 방송을 못 고르므로, 닫지 않는 한
     * 새 방송의 채팅은 <b>이미 이 소켓으로 들어오고 있다.</b>
     *
     * <p><b>스트리머가 같을 때만 부른다</b> — 등록부가 그것을 보장한다. 사람이 바뀌면
     * 토큰도 소켓도 달라야 하므로 갈아끼우기가 성립하지 않는다.
     *
     * <p>토큰은 안 바꾼다. 이미 이 토큰으로 수립·구독이 끝났고, 바꿔도 열려 있는 소켓에는
     * 영향이 없다 — 쓰이는 곳은 <b>닫을 때의 구독 반납</b>뿐이라 그때 옛 토큰이 맞다.
     */
    public void retarget(SessionKey newKey) {
        this.key = newKey;
    }

    /**
     * 열려 있는 절단 구간의 시작 시각을 <b>걷어 간다</b>({@code getAndSet(null)}이라 한 번만 이긴다).
     *
     * <p>최종 판정 줄이 「다시 못 붙고 끝났다」를 적을 때 러너가 부른다. 그 값이 세션별 상태라
     * 이쪽에 있고, 판정은 프로세스 단위라 저쪽에 있어서 생긴 이음매다 —
     * <b>접근자가 하나 생겼을 뿐 동작은 옮기기 전과 같다.</b>
     */
    public Instant takeOpenOutage() {
        return disconnectedAt.getAndSet(null);
    }

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
     *                      거절된 {@code open()}이 번호를 먹으면 로그에 구멍이 생기고,
     *                      사람은 그 구멍을 "판정 줄을 잃어버렸다"로 읽는다
     * @param cleanedUp     뒷정리 가드. 종료 경로가 둘(전송 절단·프로세스 종료)이라
     *                      같은 세션에서 두 번 도는 길이 있다
     * @param cutReason     <b>이 세션에 실제로 온 절단 사유.</b> 안 들고 있으면 세션
     *                      종료 줄이 사유를 {@code status}에서 읽어야 하는데, 재시도
     *                      중에는 그 값이 <b>앞 시도의 것</b>이라 실패한 재시도가
     *                      매번 앞 시도의 단계로 기록된다. 첫 절단이 이긴다 —
     *                      뒤엣것은 대개 그 결과다
     */
    private record SessionScope(
            ChatSession chat,
            AtomicLong no,
            AtomicBoolean cleanedUp,
            AtomicReference<StopReason> cutReason,
            AtomicReference<Heartbeat> heartbeat,
            AtomicReference<SummaryLogger> summaryLogger,
            AtomicReference<Instant> collectingSince) {

        static SessionScope opening(ChatSession chat) {
            return new SessionScope(chat, new AtomicLong(), new AtomicBoolean(),
                    new AtomicReference<>(), new AtomicReference<>(),
                    new AtomicReference<>(), new AtomicReference<>());
        }

        /** 절단 사유가 있으면 그것이 이 세션의 끝이고, 없으면 부르는 쪽이 아는 것이 끝이다. */
        StopReason endReason(StopReason fallback) {
            StopReason cut = cutReason.get();
            return cut != null ? cut : fallback;
        }
    }

    /** 지금 자리를 잡고 있는 세션. 비어 있을 때만 새 세션이 들어올 수 있다. */
    private final AtomicReference<SessionScope> activeSession = new AtomicReference<>();

    /** 아직 아무 세션도 안 선 상태의 번호. 부팅 첫 수립이 실패한 경우가 여기다. */
    private static final long NO_SESSION = 0L;

    /**
     * 재연결 루프가 <b>하나만</b> 돌게 막는다. 루프가 끝날 때 푼다.
     *
     * <p><b>감지원이 셋이라 한 절단에 신호가 둘 이상 발화한다</b> — pong 임계 초과와
     * 전송 절단이 같은 죽음을 서로 다른 경로로 본다. 그때 세션의 {@code cleanedUp}
     * 가드는 이미 소모돼 no-op이라 아무것도 안 막는다. 가드가 없으면 <b>루프가 둘
     * 큐에 쌓이고, 첫 루프가 재접속에 성공한 뒤 두 번째가 깨어나 health를 DOWN으로
     * 되돌리고 살아 있는 세션을 고아로 만든다.</b>
     */
    private final AtomicBoolean reconnectInFlight = new AtomicBoolean();

    /**
     * 루프가 도는 동안 들어온 절단 신호. 루프가 끝날 때 집어 간다.
     *
     * <p><b>사유만 두면 안 된다.</b> 루프가 재접속에 성공하고 {@code finally}에
     * 닿기까지의 창에 <b>이미 치워진 앞 세션</b>의 늦은 신호가 들어올 수 있고, 그것을
     * 그대로 재생하면 살아 있는 새 세션을 헐어낸다. 어느 세션의 신호인지를 같이 든다.
     *
     * @param sessionNo 신호를 낸 세션의 번호. 재생할 때 그 세션이 아직 자리에 있는지 본다
     */
    private record ReconnectSignal(long sessionNo, StopReason reason) { }

    private final AtomicReference<ReconnectSignal> pendingSignal = new AtomicReference<>();

    /**
     * 신호 하나가 「루프를 잡거나 대기열에 남기는」 것과, 루프가 「자리를 풀고 밀린
     * 신호를 집는」 것을 <b>서로 겹치지 않게</b> 한다.
     *
     * <p>둘을 원자 변수 둘로만 이으면 신호가 소리 없이 사라진다:
     *
     * <pre>
     * 생산자(콜백·ping 스레드)            소비자(재연결 스레드)
     * CAS(false→true) 실패 — 루프가 돈다
     *                                     reconnectInFlight.set(false)
     *                                     pendingSignal.getAndSet(null) → null
     * pendingSignal에 쓴다                ← 이제 아무도 안 읽는다
     * </pre>
     *
     * <p>그 결과가 제일 나쁘다. 생산자는 CAS <b>앞에서</b> 이미
     * {@code status.reconnecting(...)}을 찍었으므로 상태는 RECONNECTING(health DOWN)에
     * 멈추고, 루프는 다시는 안 돈다. 게다가 <b>재알림이 없다</b> — ping 송신 실패와
     * pong 임계는 {@code Heartbeat}가 구간당 한 번만 알린다. 즉 수집이 영구히 멈춘다.
     *
     * <p><b>{@code finally}에서 한 번 더 확인하는 것으로는 안 닫힌다.</b> 재확인 뒤에
     * 쓰는 순서가 그대로 남기 때문이다 — 창이 좁아질 뿐 사라지지 않는다. 락으로
     * 묶으면 둘 중 하나만 성립한다: 생산자가 먼저면 소비자가 그 신호를 집고,
     * 소비자가 먼저면 생산자의 CAS가 이겨 스스로 루프를 띄운다.
     *
     * <p><b>자리 잡기({@code open()})도 같은 락 안이다.</b> 낡은 신호를 거르는
     * {@code isCurrent} 검사와 상태 전이가 갈라져 있으면, 검사를 통과한 신호가
     * 그 사이에 붙은 새 세션을 RECONNECTING으로 되돌린다.
     *
     * <p><b>이 락 안에서 I/O를 하지 마라.</b> 여기 들어오는 스레드에 WS 수신 콜백이
     * 있어, 붙들리는 만큼 그 방송의 채팅이 통째로 늦어진다. 로그도 실행기 제출도
     * 밖에 둔 이유가 그것이다.
     *
     * <p><b>🔴 이 락을 떼도 전체가 초록이다</b>(두 자리 각각 떼고 173건 확인).
     * "원리적으로 불가능"이 아니라 <b>지금 코드에 관측 가능한 차이를 만들 손잡이가
     * 없다</b> — 막는 창이 원자 변수 두 개를 읽고 쓰는 몇 인스트럭션이라 붙잡을
     * I/O가 없고, 두 스레드를 그 사이에 세울 수단이 가짜 서버에도 없다.
     * <b>떼려거든 여기를 먼저 읽어라.</b>
     */
    private final Object signalLock = new Object();

    /**
     * 언제부터 못 받고 있나. 다시 붙으면 지운다.
     *
     * <p>재연결이 여러 번 실패해도 <b>첫 절단 시각을 유지한다</b> — 시도마다 갱신하면
     * "10분째 못 붙는다"가 매번 "방금 끊겼다"로 보인다.
     *
     * <p><b>평범한 필드로 두면 그 "첫 시각이 이긴다"가 감지원이 겹칠 때 깨진다.</b>
     * 만지는 스레드가 셋이고(WS 콜백 · ping 스케줄러 · 재연결 스레드) 한 번의 죽음을
     * 둘 이상이 서로 다른 경로로 본다 — 둘이 같이 빈 것을 보면 나중 시각이 덮어써
     * 끊긴 시간이 그만큼 짧게 보고된다. 세우기는 {@code compareAndSet(null, …)},
     * 걷기는 {@code getAndSet(null)}이라 둘 다 한 번씩만 이긴다.
     *
     * <p><b>🔴 이 원자성을 지키는 검사가 없다.</b> 평범한 필드로 되돌려도 전체가 초록이다
     * (실측). 겹치는 창이 널 검사와 대입 사이 몇 인스트럭션이라 붙잡을 I/O가 없어서
     * <b>결정적으로 재현할 장치를 못 만들었다</b> — "원리적으로 불가능"이 아니라
     * "지금 코드에 관측 가능한 차이를 만들 손잡이가 없다"이다.
     * <b>되돌려도 빌드가 안 막으니, 고치려거든 여기를 먼저 읽어라.</b>
     */
    private final AtomicReference<Instant> disconnectedAt = new AtomicReference<>();

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
     * <b>옛 경로의 입구 — {@code enabled} 스위치를 보는 자리는 여기뿐이다.</b>
     *
     * <p>그 스위치는 <b>프로세스 공용 설정</b>이라 「이 서버가 설정만 보고 한 채널에
     * 붙는가」를 뜻한다. 방송 편지로 여는 세션은 이미 <b>붙어라</b>를 들은 것이므로 그
     * 값을 볼 이유가 없다 — {@code SessionRegistry}는 {@link #open()}을 직접 부른다.
     *
     * <p><b>여기 두지 않고 {@code open()} 안에 두면 조용히 죽는다.</b> 운영 기본값이
     * {@code CHZZK_ENABLED:false}라({@code application.yml}) 편지로 연 세션이 전부 그
     * 자리에서 돌아가고, 로그는 {@code INFO chat.collector.disabled} 한 줄뿐이라
     * 「편지는 지워졌는데 그 방송은 영영 안 걷힌다」가 된다. 태스크 8이 토큰에 대해 세운
     * 규칙(<b>설정에서 읽는 코드가 하나라도 남으면 그 세션만 남의 것으로 붙는다</b>,
     * {@code accessToken} 주석)과 같은 이유다 — 감사에서 중대로 잡혔다.
     *
     * @return 이 호출이 수집을 시작시켰는가. 꺼져 있으면 false다
     */
    public boolean openIfEnabled() {
        if (!properties.enabled()) {
            // 붙지 않는다. 로그 한 줄은 남긴다 — "왜 채팅이 안 들어오지"의
            // 첫 번째 답이 대개 이것이다.
            log.info("chat.collector.disabled");
            status.disabled();
            return false;
        }
        return open();
    }

    /**
     * 부팅 경로. <b>첫 시도부터 실패한 것도 재연결 대상이다</b> — 치지직이 잠깐
     * 아플 때 부팅 타이밍이 겹쳤다는 이유로 그 프로세스가 영영 수집을 안 하면,
     * 사람이 알아채고 재배포할 때까지의 채팅이 통째로 사라진다.
     */
    public void openFromBoot() {
        try {
            openIfEnabled();
        } catch (SessionEstablishException e) {
            // 사유가 영구면 루프가 첫 바퀴에서 즉시 멈춘다.
            // stopped 줄은 open()이 detail까지 실어 이미 남겼다.
            requestReconnect(NO_SESSION, e.reason());
        }
    }

    /**
     * 세션 하나를 연다.
     *
     * @return <b>이 호출이 수집을 시작시켰는가.</b> 자리를 못 잡았거나 그 사이에
     *         끝났으면 false다. <b>값으로 주는 이유</b> — 재연결 루프가 성공 여부를
     *         {@code status.state()}로 읽으면, 거절된 호출에서 그 상태는 <b>앞 세션이
     *         남긴 값</b>이라 「붙었다」로 오독된다. 그러면 루프가 빠져나가고
     *         아무도 재시도하지 않는다
     * @throws SessionEstablishException 수립에 실패했다. <b>던지기 전에 자기가 연 것은
     *         치운다.</b> 사유를 보고 재시도할지 판단하는 것은 부르는 쪽 일이다
     */
    public boolean open() {
        ChatSession opening = sessionFactory.apply(new ChzzkSessionClient(
                restClient, properties.baseUrl(), accessToken));
        SessionScope scope = SessionScope.opening(opening);
        // <b>자리 잡기와 번호 매기기가 신호 처리와 겹치면 안 된다.</b> 절단 신호는
        // 입구에서 {@code isCurrent}로 자기 세션이 아직 자리에 있는지 보고 나서
        // 상태를 내리는데, 그 둘 사이에 새 세션이 자리를 잡으면 <b>검사를 통과한
        // 낡은 신호가 방금 붙은 세션을 RECONNECTING으로 되돌린다.</b> 그때 루프는
        // 살아 있는 세션 때문에 {@code start_skipped ALREADY_ACTIVE}만 반복하고,
        // 수집은 멀쩡한데 health가 영영 DOWN인 상태가 남는다.
        //
        // 같은 락 안이면 둘 중 하나만 성립한다 — 신호가 먼저면 그 세션이 아직
        // 자리에 있어 판단이 옳고, 자리 잡기가 먼저면 신호가 낡은 것으로 걸러진다.
        // <b>번호까지 이 안에서 매긴다.</b> 밖에서 매기면 번호가 0인 채로 자리에
        // 앉은 순간이 생기고, 그 값이 곧 "아직 세션 없음"({@code NO_SESSION})이라
        // 첫 수립 실패의 신호가 남의 세션 것으로 통과한다.
        String skipReason = null;
        long no = NO_SESSION;
        synchronized (signalLock) {
            if (!activeSession.compareAndSet(null, scope)) {
                // 앞 세션이 아직 자리를 들고 있다. 덮어쓰면 그 세션의 소켓도 스케줄러도
                // 아무도 안 닫아 ping이 죽은 소켓에 계속 나가고, 구독은 서버에 남아
                // 상한 3개를 먹는다.
                skipReason = "ALREADY_ACTIVE";
            } else if (stopSignal.getCount() == 0) {
                // 잡은 자리를 되돌린다. stop()은 자리가 빈 것을 보고 이미 지나갔을 수
                // 있고, 그러면 여기서 연 세션은 아무도 안 닫는다 — 소켓도 구독도
                // 프로세스가 죽을 때까지 남는다.
                activeSession.compareAndSet(scope, null);
                skipReason = "STOPPING";
            } else {
                no = SESSION_SEQ.incrementAndGet();
                scope.no().set(no);
                lastSessionNo.set(no);
            }
        }
        if (skipReason != null) {
            // 조용히 돌아가지 않는다 — 재연결 루프가 왜 안 붙었는지 알 길이 없으면
            // 그것이 곧 조용한 실패다.
            log.warn("chat.session.start_skipped reason={}", skipReason);
            return false;
        }
        // <b>싱크를 걸기 직전이다.</b> 수신 시계를 여기서 다시 잡지 않고 아래
        // 절단 구간을 닫는 자리(수립 마무리 뒤)에서 잡으면, 그 사이에 온 첫 채팅이
        // 앞 세션의 마지막 수신과 짝지어져 끊겨 있던 시간이 수신 공백에도 실린다.
        metrics.beginSession();
        opening.onFrame(frame -> handleFrame(scope, frame));
        // 필드가 아니라 이 덩어리를 캡처해서 넘긴다. 콜백이 나중에 필드를 다시
        // 읽으면, 그때 자리에 있는 것은 이미 다음 세션일 수 있다.
        opening.onClosed(() -> handleClosed(scope));
        status.establishing();

        try {
            ChatSession.Established established =
                    opening.open(properties.establishTimeout(), () -> stopSignal.getCount() == 0);

            // 수립을 마치는 사이에 WS 스레드가 절단을 처리했으면 정리가 이미
            // 끝났다. 그 위에 스케줄러를 올리면 닫힌 소켓에 대고 ping을 쏘고,
            // 상태까지 COLLECTING으로 되돌리면 health는 UP인 채로 수집만 죽는다.
            // 정리 가드는 이미 소모돼 stop()도 아무것도 못 한다.
            if (scope.cleanedUp().get()) {
                // <b>우리가 연 것은 우리가 치운다.</b> 정리가 ②보다 먼저 지나갔으면
                // 그 정리는 아직 없던 소켓을 못 닫았고, ④보다 먼저 지나갔으면 아직
                // 없던 구독도 못 반납했다. 가드가 소모돼 아무도 다시 안 온다.
                releaseLate(scope);
                return false;
            }

            Heartbeat beat = Heartbeat.start(established.socket(), established.handshake(),
                    heartbeatListenerFactory.apply(no));
            // 값이 아니라 읽는 길을 넘긴다 — 삼킨 예외 수는 계속 늘어난다.
            // 세션은 이 덩어리의 것이라 바뀌지 않으므로 그쪽을 직접 읽는다.
            SummaryLogger logger = SummaryLogger.start(metrics, beat, SUMMARY_PERIOD,
                    opening::sinkFailureCount, persister, buffer::droppedCount, archive.counters());

            // <b>가드를 보는 것과 상태를 올리는 것이 한 덩어리여야 한다.</b> 위 이른
            // 검사만으로는 <b>스케줄러 둘을 세우는 동안이 통째로 창</b>이다 — 거기서
            // 정리가 지나가면 그 정리는 아직 빈 홀더를 읽고 가고, 우리는 그 위에
            // COLLECTING을 찍는다. 결과가 <b>정리 완료 + health UP + 죽은 소켓에 ping</b>
            // 이고, 가드는 이미 소모돼 stop()도 아무것도 못 한다 — 이 서비스의 유일한
            // 치명 실패다. 정리도 같은 락 안에서 가드를 소모하므로 둘 중 하나만 이긴다.
            //
            // <b>홀더도 이 안에서 채운다.</b> 락 밖에서 채우면 "정리가 널을 읽고 지나간
            // 뒤에 우리가 채우는" 순서가 그대로 남아 스케줄러 둘이 아무에게도 안 닫힌다.
            // 상태 전이보다 먼저 채우는 것도 같은 이유다 — 반대 순서면 이 뒤에 오는
            // 절단의 정리가 실행기를 못 보고 지나친다.
            //
            // <b>검사가 지키는 것은 가드를 다시 보는 쪽뿐이다.</b> 락이 막는 것은
            // 「가드 읽기와 전이 사이」의 몇 인스트럭션이라 붙잡을 I/O가 없어
            // 결정적으로 열 장치를 못 만들었다. "원리적으로 불가능"이 아니라
            // "관측 가능한 차이를 만들 손잡이가 없다"이다. 락을 떼도 빌드는 초록이니
            // 떼려거든 여기를 먼저 읽어라.
            boolean started;
            synchronized (scope) {
                if (scope.cleanedUp().get()) {
                    started = false;
                } else {
                    scope.heartbeat().set(beat);
                    scope.summaryLogger().set(logger);
                    scope.collectingSince().set(Instant.now());
                    started = status.collectingIfPending();
                }
            }

            if (!started) {
                // 우리가 올린 것은 우리가 내린다 — cleanUpOnce가 이미 지나갔다면
                // 아무도 안 내려 준다.
                beat.close();
                logger.close();
                scope.heartbeat().set(null);
                scope.summaryLogger().set(null);
                // <b>세션도 치운다.</b> 여기 오는 길이 셋이다 —
                // ① 락 안에서 본 가드가 이미 소모돼 있었다. 위 이른 검사가 대부분을
                //   거르지만 그 검사와 여기 사이의 창은 이쪽만 막는다. 이 호출은
                //   가드에 막혀 소켓만 닫는다
                // ② 검사와 전이 사이에 절단이 STOPPED를 찍었다(같은 모양이다)
                // ③ 이미 영구 정지(STOPPED)인 러너에 open()이 들어왔다. establishing()이
                //   더는 그 위를 안 덮으므로 수립은 끝까지 가는데 아무도 COLLECTING으로
                //   못 올린다. 안 치우면 <b>소켓도 자리도 통째로 샌다</b> — 구독은 서버에
                //   남아 상한 3개를 먹고, 자리가 안 비어 다음 세션이 영영 못 선다.
                //
                // ①②는 이 세션에 온 절단이 사유이고 ③은 러너가 멈춘 사유다.
                cleanUpOnce(scope, scope.endReason(status.reason()));
                return false;
            }
            // 다시 붙었다. 끊겨 있던 구간을 여기서 닫는다 — 지우기만 하면 그 시간이
            // 어느 지표에도 안 남고, 수신 공백에 섞인 채로 "한산했을 뿐"과 같아 보인다.
            // 절단 시각도 같이 비운다. 안 비우면 다음 절단이 남의 시각을 물려받는다.
            Instant since = disconnectedAt.getAndSet(null);
            if (since != null) {
                metrics.recordOutage(since, Instant.now());
            }
            log.info("chat.session.collecting pingIntervalMs={} sendPeriodMs={}",
                    established.handshake().pingInterval().toMillis(),
                    established.handshake().sendPeriod().toMillis());
            return true;
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
            // <b>여기서 status.stopped()를 찍지 않는다.</b> STOPPED는 안 덮이는
            // 상태라, 재시도해도 되는 실패(5xx·시한 초과)에 찍어 두면 재연결이
            // 붙어도 영영 못 올라온다. 재시도 여부는 사유를 받은 쪽이 정한다.
            //
            // <b>사유는 이 세션에 온 절단이 있을 때만 그쪽이다.</b> 절단이 먼저였다면
            // 그것이 원인이고 여기서 잡은 시한 만료는 그 결과다 — 결과가 원인을 덮으면
            // 추적이 끊긴다. 반대로 <b>{@code status}에서 읽으면 안 된다</b>: 재시도
            // 중에는 그 값이 늘 앞 시도의 사유라, 발급 5xx로 죽은 재시도가
            // {@code reason=TRANSPORT_CLOSED}로 기록돼 막힌 단계가 뒤바뀐다.
            cleanUpOnce(scope, scope.endReason(e.reason()));
            // ③④⑤에서 실패하면 소켓이 이미 열려 있다(구독 5xx · 핸드셰이크 파싱 실패 ·
            // connected 미도착 = 연결 상한 초과의 증상). 위에서 안 치우면 시도마다
            // 소켓·HttpClient·치지직 세션이 새고, 상한이 3개라 세 번째에 스스로 막힌다.
            throw e;
        }
    }

    /**
     * 하트비트가 알리는 두 사건을 운영 로그로 옮긴다. <b>태스크 5·6이 운영에
     * 붙는 유일한 지점이다.</b>
     *
     * <p><b>익명 클래스로 {@code open()} 안에 묻어 두지 않는다.</b> 거기 있는 동안은
     * 두 메서드를 빈 몸통으로 바꿔도 전 테스트가 초록이었다(CP2 실측) — 좀비 판정도
     * 송신 실패 분류도 통째로 사라질 수 있고 아무도 몰랐다.
     *
     * <p>좀비 쪽은 가짜 서버가 pong을 끊으면 러너를 통째로 지나 재현되지만,
     * <b>송신 실패 쪽은 루프백으로 못 만든다</b> — 전송이 끊기면 우리 뒷정리가
     * 하트비트를 먼저 닫으므로 "쓰기는 실패하는데 읽기 통지는 아직 안 왔다"는
     * 실제 망 사고의 순서가 같은 기계 안에서는 만들어지지 않는다. 이름을 준 것은
     * 그 한 갈래를 검사가 지나갈 수 있게 하기 위해서다.
     *
     * @param sessionNo 이 하트비트가 붙은 세션. <b>여기서 신호에 실어 보내지 않으면
     *                  늦게 도착한 좀비 판정이 이미 다시 붙은 세션을 헐어낸다</b> —
     *                  하트비트를 닫아도 이미 시작된 주기 하나는 끝까지 돈다
     */
    // 패키지가 갈려 public이다. 원래는 package-private이었고, 검사가 러너를 상속해
    // 갈아 끼우는 쪽은 여전히 러너의 package-private 메서드다 — 열린 것은 여기 본체뿐이다.
    public HeartbeatListener heartbeatListener(long sessionNo) {
        return new HeartbeatListener() {
            @Override
            public void onSendFailed(PingFailure.Cause cause) {
                log.warn("chat.session.ping_send_failed cause={}", cause);
                // MISUSE는 우리 버그다. 재연결로 덮으면 버그는 영영 안 보이고
                // 자리만 태우므로 사유를 갈라서 넘긴다.
                requestReconnect(sessionNo, cause == PingFailure.Cause.MISUSE
                        ? StopReason.SEND_MISUSE : StopReason.PING_SEND_FAILED);
            }

            @Override
            public void onPongTimeout(Duration gap) {
                log.warn("chat.session.pong_timeout gapMs={}", gap.toMillis());
                requestReconnect(sessionNo, StopReason.PONG_TIMEOUT);
            }
        };
    }

    /**
     * 끊겼다. <b>여기서는 아무것도 하지 않는다 — 요청만 큐에 넣는다.</b>
     *
     * <p>부르는 쪽이 WS 수신 스레드거나 ping 스케줄러다. <b>ping 스케줄러 위에서
     * 뒷정리를 돌리면 {@code beat.close()}의 {@code shutdownNow()}가 자기 스레드를
     * 인터럽트한다</b>(실측). 그러면 뒤따르는 구독 반납 REST가 즉시
     * {@code InterruptedException}으로 실패해 {@code Release.FAILED}가 되고, 우아한
     * 소켓 닫기도 같은 이유로 못 돌아 <b>abort만 남는다 = 급사 경로.</b> 서버가
     * 세션을 놓아주는 데 10초~4분 42초가 걸리고 상한이 3개라, 우리가 없애려던 것을
     * 우리가 만드는 셈이 된다.
     *
     * @param sessionNo 신호를 낸 세션. <b>지금 자리에 있는 세션의 것이 아니면 버린다</b> —
     *                  이미 치워진 세션의 늦은 신호가 살아 있는 새 세션을 헐어낸다
     */
    private void requestReconnect(long sessionNo, StopReason reason) {
        if (stopSignal.getCount() == 0) {
            return;                       // 우리가 멈추는 중이다. 뒷정리는 stop()이 한다
        }
        // <b>검사부터 상태 전이·루프 잡기까지가 한 덩어리여야 한다.</b> 갈라 놓으면
        // 검사를 통과한 신호가 그 사이에 붙은 새 세션을 헐고(자리 검사와 전이 사이),
        // 대기열에 넣은 신호가 방금 끝난 루프에 안 잡힌다(CAS와 쓰기 사이).
        // 락 안에서 하는 일은 원자 변수 읽고 쓰기뿐이다 — 로그도 실행기도 밖이다.
        boolean stale;
        boolean mine = false;
        synchronized (signalLock) {
            stale = !isCurrent(sessionNo);
            if (!stale) {
                // 비어 있을 때만 세운다. 이미 서 있으면 그것이 첫 절단이다.
                disconnectedAt.compareAndSet(null, Instant.now());
                // 상태를 먼저 내린다. 실행기에 넘기기만 하면 그 사이 COLLECTING(health UP)
                // 인데 소켓은 죽은 창이 생긴다 — "UP인데 수집 없음"이 이 서비스의 유일한
                // 치명 실패라 창을 안 만든다. STOPPED는 안 덮인다.
                status.reconnecting(reason, disconnectedAt.get(), status.attempt());
                mine = reconnectInFlight.compareAndSet(false, true);
                if (!mine) {
                    // 이미 한 루프가 돈다. <b>신호를 버리지 않고 남긴다</b> — 루프가
                    // 재접속에 성공하고 finally에 닿기까지의 창에 들어온 절단이 통째로
                    // 사라지면, 상태는 COLLECTING(health UP)인데 소켓은 죽어 있게 된다.
                    //
                    // 같은 세션이면 첫 사유가 이긴다(뒤엣것은 대개 그 결과다).
                    // 다른 세션이면 나중 세션이 이긴다 — 낡은 신호를 붙들고 새 신호를
                    // 버리면 방금 죽은 세션을 아무도 못 본다.
                    pendingSignal.accumulateAndGet(new ReconnectSignal(sessionNo, reason),
                            (current, incoming) -> current == null
                                    || current.sessionNo() < incoming.sessionNo()
                                    ? incoming : current);
                }
            }
        }
        if (stale) {
            // 낡은 신호다. 그 세션은 이미 치워졌고, 받아들이면 지금 붙어 있는
            // 세션을 헐어 구독을 반납하고 health를 DOWN으로 되돌린다.
            log.debug("chat.session.signal_stale session={} reason={}", sessionNo, reason);
            return;
        }
        if (!mine) {
            return;
        }
        try {
            reconnector.execute(() -> reconnectLoop(sessionNo, reason));
        } catch (RejectedExecutionException e) {
            // 위 검사와 여기 사이에 stop()이 들어왔다. handleClosed는 JDK WS 콜백
            // 안이라 여기서 새면 예외가 콜백 밖으로 나간다.
            // 그 세션의 뒷정리는 stop()이 이미 했거나 곧 한다.
            reconnectInFlight.set(false);
        }
    }

    /** 그 번호가 지금 자리에 있는 세션의 것인가. 자리가 비어 있으면 "아직 세션 없음"이다. */
    private boolean isCurrent(long sessionNo) {
        SessionScope seated = activeSession.get();
        return seated == null ? sessionNo == NO_SESSION : seated.no().get() == sessionNo;
    }

    /**
     * 뒷정리하고, 재시도해도 되는 사유면 간격을 늘려 가며 다시 붙는다.
     *
     * <p><b>이 스레드에서 뒷정리를 한다.</b> 신호를 낸 콜백 스레드에서 하면 위
     * {@link #requestReconnect}의 인터럽트가 난다.
     */
    private void reconnectLoop(long sessionNo, StopReason firstReason) {
        SessionScope from = activeSession.get();
        if (from != null && from.no().get() == sessionNo) {
            cleanUpOnce(from, firstReason);
        }
        StopReason reason = firstReason;
        int attempt = 0;
        try {
            while (stopSignal.getCount() > 0) {
                // <b>밀린 영구 신호를 다음 시도 앞에서 반영한다.</b> 수립 중인 세션이
                // revoked를 내면 루프가 이미 돌고 있으므로 그 신호는 대기 사유로 앉는데,
                // 같은 수립이 5xx나 시한 만료로 던지면 아래에서 사유가 그 일시 오류로
                // 덮인다. 대기 사유는 {@code finally}에서만 읽히고 <b>시도가 계속
                // 실패하면 거기 영영 안 닿는다</b> — 동의가 철회됐는데도 재시도가
                // 이어지고, 나중에 번호가 큰 세션이 그 자리를 덮어써 철회 사실이
                // 통째로 사라진다. 다시 붙어도 서버가 구독을 또 취소하므로 그 재시도는
                // 연결 상한 3개만 태운다.
                reason = adoptPendingPermanent(reason);
                if (status.state() == CollectionStatus.State.STOPPED) {
                    // 이미 영구 정지다. 판정은 그때 나갔다.
                    return;
                }
                if (!ReconnectPolicy.retriable(reason)) {
                    // 수집이 다시 시작될 가능성이 없어졌다.
                    // 판정이 나가야 할 두 시점 중 하나다(다른 하나는 stop()).
                    log.warn("chat.session.stopped reason={} retriable=false attempt={}",
                            reason, attempt);
                    status.stopped(reason);
                    // <b>그 뒤는 세션이 정하지 않는다.</b> 잔량 회수·판정·프로세스 종료는
                    // 전부 프로세스 단위의 일이라 여기서 부르면 <b>한 스트리머의 동의 철회가
                    // 나머지 전원의 수집을 끊는다.</b> 손잡이를 세션 안에 안 두는 것이
                    // 그것을 구조적으로 막는 유일한 방법이다 — 있으면 언젠가 불린다.
                    // 지금 넘어가는 곳은 CollectorRunner.onPermanentStop이고, 거기 동작은
                    // 옮기기 전과 한 글자도 다르지 않다(잔량 대기 → 판정 → exit 1).
                    onPermanentStop.accept(reason);
                    return;
                }
                attempt++;
                status.reconnecting(reason, disconnectedAt.get(), attempt);
                Duration delay = policy.delayFor(attempt);
                try {
                    if (stopSignal.await(delay.toMillis(), TimeUnit.MILLISECONDS)) {
                        return;           // 종료가 대기를 깨웠다
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                log.info("chat.session.reconnecting attempt={} afterMs={} lastReason={}",
                        attempt, delay.toMillis(), reason);
                try {
                    if (open()) {
                        return;           // 붙었다. 다음 절단은 새 루프가 받는다
                    }
                    // 자리를 못 잡았거나 수립 직후에 끊겼다. 사유는 상태가 든다.
                    // <b>한 번만 읽는다.</b> 두 번 읽으면 그 사이에 다시 붙어 사유가
                    // 비워질 수 있고, 그러면 널이 아닌 것을 확인하고 널을 집는다.
                    StopReason lastReason = status.reason();
                    reason = lastReason == null ? StopReason.TRANSPORT_CLOSED : lastReason;
                } catch (SessionEstablishException e) {
                    reason = e.reason();  // open()이 던지기 전에 자기 세션을 이미 치웠다
                }
            }
        } finally {
            // 성공이든 종료든 여기서 푼다. 안 풀면 다음 절단에 루프가 영영 안 돈다.
            // 그리고 내가 도는 동안 들어온 신호를 집어 간다.
            //
            // <b>둘이 같은 락 안이어야 한다.</b> 갈라 놓으면 「CAS 실패 → 쓰기」의
            // 사이에 이 둘이 통째로 지나가는 순서가 생기고, 그때 쓰인 신호는 누구도
            // 안 읽는다 — 상태는 RECONNECTING(health DOWN)에 멈추고 ping·pong 쪽
            // 재알림은 구간당 1회 래치에 막혀 오지 않아 수집이 영구히 멈춘다.
            // 락 밖에서 재확인만 더하는 형태로는 안 닫힌다(signalLock 주석 참고).
            ReconnectSignal missed;
            synchronized (signalLock) {
                reconnectInFlight.set(false);
                missed = pendingSignal.getAndSet(null);
            }
            // <b>그 세션이 아직 자리에 있을 때만 재생한다.</b> 이미 치워진 세션의
            // 신호를 재생하면 방금 붙은 세션을 헐어낸다. STOPPED에서도 재생하지
            // 않는다 — retriable한 사유가 남아 있으면 만료 토큰으로 영원히 두드린다.
            //
            // <b>이 세 조건은 지금 아무 검사도 안 지킨다.</b> 셋 다 requestReconnect가
            // 입구에서 다시 보므로(stopSignal · isCurrent · 루프의 STOPPED 분기)
            // 여기서 떼어도 관측 가능한 차이가 없다 — 실제로 떼고 149건 전부 초록임을
            // 확인했다. 여기 남겨 두는 것은 이 블록만 읽고도 무엇이 재생되는지
            // 알 수 있게 하려는 것이고, <b>다음 사람이 이것을 검사받는 방어로
            // 읽지 않도록</b> 적어 둔다.
            //
            // 재생 자체를 지워도(pendingSignal을 통째로 버려도) 초록이다.
            //
            // <b>창이 좁아서가 아니다.</b> 창은 <b>루프의 open() 호출 전체</b>다 —
            // onClosed가 자리를 잡은 직후부터 살아 있어 수립 중 절단이 곧장 여기 앉는다.
            // 가짜 서버가 subscribed를 쏜 뒤 끊고 구독 응답을 붙들면 그 창이 결정적으로
            // 200ms 열린다(CP4가 그렇게 만들어 확인했다).
            //
            // <b>초록인 진짜 이유는 하트비트가 같은 죽음을 다시 알려 주기 때문이다.</b>
            // 첫 ping이 initialDelay=0이라 죽은 소켓에 즉시 나가 실패하고, 그 실패 통지가
            // 위 set(false)보다 늦게 도착하면 스스로 CAS를 이겨 새 루프를 띄운다.
            //
            // <b>그런데 그 재알림에는 래치가 있다.</b> Heartbeat.sendFailureReported가
            // 구간당 1회라, 재알림이 set(false)보다 <b>먼저</b> 도착하는 순서에서는
            // 그것마저 pending에 앉고 그 뒤로는 어떤 신호도 안 나온다 —
            // COLLECTING(health UP)인 채로 죽은 소켓이 영구히 남는다.
            // <b>그 순서에서는 이 재생이 유일한 방어다. 지우지 마라.</b>
            //
            // 검사를 못 붙인 이유는 장치가 없어서가 아니라 <b>고정할 지점이 없어서</b>다.
            // 그 순서를 만들려면 첫 ping 실패 보고를 이 finally보다 앞에 세워야 하는데,
            // Heartbeat.start()와 여기 사이에 붙잡을 I/O가 하나도 없다.
            if (missed != null && stopSignal.getCount() > 0
                    && status.state() != CollectionStatus.State.STOPPED
                    && isCurrent(missed.sessionNo())) {
                requestReconnect(missed.sessionNo(), missed.reason());
            }
        }
    }

    /**
     * 밀린 신호가 <b>재시도로 안 풀리는 사유</b>면 그것을 이번 판단의 사유로 삼는다.
     * 일시 사유면 아무것도 안 한다 — 그쪽은 {@code finally}의 재생이 맡는다.
     *
     * <p><b>세션 번호로 안 거른다.</b> 여기서 집는 것들(REVOKED · 401/403 · 우리 버그)은
     * 세션이 아니라 <b>토큰과 코드에 붙은 사실</b>이라 다음 시도에도 그대로 있다.
     * 낡은 신호를 거르는 규칙은 <b>살아 있는 세션을 헐지 않기</b> 위한 것인데, 이
     * 자리는 시도가 실패한 뒤라 헐 세션이 없다 — 붙은 경우는 위에서 이미 돌아갔다.
     *
     * <p>집어 간 신호는 지운다. 사유로 이미 반영했으므로 남겨 두면 {@code finally}가
     * 같은 절단을 한 번 더 재생하는 셈이 된다.
     */
    private StopReason adoptPendingPermanent(StopReason reason) {
        ReconnectSignal pending = pendingSignal.get();
        if (pending == null || ReconnectPolicy.retriable(pending.reason())) {
            return reason;
        }
        pendingSignal.compareAndSet(pending, null);
        return pending.reason();
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
        log.warn("chat.session.closed reason={}", StopReason.TRANSPORT_CLOSED);
        // <b>이 세션의 끝은 절단이다.</b> 아래 요청이 낡은 신호로 걸러지거나 종료
        // 중이라 버려져도, 이 세션을 치우는 쪽은 여전히 이 사유로 줄을 남겨야 한다.
        scope.cutReason().compareAndSet(null, StopReason.TRANSPORT_CLOSED);
        // 사유가 이미 찍혀 있어도(먼저 온 신호가 STOPPED를 남겼어도) 요청은 넘긴다.
        // <b>사유를 안 덮는 것과 뒷정리를 안 하는 것은 다른 일이다</b> — 여기서
        // 통째로 돌아가면 이 세션이 자리를 문 채 남아 구독 반납도 못 하고
        // 다음 세션이 영영 못 선다. 사유를 덮는 것은 status가 막고,
        // 영구 정지에서 재시도하지 않는 것은 루프가 막는다.
        requestReconnect(scope.no().get(), StopReason.TRANSPORT_CLOSED);
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
            if (intakeClosed.get()) {
                // 종료의 마무리 flush가 시작됐다. 세지도 담지도 않는다 — 세기만 하면
                // 등식이 그만큼 벌어지고, 담으면 flush가 끝을 못 본다. 이 채팅은
                // 어차피 프로세스가 내려가며 잃는 것이다(소켓을 먼저 닫아도 같다).
                return;
            }
            long receivedAt = System.currentTimeMillis();
            metrics.recordMessage(message, receivedAt);
            // 넣기만 한다. 여기서 I/O를 하면 이 스레드(WS 수신)가 붙들려
            // 채팅 폭주 때 수신이 밀린다 — 저장은 chzzk-persist 스레드가 한다.
            buffer.offer(new PersistableChat(message.channelId(), message.senderChannelId(),
                    message.content(), message.messageTimeMillis(), receivedAt));
            // 원본도 넣기만 한다 — 인코드·창·업로드는 전부 아카이브 스레드 몫이다.
            archive.offer(new ArchivableChat(message.channelId(), receivedAt, message.raw()));
            return;
        }

        SystemEvent event = ChatEventDecoder.decodeSystem(frame.payload());
        if (event != null) {
            metrics.recordSystemEvent(event.type());
            if ("revoked".equals(event.type())) {
                log.warn("chat.session.revoked");
                // <b>여기서 멈추지 않으면 COLLECTING(health UP)인 채로 채팅만 안 온다.</b>
                // 구독이 서버 쪽에서 취소된 것이라 소켓은 멀쩡하고 onClose도 안 온다 —
                // 감지원 셋(전송 절단 · pong 임계 · ping 송신 실패) 중 무엇도 안 걸린다.
                //
                // <b>재연결 요청으로 보내는 것이 맞다.</b> 이름과 달리 이 호출은
                // "절단 신호"이고, 루프는 백오프에 들어가기 전에 사유를 먼저 보므로
                // REVOKED에서는 재시도가 0회다. 즉시 멈추는 별도 경로를 만들면 뒷정리·
                // 판정·중복 가드·낡은 신호 거르기를 통째로 복제해야 하고, 그 사본이
                // 갈리는 순간 어느 쪽도 안 도는 조용한 실패가 된다.
                //
                // 여기는 WS 수신 콜백이라 무거운 일을 하지 않는다 — 요청만 넣는다.
                scope.cutReason().compareAndSet(null, StopReason.REVOKED);
                requestReconnect(scope.no().get(), StopReason.REVOKED);
            }
            return;
        }
        // CHAT도 SYSTEM도 아니게 깨진 것. 본문을 붙이지 않는다 — 깨진 JSON이 곧 본문이다.
        metrics.recordDecodeFailure();
    }

    /**
     * 프로세스 종료가 이 세션을 닫는다. <b>자리에 세션이 없으면 아무것도 안 한다.</b>
     *
     * <p>원래 {@code stop()} 한가운데 있던 세 줄이다. 순서를 그대로 지킨다 —
     * 러너는 이 호출 <b>앞에서</b> 수신 게이트를 내리고 싱크를 닫고 실행기를 인터럽트하며,
     * <b>뒤에서</b> 판정 줄을 낸다. 여기로 옮기면서 그 앞뒤를 바꾸지 않았다.
     */
    public void close() {
        SessionScope scope = activeSession.get();
        if (scope != null) {
            // <b>이 세션에 온 절단이 있으면 그것이 이 세션의 끝이다.</b> 멈추는 중에
            // 도착한 절단은 신호가 입구에서 버려지므로(뒷정리는 여기서 한다)
            // {@code status}에 한 글자도 안 남는다 — 거기서만 읽으면 끊겨서 죽은
            // 세션이 {@code reason=SHUTDOWN}으로 기록되고, 로그만 보는 사람은
            // 그 방송이 우아하게 끝났다고 읽는다.
            cleanUpOnce(scope, scope.endReason(status.reason()));
        }
    }

    /**
     * <b>정리가 이미 지나간 뒤에 성립한 것을 치운다.</b> 소켓만이 아니라 구독까지다.
     *
     * <p>수립은 ②(소켓)와 ④(구독)를 서로 다른 시점에 만든다. 정리가 그 사이를
     * 지나가면 <b>정리는 아직 없던 구독을 못 반납하고, 가드를 소모했으므로 아무도
     * 다시 오지 않는다</b> — 그 구독은 서버에 남아 상한 3개 중 하나를 먹는다.
     * {@code close()}만 부르면 정확히 그 상태가 된다.
     *
     * <p>{@code releaseAndClose()}는 세션 키를 {@code getAndSet(null)}로 집으므로
     * 앞선 정리가 이미 반납했으면 {@code SKIPPED}로 지나가고 소켓만 닫는다.
     * 즉 이 호출이 늘어도 반납 REST가 두 번 나가지 않는다.
     *
     * <p><b>결말이 {@code SKIPPED}가 아닐 때만 줄을 남긴다.</b> 그 경우가 곧
     * "정리가 못 본 구독이 실제로 있었다"이고, 평상시에는 한 줄도 안 늘어
     * 세션당 반납 줄이 하나라는 기존 모양이 그대로다.
     *
     * <p><b>{@code close()}로 되돌리면 빨간불이다</b> —
     * {@code 정리가_지나간_뒤에_생긴_구독도_반납한다}가 반납 1건을 기대하고 0건을 본다.
     * 창은 {@code ChatSession.open()}이 {@code connected}를 받고 깨어나 세션 키를
     * 세우기까지의 몇 줄이고, 그 사이에 절단 정리가 통째로 지나가면 열린다.
     * 그 몇 줄에 붙잡을 I/O가 없어 밖에서 끊는 것만으로는 순서가 실행마다 갈리므로,
     * 검사가 {@link ChatSession#beforeSessionKey()}를 장벽으로 써서 정리가 끝난 것을
     * 보고서야 키를 세우게 한다.
     *
     * <p><b>🔴 {@code late=true} 줄이 항상 실재하는 누수를 뜻하지는 않는다.</b> 종료와
     * 겹쳐 이 갈래에 들어오면 스레드에 인터럽트 플래그가 서 있어 <b>구독 요청부터 실패하고,
     * 뒤이은 반납 REST는 서버에 도착조차 안 한다</b>(5라운드 6/6 실측 — 서버 쪽 반납 수신이
     * 늘지 않았다). 그런데도 결말이 {@code FAILED}라 이 줄이 나간다. 운영자가 이것을
     * "상한 3개 중 하나가 남았다"로 읽으면 <b>없는 자리를 쫓는다</b> — 바로 앞줄
     * {@code chat.session.stopped stage=SUBSCRIBE}가 그 정황을 준다. 갈라 싣는 것이
     * 맞지만, 그러려면 {@code ChatSession}이 왕복이 실제로 나갔는지를 알려 줘야 한다.
     */
    private void releaseLate(SessionScope scope) {
        ChatSession.Release released = releaseAndClose(scope);
        if (released != ChatSession.Release.SKIPPED) {
            log.info("chat.session.released session={} subscription={} late=true",
                    scope.no().get(), released);
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
        // <b>수립 마무리가 같은 락 안에서 이 가드를 읽고 상태를 올린다.</b> 락이
        // 없으면 "가드는 아직 비었는데 홀더도 아직 비어 있는" 순간에 둘이 엇갈려,
        // 우리가 못 본 스케줄러 둘이 그대로 남고 health는 UP으로 올라간다.
        // 락 안에서 하는 일은 이 CAS 하나뿐이다 — 여기에 I/O를 들이지 마라.
        boolean mine;
        synchronized (scope) {
            mine = scope.cleanedUp().compareAndSet(false, true);
        }
        if (!mine) {
            // <b>가드에 막혀도 뒤늦게 생긴 것은 치운다.</b> 앞선 정리가 지나간
            // <i>뒤에</i> ②가 성립시킨 소켓과 ④가 만든 구독은 그 정리가 못 봤고,
            // 여기서 안 치우면 아무도 안 치운다 — 서버 쪽 자리가 죽은 전송을
            // 알아챌 때까지(10초~4분 42초) 남고 상한은 3개다.
            // 판정 두 줄만 가드가 막으면 된다.
            releaseLate(scope);
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
        Duration maxPingGap = beat == null ? Duration.ZERO : beat.maxPingGap();
        Duration maxPongGap = beat == null ? Duration.ZERO : beat.maxPongGap();
        long sendFailures = beat == null ? 0L : beat.sendFailureCount();
        metrics.recordSessionEnd(
                since == null ? Duration.ZERO : Duration.between(since, Instant.now()),
                maxPingGap, maxPongGap, sendFailures,
                beat == null ? 0L : beat.callbackFailureCount(),
                scope.chat().sinkFailureCount());
        if (beat != null) {
            beat.close();
        }

        // <b>판정은 여기서 안 부른다. 절단은 더 이상 끝이 아니다.</b>
        // 이 줄이 세션 하나의 끝을 표시하고, 판정은 수집이 영영 끝났을 때만 나간다.
        //
        // 세션별 하트비트 값을 여기 싣는다 — 판정 줄은 프로세스 누계라 "몇 번째
        // 세션에서 ping이 막혔나"를 못 보여준다. PRD가 세션별 진단을 이 줄에
        // 두기로 한 이유다. 값은 위에서 걷어 둔 것을 쓴다 — 닫은 하트비트에서
        // 다시 읽는 코드를 남기지 않는다. 소켓을 닫는 것보다 먼저다:
        // 닫다가 터져도 이 줄은 남는다.
        if (status.state() != CollectionStatus.State.DISABLED) {
            log.info("chat.session.ended session={} reason={} maxPingGap={}ms maxPongGap={}ms"
                            + " sendFailures={}",
                    scope.no().get(), reason == null ? "SHUTDOWN" : reason,
                    maxPingGap.toMillis(), maxPongGap.toMillis(), sendFailures);
        }

        // 구독을 반납하고 끊는다고 알린다. 안 하면 세션 반납이 우리가 아니라
        // 서버가 죽은 전송을 알아채는 때에 달리고, 실측에서 10초와 4분 42초로
        // 갈렸다. 연결 상한이 3개라 짧은 간격의 재시작 세 번이면 막힌다.
        ChatSession.Release released = releaseAndClose(scope);
        log.info("chat.session.released session={} subscription={}", scope.no().get(), released);
    }

    /**
     * <b>구독 반납 왕복이 나가는 유일한 자리.</b> 부르는 자리가 둘이라 여기로 모았다 —
     * 정리 본체와 {@code releaseLate}가 같은 왕복을 서로 다른 보호 수준으로 보내면
     * 한쪽만 보호 밖이 되고, 그 실패는 조용하다. 자리를 다시 늘리지 마라.
     *
     * <p>인터럽트되면 반납 REST가 즉시 실패하는데, 세션 키는
     * {@code ChatSession.releaseAndClose()}가 이미 걷어 간 뒤라 <b>아무도 다시 못 보낸다</b> —
     * 서버 쪽 자리가 10초~4분 42초 남고(실측) 상한은 3개다.
     *
     * <p><b>🔴 이 카운터가 지키는 범위는 좁다. 넓게 읽지 마라.</b> {@code stop()}은 이 값을
     * {@code awaitTermination(SHUTDOWN_WAIT)}가 만료한 <b>그 시점에 한 번만</b> 읽는다.
     * 그러니 지켜지는 것은 <b>그때 이미 나가 있던 왕복</b>뿐이다. {@code releaseLate}의
     * 왕복은 <b>정의상 그 뒤에 시작한다</b> — 가드를 남이 먹은 순서에서만 거기 닿고,
     * 재연결 스레드 기준 그 "남"은 {@code stop()}뿐이며 {@code stop()}은
     * {@code shutdownNow()} <b>뒤에</b> 가드를 먹는다. <b>그 갈래는 이 보장 밖이다.</b>
     *
     * <p><b>🔴 이 카운터를 떼도 전수 초록이다</b> — {@code releaseLate}를 무보호로 되돌려도,
     * {@code decrementAndGet()}을 {@code set(0)}(불리언 시절 의미)으로 바꿔도 175건 전부
     * 초록이다(5라운드 실측, 각각 단독 변이). <b>검사받는 방어로 읽지 마라.</b> 남겨 두는
     * 것은 두 자리가 대칭이라 싸고, 나노초 창에서는 실제로 도움이 되기 때문이다.
     *
     * <p>보장 밖인 그 갈래에서 무엇이 일어나는지는 5라운드에 결정적으로 쟀다(6/6) —
     * <b>인터럽트된 스레드는 어떤 REST도 못 내보내므로 「정리 뒤에 생긴 구독」이 서버에
     * 아예 안 생긴다.</b> 구독 요청이 먼저 실패하기 때문이다. <b>즉 여기서 막을 누수가
     * 실재하지 않는다</b> — 이 자리를 넓히려 들기 전에 그 사실부터 다시 재라.
     */
    private ChatSession.Release releaseAndClose(SessionScope scope) {
        releasesInFlight.incrementAndGet();
        try {
            return scope.chat().releaseAndClose();
        } finally {
            releasesInFlight.decrementAndGet();
        }
    }
}
