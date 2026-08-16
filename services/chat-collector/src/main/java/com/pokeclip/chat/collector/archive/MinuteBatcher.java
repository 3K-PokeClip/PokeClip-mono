package com.pokeclip.chat.collector.archive;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 받은 시각(UTC) 기준 1분 창을 <b>채널별로</b> 열고 닫는다. 아카이브 스레드 전용 —
 * 동기화 없다(호출자가 한 스레드임을 보장한다: ChatArchiver의 틱과 마지막 flush 모두
 * 같은 스케줄러 스레드다).
 *
 * <p>창의 열쇠는 <b>채팅의 받은 시각</b>이지 틱 시각이 아니다 — 수신에서 drain까지 최대
 * 1초 늦어 다음 분 초입에 이전 분 채팅이 온다. 닫는 조건은 둘이다:
 * ① 그 채널에 <b>다른 분</b>의 채팅이 오면 열린 창을 즉시 닫는다 — 채널당 창이 하나라
 *    이전 분이 와도(시계 역행) 같은 조각화 규칙이다. 정방향(수신 순서 = 받은 시각 순서)에서는
 *    "다음 분이 왔다 = 이전 분은 끝났다"이고, 역행이면 그 분 파일이 둘로 갈라질 뿐 유실은
 *    없다(재열림 순번이 덮어쓰기를 막는다). ② 채팅이 안 와도 틱 시각이 창 끝 + 유예(2초 =
 *    틱 1초 + 지연 여유)를 넘으면 닫는다 — 조용한 채널의 마지막 창이 영영 안 닫히지 않게.
 * 빈 창은 만들지 않는다 — 채팅이 있어야 창이 열린다.
 *
 * <p>인코드 실패 한 건은 세고 버린다 — 그 한 건 때문에 창의 다른 채팅을 잃지 않는다.
 * 원문을 로그에 싣지 않는다.
 */
public final class MinuteBatcher {

    private static final class OpenWindow {
        final String channelId;
        final long windowStart;
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(8 * 1024);
        int count;
        int seq = 1;

        OpenWindow(String channelId, long windowStart) {
            this.channelId = channelId;
            this.windowStart = windowStart;
        }

        ArchiveObject toObject(String runId) {
            return new ArchiveObject(ArchiveKey.of(channelId, windowStart, runId, seq), bytes.toByteArray(), count);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(MinuteBatcher.class);
    /** 닫은 창의 순번 기억 보관 시한 — 이보다 오래된 열쇠는 잊는다(맵이 영영 자라지 않게). 유예 2초의 재열림은 이 안에 든다. */
    static final long CLOSED_MEMORY_MS = 2 * 60 * 60_000L;
    private final String runId;
    private final long graceMillis;
    private final Map<String, OpenWindow> open = new HashMap<>();   // channelId → 열린 창(채널당 하나)
    /** channelId → (windowStart → 그 열쇠로 지금까지 닫은 횟수). 같은 창이 다시 열리면 순번 = 횟수 + 1. */
    private final Map<String, Map<Long, Integer>> closedSeq = new HashMap<>();
    /** 아카이브 스레드만 쓰고 읽기도 그 스레드·테스트뿐이라 평범한 long이다(다른 스레드에서 읽지 마라 — openWindows와 같다). */
    private long reopened;
    private long encodeFailures;

    public MinuteBatcher(String runId, Duration closeGrace) {
        this.runId = runId;
        this.graceMillis = closeGrace.toMillis();
    }

    /** drain한 채팅을 창에 쌓고, 닫힐 창을 돌려준다. drained가 비어도 부른다(유예 닫기). */
    public List<ArchiveObject> accept(List<ArchivableChat> drained, long nowMillis) {
        List<ArchiveObject> closed = new ArrayList<>();
        for (ArchivableChat chat : drained) {
            // 인코드를 창 열기 전에 한다 — 채널 첫 건이 실패하면 창을 안 열어 0바이트 파일이 안 생긴다(리뷰 1회차 사소 1).
            byte[] line;
            try {
                line = JsonLinesEncoder.encodeLine(chat);
            } catch (RuntimeException e) {
                // 원문은 안 싣는다. 첫 실패만 WARN(도배 방지) — 카운터 여섯에는 없으니 로그가 유일한 단서다.
                if (++encodeFailures == 1) {
                    log.warn("chat.archive.encode_failed causeType={}", e.getClass().getSimpleName());
                }
                continue;
            }
            long start = ArchiveKey.windowStartOf(chat.receivedAtMillis());
            OpenWindow window = open.get(chat.channelId());
            if (window != null && window.windowStart != start) {
                closeWindow(window, closed);              // ① 다른 분이 왔다 — 열린 창 닫기(클래스 주석 ①)
                window = null;
            }
            if (window == null) {
                window = openWindow(chat.channelId(), start);   // 이전 창은 여기서 덮여 map에서 사라진다
            }
            window.bytes.write(line, 0, line.length);
            window.count++;
        }
        // ② 유예 닫기 — 채팅이 없어도 시각이 지나면 닫는다
        Iterator<Map.Entry<String, OpenWindow>> it = open.entrySet().iterator();
        while (it.hasNext()) {
            OpenWindow w = it.next().getValue();
            if (nowMillis >= w.windowStart + 60_000L + graceMillis) {
                closeWindow(w, closed);
                it.remove();
            }
        }
        return closed;
    }

    /** 종료용 — 열린 창을 전부 닫는다. 빈 창은 없다(채팅이 있어야 열린다). */
    public List<ArchiveObject> closeAll() {
        List<ArchiveObject> closed = new ArrayList<>();
        for (OpenWindow w : open.values()) {
            closeWindow(w, closed);
        }
        open.clear();
        return closed;
    }

    /**
     * 창을 연다. 같은 (채널, 분) 열쇠로 이미 닫은 적이 있으면 순번을 올려 키에 접미를 붙인다 — 유예로 닫힌 뒤
     * 그 분의 채팅이 뒤늦게 오면(바구니 2초 이상 밀림·시계 역행) 같은 키로 PUT해 앞 파일을 덮어쓰던 것을 막는다
     * (리뷰 1회차 사소 2, 사용자 결정 2026-08-16). 첫 재열림만 INFO 한 줄(encode_failed와 같은 방식 — 시계 요동 때는
     * 채팅마다 재열림이 날 수 있어 도배 방지) — 카운터 여섯(PRD 결정)에는 없으니 로그가 운영의 유일한 단서다. 이후
     * 재열림은 조용하고 규모는 S3의 {@code -N} 접미로 센다. seq는 안 싣는다 — 첫 재열림은 언제나 2라 정보가 0이다.
     * 키·본문은 안 싣는다.
     */
    private OpenWindow openWindow(String channelId, long start) {
        OpenWindow window = new OpenWindow(channelId, start);
        int closedBefore = closedSeq.getOrDefault(channelId, Map.of()).getOrDefault(start, 0);
        if (closedBefore > 0) {
            window.seq = closedBefore + 1;
            if (++reopened == 1) {
                log.info("chat.archive.window_reopened");
            }
        }
        open.put(channelId, window);
        return window;
    }

    /**
     * 닫으면서 그 열쇠의 순번을 기억하고, 오래된 열쇠는 잊는다 — 방송 3시간이면 채널당 180개가 쌓이는데 재열림
     * 창은 유예 몇 초라 2시간이면 충분하다. 잊기를 여기 두는 이유: 열쇠가 느는 자리가 여기뿐이라 매 틱 훑을 필요가
     * 없다. 기준은 <b>지금 닫는 창의 시작 시각</b>이다 — 틱 시각과는 1분 + 유예 차이라 같은 자고, 시계 역행으로
     * 옛 창이 닫히면 그만큼 덜 잊을 뿐이다. open 맵에서 빼는 것은 호출자 몫.
     */
    private void closeWindow(OpenWindow w, List<ArchiveObject> closed) {
        closed.add(w.toObject(runId));
        closedSeq.computeIfAbsent(w.channelId, k -> new HashMap<>()).put(w.windowStart, w.seq);
        long cutoff = w.windowStart - CLOSED_MEMORY_MS;
        for (Map<Long, Integer> perChannel : closedSeq.values()) {
            perChannel.keySet().removeIf(start -> start < cutoff);
        }
    }

    /** 같은 창이 다시 열린 횟수 — 0이 정상이다. 테스트·진단용(카운터 여섯에는 없다 — 운영은 window_reopened 로그로 본다). */
    public long reopenedCount() {
        return reopened;
    }

    /** 동기화 없는 HashMap 읽기 — 아카이브 스레드와 테스트만 부른다. 다른 스레드(요약 로거·판정 줄)에서 부르지 마라. */
    public int openWindows() {
        return open.size();
    }

    public long encodeFailures() {
        return encodeFailures;
    }

    public String runId() {
        return runId;
    }
}
