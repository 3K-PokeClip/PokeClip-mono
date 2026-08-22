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

import java.time.Duration;
import java.util.ArrayList;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

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

    /**
     * <b>자리 열쇠는 방송이 아니라 스트리머다.</b> 한 사람은 동시에 두 방송을 할 수 없다.
     *
     * <p>방송 번호로 자리를 가르면 <b>같은 스트리머에 번호가 둘일 때 치지직 세션이 둘 선다.</b>
     * 실제로 아끼는 자원은 번호가 아니라 사람이다 — 연결 상한 3개는 <b>계정별</b>이고, 구독
     * 대상은 채널·방송 파라미터가 아니라 <b>토큰 주인</b>이 정한다(CLAUDE.md 「스트리머 여러 명」).
     * 소켓 둘이 서면 <b>같은 채팅이 두 번 들어오고</b> 상한 3개 중 둘을 우리 손으로 태운다
     * (재현: {@code authCalls=2 connections=2 connectedTokens=[tokSame]}, 채팅 1건 → 바구니 2건).
     *
     * <p><b>도달 경로가 실재한다.</b> SQS FIFO의 {@code MessageGroupId}가 방송 번호라
     * 같은 스트리머의 앞 방송 ENDED와 다음 방송 STARTED는 <b>서로 다른 그룹이라 순서 보장이
     * 없다.</b> 끝난 방송 메모도 방송 번호가 열쇠라 다른 번호를 못 막는다.
     *
     * <p><b>토큰이 아니라 {@code streamerId}로 가른다.</b> 토큰은 갱신되면 문자열이 바뀌어
     * 「같은 사람의 새 토큰」을 못 알아본다 — 그러면 이 방어선이 조용히 뚫린다.
     *
     * <p>「지금 어느 방송인가」는 여기 따로 안 든다. <b>세션이 든다</b>({@code session.key()}) —
     * 두 곳에 두면 갈아끼울 때 어긋나고, 어긋난 쪽을 태스크 12가 읽으면 채팅이 틀린 방송
     * 번호로 기록된다.
     */
    private final ConcurrentHashMap<Long, Entry> sessions = new ConcurrentHashMap<>();

    /**
     * 닫힌 세션이 받은 채팅의 누계. <b>살아 있는 세션 몫만 더하면 세션이 닫힐 때마다
     * 총량이 줄어든다</b> — 판정 줄이 그것을 「받은 게 없다」로 읽는다.
     */
    private final AtomicLong closedReceived = new AtomicLong();

    /**
     * 이 프로세스가 <b>실제로 세운</b> 세션의 수. 지금 붙어 있는 수가 아니라 누계다.
     *
     * <p>최종 판정 줄의 {@code session=}은 <b>러너 자신의</b> 마지막 세션 번호라 편지
     * 경로에서는 언제나 0이다 — 러너가 세션을 하나도 안 열기 때문이다. 그러면 판정 줄만
     * 보고 「이 프로세스는 아무것도 안 걷었다」로 읽힌다. 이 값이 그 자리를 메운다.
     *
     * <p><b>{@code lastSessionNo}를 쓰지 않는다.</b> 그것은 「가장 나중에 자리를 잡은 세션」이라
     * 세션이 N개면 뜻이 없고, 판정 줄에 실으면 <b>남의 번호가 나간다</b>(감사 사소-1).
     *
     * <p>번호 갈아끼움(retarget)은 안 센다. 세션도 소켓도 그대로이므로 새로 선 것이 아니다 —
     * 세면 방송 수가 되고, 그러면 이 항이 「몇 명을 걷었나」를 못 말한다.
     */
    private final AtomicLong sessionsOpened = new AtomicLong();

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
     * 전역 이름이다.
     *
     * <p><b>게이트는 {@link #closeAll()}이 세션을 다 닫은 뒤에 내린다.</b> 러너의 것과
     * <b>다른 객체다</b> — 러너가 자기 게이트를 내리는 시점(싱크 닫기 직전)은 이 등록부의
     * 종료보다 뒤이고, 하나로 합치려면 러너↔등록부가 서로를 참조해야 해서 빈 순환이 된다
     * (판정 줄이 등록부의 수신량을 읽는 방향이 이미 러너→등록부다).
     *
     * <p><b>🔴 {@code releasesInFlight}를 읽는 코드는 여기 0곳이다.</b> 러너에서는 그 값이
     * 「인터럽트 전에 더 기다릴까」를 정하는데, 등록부는 <b>인터럽트를 아예 안 한다</b>
     * ({@link #shutdown()}이 실행기에 {@code shutdown()}만 부른다) — 더 기다려서 지킬 것이 없다.
     * 세션 생성자가 요구해서 넘길 뿐이다.
     *
     * <p><b>🔴 {@code lastSessionNo}는 읽지 마라.</b> 세션이 자리를 잡을 때마다 덮어써서
     * 「가장 나중에 자리를 잡은 세션」이 된다 — 세션이 N개면 뜻이 없는 값이다. 러너에서는
     * 판정 줄이 이것을 「이 프로세스가 몇 번째까지 갔나」로 읽는데, 등록부에서 같은 식으로
     * 집으면 <b>남의 세션 번호가 나간다.</b> 세션 번호가 필요하면 세션이 자기 것을 들게
     * 한다(태스크 13). 지금 등록부 경로에서 읽는 코드는 0곳이다.
     */
    private final AtomicBoolean intakeClosed = new AtomicBoolean();
    private final AtomicInteger releasesInFlight = new AtomicInteger();
    private final AtomicLong lastSessionNo = new AtomicLong();

    /**
     * <b>종료 빗장.</b> 서면 이 등록부는 세션을 더 열지 않는다.
     *
     * <p>정지 신호 뒤에도 편지 한 통이 더 들어올 수 있다 — 큐 롱폴링(최대 20초)에 들어간
     * {@code receiveMessage}는 인터럽트로 안 끊기고, 돌아오면 <b>이미 받은 편지를 마저
     * 처리한다</b>({@code SqsIntakeLoop.JOIN_WAIT}는 2초다). 그 회차를 기다려 주면
     * <b>그 하나로 종료 유예 20초를 다 쓴다.</b> 그래서 기다리지 않고 여기서 막는다.
     *
     * <p><b>편지는 안 잃는다.</b> {@code open()}이 false이고 그 스트리머의 현재 방송도 없으면
     * {@code LinkedSessionStarter}가 {@code RETRY_LATER}를 내므로 편지는 지워지지 않고
     * 큐에 남는다 — 가시성 타임아웃 뒤 다음 프로세스가 받는다.
     */
    private final AtomicBoolean closing = new AtomicBoolean();

    /**
     * 살아 있는 세션 전부를 닫는 데 쓰는 예산. <b>세션 수와 무관한 값이다</b> —
     * 나란히 닫으므로 전체 = 가장 느린 하나다(태스크 1 실계정: 반납 셋 총 소요 67ms =
     * 최대 개별 소요, 전 구간 HTTP/2).
     *
     * <p>산수는 <b>관측이 아니라 시한</b>이다 — 반납 REST 접속 2초 + 읽기 5초
     * ({@code spring.http.clients}) + 소켓 닫기 1초(태스크 8B 실측: 첫 {@code .get(1s)}가
     * 만료되면 catch로 빠져 둘째가 안 돈다). 실측 왕복은 55~69ms라 평시에는 이 예산에
     * 근처도 안 간다.
     *
     * <p>종료 유예 20초 안의 자리: 마지막 회차 join 2 + <b>여기 8</b> + 싱크 닫기 5 = 15초.
     */
    static final Duration CLOSE_ALL_BUDGET = Duration.ofSeconds(8);

    /**
     * 재시도로 안 풀리는 사유가 확정됐을 때 <b>등록부에서 지우기 전에</b> 부른다. 기본은 아무것도 안 한다.
     * 등록부는 저장소를 모른다 — 메모를 남기는 쪽({@code StoppedStreamRecorder})이 건다.
     *
     * <p><b>지우기 전인 이유</b>: 지운 뒤에 알리면 메모가 남기 전까지 창구가 그 방송을 {@code unknown}
     * (배너 끔)으로 답한다 — 가장 나쁜 상태가 가장 안전하게 보이는 틈이다. 앞에 두면 그 동안 STOPPED로
     * 남아 {@code stopped}를 답한다. 반납은 안 밀린다 — 두 호출 지점 다 소켓·구독이 이미 없다.
     */
    private volatile BiConsumer<String, StopReason> permanentStopListener = (streamId, reason) -> { };

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
     * <b>단일 리스너다. 두 번 부르면 앞엣것이 조용히 사라진다</b>(add가 아니라 set) — 이름이
     * {@code on…}이라 add로 읽히기 쉬워 적어 둔다. 운영 등록처는 {@code LetterPathConfiguration}
     * 하나뿐이라 지금 도달 경로가 없지만, <b>검사에서 {@code new StoppedStreamRecorder(...)} 뒤에
     * 한 번 더 부르면 레코더가 떨어져 나가 메모가 안 남는다</b>(POK-128 critic A2 재현).
     * 리스트로 바꾸지 않은 이유는 지금 둘째 소비자가 없어서다.
     */
    public void onPermanentStop(BiConsumer<String, StopReason> listener) {
        this.permanentStopListener = listener;
    }

    /** 리스너가 던져도 등록부는 멀쩡해야 한다. 타입 이름만 — 메시지엔 DB 주소가 들어 있다. */
    private void notifyPermanentStop(String streamId, StopReason reason) {
        try {
            permanentStopListener.accept(streamId, reason);
        } catch (Throwable t) {
            log.warn("chat.registry.stop_listener_failed stream={} causeType={}",
                    streamId, t.getClass().getSimpleName());
        }
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
        long streamerId = key.streamerId();
        Entry seated = sessions.get(streamerId);
        if (seated != null) {
            return retargetOrSkip(seated, key);
        }
        CollectionStatus status = new CollectionStatus();
        CollectionMetrics metrics = new CollectionMetrics();
        CountDownLatch stopSignal = new CountDownLatch(1);
        // <b>자기 자신을 지목하는 손잡이다.</b> 세션 생성자가 콜백을 받으므로 여기서
        // 세션을 직접 캡처할 수 없어 한 칸을 먼저 세운다. 세션이 만들어진 <b>뒤</b>에
        // 채우고, 콜백은 그 세션이 열린 뒤에만 불리므로 늘 채워진 것을 본다.
        AtomicReference<StreamSession> self = new AtomicReference<>();
        StreamSession session = new StreamSession(
                key, accessToken, properties, status, metrics,
                new ReconnectPolicy(properties.reconnectFirstDelay(), properties.reconnectMaxDelay()),
                restClient, buffer, persister, archive,
                reconnectors, stopSignal, intakeClosed, releasesInFlight, lastSessionNo,
                reason -> stopOne(streamerId, self.get(), reason));
        self.set(session);
        Entry entry = new Entry(session, status, metrics, stopSignal);
        // <b>자리를 먼저 잡는다.</b> 수립이 끝난 뒤에 넣으면 REST 왕복 두 번 동안
        // 자리가 비어 있어, 같은 편지가 두 번 오면 세션이 둘 선다.
        Entry raced = sessions.putIfAbsent(streamerId, entry);
        if (raced != null) {
            // 위 get과 여기 사이에 그 스트리머의 자리가 찼다. 우리 것은 아직 아무것도
            // 안 열었으므로 버리고 자리 주인 쪽 규칙을 그대로 따른다.
            return retargetOrSkip(raced, key);
        }
        // <b>빗장을 자리 잡은 뒤에 본다.</b> 앞에서만 보면 「빗장 없음을 읽고 → closeAll이
        // 자리를 다 걷어가고 → 우리가 자리를 잡는」 순서에서 아무도 안 닫는 세션이 남는다.
        // 뒤에서 보면 둘 중 하나다 — 빗장을 보고 우리가 물러나거나, 우리 자리가 걷어가는
        // 쪽 눈에 들어오거나. (빗장 쓰기가 걷어가기보다 먼저이므로, false를 읽었다는 것은
        // 우리 자리가 그 전에 이미 맵에 있었다는 뜻이다. <b>재현이 아니라 논증이다.</b>)
        if (closing.get()) {
            sessions.remove(streamerId, entry);
            log.info("chat.registry.open_skipped stream={} reason=SHUTTING_DOWN", streamId);
            return false;
        }
        // <b>수립은 맵 밖에서 한다.</b> 발급·구독 왕복 두 번이 맵의 락 안에 들어가면
        // 그 사이 다른 방송의 열기·닫기가 전부 막힌다.
        try {
            if (session.open()) {
                sessionsOpened.incrementAndGet();
                return true;
            }
        } catch (SessionEstablishException e) {
            // 사유별 갈래는 편지를 판정하는 쪽이 정한다(태스크 10). 여기서 재시도하지
            // 않는 이유는 그쪽이다 — 큐에 남은 편지가 다시 오는 것이 재시도다.
            log.warn("chat.registry.open_failed stream={} reason={}", streamId, e.reason());
            if (!ReconnectPolicy.retriable(e.reason())) {
                // <b>첫 수립에서 영구 실패.</b> 이 길은 재연결 루프를 안 타므로 stopOne이 안 불린다.
                // 여기서 알려야 창구가 stopped를 답하고, 같은 시작 편지의 재전송이 다음 회차에
                // 「끝난 방송 뒤의 시작」으로 지워져 영원히 도는 것을 멈춘다.
                //
                // <b>이 알림은 편지 폴링 스레드 위에서 동기로 돈다</b> — DB가 반개방이면 그 회차가
                // 최악 10초 선다({@code socketTimeout}, POK-128 critic 실측 10.02초). 비동기로
                // 빼지 않는다: 같은 DB 상태면 이 편지의 판정이 이미 {@code EndedStreamStore.find}에서
                // 같은 시한에 막힌 뒤라, 여기만 비동기로 빼도 회차 시간이 안 줄어든다.
                // <b>알리기 전에 이 자리를 멈춤으로 표시한다.</b> 이 길은 status를 아무도 안
                // 건드려 ESTABLISHING 그대로인데, 창구에서 establishing은 <b>배너를 끄는 값</b>이다
                // (PRD 응답 표) — 포기한 방송이 「붙는 중」으로 보인다. 같은 401을 재연결 루프에서
                // 맞는 길(ⓐ)은 StreamSession이 이미 status.stopped(reason)을 찍고 나서 내려오므로
                // <b>두 길의 답이 갈려 있었다.</b>
                //
                // 덤으로 재연결 루프의 이중 알림 창이 닫힌다: 수립 중 절단으로 루프가 떠 있으면
                // 그 루프도 onPermanentStop을 부를 수 있는데, STOPPED를 먼저 찍으면 루프가
                // 자기 앞의 상태 검사에서 돌아선다.
                status.stopped(e.reason());
                notifyPermanentStop(streamId, e.reason());
            }
        }
        // 못 세웠으면 자리를 비우고 <b>그 세션을 닫는다.</b> 자리만 비우면 안 되는 이유는
        // <b>수립이 실패해도 그 세션의 재연결 루프는 이미 떠 있을 수 있다</b>는 것이다 —
        // 수립 중에 끊기면 절단이 {@code requestReconnect}를 태우고, 그 루프는 자기
        // {@code stopSignal}만 본다. 등록부가 자리에서 지우는 것을 루프는 모른다.
        //
        // 그러면 백오프 뒤 <b>스스로 다시 붙어</b> 등록부가 모르는 세션이 된다:
        // ① 계정당 상한 3개를 우리 손으로 태운다(429는 속도 제한이 아니라 「자리 없음」이라
        //    시간이 아니라 자리가 풀려야 낫는다 — 태스크 1 실계정) ② {@link #shutdown()}이
        //    훑을 곳에 없어 못 닫는다 ③ 같은 편지가 다시 오면 같은 계정에 소켓이 둘 서고,
        //    구독 대상이 토큰 주인이라 <b>같은 채팅이 두 소켓에 다 들어온다.</b>
        //
        // <b>수립이 예외로 끝나는 길도 여기로 합류한다.</b> 위 catch가 아래로 흘러온다 —
        // 갈래마다 따로 두면 한쪽만 고친 상태가 조용히 남는다.
        //
        // 우리가 넣은 것일 때만 지우고 닫는다 — 그 사이 남이 자리를 잡았으면 남의 것이고,
        // {@code stopOne}이 먼저 가져갔으면 받은 양이 두 번 걷힌다.
        if (sessions.remove(streamerId, entry)) {
            closeEntry(streamId, entry);
        }
        return false;
    }

    /**
     * 그 스트리머의 자리가 이미 차 있다. <b>새 방송이면 번호만 갈아끼고, 같은 방송이면
     * 아무것도 안 한다.</b>
     *
     * <p><b>닫았다 새로 열지 않는다.</b> 그 사이의 채팅이 유실되기 때문이다 — 실시간
     * 푸시라 늦게 붙으면 그 구간을 되받을 방법이 없다. 그리고 <b>닫지 않는 한 새 방송의
     * 채팅은 이미 이 소켓으로 들어오고 있다</b>(구독은 토큰 주인의 채팅이라 방송을 못 고른다).
     *
     * <p><b>거절해도 안 된다.</b> 거절하면 등록부는 앞 방송이 현재라고 믿는데 소켓으로는
     * 새 방송의 채팅이 들어온다. 그러면 ① 태스크 12가 그 채팅을 <b>끝난 방송 번호로</b>
     * 찍고 ② 뒤늦게 온 앞 방송의 ENDED가 <b>살아 있는 새 방송을 끊고</b> ③ 그것을 되살릴
     * STARTED 편지는 이미 소비돼 큐에 없다.
     *
     * @return 갈아끼웠으면 true. 같은 방송이면 false다 — <b>그것이 시작 편지의 멱등
     *         방어선이다</b>(태스크 10). 갈아끼움이 true인 것은 「더 볼 일 없음」이라
     *         그 편지가 지워져야 하기 때문이다
     */
    private boolean retargetOrSkip(Entry seated, SessionKey key) {
        SessionKey current = seated.session().key();
        if (current.streamId().equals(key.streamId())) {
            log.info("chat.registry.open_skipped stream={} reason=ALREADY_OPEN", key.streamId());
            return false;
        }
        if (seated.status().state() == CollectionStatus.State.STOPPED) {
            // 포기가 확정돼 지워지는 중인 세션이다 — 포기 메모를 남기는 동안(DB 시한 최악 10초) 자리에
            // 남는다. 여기에 새 방송을 갈아끼우면 이름만 바뀐 죽은 세션에 편지가 소비되고(true -> 삭제)
            // 곧 지워져 새 방송은 등록부에도 큐에도 없다 — 영구 유실. 거절하면 LinkedSessionStarter가
            // RETRY_LATER로 편지를 남겨 다음 회차에 빈 자리에 새로 연다(isStaleStart는 false다).
            // PR #98 2판 codex P1 「좁은 창, 재현 못 함」이 이 자리다 — SessionRegistryTest가 리스너를
            // 붙들어 결정적으로 연다.
            // <b>첫 수립이 영구 실패한 뒤 알림 구간도 이제 이 가드에 걸린다.</b> 여기 「첫 수립에서는
            // 안 걸린다 — 그때 ESTABLISHING이다」라고 적혀 있었는데, open()의 catch가 알림 앞에
            // status.stopped(reason)을 찍게 되면서 그 자리도 STOPPED가 됐다. <b>동작은 안전한 쪽으로
            // 바뀌었다</b> — 그전에는 그 창에서 갈아끼움이 성립해 위 문단이 「영구 유실」이라 부르는
            // 바로 그 모양(죽어가는 세션에 이름만 갈아끼우고 편지를 지움)이 될 수 있었다.
            // <b>지금 도달 경로가 없는 이유는 상태가 아니라 스레드 수다</b> — 편지 폴링이 스레드
            // 하나(SqsIntakeLoop)이고 registry.open을 부르는 자리도 LinkedSessionStarter 하나뿐이라,
            // 같은 스레드가 자기 open() 안에서 블로킹 중에 다시 open()을 부를 수 없다.
            // <b>수립을 워커로 빼는 날</b>(CLAUDE.md 「다음 카드로 넘긴 것」) 이 자리를 다시 본다.
            log.info("chat.registry.open_deferred streamer={} stream={} reason=SEAT_STOPPING",
                    key.streamerId(), key.streamId());
            return false;
        }
        // <b>지금 걷는 방송보다 늦게 시작한 방송만 자리를 가져간다.</b> 없으면 늦게 도착한
        // 앞 방송의 시작 편지가 <b>살아 있는 뒤 방송을 자기 쪽으로 되돌린다</b> — 그 뒤
        // 앞 방송의 종료 편지가 오면 세션이 닫히는데, 뒤 방송의 시작 편지는 이미 소비돼
        // 되살릴 길이 없다. 도달 경로는 이 클래스가 이미 아는 사실이다: FIFO 그룹이 방송
        // 번호라 같은 스트리머의 두 방송은 순서 보장이 없고, 앞 방송의 시작이 auth 장애로
        // RETRY_LATER에 걸려 밀리면 뒤 방송이 먼저 처리된다(codex P1, 재현함).
        //
        // <b>{@code sequence}로는 못 가른다</b> — 방송 안에서만 뜻이 있는 번호라
        // 다른 방송끼리 비교하면 아무것도 아니다.
        if (!key.startedAt().isAfter(current.startedAt())) {
            log.warn("chat.registry.stale_start_rejected streamer={} current={} rejected={} "
                            + "currentStartedAt={} rejectedStartedAt={}",
                    key.streamerId(), current.streamId(), key.streamId(),
                    current.startedAt(), key.startedAt());
            return false;
        }
        // 세션이 「지금 어느 방송인가」의 유일한 주인이다. 등록부가 따로 들면 어긋난다.
        // <b>앞 방송이 받은 양은 여기서 프로세스 누계로 옮긴다</b> — 세션의 방송 단위
        // 지표는 새 경계에서 0부터 다시 세므로, 안 옮기면 그만큼 총량이 줄어
        // 판정 줄이 유실로 읽는다.
        closedReceived.addAndGet(seated.session().retarget(key));
        // <b>바꾼 뒤 자리를 다시 본다.</b> 위 조회와 여기 사이에 그 세션이 영구 정지로
        // 자리를 잃었을 수 있고, 그러면 방금 이름을 바꾼 것은 <b>이미 닫힌 세션</b>이다.
        // 그때 true를 돌려주면 편지가 지워지는데 등록부에는 이 방송이 없다 — 영구 유실이다
        // (codex P1. 좁은 창이라 <b>재현하지 못했다</b> — 결정적으로 여는 장치를 못 만들었다).
        // false면 편지가 큐에 남아 다음 회차에 다시 온다.
        if (sessions.get(key.streamerId()) != seated) {
            log.warn("chat.registry.retarget_lost_seat streamer={} stream={}",
                    key.streamerId(), key.streamId());
            return false;
        }
        log.info("chat.registry.retargeted streamer={} from={} to={}",
                key.streamerId(), current.streamId(), key.streamId());
        return true;
    }

    /**
     * 이 시작 편지가 <b>지금 걷는 방송보다 이른가</b> — 즉 다시 물어도 답이 안 바뀌는가.
     *
     * <p>{@link #open}이 false를 돌려준 뒤 편지를 <b>지울지 남길지</b>를 가르는 값이다.
     * 낡은 시작을 「나중에 다시」로 두면 그 방송의 FIFO 그룹 앞을 영원히 막는다 —
     * 몇 번을 다시 물어도 그 편지는 계속 낡았기 때문이다.
     */
    public boolean isStaleStart(long streamerId, Instant startedAt) {
        Entry entry = sessions.get(streamerId);
        return entry != null && !startedAt.isAfter(entry.session().key().startedAt());
    }

    /**
     * 이 방송의 수집을 멈춘다.
     *
     * <p><b>그 방송이 아직 현재일 때만 닫는다.</b> 이미 다음 방송으로 갈아낀 뒤에 앞 방송의
     * 종료 편지가 늦게 오는 길이 있는데(FIFO 그룹이 방송 번호라 순서 보장이 없다), 거기서
     * 닫으면 <b>살아 있는 새 방송을 끊는다</b> — 그리고 그것을 되살릴 STARTED 편지는 이미
     * 소비돼 큐에 없다.
     *
     * @return 닫을 세션이 있었는가. 없거나 <b>옛 번호</b>면 false다 — 종료 편지가 두 번 와도,
     *         늦게 와도 무해하다
     */
    public boolean close(String streamId) {
        Entry entry = findByStreamId(streamId);
        if (entry == null) {
            return false;
        }
        long streamerId = entry.session().key().streamerId();
        // 그 사이에 갈아끼워졌으면 지우지 않는다. 값까지 맞을 때만 지운다.
        if (!sessions.remove(streamerId, entry)) {
            return false;
        }
        closeEntry(streamId, entry);
        return true;
    }

    /**
     * 살아 있는 세션을 <b>나란히</b> 닫는다.
     *
     * <p><b>순차로 닫으면 (반납 + 소켓 닫기) × 세션 수다.</b> 실측 기준 세션당 약 1.07초
     * (반납 55~69ms + 소켓 닫기 1초, 태스크 1·8B)라 열 명이면 약 11초, 백 명이면 유예
     * 20초를 넘긴다. 나란히 닫으면 전체가 가장 느린 하나가 된다.
     *
     * <p><b>「동시에 부른다」와 「동시에 나간다」는 다르다.</b> {@link RestClient}가 프로세스에
     * 하나라 같은 호스트 동시 연결 상한에 걸리면 실제로는 배치로 나가고 예산이 배치 수만큼
     * 곱해진다. {@code SessionShutdownTest.반납이_실제로_겹쳐서_나간다}가 그것을 <b>상대 쪽에서
     * 행동으로</b> 잰다(가짜 서버 안에 반납이 동시에 몇 개 들어와 있었나).
     *
     * <p><b>🔴 그 검사는 가짜 서버를 상대로 잰다.</b> 가짜는 HTTP/1.1이라 커넥션을 나눠 써서
     * 겹치고, 치지직은 HTTP/2라 커넥션 하나에 스트림으로 몰려 겹친다 — <b>결론은 같고 이유가
     * 다르다.</b> 판정 근거는 태스크 1의 실계정 프로브다: 세션 셋 동시 반납이 총 67ms·69ms로
     * <b>최대 개별 소요와 같았고</b>(배치면 합인 178ms·184ms가 나온다) 전 구간 HTTP_2였다.
     * 그래서 세션별 클라이언트도 반납 전용 풀도 두지 않았다.
     *
     * <p><b>여기는 빗장을 안 건다.</b> 부르고 나서도 이 등록부는 계속 쓸 수 있다 —
     * 새 편지가 오면 다시 연다. 돌아올 수 없는 종료는 {@link #shutdown()}이다.
     * (그 구분이 없을 때 검사들의 뒷정리가 빗장을 걸어 버려 다음 검사의 편지가 전부
     * 조용히 안 열렸다 — 2026-08-19에 전수 검사가 그것을 잡았다.)
     */
    public void closeAll() {
        List<Future<?>> closings = new ArrayList<>();
        for (Map.Entry<Long, Entry> seat : sessions.entrySet()) {
            Entry entry = seat.getValue();
            // 값까지 맞을 때만 걷는다. 그 사이에 stopOne·close가 가져갔으면 남의 것이다.
            if (!sessions.remove(seat.getKey(), entry)) {
                continue;
            }
            String streamId = entry.session().key().streamId();
            // <b>누계는 여기서 옮긴다 — 제출한 스레드 안에서 하면 늦다.</b> 자리는 이미
            // 뺐는데 그 스레드가 스케줄될 때까지 총량이 그 세션 몫만큼 비고, 예산을
            // 넘겨 포기하면 그 상태로 판정 줄이 찍힌다(detach 주석).
            long detached = detach(entry);
            closings.add(reconnectors.submit(() -> closeEntry(streamId, entry, detached)));
        }
        awaitClosed(closings);
    }

    /**
     * <b>프로세스가 끝난다 — 빗장을 걸고 전부 닫는다.</b> {@link #closeAll()}과 달리
     * <b>돌아올 수 없다.</b>
     *
     * <p>둘을 가른 이유: {@code closeAll()}은 「지금 붙어 있는 것을 다 닫는다」이고 그 뒤에도
     * 등록부는 계속 쓰인다(검사들의 뒷정리가 그 뜻으로 부른다 — 빗장을 거기 두었더니
     * <b>다음 검사의 편지가 전부 조용히 안 열렸다</b>). 이쪽은 종료 전용이다.
     *
     * <p>순서가 요점이다 — <b>빗장 → 전부 닫기 → 수신 게이트 → 실행기.</b>
     * 게이트를 앞에 내리면 아직 살아 있는 세션의 채팅을 <b>퍼시스터가 멀쩡한데도</b> 버린다.
     * 뒤에 내리면 소켓이 닫힌 뒤 콜백에 남아 있던 마지막 프레임이 <b>곧 닫힐 바구니</b>에
     * 들어가 아무도 저장하지 않는다.
     */
    public void shutdown() {
        // 빗장부터 건다. 자리를 걷어내는 동안 새 세션이 들어오면 아무도 그것을 닫지 않는다 —
        // 자리를 잡은 뒤 빗장을 다시 보는 open()의 검사와 짝이다.
        closing.set(true);
        closeAll();
        intakeClosed.set(true);
        // <b>{@code shutdownNow()}가 아니다.</b> 인터럽트하면 나가 있는 반납 REST가 즉시
        // 실패하는데 세션 키는 이미 소모된 뒤라 <b>아무도 다시 못 보낸다</b> — 서버 쪽 자리가
        // 10초~4분 42초 남고(실측) 상한은 계정당 3개다. 가상 스레드라 JVM 종료를 안 붙든다.
        reconnectors.shutdown();
    }

    /**
     * 예산 안에서 닫히기를 기다린다. <b>시한이 차도 취소하지 않는다</b> —
     * {@code cancel(true)}는 인터럽트라 위와 같은 이유로 반납을 끊는다. 못 기다린 것은
     * 프로세스가 죽을 때까지 데몬 위에서 계속 돈다.
     */
    private void awaitClosed(List<Future<?>> closings) {
        long deadline = System.nanoTime() + CLOSE_ALL_BUDGET.toNanos();
        for (int i = 0; i < closings.size(); i++) {
            try {
                closings.get(i).get(Math.max(deadline - System.nanoTime(), 0), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                // 전부 같은 시한을 나눠 쓰므로 하나가 만료하면 남은 것도 만료다.
                // 조용히 넘어가면 「닫았다」와 「예산이 모자랐다」가 구분되지 않는다.
                log.warn("chat.registry.close_timeout budgetMs={} pending={}",
                        CLOSE_ALL_BUDGET.toMillis(), closings.size() - i);
                return;
            } catch (ExecutionException e) {
                // 하나가 터져도 나머지는 닫는다. 자리는 이미 걷어냈으므로 재시도할 곳이 없다.
                log.warn("chat.registry.close_failed reason={}",
                        e.getCause().getClass().getSimpleName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public int activeCount() {
        return sessions.size();
    }

    /** 이 프로세스가 세운 세션의 누계. 판정 줄이 싣는다 — 위 필드 주석이 이유다. */
    public long sessionsOpened() {
        return sessionsOpened.get();
    }

    /** 지금 걷고 있는 방송 번호들. 스트리머마다 하나다. */
    public Collection<String> activeStreamIds() {
        return sessions.values().stream()
                .map(e -> e.session().key().streamId())
                .toList();
    }

    /**
     * 그 스트리머가 <b>지금</b> 하고 있는 방송의 번호. 안 걷고 있으면 null이다.
     *
     * <p>세션에게 묻는다 — 등록부가 따로 들면 갈아끼울 때 어긋나고, <b>어긋난 쪽을 태스크
     * 12가 읽으면 채팅이 틀린 방송 번호로 기록된다.</b>
     */
    public String currentStreamIdOf(long streamerId) {
        Entry entry = sessions.get(streamerId);
        return entry == null ? null : entry.session().key().streamId();
    }

    /**
     * 이 등록부가 받은 채팅의 총량. <b>닫힌 세션 몫도 포함한다.</b>
     *
     * <p>계획이 태스크 9에 요구한 <b>합산 경로</b>다. 판정 줄의 등식
     * {@code received = persisted + conflicts + poisoned + dropped}는 좌변만 세션별이고
     * 우변은 공유 부품의 프로세스 누계라, 합산할 길이 없으면 <b>등식이 깨지는 것이 아니라
     * 「받은 게 없다」로 읽힌다</b> — 유실을 알아채는 유일한 계기가 죽는다.
     *
     * <p><b>{@code CollectionMetrics}를 합쳐서 주지 않는다.</b> 그 클래스에는 병합이 없고,
     * 스무 항 중 여럿은 더할 수 있는 값이 아니다 — 지연 중앙값은 표본을 버린 뒤라 다시 못
     * 내고, 최대 수신 공백·최대 ping 간격은 최댓값이며, 절단 시각은 「마지막 하나」다.
     * 억지로 하나로 만들면 <b>판정 줄이 조용히 틀린 숫자를 싣는다.</b> 등식이 실제로
     * 필요로 하는 좌변은 {@code received} 하나라 그것만 정확하게 낸다.
     *
     * <p><b>판정 줄에 싣는 배선은 아직 없다.</b> 그 줄을 내는 곳이
     * {@code CollectorRunner.logVerdictOnce}이고 러너는 등록부를 모른다 — 이어 주는 것은
     * 종료를 다루는 태스크 11이다. 여기는 그쪽이 쓸 값을 내주는 데까지다.
     */
    public long receivedTotal() {
        return closedReceived.get()
                + sessions.values().stream().mapToLong(e -> e.metrics().totalReceived()).sum();
    }

    /**
     * 그 방송이 받은 채팅 수. 없거나 이미 닫혔으면 0이다.
     *
     * <p><b>세션별 지표가 실제로 갈려 있는지를 재는 자다.</b> 지표를 나눠 쓰면 두 방송이
     * 서로의 수를 읽는다 — 그때 {@code beginSession()}의 수신 시계 되잡기가 남의 수신
     * 공백까지 지워, 한 방송이 한 건도 못 받는데 공백이 안 자라는 상태가 된다.
     */
    public long receivedOf(String streamId) {
        Entry entry = findByStreamId(streamId);
        return entry == null ? 0L : entry.metrics().totalReceived();
    }

    /**
     * 이 방송의 지금 상태. 없으면 null이다.
     *
     * <p><b>낱개 getter가 아니라 스냅숏을 준다.</b> 상태와 사유를 이어 읽으면 그 사이에
     * 재접속이 성공해 "재연결 중인데 사유는 없음"이 나온다({@code CollectionStatus} 주석).
     */
    public CollectionStatus.Snapshot statusOf(String streamId) {
        Entry entry = findByStreamId(streamId);
        return entry == null ? null : entry.status().snapshot();
    }

    /**
     * 지금 몇이 붙어 있고 <b>그중 몇이 다시 붙는 중인가.</b> health가 상세에 싣는다.
     *
     * <p><b>한 번의 순회로 낸다.</b> 붙어 있는 수와 재연결 중인 수를 이어 세면 그 사이에
     * 세션이 닫혀 <b>재연결 중인 수가 붙어 있는 수보다 큰</b> 응답이 나간다 — 읽는 사람은
     * 그것을 「집계가 깨졌다」가 아니라 「내가 뭘 잘못 읽었다」로 읽고 진짜 신호를 흘린다.
     * {@link CollectionStatus}가 상태와 사유를 한 참조로 묶은 것과 같은 이유다.
     *
     * <p>순회 자체는 {@code ConcurrentHashMap}이라 약한 일관성이다 — 도중에 들어온 세션이
     * 보일 수도 안 보일 수도 있다. <b>막을 수 있는 것은 두 값이 서로 어긋나는 것까지다.</b>
     */
    public Counts counts() {
        int active = 0;
        int reconnecting = 0;
        for (Entry entry : sessions.values()) {
            active++;
            if (entry.status().state() == CollectionStatus.State.RECONNECTING) {
                reconnecting++;
            }
        }
        return new Counts(active, reconnecting);
    }

    /** @param reconnecting {@code active}의 부분집합이다. 둘을 더하지 마라 */
    public record Counts(int active, int reconnecting) { }

    /**
     * 방송 번호로 자리를 찾는다. <b>훑는다</b> — 자리 열쇠가 스트리머라 역방향 색인이 없다.
     * 동시 수집 상한이 스트리머 100명이라(설계 전제) 훑는 비용이 색인을 유지하는 비용보다 싸고,
     * 색인을 따로 두면 갈아끼울 때 어긋나는 자리가 하나 더 생긴다.
     */
    private Entry findByStreamId(String streamId) {
        for (Entry entry : sessions.values()) {
            if (entry.session().key().streamId().equals(streamId)) {
                return entry;
            }
        }
        return null;
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
     * <p><b>스트리머로 지우되 값까지 본다.</b> 방송 번호로 지우면 그 사이에 갈아끼워진 새
     * 방송을 못 찾아 자리가 안 비워지고, 반대로 <b>스트리머만 보고 지우면 그 자리에 이미
     * 앉은 다음 세션을 낡은 신호가 끊는다</b> — {@link #close(String)}·{@link #closeAll()}이
     * 값까지 보는 것과 같은 이유인데 여기만 빠져 있었다(감사 재현: 낡은 손잡이를 부르니
     * {@code active=1 connected=true}가 {@code active=0 connected=false}가 됐다).
     *
     * <p><b>실제 도달 경로는 재현하지 못했다.</b> 창은 루프가 {@code while}을 통과하고
     * 이 콜백에 닿기까지의 몇 줄이라 붙잡을 I/O가 없다. 다만 <b>유령 세션이 그 창을 크게
     * 넓혔다</b> — 등록부가 모르는 루프는 언제 재시도 불가 사유를 만날지 모르는데 그 자리에는
     * 이미 새 세션이 앉아 있다. 그쪽은 위 {@code open()}에서 막았고, 여기 비대칭 자체는
     * 그것과 별개로 남아 있어서 같이 맞춘다.
     *
     * <p>이 호출은 <b>그 세션의 재연결 루프 스레드 위</b>이고, 그 세션의 뒷정리는 루프가
     * 들어올 때 이미 끝냈으므로({@code reconnectLoop} 첫 줄) 아래 {@code close}는 자리가
     * 빈 것을 보고 곧장 돌아온다 — 자기 자신을 기다리지 않는다.
     *
     * @param caller 이 신호를 낸 세션. <b>자리 주인이 그것이 아니면 아무것도 안 한다.</b>
     *               갈아끼움(retarget)은 세션 객체를 그대로 두므로 여기서 안 걸린다
     */
    private void stopOne(long streamerId, StreamSession caller, StopReason reason) {
        Entry entry = sessions.get(streamerId);
        if (entry == null || entry.session() != caller) {
            return;                       // 이미 닫혔거나, 그 자리는 남의 세션이다
        }
        String streamId = entry.session().key().streamId();
        // <b>지우기 전에 알린다</b>(permanentStopListener 주석). 이 스레드는 그 세션의 재연결 루프이고
        // 뒷정리(cleanUpOnce)는 루프 첫 줄에서 이미 끝났으므로, 알림이 DB 시한에 걸려도 반납은 안 밀린다.
        // 그 동안 이 자리는 STOPPED로 남는다 — retargetOrSkip이 그것을 보고 갈아끼움을 거절한다.
        notifyPermanentStop(streamId, reason);
        if (!sessions.remove(streamerId, entry)) {
            return;                       // 위 검사와 여기 사이에 남이 가져갔다
        }
        log.warn("chat.registry.stopped stream={} reason={} retriable=false", streamId, reason);
        closeEntry(streamId, entry);
    }

    /** 자리를 뺀 세션의 누계를 옮기고 닫는다. 닫기를 제출하는 쪽은 아래 셋째 인자를 쓴다. */
    private void closeEntry(String streamId, Entry entry) {
        closeEntry(streamId, entry, detach(entry));
    }

    /**
     * <b>자리를 뺀 세션의 누계를 그 자리에서 즉시 옮긴다.</b> 호출부는 이미
     * {@code sessions.remove}를 마쳤으므로, 이 줄이 늦어지는 만큼
     * {@link #receivedTotal()}이 그 세션 몫만큼 <b>작게 나오는 창</b>이 열린다 —
     * {@link #closeAll()}은 닫기를 다른 스레드에 제출하므로 그 스레드가 스케줄될
     * 때까지, 그리고 닫기 자체가 반납 왕복만큼(반개방이면 초 단위) 벌어진다.
     *
     * <p>그 창에서 {@code closeAll}이 예산을 넘겨 포기하거나 닫기가 던지면
     * <b>작아진 값이 그대로 판정 줄에 실린다</b>({@code CollectorRunner.stop()}이 바로
     * 다음 줄에서 찍는다). 등식
     * {@code received = persisted + conflicts + poisoned + dropped}가 깨지는데,
     * 그것이 유실을 알아채는 유일한 계기다.
     *
     * @return 옮긴 양. 닫는 도중에 더 들어온 몫을 델타로 더하는 데 쓴다
     */
    private long detach(Entry entry) {
        long received = entry.metrics().totalReceived();
        closedReceived.addAndGet(received);
        return received;
    }

    /**
     * <b>멈춤 신호를 먼저 내리고 닫는다.</b> 순서를 뒤집으면 뒷정리가 끝난 뒤에도
     * 재연결 루프가 살아 있어 <b>방금 닫은 방송에 다시 붙는다</b> — 종료 편지를 받고
     * 닫았는데 스스로 되살아나는 세션이 된다.
     *
     * @param detached {@link #detach}가 이미 옮긴 양. <b>닫는 도중에 들어온 몫만</b>
     *                 finally에서 더한다 — 통째로 다시 더하면 두 번 세고, 안 더하면
     *                 {@code stopSignal} 이후에 흘러든 채팅이 빠진다
     */
    private void closeEntry(String streamId, Entry entry, long detached) {
        entry.stopSignal().countDown();
        try {
            entry.session().close();
        } finally {
            closedReceived.addAndGet(entry.metrics().totalReceived() - detached);
            log.info("chat.registry.closed stream={} active={}", streamId, sessions.size());
        }
    }
}
