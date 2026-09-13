package com.pokeclip.chat.detector.detect;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 튄 창들을 「사건」 하나로 묶는다.
 *
 * <p>왜 있나: 판정은 5초 창 단위다. 한타 하나에 채팅이 30초 튀면 창 6개가 전부 급증이고,
 * 창마다 카드를 내면 편집자가 같은 장면 카드 6장을 본다(2026-09-13 실방송에서 8장). 편집자가
 * 원하는 것은 「여기부터 여기까지 재밌었다」 한 장이다.
 *
 * <p>규칙 셋:
 * <ul>
 *   <li>튄 창이 앞 사건의 끝에서 {@code gapMs} 안에 시작하면 그 사건에 붙는다. 넘으면 앞 사건을
 *       닫고 새 사건을 연다</li>
 *   <li>사건 길이가 {@code maxSpanMs}를 넘게 되면 앞 사건을 닫고 그 창으로 새 사건을 연다 —
 *       한없이 길어지면 카드가 영영 안 나가고 구간도 쓸모없어진다</li>
 *   <li>열린 사건은 마지막 창 끝 + {@code gapMs}가 판정 지평({@code horizonMs}) 안에 들어오면
 *       닫힌다. 조용한 창은 집계 줄이 안 생기므로(GROUP BY) 「안 튄 창이 왔다」로는 못 닫고
 *       시간으로 닫는다</li>
 * </ul>
 *
 * <p>🔴 메모리에만 산다. 프로세스가 죽으면 열려 있던 사건 하나를 잃는다 — 그 창들의 발행권은
 * 이미 잡혀 있어 다시 집히지 않는다. 카드가 두 번 나가는 것보다 한 장 잃는 쪽을 골랐다
 * (사건 상태를 표에 넣으면 막을 수 있지만 이번 범위 밖이다).
 */
public final class SpikeEpisodes {

    /** 사건에 들어간 창 하나. 발행권 번호와 「집계에 쓴 채팅의 상한」을 같이 든다 */
    public record Window(long metricId, long windowStartMs, long windowSizeMs,
                         SpikeVerdict verdict, Instant countedUntil) {
        public long endMs() {
            return windowStartMs + windowSizeMs;
        }
    }

    /** 닫힌 사건. 창은 시작 시각 오름차순이다 */
    public record Episode(String streamId, List<Window> windows) {
        public Window first() {
            return windows.get(0);
        }

        public Window last() {
            return windows.get(windows.size() - 1);
        }

        public long startMs() {
            return first().windowStartMs();
        }

        public long endMs() {
            return last().endMs();
        }

        public long spanMs() {
            return endMs() - startMs();
        }

        public double maxRatio() {
            return windows.stream().mapToDouble(w -> w.verdict().ratio()).max().orElse(0.0);
        }

        public int totalMessages() {
            return windows.stream().mapToInt(w -> w.verdict().messageCount()).sum();
        }

        public int maxChatters() {
            return windows.stream().mapToInt(w -> w.verdict().chatterCount()).max().orElse(0);
        }

        public List<Long> metricIds() {
            return windows.stream().map(Window::metricId).toList();
        }
    }

    private final long gapMs;
    private final long maxSpanMs;
    private final Map<String, List<Window>> open = new ConcurrentHashMap<>();

    public SpikeEpisodes(long gapMs, long maxSpanMs) {
        if (gapMs < 0) {
            throw new IllegalArgumentException("gapMs는 음수일 수 없다: " + gapMs);
        }
        if (maxSpanMs <= 0) {
            throw new IllegalArgumentException("maxSpanMs는 0보다 커야 한다: " + maxSpanMs);
        }
        this.gapMs = gapMs;
        this.maxSpanMs = maxSpanMs;
    }

    /**
     * 튄 창을 넣는다.
     *
     * @return 이 창이 붙지 못해 <b>닫힌 앞 사건</b>. 붙었거나 처음이면 빈손
     */
    public Optional<Episode> add(String streamId, Window window) {
        List<Window> current = open.get(streamId);
        if (current == null) {
            open.put(streamId, newList(window));
            return Optional.empty();
        }
        Window last = current.get(current.size() - 1);
        boolean 이어진다 = window.windowStartMs() <= last.endMs() + gapMs;
        boolean 너무_길다 = window.endMs() - current.get(0).windowStartMs() > maxSpanMs;
        if (이어진다 && !너무_길다) {
            current.add(window);
            return Optional.empty();
        }
        Episode closed = new Episode(streamId, List.copyOf(current));
        open.put(streamId, newList(window));
        return Optional.of(closed);
    }

    /**
     * 판정 지평까지 조용했던 사건을 전부 닫아 돌려준다. 방송별 호출이 아니라 전체다 —
     * 채팅이 끊긴 방송은 활성 목록에서 빠져 방송별 경로로는 영영 안 닫힌다.
     */
    public List<Episode> drainExpired(long horizonMs) {
        List<Episode> closed = new ArrayList<>();
        for (Map.Entry<String, List<Window>> e : open.entrySet()) {
            List<Window> windows = e.getValue();
            Window last = windows.get(windows.size() - 1);
            if (last.endMs() + gapMs <= horizonMs) {
                closed.add(new Episode(e.getKey(), List.copyOf(windows)));
                open.remove(e.getKey());
            }
        }
        return closed;
    }

    /** 열린 사건 수 — 관측용 */
    public int openCount() {
        return open.size();
    }

    private static List<Window> newList(Window first) {
        List<Window> list = new ArrayList<>();
        list.add(first);
        return list;
    }
}
