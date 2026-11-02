package com.pokeclip.auth.soop;

import ch.qos.logback.classic.Level;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 연속 거절 브레이크(PR #329 봇 지적 P1). 스케줄러·resolve 두 경로의 거절을 <b>같이</b> 센다. {@code recordReject}가
 * true면 그 거절은 파기해도 되고, false면 파기하지 않는다. 연속 수가 {@code rejectBrake}(3)에 닿는 거절부터 false다.
 * 0으로 돌리는 것은 SOOP이 실제로 답한 성공(REFRESHED)뿐이다. SKIPPED_FRESH·NOT_LINKED·UNAVAILABLE은 이 빈을 부르지 않는다.
 */
class SoopRefreshBrakeTest {

    private final SoopRefreshBrake brake = new SoopRefreshBrake(SoopTokenRefreshSchedulerTest.testProps());

    @Test
    void 연달아_세_번째_거절부터_파기하지_않고_선다() {
        assertThat(brake.recordReject(1L)).isTrue();
        assertThat(brake.recordReject(2L)).isTrue();
        assertThat(brake.engaged()).isFalse();

        assertThat(brake.recordReject(3L)).as("셋째 거절은 파기하지 않는다(rejectBrake-1명까지만 닫힌다)").isFalse();
        assertThat(brake.engaged()).isTrue();
        assertThat(brake.recordReject(4L)).isFalse();
    }

    /** 총합이 아니라 「연속」을 센다(M9 첫 줄 REJ,REJ,REFRESHED,REJ,REJ → 안 섬). */
    @Test
    void 사이에_REFRESHED가_끼면_0으로_돌아간다() {
        brake.recordReject(1L);
        brake.recordReject(2L);
        brake.recordSuccess();
        assertThat(brake.recordReject(4L)).isTrue();
        assertThat(brake.recordReject(5L)).isTrue();
        assertThat(brake.engaged()).isFalse();
    }

    /**
     * 비밀값이 틀린 배포에서 resolve 호출은 대부분 토큰이 신선한 회원이다(SKIPPED_FRESH, SOOP 호출 없음). 그것이 셈을 0으로
     * 돌리면 거절 사이사이에 끼어 브레이크가 영영 안 선다. 그래서 SKIPPED_FRESH·UNAVAILABLE은 셈을 건드리지 않는다
     * (갱신기가 이 빈을 안 부른다). 여기서는 「성공 신호 없이 이어진 거절 셋이면 선다」를 잰다.
     */
    @Test
    void 성공_신호_없이_이어진_거절_셋이면_선다() {
        brake.recordReject(1L);
        brake.recordReject(2L);
        brake.recordReject(3L);
        assertThat(brake.engaged()).isTrue();
    }

    @Test
    void 서는_순간_ERROR_한_줄에_조치를_남기고_값은_안_남긴다() {
        try (LogCaptor logs = new LogCaptor()) {
            brake.recordReject(1L);
            brake.recordReject(2L);
            brake.recordReject(3L);
            brake.recordReject(4L);
            assertThat(logs.messages()).filteredOn(m -> m.startsWith("auth.soop.link.refresh_reject_burst")).hasSize(1);
            assertThat(logs.levelOf("auth.soop.link.refresh_reject_burst")).isEqualTo(Level.ERROR);
            assertThat(logs.messages()).anyMatch(m -> m.startsWith("auth.soop.link.refresh_reject_burst")
                    && m.contains("SOOP_CLIENT_SECRET") && m.contains("redeploy"));
            assertThat(logs.messages()).noneMatch(m -> m.contains("csecret"));
        }
    }

    /** 세고 판정하는 것이 한 번의 원자 연산이라, 동시에 거절 다섯이 와도 파기는 rejectBrake-1(2)건 이하다. */
    @Test
    void 동시_거절_다섯이어도_파기는_두_건_이하다() throws Exception {
        for (int round = 0; round < 50; round++) {
            SoopRefreshBrake b = new SoopRefreshBrake(SoopTokenRefreshSchedulerTest.testProps());
            ExecutorService pool = Executors.newFixedThreadPool(5);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (long i = 0; i < 5; i++) {
                long userId = i;
                results.add(pool.submit(() -> {
                    go.await();
                    return b.recordReject(userId);
                }));
            }
            go.countDown();
            int purged = 0;
            for (Future<Boolean> f : results) {
                purged += f.get(5, TimeUnit.SECONDS) ? 1 : 0;
            }
            pool.shutdown();
            assertThat(purged).as("round %d", round).isLessThanOrEqualTo(2);
            assertThat(b.engaged()).isTrue();
        }
    }
}
