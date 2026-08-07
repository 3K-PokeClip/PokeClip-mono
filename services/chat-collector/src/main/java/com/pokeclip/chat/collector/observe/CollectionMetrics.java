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
            long decodeFailures
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
                    totalDecodeFailures);
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
