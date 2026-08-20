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
