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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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
    private record Conn(long seq, String streamId, String userId, SseEmitter emitter) {
        int stripe() {
            return (int) (seq % Integer.MAX_VALUE);
        }
    }

    private final Map<SseEmitter, Conn> conns = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();
    private final ScheduledExecutorService heartbeat =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "jumpcard-stream-heartbeat");
                t.setDaemon(true);
                return t;
            });

    private final CardStreamExecutor executor;
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
     * 상한 셋을 한 번에 센다.
     *
     * <p>{@code synchronized}인 이유 — 세는 것과 더하는 것 사이에 남이 끼면 상한이 하나 넘는다.
     * 연결을 여는 것은 드문 일이라 이 직렬화의 대가가 작다.
     */
    public synchronized SseEmitter open(String streamId, String userId, Duration timeout) {
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

        SseEmitter emitter = emitterFactory.apply(timeout);
        conns.put(emitter, new Conn(seq.getAndIncrement(), streamId, userId, emitter));

        Runnable remove = () -> conns.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        return emitter;
    }

    /**
     * 연결 직후 전체 스냅샷. 끝난 방송이면 {@code ended}를 붙이고 닫는다.
     *
     * <p><b>주석 한 줄을 먼저 보낸다.</b> {@code SseEmitter}는 <b>첫 쓰기가 있어야 응답을 커밋</b>하는데,
     * 카드가 0장이고 방송이 진행 중이면 여기서 아무것도 안 써서 헤더가 <b>다음 하트비트까지</b>
     * 늦는다(실측 5.449초, 최악 20초). 받는 쪽에서 그것은 「느리다」가 아니라 <b>「연결이 안 된다」</b>로
     * 보인다 — 브라우저 {@code EventSource.onopen}이 그만큼 안 온다. 방송이 막 시작해 카드가 아직
     * 없을 때가 정확히 이 상태다.
     *
     * <p><b>끝이 아니라 앞에 둔다.</b> 끝난 방송 경로는 {@code ended}를 보내고 {@code complete()}를
     * 부르므로, 뒤에 두면 이미 닫힌 연결에 쓰게 된다.
     */
    /** 연결 직후에 보낼 것 — 그 방송 카드 전부와 「이미 끝난 방송인가」. <b>둘을 같이 읽는다</b>. */
    public record InitialSnapshot(List<JumpCardSnapshot> cards, boolean ended) {
    }

    /**
     * <b>스냅샷을 읽는 것부터 첫 제출까지가 한 임계구역이다.</b>
     *
     * <p>값이 아니라 <b>「읽는 법」</b>({@code Supplier})을 받는 이유 — 값으로 받으면 호출자가
     * 자물쇠 <b>밖에서</b> 읽게 되고, 「읽은 뒤 ~ 명부에 오르기 전」 창이 열린다. 그 창에 지나간
     * 것은 <b>영구히</b> 유실된다(PR #109 봇 지적 ②, 2026-08-23 재현):
     * <ul>
     *   <li>카드가 커밋되면 {@link #publish}는 연결이 명부에 없어 지나가고, 스냅샷에는 그보다
     *       먼저 읽혀서 없다 — <b>재연결 전까지 그 카드를 못 본다</b></li>
     *   <li>방송이 끝나면 {@link #broadcastEnded}도 같은 이유로 지나가고, 호출자가 이미 읽은
     *       상태는 {@code LIVE}라 {@code ended=false}로 열린다 — <b>연결이 토큰 만료까지 살아
     *       있고</b> 클라이언트는 방송이 끝난 줄 모른다(실측: 표는 {@code ended}인데 10초 뒤에도
     *       안 닫힘)</li>
     * </ul>
     *
     * <p><b>대가를 알고 고른다.</b> DB 조회가 자물쇠 안으로 들어와 보유 시간이
     * <b>5.9~22.4ms 늘어난다</b>(카드 300~1200장 실측). 그동안 {@link #open}·{@link #publish}·
     * {@link #broadcastEnded}가 막힌다. 그래도 감수하는 이유: 22ms는 PRD 도착 기준(3초)의
     * <b>0.7%</b>이고, 지금 잃는 것은 <b>카드와 {@code ended}의 영구 유실</b>이다.
     *
     * <p>🔴 <b>{@code get()}이 {@code open()}보다 먼저다.</b> 뒤로 옮기면 자리가 영구히 샌다 —
     * {@code open}이 명부에 자리를 잡고 정리 콜백을 거는데, 그 콜백은 서블릿 컨테이너가 emitter를
     * 받아야 불린다. 그전에 조회가 던지면(커넥션 고갈·쿼리 타임아웃·직렬화 실패) 컨테이너는 그
     * emitter를 모르므로 자리는 프로세스가 죽을 때까지 남는다(라운드 1 중대,
     * {@code expected: 0 but was: 1}로 재현한 자리다). 대신 상한 초과로 {@code open}이 던지면
     * 스냅샷을 헛읽는데, 상한 초과는 드물고 유실보다 싸다.
     */
    public synchronized SseEmitter openWithSnapshot(String streamId, String userId, Duration timeout,
                                                    Supplier<InitialSnapshot> initial) {
        InitialSnapshot snapshot = initial.get();
        SseEmitter emitter = open(streamId, userId, timeout);
        sendInitial(emitter, snapshot.cards(), snapshot.ended());
        return emitter;
    }

    /**
     * <b>스냅샷 전체가 태스크 <u>하나</u>다.</b> 카드 하나당 태스크 하나로 제출하면 큐를
     * <b>카드 수만큼</b> 먹고, 상한(운영 1000)을 넘으면 거부 처리기가 조용히 버린다.
     *
     * <p>그 버림이 여기서는 회복되지 않는다 — {@code CardStreamExecutor}의 거부 주석이 기대는
     * 「재연결이 전체 스냅샷으로 메운다」가 <b>초기 스냅샷에는 거짓</b>이기 때문이다.
     * 재연결해도 같은 스냅샷이라 <b>같은 자리에서 또 잘린다</b>(2026-08-23 실측: 1200장에서
     * 201건 유실이 재연결 2회차에도 그대로, 도착 1000장). 처음 잘리는 것이 {@code ended}라
     * <b>연결이 안 닫힌 채 남기까지</b> 한다(카드 1000장에서 거부 1건 = {@code ended}).
     *
     * <p><b>대가는 밀림이다</b> — 이 태스크가 도는 동안 같은 스트라이프의 다른 연결이 기다린다.
     * <b>기계 부하가 값을 통째로 바꾼다</b> — 어느 조건에서 잰 값인지 안 적으면 다음 사람이 자기
     * 측정과 비교할 근거가 없다. 카드 300장, 둘 다 2026-08-23 각 5회 실측
     * ({@code StripeHeadOfLineTest}가 계속 잰다):
     * <ul>
     *   <li><b>부하 중</b>(load 148, 같은 기계에 {@code yes} 20개) — 전송 <b>71~86ms</b> ·
     *       밀림 <b>32~49ms</b>. 최악 49ms는 PRD 도착 기준 3초의 <b>1.6%</b></li>
     *   <li><b>깨끗</b>(load 2.4~4.9) — 전송 <b>32~114ms</b> · 밀림 <b>15~24ms</b>.
     *       최악 24ms는 <b>0.8%</b>. 전송은 3회차 하나가 114ms로 튀어 부하 중 범위와 겹친다</li>
     *   <li><b>전수 실행 중</b>(같은 JVM에서 앞선 시험이 먼저 돈 뒤) — 전송 <b>17~24ms</b> ·
     *       밀림 <b>5ms</b>. 같은 기계·같은 부하라도 <b>단독이냐 전수냐</b>로 또 갈린다</li>
     * </ul>
     * 밀림은 <b>늦는 것</b>이고 유실은 <b>안 오는 것</b>이라 밀림을 골랐다.
     * 카드가 수천 장이 되면 이 밀림이 커지므로 그때는 마진 방식(PRD 「따라잡기」)으로 옮긴다.
     */
    public void sendInitial(SseEmitter emitter, List<JumpCardSnapshot> cards, boolean ended) {
        Conn conn = conns.get(emitter);
        if (conn == null) {
            return;
        }
        executor.submit(conn.stripe(), emitter, () -> {
            // 주석이 첫 쓰기여야 헤더가 바로 나간다(아래 문단).
            emitter.send(SseEmitter.event().comment("ok"));
            for (JumpCardSnapshot card : cards) {
                emitter.send(cardEvent(card));
            }
            if (ended) {
                emitter.send(endedEvent());
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
        for (Conn conn : conns.values()) {
            if (conn.streamId().equals(card.streamId())) {
                executor.submit(conn.stripe(), conn.emitter(), () -> conn.emitter().send(cardEvent(card)));
            }
        }
    }

    /**
     * 방송이 끝났다. 연결을 열어 둬도 더 올 카드가 없으므로 알리고 닫는다.
     *
     * <p>{@code synchronized}인 이유 — {@link #openWithSnapshot}과 겹치면 <b>{@code ended}가
     * 스냅샷을 앞질러</b> 같은 스트라이프 큐에 들어간다. 그러면 {@code ended}가 먼저 나가고
     * {@code complete()}가 돌아, 뒤따르는 카드는 <b>이미 닫힌 emitter에 쓰다</b>
     * {@code IllegalStateException}으로 삼켜진다({@code Job}이 {@code completeWithError}로 받는다).
     *
     * <p><b>창이 둘이다</b>(2026-08-23 재현, PR #109 봇 지적 ①). 지적은 앞의 하나만 봤다.
     * <ul>
     *   <li><b>(가) 등록 직후 ~ 첫 제출 전</b> — {@code ended}가 스냅샷 <b>전체</b>를 앞지른다.
     *       클라이언트가 받는 것은 {@code ended} 하나뿐이다(카드 5장 중 도착 <b>0장</b> 실측)</li>
     *   <li><b>(나) 스냅샷을 제출하는 도중</b> — 앞 카드는 가고 <b>뒤 카드가 거부</b>된다.
     *       이 창은 카드 수에 비례한다({@code openWithSnapshot} 실측 0.011~1.830ms, 카드 0~1000장)</li>
     * </ul>
     *
     * <p>같은 자물쇠가 둘 다 닫는다 — 종료 알림은 {@code openWithSnapshot} <b>앞이나 뒤로만</b>
     * 갈라진다. 앞이면 연결이 아직 명부에 없어 안 가고(그 경우는 컨트롤러가 스냅샷을 락 안에서
     * 읽어 막는다), 뒤면 스냅샷 다음에 줄을 선다.
     *
     * <p>여기도 <b>큐에 넣기만</b> 하므로 자물쇠가 잡히는 시간은 짧다.
     */
    @Override
    public synchronized void broadcastEnded(String streamId) {
        for (Conn conn : conns.values()) {
            if (conn.streamId().equals(streamId)) {
                executor.submit(conn.stripe(), conn.emitter(), () -> {
                    conn.emitter().send(endedEvent());
                    conn.emitter().complete();
                });
            }
        }
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
                executor.submit(conn.stripe(), conn.emitter(),
                        () -> conn.emitter().send(SseEmitter.event().comment("ping")));
            }
        } catch (Throwable t) {
            log.warn("jumpcard.stream.ping_failed connections={} causeType={}",
                    conns.size(), t.getClass().getSimpleName());
        }
    }

    public int connectionCount() {
        return conns.size();
    }

    /**
     * <b>{@code data}가 반드시 있어야 한다.</b> WHATWG HTML 9.2.6 「dispatch the event」 2단계가
     * "If the data buffer is an empty string, set the data buffer and the event type buffer to the
     * empty string and return"이라, {@code data} 줄이 하나도 없는 이벤트는 브라우저가
     * <b>MessageEvent를 만들기 전에 버린다</b> — {@code addEventListener("ended", …)}가 안 불린다.
     *
     * <p>전에는 {@code SseEmitter.event().name("ended")}만 보내 실제 바이트가
     * {@code event:ended\n\n}이었고, 그것을 Chrome 148과 undici(WHATWG 구현) <b>둘 다 버렸다</b>
     * (2026-08-23 재현, PR #112 봇 지적 ①). 우리 {@code SseReader}가 규약보다 관대해서 시험은
     * 초록이었다 — <b>그 파서도 같이 고쳤고, 고치니 헛통과하던 시험 여섯 개가 빨간불이 됐다.</b>
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

    @PreDestroy
    void stop() {
        heartbeat.shutdownNow();
    }
}
