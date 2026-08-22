package com.pokeclip.clip.jumpcard.stream;

import com.pokeclip.clip.broadcast.intake.EndedListener;
import com.pokeclip.clip.jumpcard.JumpCardErrors.StreamLimitExceededException;
import com.pokeclip.clip.jumpcard.JumpCardSnapshot;
import jakarta.annotation.PostConstruct;
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

/**
 * 열려 있는 연결을 들고 있다가 방송별로 이벤트를 밀어 넣는다.
 *
 * <p><b>{@link #publish}가 「카드가 생겼다/바뀌었다」를 알리는 유일한 출구다.</b> 여러 곳에
 * 흩어지면 나중에 여러 대(Redis)로 갈 때 갈아끼울 자리를 못 찾는다. 지금은 구현이 하나뿐이라
 * 인터페이스를 미리 뽑지 않는다 — 껍데기만 남는다.
 */
@Component
public class CardStreamRegistry implements EndedListener {

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
    public void sendInitial(SseEmitter emitter, List<JumpCardSnapshot> cards, boolean ended) {
        Conn conn = conns.get(emitter);
        if (conn == null) {
            return;
        }
        executor.submit(conn.stripe(), emitter, () -> emitter.send(SseEmitter.event().comment("ok")));
        for (JumpCardSnapshot card : cards) {
            executor.submit(conn.stripe(), emitter, () -> emitter.send(cardEvent(card)));
        }
        if (ended) {
            executor.submit(conn.stripe(), emitter, () -> {
                emitter.send(SseEmitter.event().name("ended"));
                emitter.complete();
            });
        }
    }

    /** 카드가 생기거나 바뀌었다고 알리는 <b>유일한 출구</b>. */
    public void publish(JumpCardSnapshot card) {
        for (Conn conn : conns.values()) {
            if (conn.streamId().equals(card.streamId())) {
                executor.submit(conn.stripe(), conn.emitter(), () -> conn.emitter().send(cardEvent(card)));
            }
        }
    }

    /** 방송이 끝났다. 연결을 열어 둬도 더 올 카드가 없으므로 알리고 닫는다. */
    @Override
    public void broadcastEnded(String streamId) {
        for (Conn conn : conns.values()) {
            if (conn.streamId().equals(streamId)) {
                executor.submit(conn.stripe(), conn.emitter(), () -> {
                    conn.emitter().send(SseEmitter.event().name("ended"));
                    conn.emitter().complete();
                });
            }
        }
    }

    /** 앞단 프록시가 조용한 연결을 끊지 않게 주석 한 줄을 보낸다. 주석은 클라이언트가 무시한다. */
    void ping() {
        for (Conn conn : conns.values()) {
            executor.submit(conn.stripe(), conn.emitter(),
                    () -> conn.emitter().send(SseEmitter.event().comment("ping")));
        }
    }

    public int connectionCount() {
        return conns.size();
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
