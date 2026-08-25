package com.pokeclip.chat.detector.publish;

import com.pokeclip.chat.detector.config.DetectionProperties;
import com.pokeclip.chat.detector.config.DetectionProperties.Metric;
import com.pokeclip.chat.detector.detect.SpikeVerdict;
import com.pokeclip.chat.detector.publish.ClipHighlightClient.PublishResult;
import com.pokeclip.chat.detector.publish.VideoPosition.State;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HighlightPublisherTest {

    /** 위치 기반 생성자다. {@code DetectionProperties}에 칸이 늘면 여기도 같이 고친다. */
    private static final DetectionProperties PROPS = new DetectionProperties(
            Duration.ofSeconds(1), List.of(5_000L), 5_000L,
            Duration.ofSeconds(2), Duration.ofMinutes(10),
            Duration.ofSeconds(60), Duration.ofMinutes(1), Duration.ofMinutes(15),
            5, 3.0, 10, Metric.MESSAGE, Duration.ofHours(24));

    private static final SpikeVerdict SPIKE =
            new SpikeVerdict(true, "spike", 4.0, 10.0, 5_000L, 40, 25);

    /** 채팅이 우리에게 온 시각. 창 시작 눈금(epoch ms)과 같은 축이다. */
    private static final long WINDOW_START_MS = 1_787_529_600_000L;

    private record Call(String streamId, long messageTime) {
    }

    private VideoPosition position = new VideoPosition(State.CONVERTED, 30_000L, 3_900L);
    /** 그 창을 우리가 다 받은 시각. 기본은 전달 지연 0(창이 닫힌 시각과 같다). */
    private java.util.Optional<Instant> lastReceivedAt =
            java.util.Optional.of(Instant.ofEpochMilli(WINDOW_START_MS + 5_000));
    private PublishResult publishResult = PublishResult.CREATED;
    private final List<HighlightCard> published = new java.util.ArrayList<>();
    private final List<Call> located = new java.util.ArrayList<>();

    private HighlightPublisher publisher() {
        VideoPositionClient positions = new VideoPositionClient(
                org.springframework.web.client.RestClient.builder(),
                new com.pokeclip.chat.detector.config.CollectorClientProperties("http://unused"),
                new com.pokeclip.chat.detector.config.InternalApiProperties("t")) {
            @Override
            public VideoPosition locate(String streamId, long messageTimeEpochMs) {
                located.add(new Call(streamId, messageTimeEpochMs));
                return position;
            }
        };
        ClipHighlightClient clip = new ClipHighlightClient(
                org.springframework.web.client.RestClient.builder(),
                new com.pokeclip.chat.detector.config.ClipClientProperties("http://unused", 1),
                new com.pokeclip.chat.detector.config.InternalApiProperties("t")) {
            @Override
            public PublishResult publish(HighlightCard card) {
                published.add(card);
                return publishResult;
            }
        };
        com.pokeclip.chat.detector.metrics.ChatWindowReader reader =
                new com.pokeclip.chat.detector.metrics.ChatWindowReader(null) {
                    @Override
                    public java.util.Optional<Instant> lastReceivedAt(String streamId, Instant from, Instant to) {
                        return lastReceivedAt;
                    }
                };
        return new HighlightPublisher(positions, clip, reader, PROPS);
    }

    /** 창 시작 시각 하나로만 변환을 부른다. 채팅마다 부르면 수집 서버가 감당 못 한다. */
    @Test
    void 변환_창구를_카드당_한_번만_부른다() {
        publisher().publish("s1", 7L, WINDOW_START_MS, SPIKE, Instant.now());

        assertThat(located).containsExactly(new Call("s1", WINDOW_START_MS));
    }

    /** 창 양 끝은 보정값이 같으므로 산수로 낸다. 지점은 창 가운데다. */
    @Test
    void 창_양_끝을_산수로_낸다() {
        publisher().publish("s1", 7L, WINDOW_START_MS, SPIKE, Instant.now());

        assertThat(published).singleElement().satisfies(card -> {
            assertThat(card.windowStartMs()).isEqualTo(30_000L);
            assertThat(card.windowEndMs()).isEqualTo(35_000L);
            assertThat(card.streamTimestampMs()).isEqualTo(32_500L);
            assertThat(card.eventId()).isEqualTo("detect-7");
        });
    }

    /** 🔴 영영 없는 화면은 카드를 안 낸다. 내면 없는 화면을 가리키는 클립이 만들어진다. */
    @Test
    void 영영_없음이면_카드를_안_낸다() {
        position = new VideoPosition(State.NO_FOOTAGE, null, 3_900L);

        assertThat(publisher().publish("s1", 7L, WINDOW_START_MS, SPIKE, Instant.now())).isFalse();
        assertThat(published).isEmpty();
    }

    @Test
    void 아직_없음이면_카드를_안_낸다() {
        position = new VideoPosition(State.NOT_YET_INDEXED, null, 3_900L);

        assertThat(publisher().publish("s1", 7L, WINDOW_START_MS, SPIKE, Instant.now())).isFalse();
        assertThat(published).isEmpty();
    }

    @Test
    void 창구가_모르면_카드를_안_낸다() {
        position = new VideoPosition(State.UNAVAILABLE, null, null);

        assertThat(publisher().publish("s1", 7L, WINDOW_START_MS, SPIKE, Instant.now())).isFalse();
        assertThat(published).isEmpty();
    }

    /**
     * 🔴 방송 아주 초반의 창은 위치가 음수가 될 수 있다. clip은 @PositiveOrZero라 400이고,
     * 400은 재시도로 안 풀린다 — 보내기 전에 우리가 거른다.
     */
    @Test
    void 위치가_음수면_보내기_전에_거른다() {
        position = new VideoPosition(State.CONVERTED, -1_000L, 3_900L);

        assertThat(publisher().publish("s1", 7L, WINDOW_START_MS, SPIKE, Instant.now())).isFalse();
        assertThat(published).isEmpty();
    }

    /**
     * 지연 두 구간이 로그에 각각 남아야 한다(사용자 결정). 우리 구간은 판정에 걸고,
     * 총 시간은 기록만 한다 — 통제 못 하는 구간을 판정에 넣으면 시청자가 늦게 쳐도 실패한다.
     */
    @Test
    void 지연_두_구간이_로그에_각각_남는다() {
        // 창이 닫힌 뒤 2.5초 지나 발행한다고 하자.
        Instant now = Instant.ofEpochMilli(WINDOW_START_MS + 5_000 + 2_500);

        // LogCaptor는 루트 로거에 붙어 모든 줄을 모은다. 이벤트 이름 접두어로 거른다 —
        // 이 클래스가 levelOf(eventPrefix)를 두고 있는 것과 같은 쓰임이다.
        try (LogCaptor captor = new LogCaptor()) {
            publisher().publish("s1", 7L, WINDOW_START_MS, SPIKE, now);

            assertThat(captor.messages())
                    .filteredOn(m -> m.startsWith("detect.card_published"))
                    .singleElement().satisfies(line -> assertThat(line)
                            // 우리 구간: 창이 닫힌 시점부터 지금까지 = 2,500ms
                            .contains("ourLatencyMs=2500")
                            // 총 시간: 우리 구간 + 보정값(장면 발생 → 채팅 도착) = 6,400ms
                            .contains("totalLatencyMs=6400"));
        }
    }

    /**
     * 🔴 <b>두 숫자의 시작점이 다르다.</b> 우리 구간은 <b>그 창을 다 받은 시각</b>부터,
     * 총 시간은 <b>창이 닫힌 시각</b>부터다. 전달이 1초 늦었으면 우리 구간만 1초 줄고
     * 총 시간은 그대로여야 한다 — 전달 지연은 우리 탓이 아니지만 <b>장면부터 재는 총 시간에는
     * 들어가야 맞다</b>.
     *
     * <p>이 갈래가 없으면 우리 구간 시작점을 창 눈금({@code message_time})으로 되돌려도
     * <b>아무 검사도 안 깨진다</b> — 그 상태가 감사 2회차 R-2였다. 위 검사는 전달 지연 0으로만
     * 재서 두 시작점이 <b>우연히 같은</b> 경우만 본다.
     */
    @Test
    void 전달이_늦으면_우리_구간만_줄고_총_시간은_그대로다() {
        Instant now = Instant.ofEpochMilli(WINDOW_START_MS + 5_000 + 2_500);
        // 창이 닫히고 1초 뒤에야 마지막 채팅이 우리에게 닿았다.
        lastReceivedAt = java.util.Optional.of(Instant.ofEpochMilli(WINDOW_START_MS + 5_000 + 1_000));

        try (LogCaptor captor = new LogCaptor()) {
            publisher().publish("s1", 7L, WINDOW_START_MS, SPIKE, now);

            assertThat(captor.messages())
                    .filteredOn(m -> m.startsWith("detect.card_published"))
                    .singleElement().satisfies(line -> assertThat(line)
                            .contains("ourLatencyMs=1500")      // 2,500 − 1,000
                            .contains("totalLatencyMs=6400"));  // 2,500 + 3,900 그대로
        }
    }

    /**
     * 그 창을 언제 다 받았는지 모르면 우리 구간도 {@code unknown}이다.
     * <b>창 눈금으로 되돌아가면 전달 지연을 0으로 본 값</b>이 나오고, 그건
     * 「지연이 없었다」는 거짓이라 목표치 표본을 오염시킨다(총 시간이 쓰는 규약과 같다).
     */
    @Test
    void 창을_언제_다_받았는지_모르면_우리_구간을_안_적는다() {
        lastReceivedAt = java.util.Optional.empty();

        try (LogCaptor captor = new LogCaptor()) {
            publisher().publish("s1", 7L, WINDOW_START_MS, SPIKE, Instant.now());

            assertThat(captor.messages())
                    .filteredOn(m -> m.startsWith("detect.card_published"))
                    .singleElement().satisfies(line -> assertThat(line)
                            .contains("ourLatencyMs=unknown"));
        }
    }

    /**
     * 보정값을 모르면 총 시간을 못 낸다. 0으로 적으면 「지연 없음」이라는 거짓이 남는다.
     *
     * <p><b>지금 창구는 이 상태를 만들 수 없다</b>(계획 검증 F17) — {@code VideoPositionController}의
     * {@code appliedOffsetMs}가 primitive {@code long}이라 늘 실린다. 그래도 방어와 시험을
     * 남기는 이유는 <b>창구가 죽어 본문이 이상할 때</b>와 창구가 그 칸을 선택으로 바꿀 때다.
     * 「지금 필요한 방어」가 아니라 「값이 없을 때의 규약」을 못박는 자리로 읽어라.
     */
    @Test
    void 보정값을_모르면_총_시간을_안_적는다() {
        position = new VideoPosition(State.CONVERTED, 30_000L, null);

        try (LogCaptor captor = new LogCaptor()) {
            publisher().publish("s1", 7L, WINDOW_START_MS, SPIKE, Instant.now());

            assertThat(captor.messages())
                    .filteredOn(m -> m.startsWith("detect.card_published"))
                    .singleElement().satisfies(line -> assertThat(line)
                            .contains("totalLatencyMs=unknown"));
        }
    }
}
