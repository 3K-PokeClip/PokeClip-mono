package com.pokeclip.chat.collector.session;

import com.pokeclip.chat.collector.ChzzkProperties;
import com.pokeclip.chat.collector.CollectionStatus;
import com.pokeclip.chat.collector.StopReason;
import com.pokeclip.chat.collector.archive.ChatArchive;
import com.pokeclip.chat.collector.chzzk.SessionEstablishException;
import com.pokeclip.chat.collector.observe.CollectionMetrics;
import com.pokeclip.chat.collector.persist.ChatBuffer;
import com.pokeclip.chat.collector.persist.ChatPersister;
import com.pokeclip.chat.collector.reconnect.ReconnectPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 살아 있는 세션을 방송 번호로 찾는다. <b>스트리머 여럿을 동시에 수집하는 자리다.</b>
 *
 * <p><b>세션 하나가 가진 것은 전부 그 세션 안에 있다</b>(소켓·재연결 일꾼·하트비트·상태).
 * 여기서 필드로 흩어 놓으면 한 세션의 뒷정리가 다른 세션 것을 지운다 — POK-86에서 겪은 모양이다.
 *
 * <p>{@code computeIfAbsent}를 쓰지 않는다. 세션을 여는 동안 REST 왕복이 두 번(발급·구독)
 * 일어나는데, 그것을 맵의 락 안에서 하면 그 사이 다른 방송의 열기·닫기가 전부 막힌다.
 * <b>자리는 {@code putIfAbsent}로 먼저 잡고 수립은 락 밖에서 한다.</b>
 */
@Component
public class SessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);

    /**
     * 세션 하나와 <b>그 세션만의</b> 살림.
     *
     * @param status     <b>세션마다 따로다.</b> 하나를 나눠 쓰면 한 스트리머가 끊긴 순간
     *                   나머지 전원이 RECONNECTING(health DOWN)으로 보인다 — 그리고
     *                   그 뒤 한 명이 다시 붙으면 아직 끊겨 있는 나머지가 COLLECTING으로
     *                   덮인다. 「UP인데 수집 없음」이 이 서비스의 유일한 치명 실패다
     * @param metrics    세션마다 따로다. 나눠 쓰면 30초 요약이 세션 수만큼 <b>같은 숫자를</b>
     *                   찍고, {@code beginSession()}의 수신 시계 되잡기가 남의 절단 구간까지
     *                   지운다
     * @param stopSignal <b>세션마다 따로다.</b> 이것이 {@code close(streamId)}가 실제로
     *                   그 세션만 멈추게 하는 열쇠다 — 하나를 나눠 쓰면 한 방송을 닫을 때
     *                   전원의 재연결이 같이 죽거나(내리면), 닫은 세션의 재연결 루프가
     *                   계속 돌아 <b>방금 닫은 방송에 다시 붙는다</b>(안 내리면)
     */
    private record Entry(StreamSession session, CollectionStatus status,
                         CollectionMetrics metrics, CountDownLatch stopSignal) { }

    private final ConcurrentHashMap<String, Entry> sessions = new ConcurrentHashMap<>();

    /**
     * 재연결 루프가 도는 실행기. <b>가상 스레드다(Java 21).</b>
     *
     * <p><b>크기를 정한 풀은 안 된다.</b> 풀이 N이면 N+1번째 세션은 재연결 루프에
     * 들어가지도 못하고 앞선 것들의 백오프(상한 60초)를 기다린다. 그동안 그 스트리머는
     * 소켓이 없어 채팅이 <b>버퍼에 쌓이는 것이 아니라 아예 안 온다</b> — 실시간 푸시라
     * 늦게 붙으면 그 구간을 되받을 방법이 없다. <b>복구 지연이 곧 유실이다.</b>
     *
     * <p>백오프 대기가 캐리어를 놓아주므로 세션 수만큼 만들어도 플랫폼 스레드가 안 는다.
     * <b>단, 이것이 성립하는 조건은 {@link StreamSession}이 락 안에 I/O를 두지 않는
     * 것이다</b> — Java 21은 {@code synchronized} 안의 블로킹에서 캐리어를 고정한다.
     * 락 안에 왕복을 하나라도 들이면 가상 스레드가 같이 무너진다. 송신 잠금을
     * {@code ReentrantLock}으로 바꾼 것(태스크 8B)이 그 마지막 자리였다 — pinned 21→0.
     *
     * <p><b>🔴 이것이 없애는 것은 기다리는 비용뿐이다.</b> 동시에 나가는 HTTP 요청 수는
     * 그대로다 — 100명이 한꺼번에 끊기면 재연결 왕복도 한꺼번에 나간다. 실제 동시
     * 수용량은 태스크 1의 실측(반납 셋이 <b>완전히 겹쳤다</b> · 429는 속도 제한이 아니라
     * <b>자리 없음</b>)과 치지직의 요청 속도 제한이 정한다.
     * <b>"가상 스레드라 100명도 괜찮다"로 읽지 마라.</b>
     */
    private final ExecutorService reconnectors = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * 수신 게이트 · 나가 있는 반납 수 · 마지막 세션 번호. <b>이 셋은 프로세스 단위라
     * 세션이 나눠 쓴다.</b> 게이트는 「종료가 싱크를 닫기 직전」이라는 프로세스 사건이고,
     * 반납 수는 종료가 인터럽트 전에 한 번 읽는 값이며, 번호는 로그에서 세션을 가리키는
     * 전역 이름이다. 실제로 쓰는 것은 태스크 11(동시 종료)이다.
     */
    private final AtomicBoolean intakeClosed = new AtomicBoolean();
    private final AtomicInteger releasesInFlight = new AtomicInteger();
    private final AtomicLong lastSessionNo = new AtomicLong();

    private final ChzzkProperties properties;
    private final RestClient restClient;
    private final ChatBuffer buffer;
    private final ChatPersister persister;
    private final ChatArchive archive;

    public SessionRegistry(ChzzkProperties properties, RestClient.Builder restClientBuilder,
                           ChatBuffer buffer, ChatPersister persister, ChatArchive archive) {
        this.properties = properties;
        // 빌더는 프로토타입 빈이다. 한 번만 build()해서 세션 전부가 나눠 쓴다.
        // <b>{@code RestClient.create()}로 만들지 마라</b> — 자동 설정을 우회해
        // {@code spring.http.clients.*}의 타임아웃이 어디에도 안 걸린다.
        this.restClient = restClientBuilder.build();
        this.buffer = buffer;
        this.persister = persister;
        this.archive = archive;
    }

    /**
     * 이 방송의 수집을 시작한다.
     *
     * @return <b>이 호출이 세션을 세웠는가.</b> 이미 열려 있으면 false다 —
     *         <b>그것이 시작 편지의 멱등 방어선이다</b>(태스크 10). 두 번 열면
     *         연결 상한 3개를 우리 손으로 태운다
     */
    public boolean open(SessionKey key, String accessToken) {
        String streamId = key.streamId();
        CollectionStatus status = new CollectionStatus();
        CollectionMetrics metrics = new CollectionMetrics();
        CountDownLatch stopSignal = new CountDownLatch(1);
        StreamSession session = new StreamSession(
                key, accessToken, properties, status, metrics,
                new ReconnectPolicy(properties.reconnectFirstDelay(), properties.reconnectMaxDelay()),
                restClient, buffer, persister, archive,
                reconnectors, stopSignal, intakeClosed, releasesInFlight, lastSessionNo,
                reason -> stopOne(streamId, reason));
        Entry entry = new Entry(session, status, metrics, stopSignal);
        // <b>자리를 먼저 잡는다.</b> 수립이 끝난 뒤에 넣으면 REST 왕복 두 번 동안
        // 자리가 비어 있어, 같은 편지가 두 번 오면 세션이 둘 선다.
        if (sessions.putIfAbsent(streamId, entry) != null) {
            log.info("chat.registry.open_skipped stream={} reason=ALREADY_OPEN", streamId);
            return false;
        }
        // <b>수립은 맵 밖에서 한다.</b> 발급·구독 왕복 두 번이 맵의 락 안에 들어가면
        // 그 사이 다른 방송의 열기·닫기가 전부 막힌다.
        try {
            if (session.open()) {
                return true;
            }
        } catch (SessionEstablishException e) {
            // 사유별 갈래는 편지를 판정하는 쪽이 정한다(태스크 10). 여기서 재시도하지
            // 않는 이유는 그쪽이다 — 큐에 남은 편지가 다시 오는 것이 재시도다.
            log.warn("chat.registry.open_failed stream={} reason={}", streamId, e.reason());
        }
        // 못 세웠으면 자리를 비운다. 안 비우면 <b>세션이 없는데 있는 것으로 보여</b>
        // 다음 편지가 ALREADY_OPEN으로 걸리고 그 방송은 영영 안 붙는다.
        // 우리가 넣은 것일 때만 지운다 — 그 사이 남이 자리를 잡았으면 남의 것이다.
        sessions.remove(streamId, entry);
        return false;
    }

    /**
     * 이 방송의 수집을 멈춘다.
     *
     * @return 닫을 세션이 있었는가. 없으면 false다 — 종료 편지가 두 번 와도 무해하다
     */
    public boolean close(String streamId) {
        Entry entry = sessions.remove(streamId);
        if (entry == null) {
            return false;
        }
        closeEntry(streamId, entry);
        return true;
    }

    /**
     * 살아 있는 세션을 전부 닫는다.
     *
     * <p><b>지금은 하나씩이다.</b> 세션당 (반납 55~69ms + 소켓 닫기 1초, 태스크 1·8B 실측)이라
     * 열 명이면 약 11초로 종료 유예 20초 안이지만 백 명이면 넘는다.
     * <b>나란히 닫는 것은 태스크 11이다</b> — 예산 계산도 거기 있다.
     */
    public void closeAll() {
        for (String streamId : List.copyOf(sessions.keySet())) {
            close(streamId);
        }
    }

    public int activeCount() {
        return sessions.size();
    }

    public Collection<String> activeStreamIds() {
        return List.copyOf(sessions.keySet());
    }

    /**
     * 이 방송의 지금 상태. 없으면 null이다.
     *
     * <p><b>낱개 getter가 아니라 스냅숏을 준다.</b> 상태와 사유를 이어 읽으면 그 사이에
     * 재접속이 성공해 "재연결 중인데 사유는 없음"이 나온다({@code CollectionStatus} 주석).
     */
    public CollectionStatus.Snapshot statusOf(String streamId) {
        Entry entry = sessions.get(streamId);
        return entry == null ? null : entry.status().snapshot();
    }

    /** 지금 다시 붙는 중인 방송의 수. 태스크 13이 health 상세에 싣는다. */
    public int reconnectingCount() {
        return (int) sessions.values().stream()
                .filter(e -> e.status().state() == CollectionStatus.State.RECONNECTING)
                .count();
    }

    /**
     * <b>재시도로 안 풀리는 사유가 확정됐다 — 그 세션만 닫는다</b>(POK-127 C3).
     *
     * <p>옮기기 전에는 여기가 「잔량을 기다리고 최종 판정을 내고 {@code exit 1}」이었다.
     * 세션이 하나뿐일 때는 그것이 맞았다 — 수집할 것이 그 하나였으니까. <b>세션이
     * 여럿이면 같은 코드가 한 스트리머의 동의 철회로 나머지 전원의 수집을 끊는다.</b>
     * 그래서 등록부는 프로세스를 내리는 손잡이를 <b>아예 받지 않는다</b>: 없으면
     * 언젠가 불릴 일도 없다.
     *
     * <p>잔량 대기·최종 판정도 여기서 안 한다. 둘 다 프로세스 단위의 사실이라
     * 세션 하나가 끝났다고 낼 것이 아니다 — 낸다면 스트리머가 한 명 그만둘 때마다
     * "최종" 판정이 쌓이고 그중 어느 것도 최종이 아니다.
     *
     * <p>이 호출은 <b>그 세션의 재연결 루프 스레드 위</b>다. 그 세션의 뒷정리는
     * 루프가 들어올 때 이미 끝냈으므로({@code reconnectLoop} 첫 줄) 여기 {@code close}는
     * 자리가 빈 것을 보고 곧장 돌아온다 — 자기 자신을 기다리지 않는다.
     */
    private void stopOne(String streamId, StopReason reason) {
        Entry entry = sessions.remove(streamId);
        if (entry == null) {
            return;                       // 이미 닫혔다
        }
        log.warn("chat.registry.stopped stream={} reason={} retriable=false", streamId, reason);
        closeEntry(streamId, entry);
    }

    /**
     * <b>멈춤 신호를 먼저 내리고 닫는다.</b> 순서를 뒤집으면 뒷정리가 끝난 뒤에도
     * 재연결 루프가 살아 있어 <b>방금 닫은 방송에 다시 붙는다</b> — 종료 편지를 받고
     * 닫았는데 스스로 되살아나는 세션이 된다.
     */
    private void closeEntry(String streamId, Entry entry) {
        entry.stopSignal().countDown();
        entry.session().close();
        log.info("chat.registry.closed stream={} active={}", streamId, sessions.size());
    }
}
