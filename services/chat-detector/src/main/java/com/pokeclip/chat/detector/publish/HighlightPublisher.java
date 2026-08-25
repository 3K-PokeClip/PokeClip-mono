package com.pokeclip.chat.detector.publish;

import com.pokeclip.chat.detector.config.DetectionProperties;
import com.pokeclip.chat.detector.detect.SpikeVerdict;
import com.pokeclip.chat.detector.metrics.ChatWindowReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 판정된 창 하나를 카드로 만들어 보낸다. 변환 창구 한 번 + clip 한 번.
 *
 * <p><b>지연을 두 구간으로 나눠 기록한다</b>(사용자 결정).
 * <ul>
 *   <li><b>우리 구간</b> — 창이 닫힌 시점부터 카드를 보낼 때까지. 우리 손 안이라 목표를 건다</li>
 *   <li><b>총 시간</b> — 장면이 실제로 벌어진 때부터. 앞에 「채팅이 우리에게 오기까지」가
 *       붙는데 그 값이 변환 창구가 실어 보내는 보정값이다(실측 약 3.9초)</li>
 * </ul>
 * 통제 못 하는 구간을 판정에 넣으면 시청자가 늦게 쳐도 우리 코드가 실패한 것이 된다.
 */
@Component
public class HighlightPublisher {

    private static final Logger log = LoggerFactory.getLogger(HighlightPublisher.class);

    private final VideoPositionClient positions;
    private final ClipHighlightClient clip;
    private final ChatWindowReader reader;
    private final DetectionProperties props;

    public HighlightPublisher(VideoPositionClient positions, ClipHighlightClient clip,
                              ChatWindowReader reader, DetectionProperties props) {
        this.positions = positions;
        this.clip = clip;
        this.reader = reader;
        this.props = props;
    }

    /**
     * @param countedUntil 집계에 쓰인 채팅의 도착 시각 상한 — <b>발행권을 잡은 시각</b>이다.
     *                     이 뒤에 도착한 채팅은 판정에 안 쓰였으므로 우리 구간 계산에서도 뺀다
     * @param now          지금. 우리 구간·총 시간의 끝점이다
     * @return 카드가 실제로 clip에 들어갔나
     */
    public boolean publish(String streamId, long metricId, long windowStartMs,
                           SpikeVerdict verdict, Instant countedUntil, Instant now) {
        long windowSizeMs = verdict.windowSizeMs();

        // 창 시작 시각 하나로만 부른다. 양 끝은 보정값이 같다고 보고 산수로 낸다.
        //
        // 🔴 그 산수의 오차 상한이 PRD가 적은 「몇십 ms」가 아니라 최대 1,500ms다
        // (계획 검증 F13). 수집 서버의 VideoPositionCalculator가 조각 경계에서
        // min(delta, duration)으로 클램프하고 그 실효 상한이 SEGMENT_DRIFT_TOLERANCE_MS=1500이다.
        // 창이 5,000ms인데 오차가 1,500ms면 자릿수가 다르다.
        //
        // 「카드당 변환 1회」 결정 자체는 유효하다 — 채팅마다 부르면 수집 서버가 감당 못 한다.
        // 다만 감수하는 대가의 크기를 알고 두는 것이다. 실제로 어긋나는 것은 조각 경계에
        // 걸친 창뿐이고, 그 창의 카드는 지점이 최대 1.5초 밀린다.
        VideoPosition position = positions.locate(streamId, windowStartMs);
        if (position.state() != VideoPosition.State.CONVERTED) {
            log.info("detect.card_skipped streamId={} windowStartMs={} reason={}",
                    streamId, windowStartMs, position.state());
            return false;
        }

        long start = position.positionMs();
        HighlightCard card = new HighlightCard(streamId, "detect-" + metricId,
                start + windowSizeMs / 2, start, start + windowSizeMs, verdict.evidenceJson(props));

        // clip은 음수를 @PositiveOrZero로 400 낸다. 400은 재시도로 안 풀리므로 여기서 막는다.
        if (!card.valid()) {
            log.info("detect.card_skipped streamId={} windowStartMs={} reason=invalid_window positionMs={}",
                    streamId, windowStartMs, start);
            return false;
        }

        ClipHighlightClient.PublishResult result = clip.publish(card);
        logLatency(streamId, card, windowStartMs, windowSizeMs, position, verdict, result, countedUntil, now);
        return result == ClipHighlightClient.PublishResult.CREATED
                || result == ClipHighlightClient.PublishResult.ALREADY_EXISTS;
    }

    /**
     * 두 구간을 한 줄에 같이 적는다. 나눠 적으면 같은 카드의 두 값을 이어 붙이는 일이
     * 로그를 읽는 쪽 몫이 된다.
     *
     * <h2>🔴 두 값의 시작점이 다르다 — 같게 만들지 마라</h2>
     *
     * <ul>
     *   <li><b>우리 구간</b>은 <b>그 창을 우리가 다 받은 시각</b>({@code max(received_at)})부터다.
     *       창이 닫힌 시각부터 재면 <b>전달 지연과 시계 어긋남이 섞인다</b> — 시청자가 늦게 쳤다는
     *       이유로 우리 목표가 실패한다. 시각 축 표 3번이고 PRD 사용자 결정이다(감사 2회차 R-2)</li>
     *   <li><b>총 시간</b>은 <b>장면이 벌어진 때</b>부터다. 그래서 창이 닫힌 시각을 쓰고
     *       보정값을 더한다 — 전달 지연이 <b>여기에는 들어가야 맞다</b></li>
     * </ul>
     *
     * <p>즉 두 숫자의 차가 곧 전달 지연이다. <b>둘을 같은 시작점으로 통일하면 한쪽이 반드시
     * 틀린다.</b>
     *
     * <p>보정값이 없으면 총 시간을 {@code unknown}으로 적는다 — 0을 적으면
     * 「지연이 없었다」는 거짓이 남고, 나중에 목표치를 정할 때 그 거짓이 표본에 섞인다.
     */
    private void logLatency(String streamId, HighlightCard card, long windowStartMs, long windowSizeMs,
                            VideoPosition position, SpikeVerdict verdict,
                            ClipHighlightClient.PublishResult result, Instant countedUntil, Instant now) {
        long windowClosedMs = windowStartMs + windowSizeMs;
        // 🔴 빈손이면 눈금으로 되돌아가지 않는다. 그러면 전달 지연을 0으로 본 값이 나오는데
        // 그건 「지연이 없었다」는 거짓이고, 목표치를 정할 때 그 거짓이 표본에 섞인다.
        // 모르면 모른다고 적는다 — 아래 총 시간이 이미 쓰는 규약과 같은 모양이다.
        String our = reader
                .lastReceivedAt(streamId, Instant.ofEpochMilli(windowStartMs),
                        Instant.ofEpochMilli(windowClosedMs), countedUntil)
                .map(receivedAt -> String.valueOf(now.toEpochMilli() - receivedAt.toEpochMilli()))
                .orElse("unknown");
        // 총 시간만 창이 닫힌 시각에서 잰다 — 장면 발생부터라 전달 지연이 들어가야 맞다.
        String total = position.appliedOffsetMs() == null
                ? "unknown"
                : String.valueOf(now.toEpochMilli() - windowClosedMs + position.appliedOffsetMs());
        log.info("detect.card_published streamId={} eventId={} result={} ratio={} count={} "
                        + "ourLatencyMs={} totalLatencyMs={}",
                streamId, card.eventId(), result, verdict.ratio(), verdict.messageCount(),
                our, total);
    }
}
