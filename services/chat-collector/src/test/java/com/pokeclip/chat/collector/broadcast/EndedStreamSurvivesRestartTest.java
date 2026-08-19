package com.pokeclip.chat.collector.broadcast;

import com.pokeclip.chat.collector.CollectorApplication;
import com.pokeclip.chat.collector.session.SessionRegistry;
import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>끝난 방송 메모가 프로세스 재시작을 건너 살아남는가.</b>
 *
 * <p><b>이 검사가 없으면 「메모를 DB에 둔다」는 결정이 어디에서도 검증되지 않는다.</b>
 * 메모를 {@code EndedStreamStore} 안의 맵에 뒀어도 이 모듈의 다른 검사는 전부 초록이다 —
 * 그것들은 컨텍스트를 하나만 쓰고, 컨텍스트가 하나면 「프로세스 안에 있는 것」과
 * 「DB에 있는 것」이 구분되지 않는다.
 *
 * <p><b>계획의 경로 3이 정확히 이 상황이다</b>: 재배포로 프로세스가 내려가면 메모리는 비고,
 * 새 프로세스가 뜨자마자 큐에 쌓여 있던 종료·시작 편지를 한꺼번에 처리한다. 가장 흔한
 * 재시작 경로에서 메모리 구현은 <b>끝난 방송에 다시 붙는다</b> — 계정별 상한 3개 중 한 자리를
 * 먹고, 그 방송을 닫을 종료 편지는 이미 소비돼 큐에 없다.
 *
 * <p><b>컨텍스트를 두 번 띄운다.</b> {@code @SpringBootTest}는 컨텍스트를 하나만 주고
 * 캐시까지 하므로 재시작을 못 만든다. {@code SpringApplicationBuilder}로 직접 띄우면 매번
 * 새 컨텍스트라 <b>빈도 전부 새로 만들어진다</b> — 그것이 여기서 재현하려는 「프로세스가
 * 내려갔다 다시 떴다」이다. 컨테이너는 {@link IntegrationTestSupport}가 static 블록에서
 * 한 번만 띄우므로 두 부팅이 <b>같은 DB</b>를 본다(그것이 운영의 「DB는 그대로, 프로세스만
 * 내려갔다」와 같은 모양이다).
 *
 * <p><b>다중 세션 문항(multi-session-test-reality)의 답</b>
 * <ul>
 *   <li><b>문항 1</b>(하나로 줄여도 통과하는가): 컨텍스트 하나 안에서 종료→시작을 이어
 *       처리하면 <b>메모리 구현으로도 초록이다</b>(확인함 — 문항 5와 같은 주입 상태에서 한
 *       컨텍스트 판을 돌려 초록, 두 컨텍스트 판은 같은 실행에서 빨간불). 그래서 둘을 연다.
 *   <li><b>문항 2</b>(자동으로 참이 되는 입력): 앞선 검사가 같은 방송 번호의 메모를 남겨 두면
 *       {@code first}가 아무 일도 안 해도 아래가 통과한다. 그래서 ① 이 검사 전용 번호를 쓰고
 *       ② 검사 <b>앞</b>에서 그 줄을 지우고(뒤에는 안 둔다 — 맨 아래 주석이 이유다)
 *       ③ {@code first}에서 <b>없음</b>을 먼저 단언한다. 결함 주입에서 {@code second}가
 *       빈손이 돼 빨간불이 났으므로, 이 검사가 {@code first}의 일에 실제로 의존한다는 것도
 *       같이 확인됐다.
 *   <li><b>문항 3</b>(의도한 동시성이 막히는가): 겹치는 것이 없다 — 두 부팅은 순차이고
 *       앞 컨텍스트는 닫힌 뒤다. <b>잴 동시성이 없다.</b>
 *   <li><b>문항 4</b>(단언을 통과시키는 잘못된 결과): {@code activeCount()}가 0인 것은
 *       <b>갓 뜬 컨텍스트에서 자동으로 참이다</b> — 메모리 구현이어도 여기 auth가 죽은
 *       주소라 세션이 안 열려 0이다. 즉 그 줄은 이 결함을 못 잡는다. 잡는 것은
 *       {@code IGNORED_STALE}이고, 그 값은 {@code handleStarted}가 메모를 찾았을 때만 나온다.
 *       {@code activeCount()}는 「무시했다면서 세션은 열었다」를 막는 보조로만 둔다.
 *   <li><b>문항 5</b>(그 결함에서 빨간불이 되는가): {@link EndedStreamStore}를 인스턴스
 *       {@code Map}으로 잠깐 바꾸니 <b>빨간불이 됐다(확인함)</b>. 멈춘 자리는 위쪽
 *       {@code find(...).orElseThrow()}이고({@code NoSuchElementException}), 그 줄을 빼고
 *       판정만 남겨도 <b>{@code RETRY_LATER}</b>로 빨간불이었다(따로 확인함 — 메모를 못 찾은
 *       판정기가 죽은 auth 주소로 열쇠를 물으러 갔다). 즉 두 단언이 각각 잡는다.
 * </ul>
 */
class EndedStreamSurvivesRestartTest extends IntegrationTestSupport {

    /**
     * 이 검사 전용 방송 번호다. 다른 검사와 겹치면 그쪽이 남긴 메모로 <b>{@code first}가
     * 아무 일도 안 해도 초록</b>이 된다(문항 2).
     */
    private static final String STREAM_ID = "restart-s1";

    private static final Instant 종료시각 = Instant.parse("2026-08-18T10:00:00Z");

    @Test
    void 서버를_껐다_켜도_끝난_방송_메모가_남아_있다() {
        try (ConfigurableApplicationContext first = bootWithSharedDatabase()) {
            // 지난 실행이 남긴 같은 번호의 메모를 여기서 지운다(문항 2). <b>컨텍스트 밖에서는
            // 못 지운다</b> — 표를 만드는 것은 Flyway이고 Flyway는 컨텍스트가 떠야 돈다.
            // 이 검사만 단독으로 돌리면 @BeforeEach 시점에 표 자체가 없다(실측:
            // relation "chat_ended_streams" does not exist).
            //
            // 표 전체를 지우지 않는다 — 남의 검사가 밀어 넣은 줄(EndedStreamStoreTest는
            // created_at을 과거로 민 줄을 쓴다)을 이 검사가 치우면 안 된다.
            first.getBean(JdbcTemplate.class)
                    .update("DELETE FROM chat_ended_streams WHERE stream_id = ?", STREAM_ID);
            assertThat(first.getBean(EndedStreamStore.class).find(STREAM_ID)).isEmpty();
            assertThat(first.getBean(BroadcastEventProcessor.class).process(ended(5)))
                    .isEqualTo(ProcessResult.PROCESSED);
        }
        // 여기서 프로세스가 내려갔다. 아래는 새로 뜬 프로세스다 — 빈도 메모리도 전부 새것이다.
        try (ConfigurableApplicationContext second = bootWithSharedDatabase()) {
            // 「메모가 남아 있는가」와 「판정이 그 메모를 쓰는가」를 따로 본다. 앞이 빨간불이면
            // 저장이, 뒤가 빨간불이면 판정이 문제다.
            assertThat(second.getBean(EndedStreamStore.class).find(STREAM_ID).orElseThrow().lastSequence())
                    .isEqualTo(5L);

            BroadcastEventProcessor processor = second.getBean(BroadcastEventProcessor.class);
            assertThat(processor.process(started(3))).isEqualTo(ProcessResult.IGNORED_STALE);
            assertThat(second.getBean(SessionRegistry.class).activeCount()).isZero();
        }
    }

    // 뒷정리를 여기 두지 않은 이유: 단언이 실패하면 뒷정리가 안 도는데, 그 남은 줄이
    // 다음 실행의 first를 통과시키면 <b>실패가 초록으로 덮인다</b>. 그래서 정리를 검사
    // 앞(위 DELETE)에 두고, 뒤에는 안 둔다. 남은 줄은 이 검사 전용 번호라 남에게 안 걸린다.

    /**
     * 운영과 같은 조립으로 띄운다 — 편지 경로를 켜야 {@link BroadcastEventProcessor} 빈이
     * 만들어진다({@code LetterPathConfiguration}이 그 스위치에 걸려 있다).
     *
     * <p><b>바깥 셋은 전부 죽은 주소다.</b> 큐는 이 검사가 편지를 손으로 넣으므로 폴링이
     * 실패해도 상관없고, auth는 <b>불리면 안 되는 자리</b>다 — 끝난 방송이면 열쇠를 물으러
     * 가지 않는다. 살아 있는 auth를 두면 메모리 결함을 주입했을 때 세션이 진짜로 열려
     * 「무엇이 빨간불을 만들었나」가 흐려진다.
     */
    private static ConfigurableApplicationContext bootWithSharedDatabase() {
        return new SpringApplicationBuilder(CollectorApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("test")
                .run("--pokeclip.broadcast.intake.enabled=true",
                        "--pokeclip.broadcast.intake.queue-url=http://localhost:1/000000000000/broadcast.fifo",
                        "--pokeclip.link.auth-base-url=http://localhost:1",
                        "--pokeclip.link.internal-token=restart-test-internal-token",
                        // 따로 띄우는 컨텍스트라 @DynamicPropertySource가 안 걸린다. 안 넘기면
                        // localhost:5432로 가서 로컬 PG를 건드린다(SessionShutdownTest와 같은 이유).
                        "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                        "--spring.datasource.username=" + POSTGRES.getUsername(),
                        "--spring.datasource.password=" + POSTGRES.getPassword());
    }

    private static LifecycleEnvelope ended(long sequence) {
        return envelope("broadcast.ended", sequence);
    }

    private static LifecycleEnvelope started(long sequence) {
        return envelope("broadcast.started", sequence);
    }

    private static LifecycleEnvelope envelope(String eventType, long sequence) {
        return new LifecycleEnvelope(1, "evt-" + eventType + "-" + STREAM_ID + "-" + sequence,
                eventType, 종료시각, STREAM_ID, "42", sequence, "trace-restart", null);
    }
}
