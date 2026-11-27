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
 * <p>구간은 <b>누른 자리 앞 {@link #LEAD} ~ 누른 자리</b>이고 지점은 구간 시작이다 — 편집자가 카드를 누르면 장면 앞에서 재생이 시작돼야
 * 한다. 🔴 끝을 누른 자리 뒤로 잡지 않는다(PR #558 리뷰): 카드는 누른 뒤 약 6초에 서는데 그보다 뒤 자리는 아직 녹화 전이라, clip이 끝
 * 장면을 못 찾아 {@code playerWindow}를 비우고 화면은 「저장」을 「편집기에서 범위를 정해 저장」으로 막는다. 누른 자리는 방금 변환에
 * 성공한 자리라 장부에 있다. 영상 위치는 이 서버의 변환기를 바로 부른다(판별기가 HTTP로 부르는 것과 같은 계산, 채널 번호 없이 —
 * 기본 보정값).
 *
 * <p>실행기가 따로다. 변환 조회와 clip 왕복이 급증 틱 실행기를 붙잡으면 채팅이 그만큼 몰려 나간다.
 */
@Component
public class DemoHighlight {

    private static final Logger log = LoggerFactory.getLogger(DemoHighlight.class);

    /** 판별기 {@code episode-lead} 기본값과 같다 — 채팅은 장면보다 늦다 */
    static final Duration LEAD = Duration.ofSeconds(15);

    /**
     * 70번째 채팅을 넣고 카드를 내기까지 기다리는 시간. 채팅은 중계(수집기 {@code pokeclip.relay.flush-max-delay} 0.1초)를 거쳐 clip에
     * 가고 화면이 중계 줄을 150ms마다 묶어 그리는데({@code useChatPanelMockState} {@code RELAY_FLUSH_MS}), 카드는 오는 즉시 그린다.
     * 기다리지 않으면 카드가 70번째 채팅보다 0.1~0.3초(채팅 2~3건) 먼저 뜬다(PR #558 리뷰). 두 값을 바꾸면 여기도 본다
     */
    static final Duration AFTER_CHAT = Duration.ofMillis(300);

    /**
     * 조각이 아직 장부에 안 들어왔을 때({@code not_yet_indexed}) 다시 묻는 간격 · 횟수. 10초면 조각 하나가 들어오고도 남는다.
     * 넘기면 카드를 포기한다 — 늦게 선 카드는 발표 흐름에서 쓸모가 없다. clip에 다시 보낼 때도 같은 간격을 쓴다
     */
    static final Duration RETRY = Duration.ofMillis(500);
    static final int ATTEMPTS = 20;

    /**
     * clip에 보내는 횟수. 못 닿음 · 시한 초과 · 5xx만 다시 보낸다(판별기 {@code ClipHighlightClient}의 {@code CLIP_MAX_ATTEMPTS} 3과 같다) —
     * 판별기는 데모 채널을 안 세므로 받쳐 줄 카드가 없다. 같은 카드는 clip 유일 색인(방송, 구간 시작)이 한 장으로 접으니 다시 보내도 안전하다
     */
    static final int SEND_ATTEMPTS = 3;

    /** 카드 한 장. clip 계약 2A의 값들이다 */
    public record Card(String eventId, long streamTimestampMs, long startMs, long endMs, int messageCount) {
    }

    /** clip에 카드를 넣는다. clip 주소나 내부 토큰이 없는 배포에서는 {@link #NONE}이다({@link DemoConfiguration}) */
    @FunctionalInterface
    public interface Sink {

        /** @return clip의 HTTP 상태. 못 닿았으면 {@code -1}. 던지지 않는다 */
        int send(String streamId, Card card);

        /** 보내지 않는다. {@link DemoHighlight#publish}가 변환 전에 거른다 */
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
    private final Duration afterChat;

    @Autowired
    public DemoHighlight(VideoPositionCalculator calculator, Sink sink) {
        this((streamId, at) -> calculator.locate(streamId, null, at), sink,
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "demo-chat-card");
                    thread.setDaemon(true);
                    return thread;
                }), RETRY, AFTER_CHAT);
    }

    DemoHighlight(Locator locator, Sink sink, ScheduledExecutorService scheduler, Duration retry, Duration afterChat) {
        this.locator = locator;
        this.sink = sink;
        this.scheduler = scheduler;
        this.retry = retry;
        this.afterChat = afterChat;
    }

    /**
     * 카드를 낸다. 바로 돌아온다 — {@link #AFTER_CHAT} 뒤 이 클래스의 실행기에서 변환 · clip 왕복을 한다.
     *
     * @param pressedAtMillis 급증 첫 채팅이 찍힌 시각(누른 순간)
     */
    public void publish(String streamId, long pressedAtMillis) {
        if (sink == Sink.NONE) {
            // 보낼 곳이 없으면 장부를 조회할 까닭도 없다 — 조각이 없으면 10초 동안 헛조회만 돈다
            log.info("demo.chat_card.skipped stream={} reason=no_clip_link", streamId);
            return;
        }
        scheduler.schedule(() -> attempt(streamId, pressedAtMillis, 1), afterChat.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void attempt(String streamId, long pressedAtMillis, int attempt) {
        try {
            VideoPosition position = locator.locate(streamId, Instant.ofEpochMilli(pressedAtMillis));
            switch (position.state()) {
                case CONVERTED -> send(streamId, pressedAtMillis, position.positionMs());
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

    private void send(String streamId, long pressedAtMillis, long positionMs) {
        if (positionMs <= 0) {
            // 판별기와 같다: 음수를 0으로 접으면 녹화 전 화면을 가리키는 카드가 된다. 0이면 구간이 비어 clip이 400으로 받지 않는다
            log.warn("demo.chat_card.skipped stream={} reason=invalid_position positionMs={}", streamId, positionMs);
            return;
        }
        long start = Math.max(0, positionMs - LEAD.toMillis());
        deliver(streamId, new Card("demo-" + pressedAtMillis, start, start, positionMs, DemoChatScript.CARD_AT_CHAT), 1);
    }

    private void deliver(String streamId, Card card, int sendAttempt) {
        int status = sink.send(streamId, card);
        if (status == 201) {
            log.info("demo.chat_card.sent stream={} eventId={} startMs={} endMs={} sendAttempts={}",
                    streamId, card.eventId(), card.startMs(), card.endMs(), sendAttempt);
        } else if (status >= 200 && status < 300) {
            // 같은 (방송, 구간 시작)의 자동 카드가 이미 있다. 영상 15초 안에서 두 번 누르면 시작이 둘 다 0으로 접혀 여기로 온다 — 새 카드는 안 선다
            log.info("demo.chat_card.duplicate stream={} eventId={} status={} startMs={}",
                    streamId, card.eventId(), status, card.startMs());
        } else if ((status < 0 || status >= 500) && sendAttempt < SEND_ATTEMPTS) {
            log.warn("demo.chat_card.retrying stream={} eventId={} status={} sendAttempt={}/{}",
                    streamId, card.eventId(), status, sendAttempt, SEND_ATTEMPTS);
            scheduler.schedule(() -> deliver(streamId, card, sendAttempt + 1), retry.toMillis(), TimeUnit.MILLISECONDS);
        } else {
            // 4xx는 다시 보내도 같은 답이다(판별기와 같다)
            log.warn("demo.chat_card.failed stream={} eventId={} status={} sendAttempts={}",
                    streamId, card.eventId(), status, sendAttempt);
        }
    }

    @PreDestroy
    void close() {
        scheduler.shutdownNow();
    }
}
