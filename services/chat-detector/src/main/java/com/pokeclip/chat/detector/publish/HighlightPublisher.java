package com.pokeclip.chat.detector.publish;

import com.pokeclip.chat.detector.config.DetectionProperties;
import com.pokeclip.chat.detector.detect.SpikeVerdict;
import com.pokeclip.chat.detector.metrics.ChatWindowReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.function.Supplier;

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

    /**
     * 이 창을 <b>다시 집어야 하나.</b> 「보냈나」만으로는 부르는 쪽이 그것을 못 가른다.
     *
     * <p>🔴 <b>가르는 축은 「다시 물으면 답이 바뀌나」다</b> — {@link VideoPosition.State}가
     * 같은 축으로 갈라 뒀는데 <b>부르는 쪽이 그 구분을 안 쓰고 있었다</b>(로컬 리뷰 라운드 2).
     * 수집 서버 계약이 정확히 그 실패를 경고한다: 「{@code not_yet_indexed}는 재시도할 자리다.
     * 포기한 쪽은 그 채팅의 하이라이트를 영영 잃는다 — <b>채팅에는 백필이 없다</b>」.
     *
     * <p>🔴 <b>그 축을 넓게 읽지 마라.</b> {@code RETRY_LATER}에 드는 것은 계약이 명시한
     * {@code not_yet_indexed} <b>하나뿐</b>이다. 「죽었으니 곧 살아나겠지」까지 넣었다가
     * 되돌린 적이 있다(라운드 3) — 자세한 것은 {@code publish} 본문 주석에.
     */
    public enum Outcome {
        /** clip에 들어갔다(새로 만들었거나 이미 있었거나) */
        SENT,
        /** 지금은 못 보낸다. <b>다시 물으면 답이 바뀐다</b> — 발행권을 되돌려야 한다 */
        RETRY_LATER,
        /** 다시 물어도 같다. 되돌리면 같은 실패를 무한히 반복한다 */
        GIVE_UP
    }

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
     * @param clock        끝점을 찍을 시계. <b>{@code Instant} 하나가 아니라 공급자다</b> —
     *                     끝점은 <b>카드가 나간 뒤</b>에 찍어야 하는데, 값으로 받으면 부르는 쪽이
     *                     <b>보내기 전</b> 시각을 넘기게 되고 우리 왕복이 통째로 빠진다(실기동에서 잡혔다)
     * @return 이 창을 다시 집어야 하는지까지 알려 준다. {@link Outcome} 참고
     */
    public Outcome publish(String streamId, long metricId, long windowStartMs,
                           SpikeVerdict verdict, Instant countedUntil, Supplier<Instant> clock) {
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
            // 🔴 「아직 없음」만 되돌린다. 발행권이 이미 잡혀 있어 여기서 포기하면 그 창은
            // 영영 다시 안 집히고, 채팅에는 백필이 없어 되찾을 방법도 없다.
            // 무한 재시도는 unpublished의 시간 하한이 막는다 — 되돌아보기(기본 1분)를
            // 벗어난 창은 목록에 아예 안 올라온다.
            //
            // 🔴 UNAVAILABLE은 여기 안 넣는다(로컬 리뷰 라운드 3에서 되돌렸다). 한 번
            // 넣었다가 뺀 이유가 둘이다.
            //   ① 그 상태는 원인 셋이 섞여 있다 — 예외 · unknown_state ·
            //      converted_without_position. 뒤의 둘은 창구가 같은 본문을 주는 한
            //      다시 물어도 영영 같은 답이라 되돌리면 매초 같은 실패를 반복한다.
            //   ② 첫째(예외)는 더 나쁘다. 창구가 죽어 있으면 매 시도가 read-timeout 3초를
            //      꺼내 쓰는데, 발행 실행기는 core 2 · queue 100이다. 수집 서버가 잠깐만
            //      내려가도 큐가 차서 그 뒤 발행이 전부 버려진다 — 되돌린 창도, 그 사이
            //      정상적으로 보낼 수 있던 다른 방송의 카드도 같이 잃는다.
            //
            // 가르는 축은 수집 서버 계약이 정한 것을 그대로 쓴다. 계약이 「재시도할 자리」로
            // 명시한 것은 not_yet_indexed 하나이고, UNAVAILABLE은 계약에 없는 우리 상태다.
            return position.state() == VideoPosition.State.NOT_YET_INDEXED
                    ? Outcome.RETRY_LATER
                    : Outcome.GIVE_UP;
        }

        long start = position.positionMs();
        HighlightCard card = new HighlightCard(streamId, "detect-" + metricId,
                start + windowSizeMs / 2, start, start + windowSizeMs, verdict.evidenceJson(props));

        // clip은 음수를 @PositiveOrZero로 400 낸다. 400은 재시도로 안 풀리므로 여기서 막는다.
        if (!card.valid()) {
            log.info("detect.card_skipped streamId={} windowStartMs={} reason=invalid_window positionMs={}",
                    streamId, windowStartMs, start);
            // 위치가 음수인 것은 다시 물어도 같다 — 그 채팅이 반응한 화면은 녹화 전이다.
            return Outcome.GIVE_UP;
        }

        ClipHighlightClient.PublishResult result = clip.publish(card);
        // 🔴 끝점은 여기서 찍는다 — 변환 창구와 clip 호출이 <b>끝난 뒤</b>다.
        // 위에서 찍으면 우리가 통제하는 왕복(정상 83ms, clip이 느리면 초 단위)이 숫자에서 사라진다.
        Instant sentAt = clock.get();
        logLatency(streamId, card, windowStartMs, windowSizeMs, position, verdict, result, countedUntil, sentAt);
        // clip이 거절(4xx)했거나 시도를 다 썼으면 되돌리지 않는다 — PRD 결정이다.
        // 거절은 다시 보내도 같고, 시도를 다 쓴 카드는 이미 늦어 되감기 창 밖이다.
        return result == ClipHighlightClient.PublishResult.CREATED
                || result == ClipHighlightClient.PublishResult.ALREADY_EXISTS
                ? Outcome.SENT
                : Outcome.GIVE_UP;
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
     * <h2>🔴 끝점은 「보내고 난 뒤」다 — 「보내기 직전」이 아니다</h2>
     *
     * 두 값 다 <b>카드가 clip에 들어간 뒤</b>에 찍은 시각으로 끝난다. 그래서 <b>변환 창구 호출과
     * clip 호출이, 그리고 <span>clip 재시도까지</span> 우리 구간 안에 들어간다.</b>
     *
     * <p><b>그게 맞는 동작이다</b> — clip이 느리면 우리 구간이 길어져야 한다. 그 왕복은
     * 우리가 통제하는 시간이고, 목표 3초는 <b>거기까지</b>를 재라고 정해진 값이다.
     *
     * <p>실기동에서 반대로 돼 있는 것이 잡혔다(2026-08-26): 부르는 쪽이 <b>보내기 전</b> 시각을
     * 넘겨서, 발행이 실제로 <b>9.02초</b> 걸린 판에서도 로그는 {@code ourLatencyMs=3219}였다.
     * 정상 경로에서도 약 <b>83ms</b>가 빠졌다. javadoc은 원래 「카드를 보낼 때까지」였으니
     * <b>코드가 문서와 어긋나 있었다.</b>
     *
     * <p>이것도 <b>낙관 방향</b>이라는 점이 R-2와 같다. 다만 R-2는 「남의 시간이 들어온 것」이었고
     * 이번은 <b>「우리 시간이 빠진 것」</b>이다 — 뒤쪽이 더 나쁘다.
     *
     * <p>보정값이 없으면 총 시간을 {@code unknown}으로 적는다 — 0을 적으면
     * 「지연이 없었다」는 거짓이 남고, 나중에 목표치를 정할 때 그 거짓이 표본에 섞인다.
     *
     * <h2>🔴 총 시간은 <b>두 시계를 뺀 값</b>이다 — 우리 구간과 정밀도가 다르다</h2>
     *
     * {@code windowClosedMs}는 <b>치지직이 찍은 시각</b>({@code message_time})의 눈금이고
     * {@code sentAt}은 <b>우리 시계</b>다. 두 시계가 어긋난 만큼이 총 시간에 그대로 실린다 —
     * 우리 시계가 앞서면 총 시간이 부풀고, 뒤처지면 줄어든다.
     *
     * <p><b>고치지 않는다</b>(로컬 리뷰 라운드 1, 사용자 결정). 고치려면 「장면이 벌어진 벽시계
     * 시각」을 따로 구해야 하는데 그건 설계 변경이다. 뿌리가 감사 1회차 A-1(시계 어긋남)과 같고
     * 그 한계는 README에 이미 적혀 있으며 {@code detect.active_but_empty}가 드러낸다.
     *
     * <p><b>{@code ourLatencyMs}는 무관하다</b> — 양 끝이 다 우리 시계({@code received_at}과
     * {@code sentAt})라 어긋나도 차가 안 변한다. <b>목표 3초를 거기에만 건 이유가 이것이다.</b>
     */
    private void logLatency(String streamId, HighlightCard card, long windowStartMs, long windowSizeMs,
                            VideoPosition position, SpikeVerdict verdict,
                            ClipHighlightClient.PublishResult result, Instant countedUntil, Instant sentAt) {
        long windowClosedMs = windowStartMs + windowSizeMs;
        // 🔴 빈손이면 눈금으로 되돌아가지 않는다. 그러면 전달 지연을 0으로 본 값이 나오는데
        // 그건 「지연이 없었다」는 거짓이고, 목표치를 정할 때 그 거짓이 표본에 섞인다.
        // 모르면 모른다고 적는다 — 아래 총 시간이 이미 쓰는 규약과 같은 모양이다.
        //
        // 🔴 이 조회는 카드가 이미 나간 뒤에 돈다. 던지면 예외가 publish까지 올라가
        // 부르는 쪽이 「발행이 실패했다」로 읽는데, 되돌릴 수 있는 것은 아무것도 없다.
        // 그래서 여기서 잡고 못 잰 칸만 unknown으로 둔다 — 줄 자체는 남아야 한다.
        // 줄을 통째로 버리면 「카드가 나갔다」는 사실이 로그에서 사라진다.
        String our;
        try {
            our = reader
                    .lastReceivedAt(streamId, Instant.ofEpochMilli(windowStartMs),
                            Instant.ofEpochMilli(windowClosedMs), countedUntil)
                    .map(receivedAt -> String.valueOf(sentAt.toEpochMilli() - receivedAt.toEpochMilli()))
                    .orElse("unknown");
        } catch (RuntimeException e) {
            // 「빈손」과 「조회 실패」는 원인이 달라 따로 남긴다. 로그에서는 둘 다 unknown이라
            // 이 줄이 없으면 나중에 unknown이 왜 늘었는지 못 찾는다.
            log.warn("detect.latency_unmeasured streamId={} eventId={}", streamId, card.eventId(), e);
            our = "unknown";
        }
        // 총 시간만 창이 닫힌 시각에서 잰다 — 장면 발생부터라 전달 지연이 들어가야 맞다.
        String total = position.appliedOffsetMs() == null
                ? "unknown"
                : String.valueOf(sentAt.toEpochMilli() - windowClosedMs + position.appliedOffsetMs());
        log.info("detect.card_published streamId={} eventId={} result={} ratio={} count={} "
                        + "ourLatencyMs={} totalLatencyMs={}",
                streamId, card.eventId(), result, verdict.ratio(), verdict.messageCount(),
                our, total);
    }
}
