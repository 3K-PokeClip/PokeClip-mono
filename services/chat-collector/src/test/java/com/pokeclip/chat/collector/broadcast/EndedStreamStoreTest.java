package com.pokeclip.chat.collector.broadcast;

import com.pokeclip.chat.collector.support.IntegrationTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 끝난 방송 메모가 실제 PostgreSQL에서 어떻게 접히는지를 잰다.
 *
 * <p><b>다중 세션 문항(multi-session-test-reality)</b> — 이 표에는 세션도 스레드도 없다.
 * 문항 1(세션 하나로 돌려도 통과하는가)·문항 3(의도한 동시성이 환경에 막히는가)은
 * <b>잴 대상이 없어</b> 해당하지 않는다. 재 보지 않은 것이 아니다.
 * 문항 2·4·5는 검사마다 주석으로 답을 남겼다.
 *
 * <p>검사마다 표를 비운다 — 컨테이너가 JVM에 하나뿐이라(IntegrationTestSupport)
 * 앞 검사가 남긴 줄이 치우기 건수 단언을 조용히 어긋나게 한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class EndedStreamStoreTest extends IntegrationTestSupport {

    private static final Instant 아주_예전 = Instant.parse("2026-01-01T00:00:00Z");

    private final EndedStreamStore store;
    private final JdbcTemplate jdbc;

    EndedStreamStoreTest(EndedStreamStore store, JdbcTemplate jdbc) {
        this.store = store;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void 표를_비운다() {
        jdbc.update("DELETE FROM chat_ended_streams");
    }

    private static final Instant 지금 = Instant.parse("2026-08-22T12:00:00Z");

    // 문항 4: 번호만 단언하면 stop_reason을 안 쓰는 구현도 초록이다 — 세 칸 다 본다.
    @Test
    void 포기하면_사유와_남긴_시각이_남고_번호는_0이다() {
        assertThat(store.rememberStopped("s1", "SESSION_AUTH_REJECTED", 지금)).isTrue();
        EndedStream memo = store.find("s1").orElseThrow();
        assertThat(memo.stopReason()).isEqualTo("SESSION_AUTH_REJECTED");
        assertThat(memo.createdAt()).isEqualTo(지금);
        assertThat(memo.lastSequence()).isZero();
    }

    // 문항 5: DO NOTHING을 DO UPDATE로 바꾸면 번호가 0으로 내려가 빨간불이다 — 역순 방어가 풀리는 모양.
    @Test
    void 종료_메모가_있는_방송을_포기해도_번호가_내려가지_않는다() {
        store.remember("s1", 7, Instant.parse("2026-08-22T11:00:00Z"));
        assertThat(store.rememberStopped("s1", "REVOKED", 지금)).isFalse();
        EndedStream memo = store.find("s1").orElseThrow();
        assertThat(memo.lastSequence()).isEqualTo(7L);
        assertThat(memo.stopReason()).as("종료 메모가 이긴다 — 끝난 방송은 끝난 것이다").isNull();
    }

    @Test
    void 포기_메모_위에_진짜_종료가_오면_끝난_것으로_바뀐다() {
        store.rememberStopped("s1", "REVOKED", 지금);
        assertThat(store.remember("s1", 3, Instant.parse("2026-08-22T12:30:00Z"))).isTrue();
        EndedStream memo = store.find("s1").orElseThrow();
        assertThat(memo.lastSequence()).isEqualTo(3L);
        assertThat(memo.stopReason()).as("포기했다가 방송이 끝났다 — ended다").isNull();
    }

    /**
     * 문항 2: {@code find("s1")}이 비었다는 것만 보면 <b>기준을 무시하고 전부 지우는 구현</b>도
     * 초록이다 — 방금 남긴 포기 메모를 한 줄 더 두고 그것이 <b>남아 있는지</b>를 같이 본다(양성 대조).
     * 옆의 {@code 하루_지난_메모만_지운다}는 두 줄이 <b>둘 다 정상 종료 메모</b>라
     * 「포기 메모가 안 지워져야 할 때 안 지워지는가」는 여기서만 잰다(critic A5).
     * <p>문항 5: {@code sweepOlderThan}의 {@code WHERE created_at < ?}를 지우면
     * 건수 2·{@code find("s2")} 빔으로 두 줄 다 빨간불(확인함).
     */
    @Test
    void 치우기가_포기_메모도_지운다() {
        store.rememberStopped("s1", "REVOKED", 아주_예전);
        store.rememberStopped("s2", "REVOKED", 지금);   // 기준보다 한참 뒤에 남긴 포기 메모
        assertThat(store.sweepOlderThan(아주_예전.plus(Duration.ofDays(1)))).isEqualTo(1);
        assertThat(store.find("s1")).isEmpty();
        assertThat(store.find("s2")).as("기준 안쪽의 포기 메모까지 쓸어 가면 산 방송이 unknown이 된다").isPresent();
    }

    /**
     * 종료 편지(폴링 스레드)와 포기(세션 스레드)가 같은 방송에 동시에 올 수 있다(PRD 가정).
     * 어느 순서로 겹쳐도 번호는 절대 내려가지 않아야 한다. 200회 반복 — 한 번이라도 0이면 빨강.
     * 문항 1: 한 스레드로 순서대로 돌리면 언제나 통과한다 — 그래서 실제로 겹친다.
     * 문항 3: <b>양쪽 순서가 다 나온다(어느 쪽도 0이 아니다)</b> — 그것만이 이 검사가 책임지는 사실이다.
     *         반환값으로 판별한다: 종료 먼저 = remember true·rememberStopped false / 포기 먼저 = 둘 다 true.
     *         <b>비율은 실행마다 크게 흔들리므로 특정 숫자를 기대하지 마라</b> — 200회 기준 관측 범위가
     *         종료 먼저 66~108 · 포기 먼저 92~134다(구현 96/104 · 계획 검증 94/106 · critic 독립 재현 66/134·108/92).
     *         순서별 결과는 위 두 검사가 따로 잰다.
     */
    @Test
    void 종료와_포기가_동시에_와도_번호는_내려가지_않는다() throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 200; i++) {
                String streamId = "race-" + i;
                java.util.concurrent.CyclicBarrier gate = new java.util.concurrent.CyclicBarrier(2);
                var ended = pool.submit(() -> { gate.await(); return store.remember(streamId, 5, 지금); });
                var stopped = pool.submit(() -> { gate.await(); return store.rememberStopped(streamId, "REVOKED", 지금); });
                ended.get(); stopped.get();
                assertThat(store.find(streamId).orElseThrow().lastSequence())
                        .as("%s: 포기가 종료 뒤에 와도 0으로 덮으면 안 된다", streamId)
                        .isEqualTo(5L);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // 문항 4: 지시서 단언(번호만)은 ended_at을 안 갱신하는 구현에도 초록이다 — 칸을 더 본다.
    // 문항 5: ON CONFLICT DO UPDATE를 DO NOTHING으로 되돌리면 빨간불(확인함).
    @Test
    void 같은_방송을_두_번_기억하면_더_높은_번호가_남는다() {
        store.remember("s1", 5, Instant.parse("2026-08-18T10:00:00Z"));
        store.remember("s1", 9, Instant.parse("2026-08-18T10:05:00Z"));
        assertThat(store.find("s1").orElseThrow().lastSequence()).isEqualTo(9L);
        // 번호만 옮기고 종료 시각을 두고 오면 메모가 두 편지의 조각으로 섞인다.
        assertThat(store.find("s1").orElseThrow().endedAt())
                .isEqualTo(Instant.parse("2026-08-18T10:05:00Z"));
    }

    // 문항 4: 번호만 지키고 ended_at은 덮어쓰는 구현이 지시서 단언을 통과한다 — 그 칸도 본다.
    // 문항 5: WHERE EXCLUDED.last_sequence > … 를 지우면 두 단언 다 빨간불(확인함).
    @Test
    void 낮은_번호로_다시_기억해도_높은_번호가_유지된다() {
        store.remember("s1", 9, Instant.parse("2026-08-18T10:05:00Z"));
        store.remember("s1", 5, Instant.parse("2026-08-18T10:00:00Z"));
        assertThat(store.find("s1").orElseThrow().lastSequence()).isEqualTo(9L);
        assertThat(store.find("s1").orElseThrow().endedAt())
                .isEqualTo(Instant.parse("2026-08-18T10:05:00Z"));
    }

    @Test
    void 하루_지난_메모만_지운다() {
        store.remember("old", 1, Instant.parse("2026-08-17T09:00:00Z"));
        store.remember("new", 1, Instant.parse("2026-08-18T09:00:00Z"));
        // createdAt을 직접 밀어 넣어 시간을 만든다 — 테스트가 하루를 기다리지 않는다.
        jdbc.update("UPDATE chat_ended_streams SET created_at = ? WHERE stream_id = 'old'",
                Timestamp.from(Instant.parse("2026-08-17T09:00:00Z")));
        int swept = store.sweepOlderThan(Instant.parse("2026-08-18T00:00:00Z"));
        assertThat(swept).isEqualTo(1);
        assertThat(store.find("old")).isEmpty();
        assertThat(store.find("new")).isPresent();
    }

    /**
     * 문항 4: 위 검사는 <b>{@code ended_at}으로 지우는 구현에도 초록이다</b> — 두 줄의 종료
     * 시각 순서가 메모 시각 순서와 같아서다. TTL의 기준 칸이 뒤바뀌면 종료가 늦게 통보된
     * 방송의 메모가 남자마자 지워지고, 그 뒤 도착한 시작 편지가 세션을 연다.
     * <p>문항 2: {@code swept}만 보면 표가 비어 있어도 0이라 참이 된다 —
     * {@code find}로 <b>남아 있어야 할 줄</b>을 같이 본다(양성 대조).
     * <p>문항 5: {@code DELETE … WHERE ended_at < ?}로 바꾸면 빨간불(확인함).
     * <p>다만 <b>치우기를 아무것도 안 하는 구현에는 이 검사가 초록이다</b>(주입 F로 확인).
     * 그 방향은 위 {@code 하루_지난_메모만_지운다}의 {@code isEqualTo(1)}이 잡는다 —
     * 둘이 반대 방향을 하나씩 맡는다. 한쪽만 지우지 마라.
     */
    @Test
    void 치우기는_종료_시각이_아니라_메모를_남긴_시각을_본다() {
        store.remember("late", 1, 아주_예전);   // 종료 시각만 아주 예전, 메모는 방금
        int swept = store.sweepOlderThan(Instant.now().minus(Duration.ofHours(24)));
        assertThat(swept).isZero();
        assertThat(store.find("late")).isPresent();
    }

    /**
     * 충돌 시 {@code created_at}을 안 갱신하는 것이 <b>의도</b>임을 검사로 못박는다
     * (계획 검증 S3). 갱신하면 ENDED가 재전송될 때마다 수명이 24시간씩 늘어난다.
     * <p>문항 2: {@code createdAt}을 {@code Instant.now()} 둘로 비교하면 두 호출이 같은
     * 마이크로초에 들 때 조용히 참이 된다 — 그래서 <b>직접 밀어 넣은</b> 값으로 잰다.
     * <p>문항 4: {@code createdAt}만 보면 ON CONFLICT가 아예 안 도는 구현도 통과한다 —
     * {@code lastSequence}가 실제로 옮겨졌는지를 양성 대조로 같이 본다.
     * <p>문항 5: {@code SET … created_at = EXCLUDED.created_at}을 넣으면 빨간불(확인함).
     */
    @Test
    void 종료_편지가_또_와도_메모를_남긴_시각은_안_바뀐다() {
        store.remember("s1", 5, Instant.parse("2026-08-18T10:00:00Z"));
        jdbc.update("UPDATE chat_ended_streams SET created_at = ? WHERE stream_id = 's1'",
                Timestamp.from(아주_예전));
        store.remember("s1", 9, Instant.parse("2026-08-18T10:05:00Z"));
        EndedStream memo = store.find("s1").orElseThrow();
        assertThat(memo.lastSequence()).isEqualTo(9L);
        assertThat(memo.createdAt()).isEqualTo(아주_예전);
    }

    /**
     * 재부착이 방송 200개를 받으면 낱개 조회가 200번이다. 한 번으로 줄인다.
     *
     * <p><b>끝났든 포기했든 가리지 않는다</b> — 재부착은 둘 다 건너뛰므로 이유를 알 필요가 없다.
     * 그래서 두 종류의 메모를 나란히 심고 둘 다 나오는지 본다.
     *
     * <p>문항 2: 「없는 것」({@code live-C-001})을 같이 물어 <b>전부 다 있다고 답하는 구현</b>을
     * 막는다 — {@code containsExactlyInAnyOrder}가 여분도 잡는다.
     * <p>문항 5: {@code IN} 절을 {@code stream_id = ?} 하나로 되돌리면 빨간불(확인함).
     */
    @Test
    void 여러_방송의_메모_유무를_한_번에_읽는다() {
        store.remember("live-A-001", 5, Instant.parse("2026-08-31T04:00:00Z"));
        store.rememberStopped("live-B-001", "LINK_UNAVAILABLE", Instant.parse("2026-08-31T04:00:00Z"));

        Set<String> found = store.findAllIds(List.of("live-A-001", "live-B-001", "live-C-001"));

        assertThat(found).containsExactlyInAnyOrder("live-A-001", "live-B-001");
    }

    /**
     * {@code IN ()}은 문법 오류다. <b>부르는 쪽이 매번 막게 두면 한 곳이 빠진다</b> — 재부착은
     * 「방송이 하나도 없는 회차」를 매 주기 지나가므로 그 갈래가 평상시 경로다.
     *
     * <p>문항 2: 빈 결과는 「안 물어서 빔」과 「물었는데 없어서 빔」이 같아 보인다. 이 검사가
     * 재는 것은 <b>예외가 안 난다</b>는 쪽이고, {@code IN} 절이 실제로 도는 것은 위 검사가 잰다.
     */
    @Test
    void 빈_목록을_주면_DB에_안_묻고_빈_결과다() {
        assertThat(store.findAllIds(List.of())).isEmpty();
    }

    /**
     * 반환값 넷을 실물로 잰다 — {@code remember}의 주석이 「실측」이라고 적은 그 넷이다.
     * <b>같은 번호가 false</b>인 것이 특히 중요하다: SQS는 at-least-once라 같은 ENDED가
     * 두 번 오는 것이 정상이고, 태스크 5가 이 false를 실패로 읽으면 정상 중복이
     * 재시도 대상으로 분류된다(계획 검증 S4).
     * <p>문항 2: 네 줄이 true·false를 둘 다 요구하므로 「늘 true」·「늘 false」가 모두 빨간불이다.
     * <p>문항 5: {@code > }를 {@code >=}로 바꾸면 넷째 줄이 빨간불(확인함).
     */
    @Test
    void 표를_바꿨을_때만_참을_돌려준다() {
        assertThat(store.remember("s1", 5, Instant.parse("2026-08-18T10:00:00Z"))).isTrue();
        assertThat(store.remember("s1", 9, Instant.parse("2026-08-18T10:05:00Z"))).isTrue();
        assertThat(store.remember("s1", 5, Instant.parse("2026-08-18T10:00:00Z"))).isFalse();
        assertThat(store.remember("s1", 9, Instant.parse("2026-08-18T10:05:00Z"))).isFalse();
    }
}
