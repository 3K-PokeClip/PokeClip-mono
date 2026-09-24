package com.pokeclip.chat.detector.detect;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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
 *   <li>튄 창이 어느 열린 사건의 앞뒤 {@code gapMs} 안에 오면 그 사건에 <b>시간순으로</b> 들어간다.
 *       어디에도 못 붙으면 새 사건을 연다</li>
 *   <li>사건 길이가 {@code maxSpanMs}를 넘게 되면 그 사건에는 안 붙고 새 사건을 연다 —
 *       한없이 길어지면 카드가 영영 안 나가고 구간도 쓸모없어진다</li>
 *   <li>열린 사건은 마지막 창 끝 + {@code gapMs} 안에 시작할 수 있는 창까지 판정 지평({@code horizonMs})
 *       안에 <b>닫혔을 때</b> 닫힌다. 조용한 창은 집계 줄이 안 생기므로(GROUP BY) 「안 튄 창이 왔다」로는
 *       못 닫고 시간으로 닫는다</li>
 * </ul>
 *
 * <p>🔴 <b>방송 하나에 열린 사건이 여럿일 수 있다</b>(봇 리뷰 1판, codex·claude). 발행이 RETRY_LATER로
 * 되돌린 창은 다음 바퀴에 <b>오래된 시각으로</b> 다시 들어오는데, 그 사이 같은 방송에 새 사건이 열려
 * 있으면 그 뒤에 붙어 첫·끝 창이 시간순이 아니게 되고 사건 길이가 음수가 됐다. 그래서 창은 항상 시간 자리에
 * 넣고, 어느 사건에도 못 붙으면 새 사건을 연다. 창이 들어와서 닫히는 사건은 <b>그 창보다 완전히 앞에 있는</b>
 * 사건뿐이다 — 뒤에 있는 사건은 아직 진행 중이다.
 *
 * <p>🔴 메모리에만 산다. 프로세스가 죽으면 열려 있던 사건을 잃는다 — 그 창들의 발행권은
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
    /** 방송 → 열린 사건들(시작 오름차순). 사건 하나는 창 목록(시작 오름차순) */
    private final Map<String, List<List<Window>>> open = new ConcurrentHashMap<>();

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
     * @return 이 창이 들어오면서 <b>닫힌 사건들</b> — 이 창보다 완전히 앞에 있어 더 이상 창이 붙을 수 없는
     *         사건. 정상 흐름(창이 시간순으로 온다)에서는 많아야 하나이고, 붙었거나 처음이면 빈 목록
     */
    public List<Episode> add(String streamId, Window window) {
        List<List<Window>> episodes = open.computeIfAbsent(streamId, k -> new ArrayList<>());
        List<Episode> closed = new ArrayList<>();
        boolean attached = false;

        for (Iterator<List<Window>> it = episodes.iterator(); it.hasNext(); ) {
            List<Window> episode = it.next();
            Window first = episode.get(0);
            Window last = episode.get(episode.size() - 1);
            boolean 뒤에서_이어진다 = window.windowStartMs() <= last.endMs() + gapMs;
            boolean 앞에서_이어진다 = window.endMs() + gapMs >= first.windowStartMs();
            boolean 너무_길다 = Math.max(window.endMs(), last.endMs())
                    - Math.min(window.windowStartMs(), first.windowStartMs()) > maxSpanMs;
            if (!attached && 뒤에서_이어진다 && 앞에서_이어진다 && !너무_길다) {
                insertInOrder(episode, window);
                attached = true;
                continue;
            }
            // 이 창보다 완전히 앞에 있고 간격도 지났으면 이제 아무 창도 못 붙는다 — 닫는다.
            // 뒤에 있는 사건(창이 되돌아온 경우)은 진행 중이라 그대로 둔다.
            if (!뒤에서_이어진다) {
                closed.add(new Episode(streamId, List.copyOf(episode)));
                it.remove();
            }
        }
        if (!attached) {
            List<Window> fresh = new ArrayList<>();
            fresh.add(window);
            insertEpisodeInOrder(episodes, fresh);
        }
        return closed;
    }

    /**
     * 판정 지평까지 조용했던 사건을 전부 닫아 돌려준다. 방송별 호출이 아니라 전체다 —
     * 채팅이 끊긴 방송은 활성 목록에서 빠져 방송별 경로로는 영영 안 닫힌다.
     *
     * <p>🔴 닫는 기준은 「마지막 창 끝 + 간격」이 아니라 <b>그 자리에 시작할 수 있는 마지막 창이 닫히는
     * 시각</b>(+ 창 크기)이다(봇 리뷰 1판, codex). 판정은 끝이 지평 안에 든 창만 하므로, 간격 끝에 딱
     * 시작한 창은 한 창 크기만큼 뒤에야 판정에 들어온다 — 그 전에 닫으면 {@link #add}가 같은 사건으로
     * 보는 창이 따로 카드가 된다.
     */
    public List<Episode> drainExpired(long horizonMs) {
        List<Episode> closed = new ArrayList<>();
        for (Map.Entry<String, List<List<Window>>> e : open.entrySet()) {
            for (Iterator<List<Window>> it = e.getValue().iterator(); it.hasNext(); ) {
                List<Window> windows = it.next();
                Window last = windows.get(windows.size() - 1);
                if (last.endMs() + gapMs + last.windowSizeMs() <= horizonMs) {
                    closed.add(new Episode(e.getKey(), List.copyOf(windows)));
                    it.remove();
                }
            }
            if (e.getValue().isEmpty()) {
                open.remove(e.getKey());
            }
        }
        return closed;
    }

    /** 열린 사건 수 — 관측용 */
    public int openCount() {
        return open.values().stream().mapToInt(List::size).sum();
    }

    private static void insertInOrder(List<Window> episode, Window window) {
        int i = episode.size();
        while (i > 0 && episode.get(i - 1).windowStartMs() > window.windowStartMs()) {
            i--;
        }
        episode.add(i, window);
    }

    private static void insertEpisodeInOrder(List<List<Window>> episodes, List<Window> fresh) {
        long start = fresh.get(0).windowStartMs();
        int i = episodes.size();
        while (i > 0 && episodes.get(i - 1).get(0).windowStartMs() > start) {
            i--;
        }
        episodes.add(i, fresh);
    }
}
