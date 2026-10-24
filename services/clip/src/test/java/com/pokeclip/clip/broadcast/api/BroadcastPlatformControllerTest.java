package com.pokeclip.clip.broadcast.api;

import com.pokeclip.clip.purge.StreamerPurgeStore;
import com.pokeclip.clip.support.IntegrationTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 수집기가 「이 방송이 이 플랫폼에서 켜져 있다」를 알리는 문(POK-397). 실제 시큐리티 체인과 DB를 태운다.
 * 편지가 정한 {@code broadcasts.platform}은 안 바꾸고 묶음 표 {@code broadcast_platforms}에만 적는다.
 */
@AutoConfigureMockMvc
class BroadcastPlatformControllerTest extends IntegrationTestSupport {

    /** application-test.yml의 pokeclip.internal-api.token과 같은 값. */
    private static final String INTERNAL = "test-only-internal-token-32bytes-long!!";
    private static final Instant 시작 = Instant.parse("2026-10-23T10:00:00Z");

    private final MockMvc mvc;
    private final JdbcTemplate jdbc;
    private final StreamerPurgeStore purgeStore;
    private final TransactionTemplate tx;

    BroadcastPlatformControllerTest(MockMvc mvc, JdbcTemplate jdbc, StreamerPurgeStore purgeStore,
                                    TransactionTemplate tx) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.purgeStore = purgeStore;
        this.tx = tx;
    }

    @BeforeEach
    @AfterEach
    void 비운다() {
        방송과_카드를_비운다(jdbc);
    }

    @Test
    void 처음_알리면_줄이_생기고_204다() throws Exception {
        방송("S-1", "CHZZK");

        알린다("S-1", "SOOP", 시작.plusSeconds(60)).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM broadcast_platforms WHERE stream_id = 'S-1'", Integer.class))
                .isOne();
        assertThat(처음("S-1", "SOOP")).isEqualTo(시작.plusSeconds(60));
        assertThat(마지막("S-1", "SOOP")).isEqualTo(시작.plusSeconds(60));
    }

    /** 수집기는 같은 것을 여러 번 알릴 수 있다(재시작 뒤 다시 알림). 줄은 하나고, 늦게 온 이른 시각이 마지막 시각을 되돌리지 않는다. */
    @Test
    void 다시_알려도_한_줄이고_마지막_시각은_앞으로만_간다() throws Exception {
        방송("S-1", "CHZZK");

        알린다("S-1", "SOOP", 시작.plusSeconds(60)).andExpect(status().isNoContent());
        알린다("S-1", "SOOP", 시작.plusSeconds(300)).andExpect(status().isNoContent());
        알린다("S-1", "SOOP", 시작.plusSeconds(120)).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM broadcast_platforms", Integer.class)).isOne();
        assertThat(처음("S-1", "SOOP")).isEqualTo(시작.plusSeconds(60));
        assertThat(마지막("S-1", "SOOP")).isEqualTo(시작.plusSeconds(300));
    }

    @Test
    void 플랫폼마다_줄이_따로다() throws Exception {
        방송("S-1", "CHZZK");

        알린다("S-1", "CHZZK", 시작).andExpect(status().isNoContent());
        알린다("S-1", "SOOP", 시작.plusSeconds(30)).andExpect(status().isNoContent());

        assertThat(jdbc.queryForList("SELECT platform FROM broadcast_platforms WHERE stream_id = 'S-1' ORDER BY platform",
                String.class)).containsExactly("CHZZK", "SOOP");
    }

    @Test
    void 본문이_없으면_지금_시각으로_적는다() throws Exception {
        방송("S-1", "CHZZK");
        Instant before = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        mvc.perform(put("/internal/broadcasts/S-1/platforms/SOOP").header("X-Internal-Token", INTERNAL))
                .andExpect(status().isNoContent());

        Instant after = Instant.now().plusMillis(1);
        assertThat(마지막("S-1", "SOOP")).isBetween(before, after);
        assertThat(처음("S-1", "SOOP")).isBetween(before, after);
    }

    /** 형식은 편지 처리기와 같은 한 자리({@code Platform.isWellFormed})가 본다. 소문자를 올려 받지 않는다. */
    @Test
    void 형식_밖_플랫폼은_400이고_적지_않는다() throws Exception {
        방송("S-1", "CHZZK");

        for (String bad : new String[]{"soop", "Soop", " ", "ABCDEFGHIJKLMNOPQ", "SO-OP"}) {
            알린다("S-1", bad, 시작)
                    .andExpect(status().isBadRequest())
                    .andExpect(content().json("{\"error\":\"invalid_platform\"}"));
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM broadcast_platforms", Integer.class)).isZero();
    }

    @Test
    void 읽을_수_없는_시각은_400이고_적지_않는다() throws Exception {
        방송("S-1", "CHZZK");

        mvc.perform(put("/internal/broadcasts/S-1/platforms/SOOP").header("X-Internal-Token", INTERNAL)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"seenAt\":\"어제\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"invalid_seen_at\"}"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM broadcast_platforms", Integer.class)).isZero();
    }

    @Test
    void 없는_방송은_404이고_적지_않는다() throws Exception {
        알린다("S-none", "SOOP", 시작)
                .andExpect(status().isNotFound())
                .andExpect(content().json("{\"error\":\"broadcast_not_found\"}"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM broadcast_platforms", Integer.class)).isZero();
    }

    @Test
    void 토큰이_없거나_틀리면_401이고_적지_않는다() throws Exception {
        방송("S-1", "CHZZK");

        mvc.perform(put("/internal/broadcasts/S-1/platforms/SOOP")).andExpect(status().isUnauthorized());
        mvc.perform(put("/internal/broadcasts/S-1/platforms/SOOP").header("X-Internal-Token", "wrong"))
                .andExpect(status().isUnauthorized());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM broadcast_platforms", Integer.class)).isZero();
    }

    /** 편지가 정한 첫 값(V218)은 생명주기 편지 처리기만의 규칙이다. 이 문은 묶음 표에만 적는다. */
    @Test
    void 편지가_정한_방송_플랫폼은_안_바뀐다() throws Exception {
        방송("S-1", "CHZZK");
        방송("S-2", "SOOP");

        알린다("S-1", "SOOP", 시작).andExpect(status().isNoContent());
        알린다("S-2", "CHZZK", 시작).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT platform FROM broadcasts WHERE stream_id = 'S-1'", String.class))
                .isEqualTo("CHZZK");
        assertThat(jdbc.queryForObject("SELECT platform FROM broadcasts WHERE stream_id = 'S-2'", String.class))
                .isEqualTo("SOOP");
    }

    /**
     * 탈퇴 정리가 방송 줄을 잡고 지우는 동안 알림이 겹치면, 알림은 그 줄을 기다렸다가 외래키 위반을 맞는다(계획 검증 실측).
     * 그것은 「방송 없음」과 같은 뜻이라 500이 아니라 404로 답하고 줄을 남기지 않는다.
     */
    @Test
    void 탈퇴_정리와_겹치면_404이고_줄이_남지_않는다() throws Exception {
        방송("S-1", "CHZZK");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<Void> purge = CompletableFuture.runAsync(() -> tx.executeWithoutResult(status -> {
            purgeStore.detach("90001");
            locked.countDown();
            try {
                assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }));
        assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<MvcResult> notify = CompletableFuture.supplyAsync(() -> {
            try {
                return 알린다("S-1", "SOOP", 시작).andReturn();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        // 알림이 정리의 줄 잠금을 실제로 기다리는 것을 본 뒤에 정리를 놓는다. 안 기다렸다면 경합을 잰 것이 아니다.
        기다리는_질의가_보일_때까지("INSERT INTO broadcast_platforms");
        release.countDown();
        purge.get(30, TimeUnit.SECONDS);

        MvcResult result = notify.get(30, TimeUnit.SECONDS);
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(result.getResponse().getContentAsString()).contains("broadcast_not_found");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM broadcast_platforms", Integer.class)).isZero();
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    private org.springframework.test.web.servlet.ResultActions 알린다(String streamId, String platform, Instant seenAt)
            throws Exception {
        return mvc.perform(put("/internal/broadcasts/{streamId}/platforms/{platform}", streamId, platform)
                .header("X-Internal-Token", INTERNAL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seenAt\":\"" + seenAt + "\"}"));
    }

    private void 방송(String streamId, String platform) {
        jdbc.update("INSERT INTO broadcasts (stream_id, streamer_id, status, started_at, last_sequence, platform) "
                + "VALUES (?, '90001', 'live', ?, 1, ?)", streamId, Timestamp.from(시작), platform);
    }

    private Instant 처음(String streamId, String platform) {
        return jdbc.queryForObject("SELECT first_seen_at FROM broadcast_platforms WHERE stream_id = ? AND platform = ?",
                Timestamp.class, streamId, platform).toInstant();
    }

    private Instant 마지막(String streamId, String platform) {
        return jdbc.queryForObject("SELECT last_seen_at FROM broadcast_platforms WHERE stream_id = ? AND platform = ?",
                Timestamp.class, streamId, platform).toInstant();
    }

    private void 기다리는_질의가_보일_때까지(String queryPrefix) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline)) {
            Integer waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                     WHERE wait_event_type = 'Lock' AND query LIKE ?""", Integer.class, queryPrefix + "%");
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("알림이 정리의 잠금을 기다리지 않았다");
    }
}
