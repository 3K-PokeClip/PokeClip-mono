package com.pokeclip.auth.soop.api;

import com.pokeclip.auth.soop.RevokeReason;
import com.pokeclip.auth.soop.SoopChannelLink;
import com.pokeclip.auth.soop.SoopChannelLinkRepository;
import com.pokeclip.auth.soop.SoopCleanupExecutor;
import com.pokeclip.auth.soop.SoopLinkStateCodec;
import com.pokeclip.auth.soop.SoopLinkTestSupport;
import com.pokeclip.auth.soop.SoopLinkWriter;
import com.pokeclip.auth.soop.SoopTokenRefresher;
import com.pokeclip.auth.soop.SoopRefreshBrake;
import com.pokeclip.auth.streamkey.secret.SecretStore;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 수집기용 내부 창구. {@code ChzzkLinkResolveTest}의 쌍둥이. 거절도 HTTP 200에 {@code valid:false}다:
 * 「연동 안 됨·끊김」(수집 안 함)과 「auth 장애」(판단 불가)는 조치가 정반대라 둘 다 4xx면 못 가른다.
 * 응답에 없어야 할 키는 본문 문자열로 본다({@code doesNotExist}는 null 값 키도 통과한다).
 */
class SoopLinkResolveTest extends SoopLinkTestSupport {

    private final TransactionTemplate tx;
    private final SoopTokenRefresher refresher;

    SoopLinkResolveTest(MockMvc mockMvc, UserService userService, UserRepository userRepository,
                        TokenService tokenService, SoopLinkStateCodec codec,
                        SoopChannelLinkRepository linkRepository, SecretStore secretStore,
                        SoopLinkWriter writer, JdbcTemplate jdbc, SoopCleanupExecutor cleanup,
                    SoopRefreshBrake brake,
                        PlatformTransactionManager transactionManager, SoopTokenRefresher refresher) {
        super(mockMvc, userService, userRepository, tokenService, codec, linkRepository, secretStore, writer, jdbc, cleanup, brake);
        this.tx = new TransactionTemplate(transactionManager);
        this.refresher = refresher;
    }

    private String body(User u) throws Exception {
        return mockMvc.perform(resolve(u.getId())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void 연동_기록이_없으면_200에_valid_false_NOT_LINKED() throws Exception {
        User u = newUser();
        mockMvc.perform(resolve(u.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reason").value("NOT_LINKED"));
        assertThat(body(u)).doesNotContain("accessToken").doesNotContain("soopUserId");
    }

    /** SOOP 아이디는 수집기가 채팅방을 가리키는 데 필요해 이 내부 응답에만 싣는다. */
    @Test
    void 살아있고_수명이_충분하면_아이디와_토큰을_주고_갱신은_안_한다() throws Exception {
        User u = newUser();
        SoopChannelLink link = linked(u, Duration.ofHours(8));
        mockMvc.perform(resolve(u.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.soopUserId").value(link.getSoopUserId()))
                .andExpect(jsonPath("$.accessToken").value("at-old"))
                .andExpect(jsonPath("$.expiresAt").exists());
        assertThat(body(u)).doesNotContain("reason").doesNotContain("rt-old");
        assertThat(SOOP.tokenCalls()).isZero();
    }

    /** resolveMinRemaining(1시간)보다 적게 남았으면 즉석 갱신해 새 토큰을 준다. */
    @Test
    void 남은_수명이_1시간_미만이면_즉석_갱신한_새_토큰을_준다() throws Exception {
        User u = newUser();
        linked(u, Duration.ofMinutes(50));
        mockMvc.perform(resolve(u.getId())).andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.accessToken").value("at-1"));
        assertThat(SOOP.tokenCalls()).isEqualTo(1);
    }

    @Test
    void 남은_수명이_1시간을_넘으면_갱신하지_않는다() throws Exception {
        User u = newUser();
        linked(u, Duration.ofMinutes(70));
        mockMvc.perform(resolve(u.getId())).andExpect(jsonPath("$.accessToken").value("at-old"));
        assertThat(SOOP.tokenCalls()).isZero();
    }

    @Test
    void 즉석_갱신이_일시_실패면_임박_토큰을_주지_않고_REFRESH_UNAVAILABLE() throws Exception {
        User u = newUser();
        linked(u, Duration.ofMinutes(10));
        SOOP.tokenResponds(503, "{}");
        mockMvc.perform(resolve(u.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reason").value("REFRESH_UNAVAILABLE"));
        assertThat(body(u)).doesNotContain("accessToken").doesNotContain("at-old");
    }

    /** 브레이크 전의 즉석 갱신 4xx는 그대로 파기한다. 다음 호출은 다시 안 부른다. */
    @Test
    void 즉석_갱신이_4xx면_BROKEN이고_파기된다() throws Exception {
        User u = newUser();
        SoopChannelLink link = linked(u, Duration.ofMinutes(10));
        SOOP.tokenResponds(401, "{}");
        mockMvc.perform(resolve(u.getId())).andExpect(jsonPath("$.reason").value("BROKEN"));
        mockMvc.perform(resolve(u.getId())).andExpect(jsonPath("$.reason").value("BROKEN"));
        assertThat(SOOP.tokenCalls()).isEqualTo(1);
        awaitCleanup();
        assertThat(secretStore.get(link.getAccessTokenRef())).isEmpty();
        assertThat(secretStore.get(link.getRefreshTokenRef())).isEmpty();
    }

    /** 브레이크가 선 뒤의 즉석 갱신 4xx는 파기하지 않고 REFRESH_UNAVAILABLE이다(수집은 멈추되 연동은 지킨다). */
    @Test
    void 브레이크가_선_뒤의_즉석_갱신_4xx는_파기하지_않고_REFRESH_UNAVAILABLE() throws Exception {
        User u = newUser();
        SoopChannelLink link = linked(u, Duration.ofMinutes(10));
        SOOP.tokenResponds(401, "{}");
        // engage·release는 package-private이다(운영에 세우기 문을 열지 않는다). 다른 패키지라 리플렉션으로 부른다.
        ReflectionTestUtils.invokeMethod(brake, "engage");
        try {
            mockMvc.perform(resolve(u.getId())).andExpect(status().isOk())
                    .andExpect(jsonPath("$.valid").value(false))
                    .andExpect(jsonPath("$.reason").value("REFRESH_UNAVAILABLE"));
            awaitCleanup();
        } finally {
            ReflectionTestUtils.invokeMethod(brake, "release");
        }
        assertThat(linkRepository.findByUserIdAndRevokedAtIsNull(u.getId())).as("🔴 브레이크 뒤 resolve가 연동을 닫았다").isPresent();
        assertThat(secretStore.get(link.getRefreshTokenRef())).contains("rt-old");
    }

    private String reasonOf(User u) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(body(u), "$.reason");
    }

    private boolean alive(User u) {
        return linkRepository.findByUserIdAndRevokedAtIsNull(u.getId()).isPresent();
    }

    /**
     * 🔴 비밀값이 틀린 배포에서 첫 스케줄러 틱(1시간 뒤) 전에 resolve가 방송하는 회원마다 파기하던 구멍(PR #329 봇 지적 P1).
     * resolve 거절도 브레이크가 센다: 셋째 거절부터 파기하지 않고 REFRESH_UNAVAILABLE이다. 닫히는 것은 rejectBrake-1(2)명까지.
     */
    @Test
    void resolve_거절도_세어_셋째부터는_파기하지_않는다() throws Exception {
        SOOP.tokenResponds(401, "{}");
        User a = newUser(), b = newUser(), c = newUser(), d = newUser();
        for (User u : java.util.List.of(a, b, c, d)) {
            linked(u, Duration.ofMinutes(10));
        }

        assertThat(reasonOf(a)).isEqualTo("BROKEN");
        assertThat(reasonOf(b)).isEqualTo("BROKEN");
        assertThat(reasonOf(c)).as("셋째 거절은 브레이크를 세우고 파기하지 않는다").isEqualTo("REFRESH_UNAVAILABLE");
        assertThat(reasonOf(d)).isEqualTo("REFRESH_UNAVAILABLE");
        awaitCleanup();
        assertThat(alive(c)).isTrue();
        assertThat(alive(d)).isTrue();
        assertThat(brake.engaged()).isTrue();
    }

    /** 두 경로가 같은 셈을 쓴다: resolve 거절 둘 + 스케줄러 점검 거절 하나 = 셋이면 선다. */
    @Test
    void resolve_둘과_스케줄러_하나가_합쳐_셋이면_선다() throws Exception {
        SOOP.tokenResponds(401, "{}");
        User a = newUser(), b = newUser(), c = newUser();
        for (User u : java.util.List.of(a, b, c)) {
            linked(u, Duration.ofMinutes(10));
        }

        reasonOf(a);
        reasonOf(b);
        refresher.refreshForCheck(c.getId());

        assertThat(brake.engaged()).isTrue();
        awaitCleanup();
        assertThat(alive(c)).as("스케줄러의 셋째 거절도 파기하지 않는다").isTrue();
    }

    /**
     * 토큰이 신선한 회원의 resolve(SKIPPED_FRESH, SOOP 호출 없음)가 거절 사이사이에 끼어도 셈은 이어진다.
     * 그것이 0으로 돌리면 비밀값이 틀린 배포에서 브레이크가 영영 안 선다(신선한 회원이 대다수다).
     */
    @Test
    void 거절_사이사이_신선한_회원의_resolve가_끼어도_셋째_거절에서_선다() throws Exception {
        User fresh = newUser();
        linked(fresh, Duration.ofHours(8));
        User a = newUser(), b = newUser(), c = newUser();
        for (User u : java.util.List.of(a, b, c)) {
            linked(u, Duration.ofMinutes(10));
        }
        SOOP.tokenResponds(401, "{}");

        reasonOf(a);
        assertThat(body(fresh)).contains("\"valid\":true");
        reasonOf(b);
        assertThat(body(fresh)).contains("\"valid\":true");

        assertThat(reasonOf(c)).isEqualTo("REFRESH_UNAVAILABLE");
        assertThat(brake.engaged()).isTrue();
    }

    /** 세고 판정하는 것이 원자적이라, 거절 다섯이 동시에 와도 파기는 두 건 이하다. */
    @Test
    void 동시_resolve_다섯이_동시에_거절받아도_파기는_두_건_이하다() throws Exception {
        SOOP.tokenResponds(401, "{}");
        SOOP.tokenDelays(Duration.ofMillis(300));   // 다섯이 같은 순간에 거절을 받게 창을 벌린다
        java.util.List<User> users = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            User u = newUser();
            linked(u, Duration.ofMinutes(10));
            users.add(u);
        }
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(5);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<String>> results = new java.util.ArrayList<>();
        for (User u : users) {
            results.add(pool.submit(() -> {
                go.await();
                return reasonOf(u);
            }));
        }
        go.countDown();
        int broken = 0;
        for (var f : results) {
            broken += "BROKEN".equals(f.get(30, java.util.concurrent.TimeUnit.SECONDS)) ? 1 : 0;
        }
        pool.shutdown();
        awaitCleanup();

        assertThat(broken).isLessThanOrEqualTo(2);
        assertThat(users.stream().filter(u -> !alive(u)).count()).as("🔴 브레이크를 넘어 닫혔다").isLessThanOrEqualTo(2);
    }

    @Test
    void 해제했으면_UNLINKED() throws Exception {
        User u = newUser();
        linked(u, Duration.ofHours(8));
        mockMvc.perform(delete("/api/soop-link").header("Authorization", bearer(u))).andExpect(status().isNoContent());
        mockMvc.perform(resolve(u.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reason").value("UNLINKED"));
    }

    /** 탈퇴(WITHDRAWN)로 닫힌 행도 UNLINKED다(계획 검증 M11). 탈퇴 연결은 태스크 7이라 닫기 쿼리로 직접 만든다. */
    @Test
    void 탈퇴로_닫혔으면_UNLINKED() throws Exception {
        User u = newUser();
        linked(u, Duration.ofHours(8));
        tx.execute(s -> linkRepository.closeAndPurgeAlive(u.getId(), Instant.now(), RevokeReason.WITHDRAWN));
        mockMvc.perform(resolve(u.getId())).andExpect(jsonPath("$.reason").value("UNLINKED"));
    }

    @Test
    void 거절은_INFO_한_줄이고_아이디_토큰이_없다() throws Exception {
        User u = newUser();
        try (LogCaptor logs = new LogCaptor()) {
            mockMvc.perform(resolve(u.getId())).andExpect(status().isOk());
            assertThat(logs.messages()).contains("auth.soop.link.resolve_rejected userId=" + u.getId() + " reason=NOT_LINKED");
        }
    }

    @Test
    void 내부_토큰_없이는_401() throws Exception {
        mockMvc.perform(post("/internal/soop-link/resolve").contentType(APPLICATION_JSON).content("{\"userId\":1}"))
                .andExpect(status().isUnauthorized());
    }

    /** 내부 API는 JWT를 안 받는다: 별도 체인. */
    @Test
    void 사용자_JWT로는_401() throws Exception {
        User u = newUser();
        mockMvc.perform(post("/internal/soop-link/resolve").header("Authorization", bearer(u))
                        .contentType(APPLICATION_JSON).content("{\"userId\":1}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void userId가_없으면_400() throws Exception {
        mockMvc.perform(post("/internal/soop-link/resolve").header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }
}
