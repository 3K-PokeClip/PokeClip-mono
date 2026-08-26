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
    /** 조회에 실제로 넘어간 상한. 발행권을 잡은 시각이어야 한다. */
    private Instant 넘어간_상한;
    /** 가짜 clip이 묶여 있는 시간. 발행 왕복이 우리 구간에 들어가는지 재려고 둔다. */
    private java.time.Duration clip이_걸리는_시간 = java.time.Duration.ZERO;
    private java.util.concurrent.atomic.AtomicReference<Instant> 밀리는_시계;
    /** 지연 조회가 터지는 상황. DB가 잠깐 안 될 때를 재현한다. */
    private RuntimeException 조회가_던질_예외;

    /** 발행권을 잡은 시각. 지금(now)보다 앞선다 — 발행이 밀릴 수 있으므로. */
    private static final Instant CLAIMED_AT = Instant.ofEpochMilli(WINDOW_START_MS + 5_000 + 500);
    private PublishResult publishResult = PublishResult.CREATED;
    private final List<HighlightCard> published = new java.util.ArrayList<>();
    private final List<Call> located = new java.util.ArrayList<>();

    /** 지표만 바꾼 설정. 위치 기반 생성자라 칸이 늘면 여기도 같이 고친다. */
    private static DetectionProperties propsWith(Metric metric) {
        return new DetectionProperties(
                Duration.ofSeconds(1), List.of(5_000L), 5_000L,
                Duration.ofSeconds(2), Duration.ofMinutes(10),
                Duration.ofSeconds(60), Duration.ofMinutes(1), Duration.ofMinutes(15),
                5, 3.0, 10, metric, Duration.ofHours(24));
    }

    private DetectionProperties props = PROPS;

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
                if (밀리는_시계 != null) {
                    밀리는_시계.updateAndGet(t -> t.plus(clip이_걸리는_시간));
                }
                return publishResult;
            }
        };
        com.pokeclip.chat.detector.metrics.ChatWindowReader reader =
                new com.pokeclip.chat.detector.metrics.ChatWindowReader(null) {
                    @Override
                    public java.util.Optional<Instant> lastReceivedAt(String streamId, Instant from,
                                                                      Instant to, Instant countedUntil) {
                        넘어간_상한 = countedUntil;
                        if (조회가_던질_예외 != null) {
                            throw 조회가_던질_예외;
                        }
                        return lastReceivedAt;
                    }
                };
        return new HighlightPublisher(positions, clip, reader, props);
    }

    /** 창 시작 시각 하나로만 변환을 부른다. 채팅마다 부르면 수집 서버가 감당 못 한다. */
    @Test
    void 변환_창구를_카드당_한_번만_부른다() {
        publisher().publish("s1", 7L, WINDOW_START_MS, SPIKE, CLAIMED_AT, Instant::now);

        assertThat(located).containsExactly(new Call("s1", WINDOW_START_MS));
    }

    /** 창 양 끝은 보정값이 같으므로 산수로 낸다. 지점은 창 가운데다. */
    @Test
    void 창_양_끝을_산수로_낸다() {
        publisher().publish("s1", 7L, WINDOW_START_MS, SPIKE, CLAIMED_AT, Instant::now);

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

        assertThat(publisher().publish("s1", 7L, WINDOW_START_MS, SPIKE, CLAIMED_AT, Instant::now)).isEqualTo(HighlightPublisher.Outcome.GIVE_UP);
        assertThat(published).isEmpty();
    }

    @Test
    void 아직_없음이면_카드를_안_낸다() {
        position = new VideoPosition(State.NOT_YET_INDEXED, null, 3_900L);

        assertThat(publisher().publish("s1", 7L, WINDOW_START_MS, SPIKE, CLAIMED_AT, Instant::now)).isEqualTo(HighlightPublisher.Outcome.RETRY_LATER);
        assertThat(published).isEmpty();
    }

    /**
     * 🔴 <b>「모름」은 재시도 대상이 아니다</b>(로컬 리뷰 라운드 3에서 되돌렸다).
     * 한 번 {@code RETRY_LATER}로 넣었다가 뺐고, 뺀 이유가 둘이다.
     *
     * <ul>
     *   <li>그 상태는 <b>원인 셋</b>이 섞여 있다 — 예외 · {@code unknown_state} ·
     *       {@code converted_without_position}. 뒤의 둘은 창구가 같은 본문을 주는 한
     *       <b>다시 물어도 영영 같다</b></li>
     *   <li>예외 쪽은 더 나쁘다 — 창구가 죽어 있으면 매 시도가 {@code read-timeout} 3초를
     *       꺼내 쓰는데 발행 실행기는 core 2 · queue 100이다. <b>큐가 차면 그 뒤 발행이
     *       전부 버려진다</b> — 되돌린 창도, 다른 방송의 정상 카드도 같이 잃는다</li>
     * </ul>
     *
     * <p>가르는 축은 수집 서버 계약이 정한 것을 그대로 쓴다 — 재시도할 자리는
     * {@code not_yet_indexed} 하나다.
     */
    @Test
    void 창구가_모르면_카드를_안_낸다() {
        position = new VideoPosition(State.UNAVAILABLE, null, null);

        assertThat(publisher().publish("s1", 7L, WINDOW_START_MS, SPIKE, CLAIMED_AT, Instant::now)).isEqualTo(HighlightPublisher.Outcome.GIVE_UP);
        assertThat(published).isEmpty();
    }

    /**
     * 🔴 방송 아주 초반의 창은 위치가 음수가 될 수 있다. clip은 @PositiveOrZero라 400이고,
     * 400은 재시도로 안 풀린다 — 보내기 전에 우리가 거른다.
     */
    @Test
    void 위치가_음수면_보내기_전에_거른다() {
        position = new VideoPosition(State.CONVERTED, -1_000L, 3_900L);

        assertThat(publisher().publish("s1", 7L, WINDOW_START_MS, SPIKE, CLAIMED_AT, Instant::now)).isEqualTo(HighlightPublisher.Outcome.GIVE_UP);
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
            publisher().publish("s1", 7L, WINDOW_START_MS, SPIKE, CLAIMED_AT, () -> now);

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
     * 🔴 <b>우리 구간에 발행 왕복이 들어간다.</b> 끝점은 <b>카드가 나간 뒤</b>에 찍혀야 한다 —
     * 변환 창구 호출과 clip 호출, 그리고 <b>clip 재시도</b>가 그 안에 들어가는 것이 맞는 동작이다.
     * 목표 3초는 「보내고 난 뒤까지」를 재라고 정해진 값이고, 그 왕복은 <b>우리가 통제하는 시간</b>이다.
     *
     * <p>실기동에서 반대로 돼 있는 것이 잡혔다 — 발행이 실제로 <b>9.02초</b> 걸린 판에서도
     * 로그는 {@code ourLatencyMs=3219}였다(정상 경로에서도 약 83ms가 빠졌다).
     * <b>javadoc은 원래 「카드를 보낼 때까지」였으니 코드가 문서와 어긋나 있었다.</b>
     *
     * <p>이것도 낙관 방향이라는 점이 R-2와 같다. 다만 R-2는 「남의 시간이 들어온 것」이었고
     * 이번은 <b>「우리 시간이 빠진 것」</b>이다 — 뒤쪽이 더 나쁘다.
     */
    @Test
    void 우리_구간에_발행_왕복이_들어간다() {
        Instant 보내기_직전 = Instant.ofEpochMilli(WINDOW_START_MS + 5_000 + 2_500);
        밀리는_시계 = new java.util.concurrent.atomic.AtomicReference<>(보내기_직전);
        clip이_걸리는_시간 = java.time.Duration.ofSeconds(9);   // 먹통 clip을 재현한다

        try (LogCaptor captor = new LogCaptor()) {
            publisher().publish("s1", 7L, WINDOW_START_MS, SPIKE, CLAIMED_AT, 밀리는_시계::get);

            assertThat(captor.messages())
                    .filteredOn(m -> m.startsWith("detect.card_published"))
                    .singleElement().satisfies(line -> assertThat(line)
                            // 2,500(보내기 전까지) + 9,000(clip이 묶인 시간)
                            .contains("ourLatencyMs=11500")
                            // 총 시간도 같은 끝점을 쓴다 — 11,500 + 보정값 3,900
                            .contains("totalLatencyMs=15400"));
        }
    }

    /**
     * 🔴 <b>집계에 쓰인 채팅만 본다.</b> 조회에 넘기는 상한이 「지금」이 아니라
     * <b>발행권을 잡은 시각</b>이어야 한다 — 발행은 실행기에서 돌고 clip 재시도까지 끼면
     * 바퀴에서 초 단위로 떨어질 수 있는데, 그 사이 도착한 채팅은 <b>판정에 쓰이지도 않았다.</b>
     * 상한이 「지금」이면 그 채팅이 {@code max}를 밀어 우리 구간이 <b>늘 낙관적으로</b> 틀린다.
     */
    @Test
    void 집계에_쓰인_채팅만_보도록_상한을_건다() {
        Instant now = Instant.ofEpochMilli(WINDOW_START_MS + 5_000 + 2_500);

        publisher().publish("s1", 7L, WINDOW_START_MS, SPIKE, CLAIMED_AT, () -> now);

        assertThat(넘어간_상한).as("상한이 발행권을 잡은 시각이어야 한다").isEqualTo(CLAIMED_AT);
        assertThat(넘어간_상한).as("「지금」을 넘기면 늦게 온 채팅이 섞인다").isNotEqualTo(now);
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
            publisher().publish("s1", 7L, WINDOW_START_MS, SPIKE, CLAIMED_AT, () -> now);

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
            publisher().publish("s1", 7L, WINDOW_START_MS, SPIKE, CLAIMED_AT, Instant::now);

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
            publisher().publish("s1", 7L, WINDOW_START_MS, SPIKE, CLAIMED_AT, Instant::now);

            assertThat(captor.messages())
                    .filteredOn(m -> m.startsWith("detect.card_published"))
                    .singleElement().satisfies(line -> assertThat(line)
                            .contains("totalLatencyMs=unknown"));
        }
    }

    /**
     * 🔴 <b>지연을 못 재는 것이 발행을 되돌리지 않는다.</b> 이 조회는 <b>카드가 clip에 들어간 뒤</b>에
     * 도는데, 여기서 던지면 예외가 {@code publish}까지 올라가 부르는 쪽이 <b>발행이 실패했다</b>고
     * 읽는다 — 실제로는 카드가 이미 나갔다.
     *
     * <p>대가가 한쪽으로만 크다. 되돌릴 수 있는 것이 아무것도 없는데
     * {@code detect.publish_threw}가 찍히고, {@code clip.publish}가 멱등이라 다음 바퀴가
     * 다시 보내도 {@code ALREADY_EXISTS}로 끝난다 — 즉 <b>얻는 것 없이 오해만 남는다.</b>
     */
    @Test
    void 지연을_못_재도_발행이_되돌아가지_않는다() {
        조회가_던질_예외 = new org.springframework.dao.QueryTimeoutException("DB가 안 된다");

        HighlightPublisher.Outcome 결과 =
                publisher().publish("s1", 7L, WINDOW_START_MS, SPIKE, CLAIMED_AT, Instant::now);

        assertThat(결과).isEqualTo(HighlightPublisher.Outcome.SENT);
        assertThat(published).hasSize(1);
    }

    /**
     * <b>줄은 남고 못 잰 칸만 {@code unknown}이 된다.</b> 조회 실패로 줄을 통째로 버리면
     * <b>카드가 나갔다는 사실 자체가 로그에서 사라진다</b> — 나중에 「그 방송에 카드가 몇 장
     * 나갔나」를 셀 때 그 카드가 없는 것이 된다.
     *
     * <p>{@code unknown}은 빈손일 때 이미 쓰는 규약이고 뜻도 같다 — <b>모르면 모른다고 적는다.</b>
     * 다만 「빈손」과 「조회 실패」는 원인이 달라 {@code detect.latency_unmeasured}를 따로 남긴다.
     * 원인을 지우면 나중에 왜 {@code unknown}이 늘었는지 못 찾는다.
     */
    @Test
    void 지연을_못_재면_모른다고_적고_줄은_남긴다() {
        조회가_던질_예외 = new org.springframework.dao.QueryTimeoutException("DB가 안 된다");

        try (LogCaptor captor = new LogCaptor()) {
            publisher().publish("s1", 7L, WINDOW_START_MS, SPIKE, CLAIMED_AT, Instant::now);

            assertThat(captor.messages())
                    .filteredOn(m -> m.startsWith("detect.card_published"))
                    .singleElement().satisfies(line -> assertThat(line)
                            .contains("ourLatencyMs=unknown")
                            // 총 시간은 이 조회와 무관하다 — 같이 버리지 않는다
                            .contains("totalLatencyMs="));
            assertThat(captor.messages())
                    .anyMatch(m -> m.startsWith("detect.latency_unmeasured"));
        }
    }

    /**
     * 🔴 <b>clip 이 거절했거나 시도를 다 쓴 것은 되돌리지 않는다.</b> 다시 보내도 같은 답이라
     * 되돌리면 같은 실패만 무한히 반복하고, 그 카드는 이미 늦어 되감기 창 밖이다(PRD 결정).
     *
     * <p>「다시 물으면 답이 바뀌나」가 가르는 축이라는 것을 <b>양쪽에서</b> 못박는다 —
     * 위 두 검사가 {@code RETRY_LATER} 쪽이고 이것이 {@code GIVE_UP} 쪽이다.
     */
    @Test
    void clip이_거절하거나_시도를_다_쓰면_되돌리지_않는다() {
        publishResult = PublishResult.REJECTED;
        assertThat(publisher().publish("s1", 7L, WINDOW_START_MS, SPIKE, CLAIMED_AT, Instant::now))
                .isEqualTo(HighlightPublisher.Outcome.GIVE_UP);

        publishResult = PublishResult.FAILED;
        assertThat(publisher().publish("s1", 7L, WINDOW_START_MS, SPIKE, CLAIMED_AT, Instant::now))
                .isEqualTo(HighlightPublisher.Outcome.GIVE_UP);
    }

    /** clip 이 이미 갖고 있는 것도 성공이다. 되돌리면 정상 중복이 재시도 대상이 된다. */
    @Test
    void 이미_있는_카드도_보낸_것으로_친다() {
        publishResult = PublishResult.ALREADY_EXISTS;

        assertThat(publisher().publish("s1", 7L, WINDOW_START_MS, SPIKE, CLAIMED_AT, Instant::now))
                .isEqualTo(HighlightPublisher.Outcome.SENT);
    }

    /**
     * 🔴 <b>clip 이 그 방송을 아직 모르면 다음 바퀴로 미룬다</b>(봇 리뷰 1판, codex).
     *
     * <p>방송 시작 알림을 수집기와 clip 이 <b>각자 다른 큐</b>에서 받으므로, clip 이 늦으면
     * 채팅은 이미 쌓이는데 clip 에는 방송 행이 없다. 그때 오는 404를 다른 4xx 와 같이
     * 영구 실패로 접으면 <b>그 급증의 하이라이트가 영영 사라진다.</b>
     *
     * <p><b>clip 이 재시도를 전제로 설계한 자리다</b> — {@code JumpCardService.record} 가
     * 「FK 위반은 500이 되고, 판별기는 404를 받아야 재시도 상한을 센다」라고 적어 뒀다.
     */
    @Test
    void clip이_방송을_아직_모르면_다음_바퀴에_다시_보낸다() {
        publishResult = PublishResult.BROADCAST_NOT_FOUND;

        assertThat(publisher().publish("s1", 7L, WINDOW_START_MS, SPIKE, CLAIMED_AT, Instant::now))
                .isEqualTo(HighlightPublisher.Outcome.RETRY_LATER);
    }

    /**
     * 🔴 <b>「나갔다」는 줄은 실제로 나갔을 때만 찍는다</b>(봇 리뷰 1판, codex).
     *
     * <p>실패에도 찍으면 이벤트 이름으로 세는 쪽이 배달된 하이라이트를 <b>과대 집계</b>하고,
     * 실패 지연이 성공 분포에 섞인다. 실패는 {@code detect.publish_rejected} ·
     * {@code publish_failed} · {@code publish_broadcast_missing} 이 이미 각각 남긴다.
     */
    @Test
    void 못_보낸_카드는_나갔다고_안_적는다() {
        for (PublishResult 실패 : List.of(PublishResult.REJECTED, PublishResult.FAILED,
                PublishResult.BROADCAST_NOT_FOUND)) {
            publishResult = 실패;
            try (LogCaptor captor = new LogCaptor()) {
                publisher().publish("s1", 7L, WINDOW_START_MS, SPIKE, CLAIMED_AT, Instant::now);

                assertThat(captor.messages())
                        .as("%s 인데 발행됐다고 적혔다", 실패)
                        .noneMatch(m -> m.startsWith("detect.card_published"));
            }
        }
    }

    /**
     * 🔴 <b>판정에 쓴 지표의 값을 적는다</b>(봇 리뷰 1판, codex).
     *
     * <p>늘 {@code messageCount} 를 찍으면 {@code metric=CHATTER} 일 때 {@code ratio} 와
     * {@code count} 가 서로 안 맞고, 이 카드가 준비해 둔 <b>MESSAGE 대 CHATTER A/B 분석</b>이
     * 오염된다. 어느 지표로 잰 값인지도 같이 적는다 — 로그 한 줄만 보는 사람은 설정을 모른다.
     */
    @Test
    void 판정에_쓴_지표의_값을_적는다() {
        props = propsWith(Metric.CHATTER);

        try (LogCaptor captor = new LogCaptor()) {
            publisher().publish("s1", 7L, WINDOW_START_MS, SPIKE, CLAIMED_AT, Instant::now);

            assertThat(captor.messages())
                    .filteredOn(m -> m.startsWith("detect.card_published"))
                    .singleElement().satisfies(line -> assertThat(line)
                            // SPIKE 는 messageCount=40 · chatterCount=25 다
                            .contains("count=25").doesNotContain("count=40")
                            .contains("metric=CHATTER"));
        }
    }

    /** 기본 지표에서는 메시지 수를 적는다. 위 검사가 「늘 chatterCount」로 뒤집혀도 잡는다. */
    @Test
    void 기본_지표에서는_메시지_수를_적는다() {
        try (LogCaptor captor = new LogCaptor()) {
            publisher().publish("s1", 7L, WINDOW_START_MS, SPIKE, CLAIMED_AT, Instant::now);

            assertThat(captor.messages())
                    .filteredOn(m -> m.startsWith("detect.card_published"))
                    .singleElement().satisfies(line -> assertThat(line)
                            .contains("count=40").contains("metric=MESSAGE"));
        }
    }
}
