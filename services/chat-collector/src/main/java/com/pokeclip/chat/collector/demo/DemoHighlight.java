package com.pokeclip.chat.collector.demo;

import com.pokeclip.chat.collector.sync.VideoPosition;
import com.pokeclip.chat.collector.sync.VideoPositionCalculator;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 발표 데모의 자동 하이라이트 카드. {@link DemoChatBurst}가 {@link DemoChatScript#CARD_AT_CHAT}번째 채팅을 넣는 순간 부른다
 * (사용자 결정, 2026-11-27 — 판별기 카드는 누른 뒤 약 37초에야 섰다).
 *
 * <p>판별기가 카드를 넣는 바로 그 문({@code POST /internal/broadcasts/{streamId}/highlights}, 출처 {@code auto})으로 넣는다.
 * 그래서 clip 저장 · 화면 통로 · 썸네일은 판별기 카드와 같은 길이고, 화면에는 「채팅 급증 (70건)」으로 선다(배율은 안 싣는다 —
 * 평소 채팅이 없는 데모 방송에서 배율은 지어낸 숫자다).
 *
 * <p>구간은 판별기와 같은 꼴이다: 누른 시각의 영상 위치에서 앞 {@link #LEAD} ~ 급증 끝 + {@link #TAIL}. 지점은 구간 시작이다 —
 * 편집자가 카드를 누르면 장면 앞에서 재생이 시작돼야 한다. 영상 위치는 이 서버의 변환기를 바로 부른다(판별기가 HTTP로 부르는 것과 같은
 * 계산, 채널 번호 없이 — 기본 보정값).
 *
 * <p>실행기가 따로다. 변환 조회와 clip 왕복이 급증 틱 실행기를 붙잡으면 채팅이 그만큼 몰려 나간다.
 */
@Component
public class DemoHighlight {

    private static final Logger log = LoggerFactory.getLogger(DemoHighlight.class);

    /** 판별기 {@code episode-lead} 기본값과 같다 — 채팅은 장면보다 늦다 */
    static final Duration LEAD = Duration.ofSeconds(15);

    /** 판별기 {@code episode-tail} 기본값과 같다 */
    static final Duration TAIL = Duration.ofSeconds(5);

    /** 급증이 흐르는 시간. 카드 끝을 급증 끝 뒤로 잡는다 */
    static final Duration BURST = DemoChatBurst.TICK.multipliedBy(DemoChatScript.BURST_TICKS);

    /**
     * 조각이 아직 장부에 안 들어왔을 때({@code not_yet_indexed}) 다시 묻는 간격 · 횟수. 10초면 조각 하나가 들어오고도 남는다.
     * 넘기면 카드를 포기한다 — 늦게 선 카드는 발표 흐름에서 쓸모가 없다
     */
    static final Duration RETRY = Duration.ofMillis(500);
    static final int ATTEMPTS = 20;

    /** 카드 한 장. clip 계약 2A의 값들이다 */
    public record Card(String eventId, long streamTimestampMs, long startMs, long endMs, int messageCount) {
    }

    /** clip에 카드를 넣는다. 중계가 꺼진 배포(로컬 · CI)에서는 {@link #NONE}이다 — 데모 채팅도 화면에 안 간다 */
    @FunctionalInterface
    public interface Sink {

        /** @return clip의 HTTP 상태. 못 닿았으면 {@code -1}. 던지지 않는다 */
        int send(String streamId, Card card);

        /** 보내지 않는다. 상태 0은 「안 보냄」이다 */
        Sink NONE = (streamId, card) -> 0;
    }

    /** 채팅 시각 → 영상 위치. 운영은 {@link VideoPositionCalculator}다 */
    @FunctionalInterface
    interface Locator {
        VideoPosition locate(String streamId, Instant messageTime);
    }

    private final Locator locator;
    private final Sink sink;
    private final ScheduledExecutorService scheduler;
    private final Duration retry;

    @Autowired
    public DemoHighlight(VideoPositionCalculator calculator, Sink sink) {
        this((streamId, at) -> calculator.locate(streamId, null, at), sink,
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "demo-chat-card");
                    thread.setDaemon(true);
                    return thread;
                }), RETRY);
    }

    DemoHighlight(Locator locator, Sink sink, ScheduledExecutorService scheduler, Duration retry) {
        this.locator = locator;
        this.sink = sink;
        this.scheduler = scheduler;
        this.retry = retry;
    }

    /**
     * 카드를 낸다. 바로 돌아온다 — 변환 · clip 왕복은 이 클래스의 실행기에서 돈다.
     *
     * @param pressedAtMillis 급증 첫 채팅이 찍힌 시각(누른 순간)
     */
    public void publish(String streamId, long pressedAtMillis) {
        scheduler.execute(() -> attempt(streamId, pressedAtMillis, 1));
    }

    private void attempt(String streamId, long pressedAtMillis, int attempt) {
        try {
            VideoPosition position = locator.locate(streamId, Instant.ofEpochMilli(pressedAtMillis));
            switch (position.state()) {
                case CONVERTED -> send(streamId, pressedAtMillis, position.positionMs(), attempt);
                case NOT_YET_INDEXED -> {
                    if (attempt < ATTEMPTS) {
                        scheduler.schedule(() -> attempt(streamId, pressedAtMillis, attempt + 1), retry.toMillis(),
                                TimeUnit.MILLISECONDS);
                    } else {
                        log.warn("demo.chat_card.skipped stream={} reason=not_yet_indexed attempts={}", streamId, attempt);
                    }
                }
                // 방송 시작 직후 보정값만큼은 영상이 없다(VideoPositionCalculator) — 다시 물어도 같다
                case NO_FOOTAGE -> log.warn("demo.chat_card.skipped stream={} reason=no_footage", streamId);
            }
        } catch (RuntimeException e) {
            // 실행기 스레드에서 던지면 조용히 사라진다. 메시지는 안 찍는다(주소 · SQL이 실린다)
            log.warn("demo.chat_card.failed stream={} cause={}", streamId, e.getClass().getSimpleName());
        }
    }

    private void send(String streamId, long pressedAtMillis, long positionMs, int attempt) {
        if (positionMs < 0) {
            // 판별기와 같다: 0으로 접으면 녹화 전 화면을 가리키는 카드가 된다
            log.warn("demo.chat_card.skipped stream={} reason=invalid_position positionMs={}", streamId, positionMs);
            return;
        }
        long start = Math.max(0, positionMs - LEAD.toMillis());
        long end = positionMs + BURST.toMillis() + TAIL.toMillis();
        Card card = new Card("demo-" + pressedAtMillis, start, start, end, DemoChatScript.CARD_AT_CHAT);
        int status = sink.send(streamId, card);
        if (status >= 200 && status < 300) {
            log.info("demo.chat_card.sent stream={} eventId={} status={} startMs={} endMs={} attempts={}",
                    streamId, card.eventId(), status, start, end, attempt);
        } else if (status == 0) {
            log.info("demo.chat_card.skipped stream={} reason=relay_off", streamId);
        } else {
            log.warn("demo.chat_card.failed stream={} eventId={} status={}", streamId, card.eventId(), status);
        }
    }

    @PreDestroy
    void close() {
        scheduler.shutdownNow();
    }
}
