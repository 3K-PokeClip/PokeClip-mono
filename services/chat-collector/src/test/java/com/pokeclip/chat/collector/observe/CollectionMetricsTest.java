package com.pokeclip.chat.collector.observe;

import com.pokeclip.chat.collector.chzzk.ChatMessage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CollectionMetricsTest {

    @Test
    void 수신_건수와_최대_공백을_센다() {
        CollectionMetrics metrics = new CollectionMetrics();

        metrics.recordMessage(chat(1_000L), 10_000L);
        metrics.recordMessage(chat(2_000L), 10_500L);
        metrics.recordMessage(chat(3_000L), 14_000L);

        CollectionMetrics.Snapshot s = metrics.snapshot();
        assertThat(s.received()).isEqualTo(3);
        assertThat(s.maxReceiveGap()).isEqualTo(Duration.ofMillis(3_500));
    }

    /** 관측값이지 실패 조건이 아니다. 채팅은 원래 어긋나서 온다. */
    @Test
    void messageTime_순서_위반을_세되_실패로_보지_않는다() {
        CollectionMetrics metrics = new CollectionMetrics();

        metrics.recordMessage(chat(5_000L), 10_000L);
        metrics.recordMessage(chat(4_000L), 10_100L);   // 역전
        metrics.recordMessage(chat(6_000L), 10_200L);

        assertThat(metrics.snapshot().orderViolations()).isEqualTo(1);
    }

    /**
     * 위 테스트만 있으면 "항상 1을 돌려주는" 구현도 통과한다.
     * 순서가 멀쩡할 때 0인지 봐야 그 값이 무언가를 세고 있다는 뜻이 된다.
     */
    @Test
    void 순서가_멀쩡하면_위반이_0이다() {
        CollectionMetrics metrics = new CollectionMetrics();

        metrics.recordMessage(chat(1_000L), 10_000L);
        metrics.recordMessage(chat(2_000L), 10_100L);
        metrics.recordMessage(chat(3_000L), 10_200L);

        assertThat(metrics.snapshot().orderViolations()).isZero();
    }

    /**
     * 팀 사양서 TBD 첫 줄「이벤트 전달 지연 실측」을 메우는 값이다.
     *
     * <p><b>이 값 하나를 POK-92의 시차 보정 오프셋으로 쓰지 마라.</b>
     * 오프셋은 아래와 같고 이 값은 그중 <b>한 항</b>일 뿐이다.
     *
     * <pre>
     * 오프셋 = (치지직 방송 지연 + 시청자 반응 지연 + 전달 지연) − 우리 인제스트 지연
     *           미측정·초 단위      미측정·초 단위      이 값       인코딩+SRT 버퍼
     * </pre>
     *
     * <p>앞의 둘이 초 단위인데 이 값은 0.175초라 이것만으로 잡으면 자릿수가
     * 어긋난다. 정본은 {@code services/README.md}와
     * {@code PokeClip-LLM-WIKI/adr/ADR-034}다.
     */
    @Test
    void 전달_지연의_최소_중앙_최대를_낸다() {
        CollectionMetrics metrics = new CollectionMetrics();

        metrics.recordMessage(chat(1_000L), 1_100L);    // 100ms
        metrics.recordMessage(chat(2_000L), 2_500L);    // 500ms
        metrics.recordMessage(chat(3_000L), 3_300L);    // 300ms

        CollectionMetrics.Snapshot s = metrics.snapshot();
        assertThat(s.delayMin()).isEqualTo(Duration.ofMillis(100));
        assertThat(s.delayMedian()).isEqualTo(Duration.ofMillis(300));
        assertThat(s.delayMax()).isEqualTo(Duration.ofMillis(500));
    }

    /** 창마다 비운다. 안 비우면 10분 뒤 표본이 무한정 쌓인다. */
    @Test
    void 스냅샷을_찍으면_창이_비워진다() {
        CollectionMetrics metrics = new CollectionMetrics();
        metrics.recordMessage(chat(1_000L), 1_100L);

        assertThat(metrics.snapshot().received()).isEqualTo(1);
        assertThat(metrics.snapshot().received()).isZero();
    }

    /** 누적은 따로 센다. 최종 판정 라인이 이걸 쓴다. */
    @Test
    void 누적_건수는_창을_비워도_남는다() {
        CollectionMetrics metrics = new CollectionMetrics();
        metrics.recordMessage(chat(1_000L), 1_100L);
        metrics.snapshot();
        metrics.recordMessage(chat(2_000L), 2_100L);
        metrics.snapshot();

        assertThat(metrics.totalReceived()).isEqualTo(2);
    }

    /**
     * 마지막 수신 시각은 창을 비워도 남아야 한다. 창이 비었다고 이 값이 사라지면
     * "30초째 한 건도 못 받았다"를 요약이 말할 수 없게 된다 — 그게 정확히
     * 알아야 하는 상태다.
     */
    @Test
    void 마지막_수신_시각은_빈_창에서도_남는다() {
        CollectionMetrics metrics = new CollectionMetrics();
        metrics.recordMessage(chat(1_000L), 1_100L);
        metrics.snapshot();

        CollectionMetrics.Snapshot empty = metrics.snapshot();
        assertThat(empty.received()).isZero();
        assertThat(empty.lastReceivedAt()).isEqualTo(java.time.Instant.ofEpochMilli(1_100L));
    }

    /**
     * SYSTEM은 방송 내내 네 건뿐이라 창마다 비우면 대부분의 요약 줄에서 사라진다.
     * revoked가 왔다는 사실은 그 뒤 모든 줄에 남아 있어야 한다.
     */
    @Test
    void 시스템_이벤트는_종류별로_세고_창을_비워도_남는다() {
        CollectionMetrics metrics = new CollectionMetrics();
        metrics.recordSystemEvent("connected");
        metrics.recordSystemEvent("subscribed");
        metrics.snapshot();

        assertThat(metrics.snapshot().systemEvents())
                .containsEntry("connected", 1L)
                .containsEntry("subscribed", 1L);
    }

    /** 디코딩 실패는 창 단위다. 어느 30초에 몰렸는지가 보여야 한다. */
    @Test
    void 디코딩_실패는_창마다_센다() {
        CollectionMetrics metrics = new CollectionMetrics();
        metrics.recordDecodeFailure();
        metrics.recordDecodeFailure();

        assertThat(metrics.snapshot().decodeFailures()).isEqualTo(2);
        assertThat(metrics.snapshot().decodeFailures()).isZero();
    }

    /**
     * 세션 지표는 세션마다 0에서 다시 시작한다({@code Heartbeat}는 소켓마다 새로
     * 만들어지고, 삼킨 프레임 수는 {@code ChatSession}이 든다). 걷어 올리지 않으면
     * 판정 줄이 <b>누계와 마지막 세션 값을 섞어 싣는다.</b>
     */
    @Test
    void 세션이_끝날_때_걷은_세션_값이_판정에_남는다() {
        CollectionMetrics metrics = new CollectionMetrics();

        // 인자 여섯이다 —
        // (collectedFor, maxPingGap, maxPongGap, sendFailures, callbackFailures, sinkFailures)
        metrics.recordSessionEnd(Duration.ofSeconds(60), Duration.ofSeconds(38), Duration.ofSeconds(9), 2, 0, 5);
        metrics.recordSessionEnd(Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(3), 0, 1, 0);

        CollectionMetrics.Verdict v = metrics.verdict();
        assertThat(v.totalCollectedFor())
                .as("이것만 마지막 세션 값으로 두면 received·maxPingGap과 경계가 어긋난다")
                .isEqualTo(Duration.ofSeconds(90));
        assertThat(v.maxPingGap())
                .as("마지막 세션 값만 남으면 앞 세션에서 ping이 막힌 것을 판정이 못 본다 "
                        + "— POK-85가 정한 실패 조건이 그대로 무력해진다")
                .isEqualTo(Duration.ofSeconds(38));
        assertThat(v.maxPongGap()).isEqualTo(Duration.ofSeconds(9));
        assertThat(v.sendFailures()).isEqualTo(2);
        assertThat(v.callbackFailures()).isEqualTo(1);
        assertThat(v.sinkFailures())
                .as("마지막 세션 값으로 두면 앞 세션이 삼킨 프레임이 판정에서 사라진다 "
                        + "— 판정이 프로세스 종료 1회로 옮겨지면 '이 프로세스에서 삼킨 프레임 0건'으로 읽힌다")
                .isEqualTo(5);
    }

    /**
     * 세션이 하나도 안 끝났을 때(수립조차 못 했을 때) 0이어야 한다.
     * 위 테스트만 있으면 "무엇을 넣어도 큰 값을 돌려주는" 구현도 통과한다.
     */
    @Test
    void 걷은_세션이_없으면_세션_항이_0이다() {
        CollectionMetrics.Verdict v = new CollectionMetrics().verdict();

        assertThat(v.totalCollectedFor()).isEqualTo(Duration.ZERO);
        assertThat(v.maxPingGap()).isEqualTo(Duration.ZERO);
        assertThat(v.maxPongGap()).isEqualTo(Duration.ZERO);
        assertThat(v.sendFailures()).isZero();
        assertThat(v.callbackFailures()).isZero();
        assertThat(v.sinkFailures()).isZero();
        assertThat(v.reconnects()).isZero();
        assertThat(v.totalOutage()).isEqualTo(Duration.ZERO);
        // 0을 찍으면 1970년으로 읽힌다. 한 번도 안 끊겼다는 것은 시각이 없다는 뜻이다.
        assertThat(v.lastOutageFrom()).isNull();
        assertThat(v.lastOutageTo()).isNull();
    }

    /**
     * <b>끊겨 있던 시간은 수신 공백이 아니다.</b> 손대지 않으면 절단 구간이 통째로
     * 하나의 {@code maxReceiveGap}으로 남아, "한산했을 뿐"과 "끊겨 있었다"가
     * 판정 줄에서 같은 숫자로 보인다.
     *
     * <p>여기 담긴 순서는 <b>절단 기록이 새 세션의 첫 채팅보다 먼저</b>인 쪽이다.
     * 반대 순서는 아래 테스트가 본다. 둘 다 같은 값이 나와야 한다 —
     * 순서에 따라 갈리면 그것이 곧 경합이다.
     */
    @Test
    void 절단_구간은_수신_공백에_안_섞인다() {
        CollectionMetrics metrics = new CollectionMetrics();
        metrics.recordMessage(chat(1_000L), 1_000L);

        // 30초 끊겨 있었다. 시각 둘을 넘긴다 — 누적 시간만으로는 "언제 놓쳤나"를 못 찾는다.
        Instant from = Instant.ofEpochMilli(1_100L);
        metrics.beginSession();
        metrics.recordOutage(from, from.plusSeconds(30));
        metrics.recordMessage(chat(31_100L), 31_100L);

        CollectionMetrics.Verdict v = metrics.verdict();
        assertThat(v.maxReceiveGap())
                .as("절단을 수신 공백에 섞으면 '한산했다'와 '끊겨 있었다'가 같아 보인다")
                .isLessThan(Duration.ofSeconds(1));
        assertThat(v.reconnects()).isEqualTo(1);
        assertThat(v.totalOutage()).isEqualTo(Duration.ofSeconds(30));

        // <b>양성 대조.</b> 위 단언은 부정형이라 "무엇을 막았나"를 스스로 말하지
        // 못한다. 손대지 않았을 때의 값을 같은 입력으로 같이 못박는다 — 30.1초가
        // 그대로 공백으로 남고, 그것이 위에서 사라진 바로 그 숫자다.
        CollectionMetrics untouched = new CollectionMetrics();
        untouched.recordMessage(chat(1_000L), 1_000L);
        untouched.recordMessage(chat(31_100L), 31_100L);
        assertThat(untouched.verdict().maxReceiveGap())
                .as("대조가 0이면 위 단언은 애초에 공백이 없던 입력을 본 것이다")
                .isEqualTo(Duration.ofMillis(30_100));
    }

    /**
     * <b>새 세션의 첫 채팅이 {@code recordOutage}보다 먼저 오는 순서.</b> 위 테스트만
     * 있으면 운 좋은 순서 하나만 보는 것이다.
     *
     * <p>실제로 이 순서가 이긴다. 프레임 싱크는 세션이 서자마자 살아 있고, 그
     * 뒤로 재연결 스레드는 하트비트·요약 스레드를 만들고 상태를 옮긴 다음에야
     * {@code recordOutage}에 닿는다. 그 사이에 도착한 첫 채팅이 <b>앞 세션의 마지막
     * 수신과 짝지어지면 절단 구간 전체가 수신 공백으로 찍힌다</b> —
     * {@code maxReceiveGap}은 최댓값 누계라 한 번 찍히면 프로세스 생애 내내 안 내려온다.
     *
     * <p>그래서 수신 시계를 다시 잡는 자리가 {@code recordOutage}가 아니라
     * <b>세션이 서는 시점</b>({@link CollectionMetrics#beginSession()})이어야 한다.
     * 거기서 잡으면 첫 채팅이 언제 오든 앞 세션과 짝지어질 수 없다.
     */
    @Test
    void 새_세션의_채팅이_절단_기록보다_먼저_와도_공백에_안_섞인다() {
        CollectionMetrics metrics = new CollectionMetrics();
        metrics.recordMessage(chat(1_000L), 1_000L);        // 앞 세션의 마지막 수신

        metrics.beginSession();                              // 새 세션이 섰다
        metrics.recordMessage(chat(31_100L), 31_100L);       // 재연결 직후 첫 채팅 — 먼저 도착
        Instant from = Instant.ofEpochMilli(1_100L);
        metrics.recordOutage(from, from.plusSeconds(30));    // 재연결 스레드가 뒤늦게 닫는다

        CollectionMetrics.Verdict v = metrics.verdict();
        assertThat(v.maxReceiveGap())
                .as("채팅이 먼저 오는 순서에서 새면 '한산했을 뿐'과 '끊겨 있었다'가 같아 보인다")
                .isEqualTo(Duration.ZERO);
        assertThat(v.reconnects()).isEqualTo(1);
        assertThat(v.totalOutage()).isEqualTo(Duration.ofSeconds(30));

        // <b>양성 대조.</b> 위는 부정형이라 "무엇을 막았나"를 스스로 말하지 못한다.
        // 세션 시작을 안 알린 같은 입력이 얼마를 찍는지 못박는다 — 30.1초가 그대로
        // 공백에 남고, 그것이 위에서 사라진 바로 그 숫자다.
        CollectionMetrics untouched = new CollectionMetrics();
        untouched.recordMessage(chat(1_000L), 1_000L);
        untouched.recordMessage(chat(31_100L), 31_100L);
        untouched.recordOutage(from, from.plusSeconds(30));
        assertThat(untouched.verdict().maxReceiveGap())
                .as("대조가 0이면 위 단언은 애초에 공백이 없던 입력을 본 것이다")
                .isEqualTo(Duration.ofMillis(30_100));
    }

    /**
     * 절단은 한 번으로 안 끝난다. 시간은 쌓고 시각은 마지막 것을 남긴다 —
     * 누적 시간만 있으면 "얼마나"는 알아도 <b>"언제"를 못 찾아</b> 영상과 대조할 수 없다.
     */
    @Test
    void 절단이_여러_번이면_시간은_쌓이고_시각은_마지막_것이_남는다() {
        CollectionMetrics metrics = new CollectionMetrics();

        Instant first = Instant.ofEpochMilli(1_000L);
        metrics.recordOutage(first, first.plusSeconds(5));
        Instant second = Instant.ofEpochMilli(60_000L);
        metrics.recordOutage(second, second.plusSeconds(12));

        CollectionMetrics.Verdict v = metrics.verdict();
        assertThat(v.reconnects()).isEqualTo(2);
        assertThat(v.totalOutage())
                .as("마지막 절단만 남기면 앞에서 놓친 시간이 판정에서 사라진다")
                .isEqualTo(Duration.ofSeconds(17));
        assertThat(v.lastOutageFrom()).isEqualTo(second);
        assertThat(v.lastOutageTo()).isEqualTo(second.plusSeconds(12));
    }

    private static ChatMessage chat(long messageTime) {
        return new ChatMessage("CH1", "S1", "내용", messageTime);
    }
}
