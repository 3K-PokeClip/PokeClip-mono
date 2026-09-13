package com.pokeclip.clip.jumpcard.stream;

import com.pokeclip.clip.broadcast.intake.EndedListener;
import com.pokeclip.clip.jumpcard.JumpCardErrors.StreamLimitExceededException;
import com.pokeclip.clip.jumpcard.JumpCardSnapshot;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 열려 있는 연결을 들고 있다가 방송별로 이벤트를 밀어 넣는다.
 *
 * <p><b>{@link #publish}가 「카드가 생겼다/바뀌었다」를 알리는 유일한 출구다.</b> 여러 곳에
 * 흩어지면 나중에 여러 대(Redis)로 갈 때 갈아끼울 자리를 못 찾는다. 지금은 구현이 하나뿐이라
 * 인터페이스를 미리 뽑지 않는다 — 껍데기만 남는다.
 */
@Component
public class CardStreamRegistry implements EndedListener {

    private static final Logger log = LoggerFactory.getLogger(CardStreamRegistry.class);

    /** 연결 하나. {@code seq}가 스트라이프를 정해 같은 연결의 이벤트 순서가 지켜진다. */
    private record Conn(long seq, String streamId, String userId, SseEmitter emitter, WriteGate gate) {
        int stripe() {
            return (int) (seq % Integer.MAX_VALUE);
        }
    }

    /**
     * 🔴 <b>쓰기가 이만큼 넘게 안 끝난 연결은 막힌 것으로 보고 건너뛴다</b>(POK-234 main 결정).
     *
     * <p><b>왜 필요한가 — 채팅 줄을 갈라도 카드 줄이 막혔다.</b> 줄(스레드)은 갈랐지만 <b>소켓과 emitter 쓰기 자물쇠는
     * 연결당 하나</b>다. 안 읽는 구독자 A에게 채팅이 TCP 버퍼를 채우면 채팅 스레드가 A의 자물쇠를 쥔 채 멈추고,
     * 카드 발행 때 A 몫 카드 job이 <b>카드 스레드</b>에서 그 자물쇠를 기다린다. 같은 카드 스트라이프의 멀쩡한 B는
     * 그 뒤에 선다 — 실측 <b>65,403 · 65,087 · 65,089 · 65,020ms</b>(2026-09-13, 스트라이프 1·큐 5, 하트비트를 1시간으로
     * 없애도 같았다). 그 60여 초는 톰캣 커넥터 {@code connectionTimeout}(기본 60초 —
     * {@code AbstractHttp11Protocol}이 {@code Constants.DEFAULT_CONNECTION_TIMEOUT}으로 두고
     * {@code NioEndpoint.setSocketOptions}가 그 값을 소켓 write timeout으로 건다. 우리는
     * {@code server.tomcat.connection-timeout}을 안 준다)이 막힌 쓰기를 {@code IOException}으로 풀 때까지다.
     * 나머지 약 5초는 채팅이 버퍼를 채운 시간과 톰캣 감시 주기로 보이는데 <b>항을 나눠 재지 않았다</b>.
     *
     * <p><b>왜 1초인가.</b> 정상 쓰기는 ms 미만이다 — 같은 날 실측으로 연결 100·초당 50건×5초(표본 25,000)에서
     * 보낸 뒤 받기까지 p50 7ms · p95 16ms · 최대 27ms였고 그것도 HTTP 왕복을 포함한 값이다. 1초면 수십 배 여유다.
     * 설정값으로 빼지 않는다 — 운영자가 조정할 이유가 생긴 적이 없다.
     *
     * <p>🔴 <b>「쓰는 중이면 건너뜀」만으로는 안 된다.</b> 채팅이 초당 수백 건이면 <b>멀쩡한 연결</b>에서도 카드 job 순간에
     * 채팅 쓰기가 겹친다 — 그 카드를 잃는다({@code ChatStreamEndToEndTest.멀쩡한_연결은…}이 잰다).
     */
    static final Duration STUCK_THRESHOLD = Duration.ofSeconds(1);

    /**
     * 연결 하나의 쓰기 문. <b>emitter에 쓰는 길은 {@link #send} 하나뿐이다</b>({@code SseSendSiteTest}가 소스에서 센다).
     *
     * <p>🔴 <b>main 결정에서 한 가지를 더했다 — 우리 자물쇠를 {@link #STUCK_THRESHOLD}까지만 기다린다.</b>
     * 「1초 넘게 쓰는 중이면 건너뜀」만 두면 <b>막히기 시작하고 1초가 안 된 사이</b>에 온 job(카드·ping)이 판정을 통과해
     * emitter 자물쇠에서 60초를 기다리고, 같은 스트라이프가 그대로 막힌다. 안 읽는 연결에 채팅이 막 막힌 순간 카드가
     * 발행되는 것이 바로 그 경우다 — 우리 자물쇠를 빼고 1초 판정만 남기는 주입에서
     * {@code ChatStreamBackpressureTest.채팅이_큐를_채워도_같은_스트라이프_카드는_간다}가 3회 중 3회 빨간불이었다(건너뜀 0). 그래서 쓰기 전에 우리 자물쇠를
     * 먼저 잡고, 1초 안에 못 잡으면 건너뛴다. emitter 자물쇠를 직접 기다리지 않는다.
     *
     * <p><b>공정 자물쇠로 둔다.</b> 채팅 스레드는 이벤트마다 풀고 곧바로 다시 잡는다. 비공정이면 그 틈을 채팅이 계속
     * 새치기해 카드 스레드가 1초를 굶고 <b>멀쩡한 연결에서 카드를 건너뛸 수 있다</b>. 🔴 <b>재현하지 못했다</b> — 비공정으로
     * 바꾸는 주입이 폭주 시험(초당 1만 건대)에서 초록이었다. 채팅 job 사이에 HTTP 왕복만큼 틈이 있어 새치기가 1초를
     * 못 이어 간 것으로 보인다. 공정의 대가는 자물쇠를 넘겨줄 때의 깨우기 비용뿐이라 그대로 둔다. 시한 있는
     * {@code tryLock}은 공정성을 지키고 시한 없는 {@code tryLock()}은 공정 자물쇠에서도 새치기한다(JDK 문서).
     *
     * <p><b>남는 한계 둘</b>(README clip 절에도 적는다) —
     * ① A의 버퍼가 <b>이미 찼는데</b> 채팅이 잠잠한 틈에 카드·ping이 먼저 자물쇠를 잡고 쓰기를 시작하면 그 job 자체가
     * 소켓에서 막힌다. 건너뛸 판정은 쓰기 <b>전</b>에만 하므로 같은 카드 스트라이프가 write timeout(약 60초)만큼 늦는다.
     * ② 판정을 통과한 직후에 막히기 시작하는 쓰기도 같다. 둘 다 「첫 번째로 막힌 쓰기」는 못 건너뛴다는 한 가지다.
     */
    static final class WriteGate {

        private final ReentrantLock lock = new ReentrantLock(true);

        /** 자물쇠를 쥔 쪽이 {@code send} 바로 앞에서 세우고 끝나면 0. 0은 「쓰는 중 아님」이다. */
        private volatile long writeStartedNanos;

        /** @return 보냈으면 {@code true}, 막힌 연결이라 건너뛰었으면 {@code false} */
        boolean send(SseEmitter emitter, SseEmitter.SseEventBuilder event) throws Exception {
            long started = writeStartedNanos;
            if (started != 0 && System.nanoTime() - started > STUCK_THRESHOLD.toNanos()) {
                return false;   // 이미 1초 넘게 막혔다 — 기다리지도 않는다(막힌 연결 몫 job이 쌓여도 줄이 안 밀린다)
            }
            if (!lock.tryLock(STUCK_THRESHOLD.toNanos(), TimeUnit.NANOSECONDS)) {
                return false;
            }
            try {
                writeStartedNanos = System.nanoTime() | 1;   // 0은 「쓰는 중 아님」 표식이라 피한다
                emitter.send(event);
                return true;
            } finally {
                writeStartedNanos = 0;
                lock.unlock();
            }
        }
    }

    private final Map<SseEmitter, Conn> conns = new ConcurrentHashMap<>();

    /**
     * 방송별 · <b>카드별</b> 마지막 발행 순번. {@link #publish}가 이보다 낮거나 같은
     * {@code eventSeq}를 버리는 데 쓴다.
     *
     * <p>🔴 <b>카드별이어야 한다.</b> {@code jump_card_event_seq}는 방송별이 아니라
     * <b>전역 시퀀스</b>다(V202). 연결이나 방송 단위로 워터마크 하나만 두면 <b>다른 카드</b>의
     * 변경이 그 값을 올려서, 늦게 도착한 카드의 갱신을 「이미 더 큰 번호가 갔다」는 이유로
     * <b>영영 버린다</b> — 그 카드가 화면에 낡은 채로 굳는다.
     *
     * <p>바깥 맵을 방송으로 가르는 이유는 <b>지울 자리를 만들기 위해서다</b>. 카드별 맵만 두면
     * 무엇을 버려도 되는지 알 수 없어 프로세스 수명 동안 자란다.
     *
     * <p><b>평범한 {@code HashMap}이다.</b> 만지는 곳이 {@code publish}·{@code broadcastEnded}·
     * {@link #sweepIdleStreams} 셋뿐이고 <b>전부 같은 자물쇠 안</b>이라 동시 접근이 없다.
     * <b>연결 정리 콜백({@code conns.remove})은 자물쇠 밖에서 도니 여기를 만지면 안 된다.</b>
     *
     * <p><b>수명은 「그 방송에 연결이 살아 있는 동안」이다.</b> 이 표가 막는 것은 <b>열려 있는
     * 연결</b>에 낡은 값이 나가는 것이고, {@link #publish}는 {@code streamId}가 같은 연결에만
     * 보낸다. 연결이 하나도 없는 방송의 항목은 그 순간 아무것도 지키지 않으므로
     * {@link #sweepIdleStreams}가 하트비트마다 버린다.
     *
     * <p>🔴 <b>{@link #broadcastEnded}에만 맡길 수 없다.</b> 그 자리는 SQS 생명주기 이벤트로만
     * 불리는데 {@code BROADCAST_INTAKE_ENABLED}의 <b>기본값이 {@code false}</b>다. 통로가 꺼진
     * 배포에서는 <b>한 번도 안 불려</b> 모든 방송·모든 카드의 항목이 프로세스 수명 동안 쌓인다 —
     * 설계 전제(동시 방송 100 × 카드 300)면 하루 3만 항목이다. 전에 이 자리에 「항목 몇
     * 개짜리라 두고 본다」고 적혀 있었는데, 그 근거가 성립하지 않는다.
     *
     * <p><b>버린 뒤 새 연결이 붙어도 되는 근거</b> — 🔴 <b>POK-174가 이 근거를 갈아 끼웠다.</b>
     * 전에는 새 연결이 {@link #openWithSnapshot}의 DB 스냅샷으로 <b>그 방송 카드의 커밋된 상태를
     * 전부</b> 들고 시작해서 「표가 비어 있어도 뒤로 갈 자리가 없다」였다. 지금 그 스냅샷은
     * {@code ended} 하나뿐이라 <b>그 근거는 사라졌다.</b> 지금 서는 것은 <b>순서</b>다 —
     * 화면은 통로를 연 <u>뒤에</u> 카드 목록 문을 부르므로(「통로 먼저, 목록 나중」), 빈 표를
     * 지나 통로로 나간 낡은 발행은 <b>뒤에 오는 목록 응답이 덮는다</b>
     * (카드는 사건의 나열이 아니라 상태다).
     *
     * <p><b>남는 창 하나 — 재현하지 않았다.</b> {@code publish}는 트랜잭션 <b>안</b>에서 읽은
     * 스냅샷을 {@code afterCommit}에서 낸다({@code JumpCardService.publishAfterCommit}). 그래서
     * 「커밋은 됐는데 {@code afterCommit}이 아직 안 돈」 발행이 <b>목록 응답보다 늦게</b> 닿으면
     * 덮을 것이 없다. 열리려면 그 창 안에 <b>같은 카드의 더 큰 순번이 이미 커밋되고 · 그 방송
     * 연결이 전부 끊기고 · 쓸기가 돌고 · 새 연결이 붙어야</b> 한다.
     * 🔴 <b>창의 크기는 안 쟀다</b> — 화면이 목록을 언제 부르는지가 2번(web) 몫이라 모른다.
     *
     * <p>🔴 <b>표를 미리 채워 그 창을 막으려 하지 마라 — 대칭인 창이 열린다.</b>
     * 표는 방송별로 <b>공유</b>인데 채우는 재료는 <b>연결별</b>이다. 먼저 붙어 있던 연결이 아직
     * 못 본 순번을 새 연결 쪽 값이 채우면 뒤이은 {@code publish}가 「이미 보냈다」로 버려져
     * <b>먼저 붙은 연결이 그 갱신을 영영 못 본다</b>. {@code putIfAbsent}로 해도 같다 —
     * 그 카드가 아직 표에 없는 때가 정확히 그 경우다. POK-174 전에는 그 재료가 새 연결의 초기
     * 스냅샷이었고, 지금은 카드를 안 읽지만 <b>목록 문의 응답을 여기로 흘리는 것도 같은 모양</b>이다.
     */
    private final Map<String, Map<Long, Long>> lastPublishedSeq = new HashMap<>();
    private final AtomicLong seq = new AtomicLong();
    private final ScheduledExecutorService heartbeat =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "jumpcard-stream-heartbeat");
                t.setDaemon(true);
                return t;
            });

    private final CardStreamExecutor executor;

    /**
     * 🔴 <b>채팅 전용 전송 줄</b>(POK-234 main 결정 F4). 카드와 같은 큐를 쓰면 안 읽는 구독자 하나가 자기 스트라이프를
     * 막을 때 채팅(초당 수십 건)이 큐를 금방 채워, <b>같은 스트라이프의 멀쩡한 연결이 카드를 잃는다</b> — 카드가
     * 제품의 핵심이다. 줄을 갈라 막힌 연결의 피해를 「그 스트라이프의 채팅」으로 가둔다.
     *
     * <p>🔴 <b>줄을 가르는 것만으로는 카드 줄이 안 지켜졌다</b>(태스크 19 실측). 소켓과 emitter 쓰기 자물쇠는 연결당 하나라,
     * 채팅이 A의 쓰기를 막으면 A 몫 카드 job이 카드 스레드에서 그 자물쇠를 기다리고 같은 카드 스트라이프의 B가 약 65초 늦었다.
     * 그것을 막는 것은 줄 분리가 아니라 {@link WriteGate}다 — 막힌 연결의 쓰기를 건너뛴다.
     *
     * <p><b>남는 한계</b>: 안 읽는 구독자와 같은 <b>채팅</b> 스트라이프의 연결은 채팅을 잃을 수 있다.
     * 카드 스트라이프는 {@link WriteGate}의 한계 둘(첫 번째로 막힌 쓰기가 카드·ping 자신인 경우)만큼 늦을 수 있다.
     * 연결마다 줄을 두는 안(A)은 {@link CardStreamExecutor} 주석이 「상한 500에 스레드 500」으로 이미 기각했다.
     *
     * <p>스트라이프 수·큐 크기는 카드와 같은 설정값이다. 스레드는 카드 4 + 채팅 4 = 8.
     */
    private final CardStreamExecutor chatExecutor;

    /** 채팅 큐 거부로 연결에 못 넣은 이벤트 수(누적). 연결마다 센다 — 두 연결에서 거부되면 두 번이다. */
    private final AtomicLong chatDropped = new AtomicLong();

    /** 채팅 거부 경고를 모아 찍는 간격. 거부마다 찍으면 막힌 연결 하나가 초당 수십 줄을 만든다. */
    private static final Duration CHAT_DROP_LOG_EVERY = Duration.ofSeconds(10);
    private final AtomicLong nextChatDropLogNanos = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong chatDroppedSinceLog = new AtomicLong();

    /** 막힌 연결이라 건너뛴 쓰기 수(누적) — 카드·ping·채팅·종료 알림 모두. 연결마다 센다. */
    private final AtomicLong stuckSkipped = new AtomicLong();
    private final AtomicLong nextStuckSkipLogNanos = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong stuckSkippedSinceLog = new AtomicLong();

    /** B에서 아는 종류. 모르는 것은 묶음째 거부하지 않고 건너뛰고 센다(F8) — PR-C가 {@code broadcast-info}를 더한다. */
    static final java.util.Set<String> CHAT_KINDS = java.util.Set.of("chat", "donation");
    private final StreamProperties properties;
    private final ObjectMapper mapper;
    private final Function<Duration, SseEmitter> emitterFactory;

    /**
     * 생성자가 둘이라 실물 쪽에 {@code @Autowired}가 필요하다 — 없으면
     * {@code No default constructor found}로 컨텍스트 기동이 통째로 실패한다
     * ({@code SqsIntakeRunner}가 같은 함정을 이미 겪었다).
     *
     * <p>기본 팩토리가 {@code SseEmitter::new}가 아닌 이유: 그 생성자는 {@code SseEmitter(Long)}이라
     * {@code Function<Duration, SseEmitter>}와 맞지 않는다.
     */
    @Autowired
    public CardStreamRegistry(CardStreamExecutor executor, StreamProperties properties, ObjectMapper mapper) {
        this(executor, properties, mapper, d -> new SseEmitter(d.toMillis()));
    }

    CardStreamRegistry(CardStreamExecutor executor, StreamProperties properties, ObjectMapper mapper,
                       Function<Duration, SseEmitter> emitterFactory) {
        this(executor, new CardStreamExecutor("chat-stream-", properties.stripes(), properties.queueCapacity(), false),
                properties, mapper, emitterFactory);
    }

    /** 채팅 줄을 검사가 작게(스트라이프 1·큐 1) 넣는 모양. 운영은 위 생성자가 카드와 같은 설정값으로 만든다. */
    CardStreamRegistry(CardStreamExecutor executor, CardStreamExecutor chatExecutor, StreamProperties properties,
                       ObjectMapper mapper, Function<Duration, SseEmitter> emitterFactory) {
        this.chatExecutor = chatExecutor;
        this.executor = executor;
        this.properties = properties;
        this.mapper = mapper;
        this.emitterFactory = emitterFactory;
    }

    @PostConstruct
    void startHeartbeat() {
        long period = properties.heartbeat().toMillis();
        heartbeat.scheduleAtFixedRate(this::ping, period, period, TimeUnit.MILLISECONDS);
    }

    /**
     * <b>상한 셋을 센다. 넘겼으면 던진다.</b>
     *
     * <p>🔴 <b>이 계산이 한 곳에 있어야 한다.</b> {@link #openWithSnapshot}이 DB 조회 <b>앞에서</b>
     * 한 번, {@link #open}이 자리를 잡기 <b>직전에</b> 한 번 부른다. 두 자리에 조건을 각각 적으면
     * 언젠가 갈리고, 갈리는 순간 앞 검사만 통과해 <b>스냅샷을 헛읽는 일이 그대로 돌아온다</b> —
     * 그것이 이 메서드를 뽑은 이유의 전부다. 기준·순서·예외가 같아야 하므로 <b>복사하지 말고
     * 이 메서드를 불러라.</b>
     *
     * <p>둘 다 <b>같은 자물쇠 안</b>이라 사이에 남이 못 낀다 — 앞 검사가 통과하면 뒤 검사도
     * 통과한다. 두 번 세는 비용은 {@code conns} 순회 둘(상한 500)이고 DB가 없다.
     *
     * <p>{@code synchronized}인 이유 — 세는 것과 더하는 것 사이에 남이 끼면 상한이 하나 넘는다.
     * 연결을 여는 것은 드문 일이라 이 직렬화의 대가가 작다.
     */
    private void checkLimits(String streamId, String userId) {
        long perUser = conns.values().stream().filter(c -> c.userId().equals(userId)).count();
        long perStream = conns.values().stream().filter(c -> c.streamId().equals(streamId)).count();
        if (perUser >= properties.maxPerUser()) {
            throw new StreamLimitExceededException("user");
        }
        if (perStream >= properties.maxPerStream()) {
            throw new StreamLimitExceededException("stream");
        }
        if (conns.size() >= properties.maxTotal()) {
            throw new StreamLimitExceededException("total");
        }
    }

    /**
     * 자리를 잡고 정리 콜백을 건다. 상한은 {@link #checkLimits}가 본다.
     *
     * <p>{@code synchronized}인 이유 — 세는 것과 더하는 것 사이에 남이 끼면 상한이 하나 넘는다.
     * 연결을 여는 것은 드문 일이라 이 직렬화의 대가가 작다.
     */
    public synchronized SseEmitter open(String streamId, String userId, Duration timeout) {
        checkLimits(streamId, userId);

        SseEmitter emitter = emitterFactory.apply(timeout);
        conns.put(emitter, new Conn(seq.getAndIncrement(), streamId, userId, emitter, new WriteGate()));

        Runnable remove = () -> conns.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        return emitter;
    }

    /**
     * 연결 직후에 확인할 것 — 「이미 끝난 방송인가」. <b>카드는 더 이상 안 보낸다</b>(POK-174).
     *
     * <p>지난 카드를 받아 가는 자리는 목록 문({@code GET .../jump-cards})이다. 통로가 같은 것을
     * 또 보내면 화면이 같은 카드를 두 경로로 받고, 「어느 쪽이 최신인가」를 화면이 판정해야 한다.
     */
    public record InitialSnapshot(boolean ended) {
    }

    /**
     * <b>스냅샷을 읽는 것부터 첫 제출까지가 한 임계구역이다.</b>
     *
     * <p>값이 아니라 <b>「읽는 법」</b>({@code Supplier})을 받는 이유 — 값으로 받으면 호출자가
     * 자물쇠 <b>밖에서</b> 읽게 되고, 「읽은 뒤 ~ 명부에 오르기 전」 창이 열린다. 그 창에 방송이
     * 끝나면 {@link #broadcastEnded}는 연결이 명부에 없어 지나가고, 호출자가 이미 읽은 상태는
     * {@code LIVE}라 {@code ended=false}로 열린다 — <b>연결이 토큰 만료까지 살아 있고</b>
     * 클라이언트는 방송이 끝난 줄 모른다(PR #109 봇 지적 ②, 2026-08-23 재현: 표는
     * {@code ended}인데 10초 뒤에도 안 닫힘).
     *
     * <p><b>POK-174가 카드 전송을 없앤 뒤에도 이 창은 그대로다.</b> 없애기 전에는 창에 커밋된
     * 카드까지 영구히 유실됐고(재연결 전까지 못 본다) 지금은 {@code ended} 하나가 걸린다 —
     * <b>잃는 것이 줄었지 사라지지 않았다.</b>
     *
     * <p><b>대가를 알고 고른다.</b> DB 조회가 자물쇠 안으로 들어와 보유 시간이 늘어난다
     * (카드를 읽던 때는 <b>5.9~22.4ms</b>, 지금은 방송 한 줄이라 훨씬 짧다). 그동안
     * {@link #open}·{@link #publish}·{@link #broadcastEnded}가 막힌다.
     *
     * <p>🔴 <b>{@code get()}이 {@code open()}보다 먼저다.</b> 뒤로 옮기면 자리가 영구히 샌다 —
     * {@code open}이 명부에 자리를 잡고 정리 콜백을 거는데, 그 콜백은 서블릿 컨테이너가 emitter를
     * 받아야 불린다. 그전에 조회가 던지면(커넥션 고갈·쿼리 타임아웃·직렬화 실패) 컨테이너는 그
     * emitter를 모르므로 자리는 프로세스가 죽을 때까지 남는다(라운드 1 중대,
     * {@code expected: 0 but was: 1}로 재현한 자리다). 대신 상한 초과로 {@code open}이 던지면
     * 스냅샷을 헛읽는데, 상한 초과는 드물고 유실보다 싸다.
     */
    public synchronized SseEmitter openWithSnapshot(String streamId, String userId,
                                                    Supplier<Duration> timeout,
                                                    Supplier<InitialSnapshot> initial) {
        // 🔴 <b>DB 조회 앞이다.</b> 상한을 넘긴 요청은 어차피 아래 open()이 거절하는데, 그전에
        // 조회하면 <b>거절될 요청이 자물쇠 안에서 DB를 읽는다</b>. 재연결 루프는 그것을 초당
        // 수백 번 한다 — 2026-08-24 재현(PR #113 봇 지적 ②, 그때는 카드 전부를 읽었다):
        // 503 1615회에 조회 1615회(비율 1.00), 5초 중 자물쇠가 41~72% 잡혀 있었고 그동안
        // publish 막힘 중앙값이 55us → 499us(300장) · 2010us(1200장)로 뛰었다.
        // 거절되는 쪽은 안 아프고(왕복 2~4ms) 같은 자물쇠를 기다리는 남의 화면이 아프다.
        //
        // POK-174로 읽는 것이 방송 한 줄이 되어 규모는 줄었지만 <b>비율 1.00은 그대로다</b> —
        // 이 검사를 뒤로 옮기면 거절되는 요청마다 질의가 하나씩 늘어난다.
        //
        // 아래 open()의 검사를 <b>지우지 않는다</b>. 여기 것은 「조회를 아끼는」 사전 검사이고,
        // 자리를 잡는 것과 원자적인 최종 판정은 그쪽이다. 둘 다 checkLimits 하나를 부르므로
        // 기준이 갈릴 수 없다.
        checkLimits(streamId, userId);

        InitialSnapshot snapshot = initial.get();
        // 🔴 시한도 <b>값이 아니라 「재는 법」</b>으로 받아 여기서 다시 잰다. 호출자가 자물쇠 밖에서
        // 잰 값을 넘기면, 그 뒤 자물쇠 대기와 위 조회에 흐른 시간이 시한에 안 반영된다 —
        // 만료된 토큰으로 연 연결이 exp를 넘겨 산다(PR #112 봇 지적 ④, 2026-08-23 재현:
        // 자물쇠를 3초 쥐었더니 만료 2,508ms 뒤에 200으로 열렸고 연결이 exp를 3,398ms 넘겼다).
        //
        // 🔴 <b>open() 앞이다.</b> 뒤로 옮기면 여기서 던질 때 자리가 영구히 샌다 — open이 명부에
        // 자리를 잡고 거는 정리 콜백은 서블릿 컨테이너가 emitter를 받아야 불리는데, 컨테이너는
        // 그 emitter를 모른다(라운드 1 중대와 같은 자리, 위 문단 참고).
        Duration remaining = timeout.get();
        SseEmitter emitter = open(streamId, userId, remaining);
        sendInitial(emitter, snapshot.ended());
        return emitter;
    }

    /**
     * 연결 직후에 딱 두 가지를 보낸다 — <b>주석 한 줄</b>과, 끝난 방송이면 {@code ended}.
     *
     * <p><b>주석이 첫 쓰기여야 한다.</b> {@code SseEmitter}는 <b>첫 쓰기가 있어야 응답을 커밋</b>하는데,
     * 여기서 아무것도 안 쓰면 헤더가 <b>다음 하트비트까지</b> 늦는다(실측 5.449초, 최악 20초).
     * 받는 쪽에서 그것은 「느리다」가 아니라 <b>「연결이 안 된다」</b>로 보인다 — 브라우저
     * {@code EventSource.onopen}이 그만큼 안 온다. <b>POK-174가 카드 전송을 없앤 뒤로는
     * 진행 중인 방송에서 이 주석이 유일한 첫 쓰기다</b>(전에는 카드가 있으면 그것이 대신했다).
     *
     * <p><b>{@code ended}가 주석 뒤인 것도 순서다.</b> 그 갈래는 {@code complete()}로 닫으므로
     * 앞에 두면 이미 닫힌 연결에 주석을 쓰게 된다.
     *
     * <p><b>지난 카드는 여기서 안 나간다</b>(POK-174). 그 자리는 목록 문이다. 되살리면
     * {@code StreamAccessTest.통로를_열어도_기존_카드가_안_오고…}가 빨간불이 된다.
     * 되살릴 때 함께 돌아오는 것 셋을 적어 둔다 — ① 큐를 카드 수만큼 먹어 상한(운영 1000)을
     * 넘는 순간 <b>영구 유실</b>(1200장에서 201건, 재연결 2회차에도 같은 자리, 2026-08-23 실측)
     * ② 같은 스트라이프의 다른 연결이 밀린다(카드 300장에 15~49ms) ③ 아래 early-send 규모가
     * <b>454건 · 350,637자</b>로 커진다.
     *
     * <p>🔴 <b>이 전송이 전용 스레드가 아니라 요청 스레드에서 나갈 수 있다 — 못 고친다.</b>
     * {@code ResponseBodyEmitter}는 MVC가 emitter를 받기 전의 {@code send}를 <b>early-send
     * 버퍼</b>에 쌓고, 그 버퍼를 비우는 것이 <b>요청 스레드</b>다
     * ({@code ResponseBodyEmitterReturnValueHandler.handleReturnValue} → {@code initialize} →
     * {@code sendInternal}). <b>지금은 최대 두 건이라 규모가 작지만 자리 자체는 남아 있다.</b>
     * <ul>
     *   <li><b>공개 훅이 없다 — 셋을 시도해 보고 적는다</b>(「찾아봤는데 못 찾았다」와
     *       「존재하지 않는다」는 다르므로 막힌 자리를 남긴다):
     *       ① {@code send(Set&lt;DataWithMediaType&gt;)} 오버라이드는 <b>불리지 않는다</b> —
     *       {@code SseEmitter.send(SseEventBuilder)}가 {@code super.send(dataToSend)}로
     *       <b>정적 바인딩</b>해 서브클래스를 건너뛴다(spring-webmvc 7.0.8, 127~131행).
     *       ② {@code extendResponse}(protected)는 불리지만 {@code initialize} <b>앞</b>이라
     *       그 뒤에도 창이 남는다. ③ {@code initialize} 오버라이드는 되지만 package-private이라
     *       <b>{@code org.springframework.web.servlet.mvc.method.annotation} 패키지에 클래스를
     *       둬야 한다</b>(계측할 때 실제로 그렇게 했다). 이 셋 말고 다른 길을 찾으면 이 문단이 틀린 것이다</li>
     *   <li><b>커넥션은 안 쥔다.</b> 여는 쪽의 트랜잭션은 {@code initialize} 전에 닫힌다 —
     *       잃는 것은 워커 하나이고 POK-93의 풀 고갈과 급이 다르다</li>
     *   <li><b>{@code initialize}를 오버라이드해 첫 전송을 미루지 않는다.</b> 그러면 첫 전송이
     *       자물쇠 <b>밖</b>으로 나가 「새 카드가 {@code ended}를 앞지름」이 재발한다 —
     *       PR #109가 고친 구멍이다</li>
     * </ul>
     */
    public void sendInitial(SseEmitter emitter, boolean ended) {
        Conn conn = conns.get(emitter);
        if (conn == null) {
            return;
        }
        // 반환값을 안 본다 — 초기 전송 전체가 <b>태스크 하나</b>라 큐를 한 칸만 쓴다.
        // 그 한 칸이 거부될 확률은 극히 낮고, 여기서 자리를 빼면 <b>연결 직후에 통로가 죽는다</b>
        // (broadcastEnded와 달리 클라이언트가 아직 아무것도 못 받은 상태다).
        executor.submit(conn.stripe(), emitter, () -> {
            // 주석이 첫 쓰기여야 헤더가 바로 나간다(위 문단).
            send(conn, SseEmitter.event().comment("ok"));
            if (ended && send(conn, endedEvent())) {
                emitter.complete();
            }
        });
    }

    /**
     * 카드가 생기거나 바뀌었다고 알리는 <b>유일한 출구</b>.
     *
     * <p>{@code synchronized}인 이유는 {@link #openWithSnapshot}과 겹치지 않기 위해서다 —
     * 겹치면 갓 붙은 연결에 새 값이 옛 스냅샷보다 먼저 간다.
     */
    public synchronized void publish(JumpCardSnapshot card) {
        if (isStale(card)) {
            return;
        }
        for (Conn conn : conns.values()) {
            // 반환값을 안 본다. 🔴 <b>POK-174가 그 근거를 갈아 끼웠다</b> — 전에는 「재연결이 전체
            // 스냅샷으로 메운다」였는데 통로가 지난 카드를 안 보내므로 재연결은 안 메운다.
            // 메우는 것은 <b>카드 목록 문</b>({@code GET .../jump-cards})이고, 언제 다시 부를지는
            // 화면이 정한다(「통로 먼저, 목록 나중」). 재연결만 도는 동안은 계속 안 보인다.
            //
            // <b>그래도 버리는 이유는 여기서 할 수 있는 처방이 더 나쁘기 때문이다.</b>
            // broadcastEnded처럼 자리를 회수하면 명부에서만 사라지고 <b>소켓은 열린 채</b>라
            // 클라이언트는 살아 있다고 믿는데 새 카드도 하트비트도 영영 안 온다(주입으로 재현,
            // services/README.md POK-118 절). 잃는 것이 「카드 한 장」에서 「남은 수명 전부」로 커진다.
            // 종료 알림만 메울 것이 없어 자리를 회수한다(broadcastEnded) — 그쪽은 뺀 뒤 갈 것이 없다.
            //
            // 🔴 <b>유실 크기는 안 쟀다</b> — 큐(운영 1000)를 채워 실제로 몇 장이 사라지는지
            // 재현한 적이 없다. 「버려도 된다」는 처방 비교의 결론이지 유실 확률의 판정이 아니다.
            if (conn.streamId().equals(card.streamId())) {
                // 막힌 연결이면 건너뛴다({@link WriteGate}). 그 카드도 위와 같이 카드 목록 문이 메운다.
                executor.submit(conn.stripe(), conn.emitter(), () -> send(conn, cardEvent(card)));
            }
        }
    }

    /**
     * <b>이 카드에 대해 이미 더 크거나 같은 순번을 보냈는가.</b> 그렇다면 지금 것은 낡았다.
     *
     * <p>순서를 바로잡는 것이 아니라 <b>낡은 것을 안 보내는 것</b>이 처방인 이유:
     * {@code publish}의 자물쇠를 공정 락으로 바꿔도 그것이 보존하는 것은 「자물쇠에 줄 선 순서」이고,
     * <b>커밋 순서와 {@code afterCommit} 도달 순서는 애초에 별개다</b>(각자 다른 요청 스레드에서
     * 돈다). 줄에 서는 순서가 이미 뒤집혀 있으면 공정 락도 낡은 값을 먼저 보낸다.
     *
     * <p>재현(2026-08-23, PR #112 봇 지적 ③): 대기 순서를 N → N+1로 강제하면
     * <b>100회 시도 100회</b> 뒤집혔고, 마지막에 적용되는 것이 낡은 쪽이라
     * <b>놓은 카드가 집힌 것으로 남았다.</b>
     *
     * <p><b>같은 순번도 버린다</b>({@code <=}). 같은 {@code eventSeq}면 내용도 같으므로
     * 다시 보내 봐야 화면이 헛돌 뿐이다 — {@code toggleHidden}이 「안 바뀌었으면 발행 안 함」으로
     * 막는 것과 같은 이유다.
     *
     * <p><b>중간 이벤트가 빠질 수 있다.</b> 낡은 것을 버리므로 화면은 N+1만 받고 N을 못 본다.
     * 무해하다 — 카드는 <b>상태</b>이지 사건의 나열이 아니고, 최신이 맞으면 화면이 맞다.
     */
    private boolean isStale(JumpCardSnapshot card) {
        Map<Long, Long> seqs = lastPublishedSeq.computeIfAbsent(card.streamId(), key -> new HashMap<>());
        Long previous = seqs.get(card.id());
        if (previous != null && card.eventSeq() <= previous) {
            log.info("jumpcard.stream.stale_publish_skipped streamId={} cardId={} eventSeq={} lastSeq={}",
                    card.streamId(), card.id(), card.eventSeq(), previous);
            return true;
        }
        seqs.put(card.id(), card.eventSeq());
        return false;
    }

    /**
     * 방송이 끝났다. 연결을 열어 둬도 더 올 카드가 없으므로 알리고 닫는다.
     *
     * <p>{@code synchronized}인 이유 — {@link #openWithSnapshot}과 겹치면 <b>{@code ended}가
     * 스냅샷을 앞질러</b> 같은 스트라이프 큐에 들어간다. 그러면 {@code ended}가 먼저 나가고
     * {@code complete()}가 돌아, 뒤따르는 카드는 <b>이미 닫힌 emitter에 쓰다</b>
     * {@code IllegalStateException}으로 삼켜진다({@code Job}이 {@code completeWithError}로 받는다).
     *
     * <p><b>창이 둘이었다</b>(2026-08-23 재현, PR #109 봇 지적 ①. 지적은 앞의 하나만 봤다).
     * <ul>
     *   <li><b>(가) 등록 직후 ~ 첫 제출 전</b> — {@code ended}가 초기 전송을 앞지른다.
     *       그때 클라이언트가 받는 것은 {@code ended} 하나뿐이다(카드 5장 중 도착 <b>0장</b> 실측).
     *       <b>이 창은 지금도 그대로다</b> — 앞지르는 대상이 카드에서 주석 한 줄로 줄었을 뿐이다</li>
     *   <li><b>🔴 (나) 스냅샷을 제출하는 도중 — POK-174가 없앴다.</b> 「앞 카드는 가고 뒤 카드가
     *       거부된다」였고 창이 <b>카드 수에 비례</b>했다({@code openWithSnapshot} 실측
     *       0.011~1.830ms, 카드 0~1000장). 지금 {@link #sendInitial}은 <b>태스크 하나</b>를
     *       제출하므로 「제출 도중」이라는 상태 자체가 없다. <b>초기 전송을 되살리면 이 창도
     *       같이 돌아온다</b> — 그래서 지우지 않고 남긴다</li>
     * </ul>
     *
     * <p>같은 자물쇠가 둘 다 닫았다 — 종료 알림은 {@code openWithSnapshot} <b>앞이나 뒤로만</b>
     * 갈라진다. 앞이면 연결이 아직 명부에 없어 안 가고(그 경우는 컨트롤러가 스냅샷을 락 안에서
     * 읽어 막는다), 뒤면 초기 전송 다음에 줄을 선다. <b>남은 (가)를 닫는 것도 이 자물쇠다.</b>
     *
     * <p>여기도 <b>큐에 넣기만</b> 하므로 자물쇠가 잡히는 시간은 짧다.
     */
    @Override
    public synchronized void broadcastEnded(String streamId) {
        for (Conn conn : conns.values()) {
            if (conn.streamId().equals(streamId)) {
                boolean queued = executor.submit(conn.stripe(), conn.emitter(), () -> {
                    // 건너뛰었으면 complete()도 안 부른다 — 그것도 막힌 쓰기가 쥔 emitter 자물쇠를 기다린다.
                    // 그 연결은 막힌 쓰기가 write timeout으로 실패할 때 Job의 completeWithError로 명부에서 빠진다.
                    if (send(conn, endedEvent())) {
                        conn.emitter().complete();
                    }
                });
                if (!queued) {
                    dropRejectedEnded(conn, streamId);
                }
            }
        }
        // 더 올 카드가 없으므로 순번 표도 여기서 버린다 — 기다릴 필요가 없어 즉시 치운다.
        // 🔴 <b>이 자리에만 맡기지 않는다.</b> 여기는 SQS 생명주기 이벤트로만 불리는데
        // BROADCAST_INTAKE_ENABLED 기본값이 false라 통로가 꺼진 배포에서는 한 번도 안 불린다.
        // 그때 실제로 치우는 것은 sweepIdleStreams다(필드 주석).
        lastPublishedSeq.remove(streamId);
    }

    /**
     * <b>큐가 차서 {@code ended}를 못 보냈다. 자리만 회수하고 emitter는 건드리지 않는다.</b>
     *
     * <p>안 빼면 그 연결이 <b>죽은 채로 상한을 먹는다</b> — 2026-08-23 재현(PR #112 봇 지적 ②):
     * {@code broadcastEnded}가 0ms에 예외 없이 반환하고 {@code connectionCount}가 60초 뒤에도 1이었다.
     * 그동안 하트비트도 같은 큐에서 거부돼 <b>회복 계기 자체가 큐에 못 들어갔다.</b>
     * 명부에서 빼면 {@link #publish}·{@link #ping}이 더는 그 큐를 두드리지 않는다.
     *
     * <p>🔴 <b>{@code complete()}도 {@code completeWithError()}도 부르지 않는다.</b> 둘 다
     * {@code ResponseBodyEmitter}의 <b>같은 {@code writeLock}</b>을 잡는데(spring-webmvc 7.0.8 소스),
     * 지금은 막힌 {@code send}가 그 락을 쥐고 있다 — 부르는 스레드가 거기서 같이 잠긴다.
     * 1판에서 요청 스레드가 <b>59,164ms</b> 잠긴 그림이 정확히 그것이다.
     *
     * <p><b>알림은 그대로 유실된다.</b> 이 처방이 고치는 것은 「자리와 큐를 계속 먹는 것」이지
     * 「알림이 가는 것」이 아니다 — 그래서 로그 이름이 {@code ended_dropped}다. 클라이언트는
     * emitter 시한(= 토큰 {@code exp})에 끊긴 뒤 <b>재연결 때 스냅샷에서</b> {@code ended}를 받는다.
     *
     * <p>{@code conns}가 {@code ConcurrentHashMap}이라 순회 중 삭제가 안전하고, 나중에
     * {@code onCompletion} 콜백이 또 지워도 멱등이다.
     */
    private void dropRejectedEnded(Conn conn, String streamId) {
        conns.remove(conn.emitter());
        log.warn("jumpcard.stream.ended_dropped streamId={} connections={} "
                + "reason=queue_full detail=연결을 명부에서 뺐다. 알림은 재연결 때 스냅샷이 대신한다",
                streamId, conns.size());
    }

    /**
     * 앞단 프록시가 조용한 연결을 끊지 않게 주석 한 줄을 보낸다. 주석은 클라이언트가 무시한다.
     *
     * <p><b>절대 던지지 않는다.</b> {@code scheduleAtFixedRate}는 주기 작업이 예외를 던지면
     * <b>이후 실행을 취소하고 재개하지 않는다</b> — 한 번 새면 모든 연결의 하트비트가 영구히 멈추고,
     * 앞단 프록시가 조용해진 연결을 끊기 시작한다. 하트비트의 존재 이유가 바로 그것을 막는 것인데
     * 로그도 안 남는 조용한 고장이 된다. 그래서 {@code Throwable}까지 받아 삼키고 흔적을 남긴다.
     */
    void ping() {
        try {
            for (Conn conn : conns.values()) {
                // 반환값을 안 본다 — 하트비트는 다음 주기가 다시 온다.
                executor.submit(conn.stripe(), conn.emitter(),
                        () -> send(conn, SseEmitter.event().comment("ping")));
            }
            // 하트비트가 이 클래스의 <b>유일한 주기 훅</b>이라 순번 표 청소를 여기 얹는다.
            // 🔴 <b>전송보다 뒤다.</b> 앞에 두면 이 메서드가 <b>자물쇠를 기다리게</b> 되어
            // ping이 그만큼 밀린다 — {@code openWithSnapshot}이 DB 조회를 자물쇠 안에서 한다
            // (5.9~22.4ms 실측, DB가 늘어지면 더). 앞단 프록시가 조용한 연결을 끊지 않게 하는
            // 것이 이 메서드의 존재 이유라 그쪽을 먼저 보낸다. 대신 위에서 던지면 이번 회차의
            // 청소를 건너뛰는데, 다음 주기가 다시 오므로 새는 것이 아니라 늦는 것이다.
            sweepIdleStreams();
        } catch (Throwable t) {
            log.warn("jumpcard.stream.ping_failed connections={} causeType={}",
                    conns.size(), t.getClass().getSimpleName());
        }
    }

    public int connectionCount() {
        return conns.size();
    }

    /**
     * <b>연결이 하나도 없는 방송의 순번 표를 버린다.</b> {@link #ping}이 주기마다 부른다.
     *
     * <p>왜 여기냐 — 표를 버려도 되는 순간은 「그 방송의 마지막 연결이 사라진 때」인데,
     * 그 순간을 아는 자리({@code conns.remove} 정리 콜백)는 서블릿 컨테이너가
     * <b>자물쇠 밖에서</b> 부른다. {@code lastPublishedSeq}가 평범한 {@code HashMap}으로
     * 버틸 수 있는 근거가 「자물쇠 안에서만 만진다」라 거기서 만지면 그 근거가 무너진다.
     * 그래서 <b>이미 자물쇠 규율을 지키는 주기 작업이 늦게 치운다.</b>
     *
     * <p><b>대가는 최대 한 주기(기본 20초)의 지연이다.</b> 그 지연이 이득이기도 하다 —
     * 그 안에 재연결하면 표를 그대로 물려받아 필드 주석의 「남는 창」조차 안 열린다.
     *
     * <p>{@code conns}를 매번 훑는다. 상한이 전체 500이라 한 번에 500개짜리 순회 하나이고,
     * 20초에 한 번이다. 방송별 연결 수를 따로 세어 두면 그 수를 갱신하는 자리가
     * {@code open}·정리 콜백 둘로 갈리는데 <b>콜백이 자물쇠 밖</b>이라 같은 문제로 돌아온다.
     */
    synchronized void sweepIdleStreams() {
        if (lastPublishedSeq.isEmpty()) {
            return;
        }
        Set<String> live = new HashSet<>();
        for (Conn conn : conns.values()) {
            live.add(conn.streamId());
        }
        lastPublishedSeq.keySet().retainAll(live);
    }

    /** 순번 표가 들고 있는 방송 수. <b>새는지 재는 검사만</b> 쓴다. */
    synchronized int trackedStreamCount() {
        return lastPublishedSeq.size();
    }

    /**
     * <b>{@code data}가 반드시 있어야 한다.</b> WHATWG HTML 9.2.6 「dispatch the event」 2단계가
     * "If the data buffer is an empty string, set the data buffer and the event type buffer to the
     * empty string and return"이라, {@code data} 줄이 하나도 없는 이벤트는 브라우저가
     * <b>MessageEvent를 만들기 전에 버린다</b> — {@code addEventListener("ended", …)}가 안 불린다.
     *
     * <p>전에는 {@code SseEmitter.event().name("ended")}만 보내 실제 바이트가
     * {@code event:ended\n\n}이었고, 그것을 Chrome 148과 undici(WHATWG 구현) <b>둘 다 버렸다</b>
     * (2026-08-23 재현). 우리 {@code SseReader}가 관대해서 시험은 초록이었다 — 그 파서도 같이 고쳤다.
     *
     * <p>내용이 {@code &#123;&#125;}인 이유: 받는 쪽이 쓸 값이 없다. 빈 문자열({@code data:\n})로도
     * 규약은 만족하지만, 웹이 {@code JSON.parse(e.data)}를 그대로 걸 수 있게 빈 객체를 준다.
     */
    private SseEmitter.SseEventBuilder endedEvent() {
        return SseEmitter.event().name("ended").data("{}", MediaType.APPLICATION_JSON);
    }

    private SseEmitter.SseEventBuilder cardEvent(JumpCardSnapshot card) {
        // 직접 직렬화한다 — data(Object, MediaType)은 메시지 컨버터를 찾는 경로라
        // 요청 스레드 밖에서 돌 때 컨텍스트 의존이 생긴다.
        return SseEmitter.event()
                .id(Long.toString(card.eventSeq()))
                .name("card")
                .data(mapper.writeValueAsString(card), MediaType.APPLICATION_JSON);
    }

    /**
     * 수집기가 민 채팅·후원 묶음을 그 방송 연결에 뿌린다(POK-234 PR-B).
     *
     * <p>🔴 <b>요청 하나 = 연결당 job 하나</b>(F4). 이벤트마다 job을 넣으면 한 요청(최대 500건)이 큐를 통째로 먹는다.
     * JSON은 <b>이벤트당 한 번</b> 만들어 연결들이 나눠 쓴다(F16) — SSE 빌더는 {@code build()}마다 줄바꿈을
     * 덧붙이는 상태가 있어 공유하지 않고, 문자열만 공유한다.
     *
     * <p>{@code publish}와 같은 자물쇠 안에서만 {@code conns}를 훑는다. {@code isStale}·{@code lastPublishedSeq}는
     * 카드 전용이라 안 건드린다 — 채팅은 워터마크가 없다(순서는 스트라이프가 지키고 빈틈은 화면이 {@code seq}로 본다).
     *
     * <p>거부되면 그 연결에 넣으려던 이벤트 수만큼 {@link #chatDroppedCount()}에 더한다. 채팅은 「사건의 나열」이라
     * 버린 수가 곧 그 연결의 유실 수다. 메우는 것은 수집기 범위 창구다.
     */
    public synchronized ChatPublishResult publishChatEvents(String streamId, List<ChatEvent> events) {
        List<String[]> prepared = new ArrayList<>(events.size());
        int unknown = 0;
        for (ChatEvent event : events) {
            if (!CHAT_KINDS.contains(event.kind())) {
                unknown++;
                continue;
            }
            prepared.add(new String[]{Long.toString(event.seq()), event.kind(), mapper.writeValueAsString(event.raw())});
        }
        if (!prepared.isEmpty()) {
            for (Conn conn : conns.values()) {
                if (!conn.streamId().equals(streamId)) {
                    continue;
                }
                boolean queued = chatExecutor.submit(conn.stripe(), conn.emitter(), () -> {
                    // 이벤트마다 문을 지난다 — job 하나(최대 500건)를 통째로 한 번 잡으면 멀쩡한 연결에서도
                    // 쓰기가 1초를 넘겨 그 사이 카드 job이 「막혔다」로 건너뛴다.
                    for (String[] e : prepared) {
                        send(conn, SseEmitter.event().id(e[0]).name(e[1]).data(e[2], MediaType.APPLICATION_JSON));
                    }
                });
                if (!queued) {
                    chatDropped.addAndGet(prepared.size());
                    logChatDropThrottled(streamId, prepared.size());
                }
            }
        }
        return new ChatPublishResult(prepared.size(), unknown);
    }

    /**
     * @param accepted 연결에 뿌리려고 넣은 이벤트 수(아는 kind)
     * @param dropped  모르는 kind라 건너뛴 수. 큐 거부는 여기 없다 — 연결마다 달라 {@link #chatDroppedCount()}가 센다
     */
    public record ChatPublishResult(int accepted, int dropped) {
    }

    private void logChatDropThrottled(String streamId, int events) {
        chatDroppedSinceLog.addAndGet(events);
        long now = System.nanoTime();
        long next = nextChatDropLogNanos.get();
        if (next != Long.MIN_VALUE && now < next) {
            return;
        }
        if (nextChatDropLogNanos.compareAndSet(next, now + CHAT_DROP_LOG_EVERY.toNanos())) {
            log.warn("jumpcard.stream.chat_dropped streamId={} events={} total={}",
                    streamId, chatDroppedSinceLog.getAndSet(0), chatDropped.get());
        }
    }

    /**
     * <b>emitter에 쓰는 유일한 길.</b> 막힌 연결이면 건너뛰고 센다({@link WriteGate}).
     *
     * <p>건너뛴 카드는 <b>카드 목록 문</b>이 메운다(기존 계약 — 큐 거부로 버린 카드와 같다). 건너뛴 채팅은 화면이
     * {@code seq} 빈틈으로 알고 범위 창구로 메운다. 로그는 모아서 연결 번호와 건수만 싣는다 — 막힌 연결 하나가
     * 초당 수십 번 건너뛴다.
     */
    private boolean send(Conn conn, SseEmitter.SseEventBuilder event) throws Exception {
        if (conn.gate().send(conn.emitter(), event)) {
            return true;
        }
        stuckSkipped.incrementAndGet();
        stuckSkippedSinceLog.incrementAndGet();
        long now = System.nanoTime();
        long next = nextStuckSkipLogNanos.get();
        if ((next == Long.MIN_VALUE || now >= next)
                && nextStuckSkipLogNanos.compareAndSet(next, now + CHAT_DROP_LOG_EVERY.toNanos())) {
            log.warn("jumpcard.stream.stuck_skipped conn={} skipped={} total={}",
                    conn.seq(), stuckSkippedSinceLog.getAndSet(0), stuckSkipped.get());
        }
        return false;
    }

    /** 막힌 연결이라 건너뛴 쓰기 누계. */
    public long stuckSkippedCount() {
        return stuckSkipped.get();
    }

    /** 채팅 큐 거부로 연결에 못 넣은 이벤트 누계. */
    public long chatDroppedCount() {
        return chatDropped.get();
    }

    /** 이 방송에 열린 연결이 하나라도 있나. 접수 문이 DB를 치기 전에 묻는다(F11). */
    public boolean hasConnections(String streamId) {
        for (Conn conn : conns.values()) {
            if (conn.streamId().equals(streamId)) {
                return true;
            }
        }
        return false;
    }

    @PreDestroy
    void stop() {
        heartbeat.shutdownNow();
        chatExecutor.shutdown();
    }
}
