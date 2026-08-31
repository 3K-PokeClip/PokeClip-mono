package com.pokeclip.clip.broadcast.intake;

import ch.qos.logback.classic.Level;
import com.pokeclip.clip.broadcast.BroadcastEventProcessor;
import com.pokeclip.clip.broadcast.LifecycleEnvelope;
import com.pokeclip.clip.broadcast.ProcessResult;
import com.pokeclip.clip.support.IntegrationTestSupport;
import com.pokeclip.clip.support.TestIds;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>「방송 중인 줄의 시작 시각은 항상 있다」가 어디서 오는가</b>를 고정한다(POK-218 태스크 1).
 *
 * <p>「방송 중 목록」 창구와 그 정렬이 이 문장에 기댄다. 틀리면 시각이 빈 줄이 목록에 실려
 * 나가고 수집기는 갈아끼움을 판정하지 못하는데, <b>그 실패는 조용하다</b> — 목록은 정상으로
 * 보이고 채팅만 안 걷힌다.
 *
 * <p><b>지키는 것은 처리기가 아니라 러너의 봉투 검증 한 줄</b>({@code
 * SqsIntakeRunner.firstInvalidField})이고, 이 클래스의 네 시험이 그 사실을 넷으로 나눠 재는
 * 한 벌이다.
 *
 * <ol>
 *   <li>러너를 건너뛰고 처리기에 직접 넣으면 <b>시각이 빈 {@code live} 줄이 실제로 만들어진다</b>
 *       — 처리기에는 방어가 없다</li>
 *   <li>러너를 통과시키면 그 봉투가 <b>버려져</b> 줄이 안 생긴다</li>
 *   <li>같은 러너가 시각이 <b>든</b> 알림은 통과시켜 줄을 만든다 — 대조가 없으면 (2)의
 *       「0행」이 「이 조립으로는 원래 아무 줄도 안 생긴다」와 구분되지 않는다</li>
 *   <li>러너를 건너뛰면 <b>이미 있던 줄의 시작 시각까지 지워진다</b> — (1)은 「안 채운다」이고
 *       이쪽은 「있던 것을 지운다」라 뿌리가 다르다</li>
 * </ol>
 *
 * <p><b>운영 경로로는 시각이 빈 {@code live} 줄이 도달 불가다.</b> 근거는 한 문장이 아니라
 * 사슬 넷이고, 2026-08-31에 그 넷을 기준으로 훑었다 — {@code broadcasts}에 쓰는 운영 코드가
 * {@code BroadcastEventProcessor} 하나(네이티브 {@code INSERT}·{@code UPDATE}는 마이그레이션
 * 말고 없다) · {@code started_at}에 값을 대입하는 자리가 셋(팩토리 둘과 {@code applyStarted}) ·
 * {@code process}의 운영 호출자가 {@code SqsIntakeRunner} 하나 · 그 러너가 {@code occurredAt}이
 * 빈 봉투를 {@code firstInvalidField}에서 버린다.
 *
 * <p>🔴 <b>「없다」가 아니라 「이 기준으로 훑어 못 찾았다」이다.</b> 기준 밖은 못 본다
 * (운영 SQL · 이 표에 쓰는 다른 프로세스). 그리고 넷 중 <b>방어는 마지막 하나뿐</b>이고
 * 앞 셋은 그것이 유일한 통로임을 말할 뿐이다. (1)·(4)가 보이는 것은 「위험이 지금
 * 실재한다」가 아니라 <b>「러너의 검증이 사라지는 날 무엇이 되는가」</b>이다.
 *
 * <p>기존 {@code SqsIntakeRunnerTest.필수_칸이_빠진_봉투는_지우고_어느_칸인지_남긴다}와 겹치지
 * 않는다 — 그쪽은 <b>가짜 처리기</b>({@code envelope -> PROCESSED})를 써서 「러너가 버린다」
 * 까지만 재고, <b>안 버려졌을 때 명부에 무엇이 남는지</b>는 한 번도 안 잰다. 여기가 그 사슬을
 * 잇는 자리다.
 */
class LiveStartedAtNeverNullTest extends IntegrationTestSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 봉투 JSON의 {@code occurredAt}과 <b>같은 값</b>이다. 갈라 두면 두 갈래의 대조가 흐려진다. */
    private static final Instant 시작_시각 = Instant.parse("2026-08-31T00:00:00Z");

    private final BroadcastEventProcessor processor;
    private final JdbcTemplate jdbc;

    LiveStartedAtNeverNullTest(BroadcastEventProcessor processor, JdbcTemplate jdbc) {
        this.processor = processor;
        this.jdbc = jdbc;
    }

    /**
     * 같은 DB를 여러 클래스가 쓴다. 아래 「0행」 단언이 남은 줄에 오염되면 반대로도 뒤집힌다.
     * 지우는 순서(자식 먼저)는 {@code IntegrationTestSupport}가 안다 — 여기서 다시 정하지 않는다.
     */
    @BeforeEach
    void 앞_시험의_흔적을_지운다() {
        방송과_카드를_비운다(jdbc);
    }

    /**
     * 처리기는 시각이 없는 시작 봉투를 <b>거르지 않는다.</b> 그대로 {@code PROCESSED}가 되고
     * {@code status='live'}인데 {@code started_at}이 빈 줄이 남는다.
     *
     * <p>이것이 「방어가 러너에 있다」의 뒷면이다 — 러너의 검증을 지우거나 처리기에 새 호출자가
     * 생기면 이 줄이 운영에 나타난다.
     */
    @Test
    void 러너를_건너뛰고_처리기에_직접_넣으면_시각이_빈_방송_중_줄이_생긴다() {
        ProcessResult result = processor.process(
                startedEnvelopeWithoutOccurredAt("evt-t1-direct", "stream-t1-direct"));

        assertThat(result)
                .as("처리기가 걸렀다면 여기서 예외가 나거나 PROCESSED가 아니어야 한다")
                .isEqualTo(ProcessResult.PROCESSED);

        List<Map<String, Object>> rows = rowsOf("stream-t1-direct");
        assertThat(rows)
                .as("줄이 없으면 아래 두 단언이 자동으로 참이 된다 — 개수를 먼저 못 박는다")
                .hasSize(1);
        assertThat(rows.get(0).get("status")).isEqualTo("live");
        assertThat(rows.get(0).get("started_at"))
                .as("이 줄이 「방송 중인데 시작 시각이 없는」 바로 그 줄이다")
                .isNull();
    }

    /**
     * <b>이 시험이 계약의 근거다.</b> 러너를 통과시키면 시각 없는 시작 알림은 명부에 닿지
     * 못한다.
     *
     * <p>「0행」만 재면 <b>봉투가 다른 이유로 버려져도 통과한다</b>(식별자 오타·파싱 실패).
     * 그래서 사유가 {@code occurredAt}임을 로그로 좁히고, 편지를 <b>지웠는지</b>까지 본다 —
     * 안 지우면 유실은 없지만 FIFO 같은 그룹의 뒤 편지가 영영 막힌다.
     */
    @Test
    void 러너를_통과시키면_시각_없는_시작_알림이_버려져_줄이_안_생긴다() {
        FakeSqsClient sqs = FakeSqsClient.withMessages(
                startedJson("evt-t1-drop", "stream-t1-drop", false));
        SqsIntakeRunner runner = newRunner(sqs);

        try (LogCaptor captor = new LogCaptor()) {
            runner.pollOnce();

            // 🔴 명부 단언이 먼저다. 로그 단언을 앞에 두면 그것이 실패하는 순간 JUnit이 여기서
            // 멈춰 「0행」이 평가조차 안 된다 — 감사자가 실측했다(로그 단언 줄에서 죽어 명부 줄에
            // 도달 못 함). 이 시험의 핵심은 「명부에 줄이 안 생긴다」이고 로그는 사유를 좁히는
            // 곁가지라, 로그 단언을 통째로 지워도 핵심이 살아 있어야 한다.
            assertThat(rowsOf("stream-t1-drop"))
                    .as("이 0행이 「방송 중인 줄의 시작 시각은 항상 있다」의 유일한 근거다")
                    .isEmpty();

            assertThat(captor.messages())
                    .as("사유를 안 좁히면 식별자 오타로 버려져도 이 시험이 초록이 된다")
                    .anyMatch(m -> m.contains("broadcast.intake.incomplete_dropped")
                            && m.contains("field=occurredAt")
                            && m.contains("reason=missing"));
            assertThat(captor.levelOf("broadcast.intake.incomplete_dropped")).isEqualTo(Level.WARN);
        }

        assertThat(sqs.deletedReceiptHandles())
                .as("안 지우면 FIFO 같은 그룹의 뒤 편지가 영영 막힌다")
                .containsExactly("rh-0");
    }

    /**
     * 대조 갈래. <b>러너의 판정을 가르는 칸은 {@code occurredAt} 하나다</b> — 다른 칸도 갈래를
     * 가르면 위 시험의 「0행」이 그 칸 때문이라는 근거가 못 된다.
     *
     * <p>🔴 <b>봉투가 한 칸만 다른 것은 아니다.</b> {@code eventId}·{@code streamId}도 함께
     * 다르다 — 시험마다 자기 줄만 조회해야 하고 {@code event_id}가 UNIQUE라서다. 그 둘은
     * <b>양쪽 다 {@code checkIdentifier}를 통과하는 정상 값</b>이라 러너의 판정을 안 가른다.
     */
    @Test
    void 같은_러너가_시각이_든_시작_알림은_통과시켜_줄을_만든다() {
        FakeSqsClient sqs = FakeSqsClient.withMessages(
                startedJson("evt-t1-keep", "stream-t1-keep", true));

        newRunner(sqs).pollOnce();

        List<Map<String, Object>> rows = rowsOf("stream-t1-keep");
        assertThat(rows)
                .as("여기가 비면 위 시험의 0행은 「이 조립으로는 원래 줄이 안 생긴다」일 뿐이다")
                .hasSize(1);
        assertThat(rows.get(0).get("status")).isEqualTo("live");
        assertThat(rows.get(0).get("started_at")).isNotNull();
        assertThat(sqs.deletedReceiptHandles()).containsExactly("rh-0");
    }

    /**
     * <b>갱신 경로에는 그물이 하나도 없었다</b> — 감사자의 INJ-1(러너의 봉투 검증 삭제)에서
     * 476건 중 빨강이 둘뿐이었다. 이 시험은 그 자리를 <b>고치는 것이 아니라 재는 것</b>이다.
     *
     * <p>{@link com.pokeclip.clip.broadcast.Broadcast#applyStarted}는 시작 시각을 <b>무조건
     * 덮는다.</b> 그래서 시각 없는 시작 봉투가 처리기에 직접 들어오면 이미 값이 있던 줄의
     * 시작 시각이 <b>지워진다</b>. 위 (1)과 결과가 같아 보이지만 뿌리가 다르다 — 저쪽은
     * 「안 채운다」이고 이쪽은 「있던 것을 지운다」다. 러너의 검증이 사라지는 날 이쪽이 더
     * 나쁘다: 정상으로 돌던 방송이 목록에서 정렬 기준을 잃는다.
     *
     * <p><b>이 카드는 {@code applyStarted}를 안 고친다.</b> 덮어쓰기는 뒤늦게 온 시작 알림이
     * placeholder를 채우는 경로와 같은 코드라, 막으려면 「어떤 갱신을 허용하는가」를 먼저
     * 정해야 하고 그것은 새 요구사항이다. 여기서는 <b>현재 동작을 고정</b>해, 누가 고치면
     * 이 시험이 빨간불로 알린다.
     */
    @Test
    void 러너를_건너뛰면_이미_있던_줄의_시작_시각까지_지워진다() {
        processor.process(startedEnvelope("evt-t1-wipe-1", "stream-t1-wipe", 1L, 시작_시각));
        assertThat(rowsOf("stream-t1-wipe").get(0).get("started_at"))
                .as("덮이기 전에 값이 있어야 아래 단언이 「지워졌다」를 잰다 — 없으면 「안 채웠다」와 못 가른다")
                .isNotNull();

        ProcessResult result = processor.process(
                startedEnvelope("evt-t1-wipe-2", "stream-t1-wipe", 2L, null));

        assertThat(result)
                .as("낡은 편지로 걸러졌다면 이 시험은 덮어쓰기를 안 잰다 — 순서 번호를 올려 그 갈래를 뺐다")
                .isEqualTo(ProcessResult.PROCESSED);

        List<Map<String, Object>> rows = rowsOf("stream-t1-wipe");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("status")).isEqualTo("live");
        assertThat(rows.get(0).get("started_at"))
                .as("갱신 경로도 안 막는다 — 이 줄이 빨간불이면 누가 applyStarted를 고친 것이다")
                .isNull();
    }

    private List<Map<String, Object>> rowsOf(String streamId) {
        return jdbc.queryForList(
                "SELECT status, started_at FROM broadcasts WHERE stream_id = ?", streamId);
    }

    /** 운영과 같은 조립이되 큐만 가짜다. 처리기는 <b>진짜 빈</b>이라 트랜잭션 프록시를 그대로 탄다. */
    private SqsIntakeRunner newRunner(FakeSqsClient sqs) {
        return new SqsIntakeRunner(sqs, properties(), new IntakeStatus(true), processor,
                new ObjectMapper());
    }

    private static IntakeProperties properties() {
        // 큐 주소는 안 쓰인다 — 가짜 클라이언트가 요청을 무시하고 자기 목록을 준다.
        return new IntakeProperties(true, "http://localhost:4566/000000000000/q.fifo",
                "ap-northeast-2", null, Duration.ofSeconds(20), 10);
    }

    /**
     * 두 갈래를 <b>한 자리에서</b> 만든다. 텍스트를 따로 두면 러너의 판정에 걸리는 칸이
     * 조용히 갈라져 대조가 무의미해진다.
     *
     * <p>{@code eventId}·{@code streamId}는 부르는 쪽이 다르게 넘긴다 — 둘 다 정상 값이라
     * 판정을 안 가른다. 갈래를 가르는 것은 {@code withOccurredAt} 하나다.
     */
    private static String startedJson(String eventId, String streamId, boolean withOccurredAt) {
        String occurredAt = withOccurredAt ? "\"occurredAt\":\"" + 시작_시각 + "\"," : "";
        return """
                {"schemaVersion":1,"eventId":"%s","eventType":"broadcast.started",
                 %s"streamId":"%s","streamerId":"%s",
                 "sequence":1,"traceId":"trace-1","payload":{}}
                """.formatted(eventId, occurredAt, streamId, TestIds.STREAMER);
    }

    /** {@code occurredAt}이 {@code null}인 갈래를 <b>여기 한 자리에서만</b> 만든다. */
    private static LifecycleEnvelope startedEnvelopeWithoutOccurredAt(String eventId, String streamId) {
        return startedEnvelope(eventId, streamId, 1L, null);
    }

    private static LifecycleEnvelope startedEnvelope(String eventId, String streamId,
                                                     long sequence, Instant occurredAt) {
        return new LifecycleEnvelope(1, eventId, "broadcast.started", occurredAt, streamId,
                TestIds.STREAMER, sequence, "trace-1", MAPPER.createObjectNode());
    }
}
