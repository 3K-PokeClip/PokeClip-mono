package com.pokeclip.chat.collector.observe;

import com.pokeclip.chat.collector.chzzk.ChatMessage;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 30초 창 하나 분량의 관측값. <b>본문·작성자 식별자·닉네임을 담지 않는다</b> —
 * 세는 것만 세고, 무엇이었는지는 안 남긴다.
 *
 * <p>수신 콜백과 요약 스레드가 같이 만지므로 전부 {@code synchronized}다.
 * 30초에 표본 수천 건이라 락 경합은 문제가 되지 않는다.
 */
public final class CollectionMetrics {

    /**
     * @param systemEvents  누적이다. SYSTEM은 방송 내내 네 건뿐이라 창마다 비우면
     *                      대부분의 요약 줄에서 사라지는데, revoked가 왔다는 사실은
     *                      그 뒤 모든 줄에 남아 있어야 한다
     * @param lastReceivedAt 누적이다. 창이 비었다고 사라지면 "30초째 한 건도 못
     *                      받았다"를 요약이 말할 수 없다 — 그게 알아야 하는 상태다
     */
    public record Snapshot(
            long received,
            Instant lastReceivedAt,
            Duration maxReceiveGap,
            long orderViolations,
            Duration delayMin,
            Duration delayMedian,
            Duration delayMax,
            Map<String, Long> systemEvents,
            long decodeFailures
    ) { }

    /**
     * 세션 전체 누적. 30초 요약이 창 값이라 <b>끝났을 때 20줄을 뒤지지 않으려면</b>
     * 이 한 줄이 있어야 한다. 창을 비워도 여기 값은 안 지운다.
     *
     * @param delaySamples 중앙값을 낸 표본 수. {@code totalReceived}보다 작으면
     *                     상한에 걸려 잘린 것이고, 그때 중앙값은 앞부분의 값이다
     * @param totalCollectedFor 모든 세션의 수집 시간 합. <b>이것만 마지막 세션 값으로
     *                     두면 {@code totalReceived}와 경계가 어긋나</b>
     *                     "received=1000 collectedFor=5s"가 초당 200건처럼 읽힌다
     * @param maxReceiveGap <b>절단 구간이 안 들어간다.</b> {@link #beginSession()}이 수신
     *                     시계를 다시 잡아 빼낸다 — 안 빼면 "한산했을 뿐"과 "끊겨
     *                     있었다"가 같은 숫자가 된다. 끊긴 시간은 {@code totalOutage}가 든다
     * @param lastOutageFrom 누계가 아니라 <b>마지막 절단 하나</b>의 시각이다.
     *                     한 번도 안 끊겼으면 null — 0을 찍으면 1970년으로 읽힌다
     * @param lastOutageTo <b>{@code lastOutageFrom}이 있는데 여기가 null이면
     *                     "끊긴 채로 끝났다"</b>는 뜻이다. 둘 다 null이면 한 번도
     *                     안 끊긴 것이라, 둘을 같이 읽어야 두 상태가 갈린다
     */
    public record Verdict(
            long totalReceived,
            Instant lastReceivedAt,
            Duration maxReceiveGap,
            long orderViolations,
            Duration delayMin,
            Duration delayMedian,
            Duration delayMax,
            long delaySamples,
            Map<String, Long> systemEvents,
            long decodeFailures,
            // --- 아래 여섯은 세션이 끝날 때 걷어 올린 값이다. 위와 경계가 같다 ---
            Duration totalCollectedFor,
            Duration maxPingGap,
            Duration maxPongGap,
            long sendFailures,
            long callbackFailures,
            long sinkFailures,
            // --- 아래 넷은 세션 사이(끊겨 있던 동안)의 값이다. 위 어느 항에도 안 들어간다 ---
            long reconnects,
            Duration totalOutage,
            Instant lastOutageFrom,
            Instant lastOutageTo
    ) { }

    /**
     * 누적 지연 표본의 상한. 없으면 오래 도는 프로세스에서 무한정 쌓인다.
     * 10분 실측이 186건이었으므로 이 값이면 며칠을 돌려도 안 닿는다.
     */
    private static final int DELAY_SAMPLE_LIMIT = 100_000;

    private final Object lock = new Object();

    // 창 단위 — snapshot()에서 비운다
    private final List<Long> delaysMillis = new ArrayList<>();
    private long received;
    private long maxReceiveGapMillis;
    private long orderViolations;
    private long decodeFailures;

    // 누적 — snapshot()이 안 건드린다
    private long totalReceived;
    private final Map<String, Long> systemEvents = new LinkedHashMap<>();
    private long lastReceivedAtMillis;
    private long previousReceivedAtMillis;
    private long previousMessageTimeMillis;

    // 누적 — 최종 판정 라인이 쓴다. 창 값과 같은 이름이지만 안 비운다.
    private final List<Long> totalDelaysMillis = new ArrayList<>();
    private long totalMaxReceiveGapMillis;
    private long totalOrderViolations;
    private long totalDecodeFailures;

    // 세션이 끝날 때 걷어 올린다. Heartbeat는 소켓마다 새로 만들어져 저절로
    // 리셋되므로, 안 걷으면 판정 줄에 마지막 세션 값만 남는다.
    private long totalCollectedMillis;
    private long totalMaxPingGapMillis;
    private long totalMaxPongGapMillis;
    private long totalSendFailures;
    private long totalCallbackFailures;
    private long totalSinkFailures;

    // 끊겼다 붙은 흔적. 수신 공백에서 빼낸 시간이 여기로 온다.
    private long reconnects;
    private long totalOutageMillis;
    // 마지막 절단의 시각 둘. 누적 시간만으로는 "언제 놓쳤나"를 못 찾는다 —
    // 나중에 영상과 대조하려면 시각이 필요하다(PRD 완료 조건).
    private long lastOutageFromMillis;
    private long lastOutageToMillis;

    /**
     * 세션 하나가 끝났다. <b>그 세션과 함께 사라지는 값 전부</b>를 누계에 합친다 —
     * 하트비트 지표·수집 시간·삼킨 프레임 수.
     *
     * <p><b>한 항이라도 빠지면 판정 줄이 두 경계를 섞어 싣는다.</b>
     * {@code received}(누계) 옆에 {@code collectedFor}(마지막 세션)이 서면
     * 초당 수신량이 몇 배로 읽히고, {@code sinkFailures}가 마지막 세션 값이면
     * 앞 세션이 삼킨 프레임이 "이 프로세스에서 0건"으로 읽힌다.
     *
     * <p>공백 둘은 합이 아니라 <b>최대</b>다. 이어 붙이면 세션 사이의 끊긴 시간까지
     * 한 번의 공백처럼 보이고, 그러면 "ping이 한 사이클 막혔다"를 못 가른다.
     */
    public void recordSessionEnd(Duration collectedFor, Duration maxPingGap, Duration maxPongGap,
                                 long sendFailures, long callbackFailures, long sinkFailures) {
        synchronized (lock) {
            totalCollectedMillis += collectedFor.toMillis();
            totalMaxPingGapMillis = Math.max(totalMaxPingGapMillis, maxPingGap.toMillis());
            totalMaxPongGapMillis = Math.max(totalMaxPongGapMillis, maxPongGap.toMillis());
            totalSendFailures += sendFailures;
            totalCallbackFailures += callbackFailures;
            totalSinkFailures += sinkFailures;
        }
    }

    /**
     * 새 세션이 선다. <b>수신 시계를 다시 잡는다</b> — 안 그러면 끊겨 있던 구간이
     * 통째로 하나의 수신 공백이 되어 "한산했을 뿐"과 구분되지 않는다. 한산한 것은
     * 정상이고(방송을 꺼도 세션은 살아 있다) 끊긴 것은 유실이다.
     *
     * <p><b>{@link #recordOutage}가 아니라 여기서 잡는다.</b> 절단 구간을 닫는 것은
     * 수립이 다 끝난 뒤인데, 프레임 싱크는 그보다 훨씬 앞서 살아 있다 — 그 사이에
     * 온 첫 채팅이 앞 세션의 마지막 수신과 짝지어지면 절단이 두 항에 동시에 실린다
     * (실측 3/10). 세션이 서는 시점에 잡으면 첫 채팅이 언제 오든 짝지을 상대가 없다.
     *
     * <p><b>부르는 자리는 프레임 싱크를 걸기 직전이어야 한다.</b> 뒤로 미루면 그만큼
     * 창이 다시 열린다.
     */
    public void beginSession() {
        synchronized (lock) {
            previousReceivedAtMillis = 0;
        }
    }

    /**
     * <b>같은 소켓으로 다음 방송을 걷기 시작한다</b>(갈아끼움).
     *
     * <p>{@link #beginSession()}과 다른 일이다 — 그쪽은 <b>세션</b>이 다시 선 것이라 수신
     * 시계만 되잡는다. 여기는 <b>세션은 그대로인데 방송만 바뀐</b> 자리라, 방송 단위로 세는
     * 값을 전부 0에서 다시 시작한다. 안 그러면 {@code stream=} 레이블이 새 방송을 가리키는
     * 동안 그 안의 숫자는 앞 방송 것을 안고 간다 — {@code SessionRegistry.receivedOf}가
     * 「이 방송이 받은 양」이라고 문서화한 값에 <b>앞 방송 몫이 그대로 섞였다</b>
     * (codex P2, 재현함: 갈아끼운 직후 새 방송의 수신량이 앞 방송의 5였다).
     *
     * <p><b>세션 단위 값은 안 건드린다</b>({@code totalCollectedMillis}·ping/pong 간격·
     * 절단 구간). 소켓이 그대로라 그 세션은 끝나지 않았고, 판정 줄은 프로세스 전체를
     * 말하는 자리라 방송 경계로 잘리면 안 된다.
     *
     * @return 앞 방송이 받은 양. <b>부르는 쪽이 프로세스 누계로 옮겨야 한다</b> —
     *         여기서 0으로 만들기만 하면 그만큼 총량이 줄어 판정 줄이 유실로 읽는다
     */
    public long beginBroadcast() {
        synchronized (lock) {
            long carried = totalReceived;
            totalReceived = 0;
            received = 0;
            delaysMillis.clear();
            maxReceiveGapMillis = 0;
            orderViolations = 0;
            decodeFailures = 0;
            systemEvents.clear();
            lastReceivedAtMillis = 0;
            previousReceivedAtMillis = 0;
            previousMessageTimeMillis = 0;
            return carried;
        }
    }

    /**
     * 끊겼다가 다시 붙었다. <b>얼마나 · 언제 놓쳤는지만 적는다</b> —
     * 수신 시계는 {@link #beginSession()}이 이미 다시 잡았다.
     *
     * @param from 못 받기 시작한 시각. 재연결이 여러 번 실패해도 <b>첫 절단</b>이다
     * @param to   다시 받기 시작한 시각
     */
    public void recordOutage(Instant from, Instant to) {
        synchronized (lock) {
            reconnects++;
            totalOutageMillis += Duration.between(from, to).toMillis();
            lastOutageFromMillis = from.toEpochMilli();
            lastOutageToMillis = to.toEpochMilli();
        }
    }

    /**
     * 끊긴 채로 수집이 끝났다. <b>{@link #recordOutage}와 달리 {@code reconnects}를
     * 안 올린다</b> — 다시 붙은 적이 없다. 같이 올리면 한 번도 못 붙은 프로세스가
     * 재연결 1회로 보고돼, 재연결이 실제로 도는지를 그 숫자로 못 읽는다.
     *
     * <p>이 갈래가 없으면 판정 줄이 {@code outage=0ms lastOutageFrom=none}이라고
     * 말한다 — 원래 절단 이후로 계속 못 받고 있는데도. 「얼마나 놓쳤는지 한 줄로
     * 보이게 한다」가 <b>영구 정지·끊긴 채 종료</b>에서만 조용히 무력해진다.
     *
     * @param from  못 받기 시작한 시각
     * @param until 판정을 내는 시각. <b>복구 시각이 아니다</b> — 여기까지가 우리가
     *              아는 마지막 지점이고, 실제 유실은 이보다 길 수 있다
     */
    public void recordUnrecoveredOutage(Instant from, Instant until) {
        synchronized (lock) {
            totalOutageMillis += Duration.between(from, until).toMillis();
            lastOutageFromMillis = from.toEpochMilli();
            // <b>복구 시각을 비운다.</b> 앞 절단의 복구 시각이 남아 있으면 시각 둘이
            // 서로 다른 절단의 것이 되어 "이때 끊겨 이때 돌아왔다"로 읽힌다.
            // 비어 있으면 none이라, from만 있고 to가 none인 모양이 곧 "안 돌아왔다"다.
            lastOutageToMillis = 0;
        }
    }

    public void recordMessage(ChatMessage message, long receivedAtMillis) {
        synchronized (lock) {
            received++;
            totalReceived++;

            if (previousReceivedAtMillis > 0) {
                long gap = receivedAtMillis - previousReceivedAtMillis;
                maxReceiveGapMillis = Math.max(maxReceiveGapMillis, gap);
                totalMaxReceiveGapMillis = Math.max(totalMaxReceiveGapMillis, gap);
            }
            previousReceivedAtMillis = receivedAtMillis;
            lastReceivedAtMillis = receivedAtMillis;

            // 역전은 관측값이지 실패가 아니다. 채팅은 원래 어긋나서 온다.
            if (previousMessageTimeMillis > 0
                    && message.messageTimeMillis() < previousMessageTimeMillis) {
                orderViolations++;
                totalOrderViolations++;
            }
            previousMessageTimeMillis = message.messageTimeMillis();

            long delay = receivedAtMillis - message.messageTimeMillis();
            delaysMillis.add(delay);
            if (totalDelaysMillis.size() < DELAY_SAMPLE_LIMIT) {
                totalDelaysMillis.add(delay);
            }
        }
    }

    public void recordDecodeFailure() {
        synchronized (lock) {
            decodeFailures++;
            totalDecodeFailures++;
        }
    }

    /** 세션 전체 누적. 창을 몇 번 비웠든 값이 남는다. */
    public Verdict verdict() {
        synchronized (lock) {
            List<Long> sorted = new ArrayList<>(totalDelaysMillis);
            sorted.sort(null);

            return new Verdict(
                    totalReceived,
                    lastReceivedAtMillis == 0 ? null : Instant.ofEpochMilli(lastReceivedAtMillis),
                    Duration.ofMillis(totalMaxReceiveGapMillis),
                    totalOrderViolations,
                    at(sorted, 0),
                    at(sorted, sorted.size() / 2),
                    at(sorted, sorted.size() - 1),
                    sorted.size(),
                    Map.copyOf(systemEvents),
                    totalDecodeFailures,
                    Duration.ofMillis(totalCollectedMillis),
                    Duration.ofMillis(totalMaxPingGapMillis),
                    Duration.ofMillis(totalMaxPongGapMillis),
                    totalSendFailures,
                    totalCallbackFailures,
                    totalSinkFailures,
                    reconnects,
                    Duration.ofMillis(totalOutageMillis),
                    lastOutageFromMillis == 0 ? null : Instant.ofEpochMilli(lastOutageFromMillis),
                    lastOutageToMillis == 0 ? null : Instant.ofEpochMilli(lastOutageToMillis));
        }
    }

    public void recordSystemEvent(String type) {
        synchronized (lock) {
            systemEvents.merge(type, 1L, Long::sum);
        }
    }

    public long totalReceived() {
        synchronized (lock) {
            return totalReceived;
        }
    }

    /** 창을 비우고 돌려준다. 안 비우면 10분 뒤 표본이 무한정 쌓인다. */
    public Snapshot snapshot() {
        synchronized (lock) {
            List<Long> sorted = new ArrayList<>(delaysMillis);
            sorted.sort(null);

            Snapshot snapshot = new Snapshot(
                    received,
                    lastReceivedAtMillis == 0 ? null : Instant.ofEpochMilli(lastReceivedAtMillis),
                    Duration.ofMillis(maxReceiveGapMillis),
                    orderViolations,
                    at(sorted, 0),
                    at(sorted, sorted.size() / 2),
                    at(sorted, sorted.size() - 1),
                    Map.copyOf(systemEvents),
                    decodeFailures);

            delaysMillis.clear();
            received = 0;
            maxReceiveGapMillis = 0;
            orderViolations = 0;
            decodeFailures = 0;
            // previousReceivedAtMillis는 안 지운다. 지우면 창 경계를 넘는 공백이
            // 통째로 사라져, 30초 동안 한 건도 안 온 구간이 어느 요약에도 안 남는다.
            return snapshot;
        }
    }

    private static Duration at(List<Long> sorted, int index) {
        return sorted.isEmpty() ? Duration.ZERO : Duration.ofMillis(sorted.get(index));
    }
}
