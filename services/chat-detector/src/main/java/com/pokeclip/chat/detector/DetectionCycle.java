package com.pokeclip.chat.detector;

import com.pokeclip.chat.detector.config.DetectionProperties;
import com.pokeclip.chat.detector.detect.SpikeDetector;
import com.pokeclip.chat.detector.detect.SpikeVerdict;
import com.pokeclip.chat.detector.metrics.ChatMetricsStore;
import com.pokeclip.chat.detector.metrics.ChatWindowReader;
import com.pokeclip.chat.detector.metrics.MetricRow;
import com.pokeclip.chat.detector.metrics.WindowGrid;
import com.pokeclip.chat.detector.publish.HighlightPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * 한 바퀴: 활성 방송을 고르고 → 닫힌 창을 집계하고 → 판정하고 → 발행을 의뢰한다.
 *
 * <p><b>발행은 이 스레드에서 하지 않는다.</b> clip이 죽어 있으면 재시도가 초 단위로 걸리는데
 * 그동안 판정이 멈추면 안 된다(POK-139 완료 조건). 별도 실행기에 던지고 바로 다음으로 간다.
 */
@Component
public class DetectionCycle {

    private static final Logger log = LoggerFactory.getLogger(DetectionCycle.class);

    /** 한 줄에 실을 방송 번호 표본 수. 개수가 신호이고 번호는 어느 방송인지 짚을 실마리다. */
    private static final int SAMPLE = 5;

    private final ChatWindowReader reader;
    private final ChatMetricsStore store;
    private final SpikeDetector detector;
    private final HighlightPublisher publisher;
    private final DetectionProperties props;
    private final TaskExecutor publishExecutor;

    public DetectionCycle(ChatWindowReader reader, ChatMetricsStore store, SpikeDetector detector,
                          HighlightPublisher publisher, DetectionProperties props,
                          TaskExecutor publishExecutor) {
        this.reader = reader;
        this.store = store;
        this.detector = detector;
        this.publisher = publisher;
        this.props = props;
        this.publishExecutor = publishExecutor;
    }

    /**
     * <b>{@code Throwable}까지 잡는다.</b> {@code @Scheduled}는 태스크가 한 번이라도 던지면
     * 그 뒤 주기가 안 돈다 — 판별이 통째로 멈추는데 아무 신호도 없다(chat-collector가
     * 같은 이유로 같은 폭을 쓴다).
     *
     * <p>{@code initialDelay}도 준다 — 기본값 0이면 컨텍스트가 뜨자마자 조회가 나가 부팅과 겹친다.
     */
    @Scheduled(fixedDelayString = "${pokeclip.detection.cycle-interval}",
            initialDelayString = "${pokeclip.detection.cycle-interval}")
    public void tick() {
        try {
            runOnce(Instant.now());
        } catch (Throwable t) {
            log.warn("detect.cycle_failed causeType={}", t.getClass().getSimpleName());
        }
    }

    /** 시계를 밖에서 받는다 — 테스트가 특정 시점을 재현할 수 있어야 한다. */
    public void runOnce(Instant now) {
        // 창이 닫히고 유예만큼 더 지난 시점까지만 본다. 유예가 없으면 늦게 도착하는
        // 채팅이 빠진 채로 집계되고, 그 수로 낸 카드는 나중에 고칠 수 없다.
        Instant until = now.minus(props.windowGrace());

        List<String> activeButEmpty = new ArrayList<>();
        for (String streamId : reader.activeStreams(now.minus(props.activeStreamWindow()))) {
            // 🔴 방송 하나가 터져도 나머지가 그 바퀴를 잃지 않는다. 바깥 tick()의 포획은
            // 주기를 살리지만 그 바퀴는 이미 끝난 뒤라, 100 방송 중 하나 때문에 99개가
            // 통째로 건너뛴다(감사 2회차 R-4).
            //
            // 지금 이 자리를 지속적으로 터뜨리는 경로는 없다 — 두 표의 stream_id가 같은
            // VARCHAR(128)이고 값은 전부 chat_messages에서 온다(감사자가 전수로 보고
            // 「못 찾았다」고 적었다. 「없다」가 아니다). 그래도 두는 이유는 방어 비용이
            // try 하나인데 잃는 것이 한 바퀴 전체이기 때문이다.
            try {
                int collected = 0;
                for (long windowSizeMs : props.windowSizesMs()) {
                    collected += collect(streamId, windowSizeMs, until);
                }
                if (collected == 0) {
                    activeButEmpty.add(streamId);
                }
                detectAndPublish(streamId, now, until);
            } catch (Exception e) {
                // 어느 방송에서 터졌는지 남긴다 — 이것이 없으면 「그 방송만 카드가 안 나간다」를
                // 알 길이 없다. 예외 메시지는 안 찍는다(본문이 딸려 올 수 있다).
                log.warn("detect.stream_failed streamId={} causeType={}",
                        streamId, e.getClass().getSimpleName());
            }
        }
        reportActiveButEmpty(activeButEmpty);
    }

    /**
     * 🔴 <b>감사 1회차 중대 A-1의 유일한 안전망이다. 지우면 그 결정의 대가가 안 보인다.</b>
     *
     * <p>활성 판단은 {@code received_at}(우리 시계)이라 치지직 시계가 어긋나도 방송을
     * 놓치지 않는다. 그런데 집계 범위는 <b>우리 시계에서 만든 {@code collect-lookback} 폭</b>을
     * {@code message_time}에 거는 것이라, 치지직 시각이 그 폭보다 크게 어긋나면
     * <b>활성 목록에는 있는데 셀 창이 0줄</b>이 된다. 되돌아보는 폭이 계속 앞으로 가므로
     * <b>나중에 메워지지도 않는다</b> — 그 방송은 영영 카드가 안 나가고 오류도 안 난다.
     *
     * <p>구조를 안 바꾸기로 한 근거 셋(15분으로 되돌리면 F8 재발 · 틀리는 방향이 안전한 쪽 ·
     * 1분 넘는 어긋남의 실측이 없음)의 <b>대가가 정확히 이것</b>이고, 이 줄이 그 대가를 밖으로
     * 내보내는 유일한 통로다.
     *
     * <h3>🔴 알려진 잡음 둘 — 시계 어긋남만 켜는 줄이 아니다</h3>
     *
     * 기본값(활성 창 60초 · 유예 2초 · 되돌아보기 60초)에서 집계 범위는
     * {@code message_time} 기준 {@code [now-62s, now-2s]}다. 그래서 문턱이 <b>방송의 촘촘함에
     * 따라 다르다</b>(직접 유도해 확인했다).
     *
     * <ul>
     *   <li><b>첫 채팅</b> — 아직 안 닫힌 창에만 채팅이 있으면 한두 바퀴 걸린다. 이어서 오면
     *       저절로 사라진다</li>
     *   <li><b>🔴 성긴 방송 + 전달 지연</b> — 활성 창 끝자락(약 59초 전)에 온 채팅만 있는 방송은
     *       <b>전달 지연이 3초만 넘어도</b> 걸린다. 즉 이 줄은 시계 어긋남뿐 아니라
     *       <b>재연결 중에도 켜진다</b>. 촘촘한 방송은 62초를 넘어야 걸린다(감사 2회차)</li>
     * </ul>
     *
     * <p>그래서 <b>계속 찍히는 것</b>이 신호이지 한 번 찍히는 것이 신호가 아니다.
     *
     * <p>방송마다가 아니라 <b>바퀴마다 한 줄</b>이다 — 방송이 100개면 100줄이 아니라 1줄이다.
     */
    private void reportActiveButEmpty(List<String> streamIds) {
        if (streamIds.isEmpty()) {
            return;
        }
        // 🔴 방송 번호를 전부 싣지 않는다. 100 방송이 다 걸리면 한 줄이 거대해진다 —
        // 줄 수가 아니라 줄 길이가 문제다(감사 2회차). 개수가 신호이고 번호는 표본이면 된다.
        List<String> sample = streamIds.size() <= SAMPLE ? streamIds : streamIds.subList(0, SAMPLE);
        log.info("detect.active_but_empty streams={} sample={}", streamIds.size(), sample);
    }

    /**
     * 이 방송의 이 창 크기를 집계해 표에 넣는다. 이미 있는 창은 {@code DO NOTHING}으로 접힌다.
     *
     * <p>🔴 <b>되돌아보는 기간은 베이스라인 기간(15분)이 아니라 별도 설정이다.</b>
     * 15분으로 두면 매 바퀴 15분치를 다시 집계하는데, 계획 검증(F8)이 방송 하나에 8ms를
     * 실측했다 — 동시 100 방송이면 0.8초, 바쁜 방송이면 8.3초라 <b>1초 주기를 못 지키고
     * 그 밀림이 그대로 「우리 구간 지연」에 더해진다</b>(목표 3초인데 유예가 이미 2초다).
     *
     * <p>기본 1분이면 한 바퀴가 밀리거나 잠깐 끊겨도 그 사이 창이 메워진다. 더 긴 공백은
     * 메우지 않는다 — 늦게 만든 카드는 되감기 창을 벗어나 가치가 없다(PRD 결정).
     *
     * @return {@code chat_messages}에서 실제로 읽어 온 창의 수. <b>표에 새로 들어간 수가
     *         아니다</b> — 이미 집계된 창은 정상적으로 0을 반환하므로 그것으로는
     *         「셀 것이 없다」를 가릴 수 없다(위 {@code reportActiveButEmpty}가 쓴다)
     */
    private int collect(String streamId, long windowSizeMs, Instant until) {
        Instant from = until.minus(props.collectLookback());
        long firstWindow = WindowGrid.floorTo(from.toEpochMilli(), windowSizeMs);
        List<Long> closed = WindowGrid.closedWindowsBetween(firstWindow, until.toEpochMilli(), windowSizeMs);
        if (closed.isEmpty()) {
            return 0;
        }
        long lastEnd = closed.get(closed.size() - 1) + windowSizeMs;
        List<MetricRow> rows = reader.countWindows(streamId, windowSizeMs,
                Instant.ofEpochMilli(firstWindow), Instant.ofEpochMilli(lastEnd));
        store.upsert(rows);
        return rows.size();
    }

    /**
     * 발행 창만 판정한다. 나머지 크기는 쌓아만 둔다 — 여럿을 발행하면 카드가 여러 장이 된다.
     *
     * <p>🔴 <b>판정 대상의 하한을 집계와 같은 산식으로 낸다</b>(로컬 리뷰 라운드 2).
     * {@code collect}가 되돌아보는 첫 눈금과 <b>글자 그대로 같은 식</b>이라야 「집계는 하는데
     * 판정은 안 하는 창」이나 그 반대가 안 생긴다. 하한이 없으면 보관 기간 전체가 대상이 되고,
     * 발행 창 설정을 바꾸는 날 과거 24시간치가 한 바퀴에 쏟아진다 —
     * 사정은 {@link ChatMetricsStore#unpublished}의 javadoc에 숫자와 함께 적었다.
     *
     * @param until 집계 상한(바퀴 시각 − 유예). {@code collect}가 받은 것과 <b>같은 값</b>이다
     */
    private void detectAndPublish(String streamId, Instant now, Instant until) {
        long windowSizeMs = props.publishWindowMs();
        long since = WindowGrid.floorTo(
                until.minus(props.collectLookback()).toEpochMilli(), windowSizeMs);

        for (MetricRow row : store.unpublished(streamId, windowSizeMs, since)) {
            long baselineFrom = row.windowStartMs() - props.baselineWindow().toMillis();
            SpikeVerdict verdict = detector.judge(row, store.baselineCounts(
                    streamId, windowSizeMs, row.windowStartMs(), baselineFrom, props.metric()));

            if (!verdict.spike()) {
                // 급증이 아니어도 발행권을 잡아 둔다. 안 잡으면 이 줄이 매 바퀴 다시 판정돼
                // 조회가 계속 늘고, 베이스라인이 흘러 나중에 뒤늦게 카드가 나갈 수 있다.
                store.claimForPublish(streamId, windowSizeMs, row.windowStartMs(), now);
                continue;
            }
            OptionalLong metricId = store.claimForPublish(streamId, windowSizeMs, row.windowStartMs(), now);
            if (metricId.isEmpty()) {
                continue;
            }
            long id = metricId.getAsLong();
            long windowStartMs = row.windowStartMs();
            publishExecutor.execute(() -> {
                // 🔴 던지면 카드가 사라지는데 발행권은 이미 잡혀 재시도가 없다. 실행기 스레드의
                // 기본 처리는 stderr 스택 한 덩어리라 구조화된 로그로 안 남는다(감사 2회차 R-5).
                //
                // 지금 여기서 새는 경로는 못 찾았다 — VideoPositionClient·ClipHighlightClient가
                // 둘 다 Exception을 다 잡고 그 사이는 문자열 조립과 산수뿐이다. 그래도 두는 이유는
                // 그 사이 코드가 앞으로 바뀌기 때문이고, 그때 조용히 사라지는 것이 이 기능에서
                // 가장 나쁜 실패이기 때문이다.
                try {
                    // now = 발행권을 잡은 시각(집계에 쓰인 채팅의 상한).
                    // 끝점은 값이 아니라 시계를 넘긴다 — 발행이 끝난 뒤에 찍혀야 우리 왕복이
                    // 우리 구간에 들어간다. 여기서 Instant.now()를 찍어 넘기면 보내기 전 시각이다.
                    HighlightPublisher.Outcome outcome =
                            publisher.publish(streamId, id, windowStartMs, verdict, now, Instant::now);
                    // 🔴 계약이 「재시도할 자리」로 명시한 것만 되돌린다(라운드 2에서 넣고
                    // 라운드 3에서 폭을 좁혔다). 조각이 아직 장부에 안 온 것은 몇 초 뒤면
                    // 풀리는데, 발행권이 잡힌 채로 두면 그 창은 영영 다시 안 집히고
                    // 하이라이트가 사라진다 — 채팅에는 백필이 없어 되찾을 방법도 없다.
                    // 「창구를 못 물었다」를 여기 넣으면 안 되는 이유는 HighlightPublisher에.
                    if (outcome == HighlightPublisher.Outcome.RETRY_LATER) {
                        store.releaseClaim(id);
                    }
                } catch (Throwable t) {
                    log.warn("detect.publish_threw streamId={} metricId={} causeType={}",
                            streamId, id, t.getClass().getSimpleName());
                }
            });
        }
    }
}
