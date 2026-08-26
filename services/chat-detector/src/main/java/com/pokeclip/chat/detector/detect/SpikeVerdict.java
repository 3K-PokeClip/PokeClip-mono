package com.pokeclip.chat.detector.detect;

import com.pokeclip.chat.detector.config.DetectionProperties;

/**
 * 판정 하나의 결과와 그 근거.
 *
 * @param reason 왜 그렇게 판정했나. {@code spike} · {@code warming_up} ·
 *               {@code below_min_count} · {@code below_ratio}
 */
public record SpikeVerdict(boolean spike,
                           String reason,
                           double ratio,
                           double baselineMedian,
                           long windowSizeMs,
                           int messageCount,
                           int chatterCount) {

    /**
     * clip의 {@code jump_cards.evidence}(jsonb)에 실을 본문.
     *
     * <p><b>모양을 우리가 정한다.</b> 그 칸의 주석이 "판정 근거(배수·건수 등). 모양이 멘토
     * 협업 미결이라 칸을 쪼개지 않는다"라고 적혀 있다 — 이 카드를 위해 비워 둔 자리다.
     * JSON 한 덩어리라 판정식이 바뀌어도 clip을 안 고치고 바꿀 수 있다.
     *
     * <p>임계값을 같이 싣는 이유: 값이 설정이라 <b>나중에 바뀐다</b>. 그때 옛 카드를 보고
     * "왜 이게 잡혔지"를 되짚으려면 그 시점의 임계값이 카드 안에 있어야 한다.
     *
     * <p>문자열을 손으로 조립한다 — 값이 전부 숫자와 열거형 이름이라 이스케이프할 것이 없다.
     */
    public String evidenceJson(DetectionProperties props) {
        return "{\"windowSizeMs\":" + windowSizeMs
                + ",\"messageCount\":" + messageCount
                + ",\"chatterCount\":" + chatterCount
                + ",\"baselineMedian\":" + baselineMedian
                + ",\"ratio\":" + ratio
                + ",\"thresholdRatio\":" + props.spikeRatio()
                + ",\"thresholdMinCount\":" + props.minCount()
                + ",\"metric\":\"" + props.metric() + "\"}";
    }
}
