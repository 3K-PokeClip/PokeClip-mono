package com.pokeclip.auth.withdrawal;

import ch.qos.logback.classic.Level;
import com.pokeclip.auth.config.WithdrawnAccountFilter;
import com.pokeclip.auth.support.IntegrationTestSupport;
import com.pokeclip.auth.token.TokenPair;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserService;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 탈퇴한 회원에게 남아 있는 접근 표가 막히는지 잰다(PRD D3).
 *
 * <p><b>모든 갈래를 한 표로 두 번 부른다</b> — 탈퇴 표시 전에 한 번, 후에 한 번. 뒤엣것만 재면
 * 「401이 나온다」가 <b>필터 때문인지 원래 그런 창구인지</b> 구분되지 않는다. 앞엣것이 그 자리를
 * 못박는다: 방금 되던 표가 안 되게 되는 것이 이 기능이다.
 *
 * <p><b>탈퇴 창구는 아직 없다</b>(태스크 3). 그래서 {@code deleted_at}만 {@code JdbcTemplate}으로
 * 직접 넣는다 — 이 필터가 읽는 것이 그 칸뿐이라 그것으로 충분하고, 익명화까지 흉내 내면
 * <b>태스크 3의 구현을 앞질러 굳히게 된다.</b>
 */
@AutoConfigureMockMvc
class WithdrawnAccountBlockTest extends IntegrationTestSupport {

    /** application-test.yml의 pokeclip.internal-api.token과 같아야 한다. */
    private static final String INTERNAL_TOKEN = "test-only-internal-token-32bytes-long!!";

    private static final Instant WITHDRAWN_AT = Instant.parse("2026-08-31T12:00:00Z");

    private final MockMvc mockMvc;
    private final UserService userService;
    private final TokenService tokenService;
    private final JdbcTemplate jdbc;
    private final ApplicationContext context;

    WithdrawnAccountBlockTest(MockMvc mockMvc, UserService userService, TokenService tokenService,
                              JdbcTemplate jdbc, ApplicationContext context) {
        this.mockMvc = mockMvc;
        this.userService = userService;
        this.tokenService = tokenService;
        this.jdbc = jdbc;
        this.context = context;
    }

    /** 이 클래스가 심은 회원 번호. 아래 거두기가 이것으로만 지운다. */
    private final List<Long> 심은_회원 = new ArrayList<>();

    /**
     * 🔴 <b>이름이 아니라 번호로 지운다.</b> 태스크 1에서 데인 자리다 — {@code withdraw}가
     * {@code google_sub}를 {@code withdrawn:<번호>}로 바꾸므로 이름으로 지우는 정리는 헛돈다.
     * 여기는 아직 그 메서드를 부르지 않지만 <b>부르게 되는 날 조용히 새는 자리</b>라 처음부터 번호로 둔다.
     *
     * <p>자식을 먼저 지운다 — {@code refresh_tokens}({@code bearer()}가 만든다) ·
     * {@code pairing_codes}·{@code stream_keys}(발급 갈래가 만든다). {@code secrets}는 회원을
     * 가리키는 외래키가 없어 번호로 못 고르는데, 그 표를 세는 검사들이 전부 자기 시작에서
     * 통째로 비우므로(ChzzkLinkTestSupport 등) 고아를 남겨도 남을 안 흔든다.
     */
    @AfterEach
    void 심은_행을_거둔다() {
        for (Long id : 심은_회원) {
            jdbc.update("DELETE FROM pairing_codes WHERE user_id = ?", id);
            jdbc.update("DELETE FROM stream_keys WHERE user_id = ?", id);
            jdbc.update("DELETE FROM refresh_tokens WHERE user_id = ?", id);
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
        심은_회원.clear();
    }

    // ── 다시 채우는 창구 넷 + 회원 정보 조회 ────────────────────────────

    @Test
    void 회원_정보_조회는_살아있으면_200이고_탈퇴하면_401이다() throws Exception {
        User user = newUser();
        String token = bearer(user);

        mockMvc.perform(get("/api/auth/me").header("Authorization", token))
                .andExpect(status().isOk());

        withdraw(user);

        mockMvc.perform(get("/api/auth/me").header("Authorization", token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 이름_수정은_살아있으면_200이고_탈퇴하면_401이다() throws Exception {
        User user = newUser();
        String token = bearer(user);

        mockMvc.perform(patchName(token)).andExpect(status().isOk());

        withdraw(user);

        mockMvc.perform(patchName(token)).andExpect(status().isUnauthorized());
    }

    /**
     * 이 컨텍스트는 창고가 꺼져 있어 살아있는 회원의 답이 <b>503</b>이다(ProfilePhotoDisabledTest와 같은 상태).
     * 재려는 것은 「200이 된다」가 아니라 <b>「401이 아니다」</b>이므로 그 숫자로 충분하다 —
     * 오히려 창고를 켜면 컨텍스트가 하나 더 뜨고 이 검사의 요지와 무관한 짐이 붙는다.
     */
    @Test
    void 사진_올리기는_살아있으면_503이고_탈퇴하면_401이다() throws Exception {
        User user = newUser();
        String token = bearer(user);

        mockMvc.perform(putPhoto(token)).andExpect(status().isServiceUnavailable());

        withdraw(user);

        mockMvc.perform(putPhoto(token)).andExpect(status().isUnauthorized());
    }

    /**
     * 스트림키 발급의 유일한 입구다 — {@code ensureKey}를 부르는 창구가 이것뿐이라
     * (README auth 절) 「탈퇴한 회원이 키를 새로 받는다」를 막는 자리도 여기다.
     */
    @Test
    void 스트림키_발급은_살아있으면_201이고_탈퇴하면_401이다() throws Exception {
        User user = newUser();
        String token = bearer(user);

        mockMvc.perform(post("/api/stream-keys/pairing-codes").header("Authorization", token))
                .andExpect(status().isCreated());

        withdraw(user);

        mockMvc.perform(post("/api/stream-keys/pairing-codes").header("Authorization", token))
                .andExpect(status().isUnauthorized());
    }

    /**
     * state가 우리 서명이 아니라 살아있는 회원도 <b>400</b>(INVALID_STATE)이다. 동의 왕복을 진짜로
     * 돌지 않는 이유는 이 검사가 재는 것이 <b>연동의 성공이 아니라 문이 열려 있는지</b>여서다 —
     * 400과 401이 갈리면 필터가 실제로 앞을 막았다는 뜻이다.
     */
    @Test
    void 채널_연동은_살아있으면_400이고_탈퇴하면_401이다() throws Exception {
        User user = newUser();
        String token = bearer(user);

        mockMvc.perform(postChzzkLink(token)).andExpect(status().isBadRequest());

        withdraw(user);

        mockMvc.perform(postChzzkLink(token)).andExpect(status().isUnauthorized());
    }

    // ── 안 걸려야 하는 것 ───────────────────────────────────────────────

    /**
     * 재발급은 {@code permitAll}이고 <b>표를 헤더에 안 실었을 때만</b> 주체가 없다 —
     * 그때는 필터가 볼 것이 없어 탈퇴해도 평소대로 돈다.
     * 🔴 <b>「permitAll이면 안 걸린다」는 틀린 문장이다</b>(2회차 감사 실측) —
     * 같은 요청에 Authorization 헤더를 실으면 {@code BearerTokenAuthenticationFilter}가
     * {@code permitAll} 경로에서도 인증을 끝내므로 <b>주체가 생기고 필터가 막는다.</b>
     * 아래 두 갈래가 그 사실을 나란히 못박는다.
     *
     * <p>이 상태는 {@code deleted_at}만 직접 넣어 만든 것이라 <b>갱신 표가 살아 있다.</b>
     * 갱신 표를 실제로 죽이는 것은 탈퇴 창구(POK-171 태스크 3)의 몫이고, 그 창구는 여기를 안 지난다 —
     * 그래서 이 시험은 태스크 3 뒤에도 그대로 초록이어야 한다.
     */
    @Test
    void 로그인_없이_부르는_재발급은_탈퇴해도_평소대로_된다() throws Exception {
        User user = newUser();
        TokenPair pair = tokenService.issue(user);
        withdraw(user);

        mockMvc.perform(refresh(pair.refreshToken()))
                .andExpect(status().isOk());
    }

    /**
     * 위와 <b>같은 요청에 표만 실었다.</b> 헤더 하나로 200과 401이 갈리는 것이 요지다 —
     * 「표를 헤더로 안 싣는다」는 클라이언트 구현에 대한 가정이지 서버가 보장하는 것이 아니다.
     *
     * <p>방향은 <b>보안 강화 쪽</b>이라 고칠 것은 코드가 아니라 적힌 사실이다. 다만 탈퇴 직후 30분
     * 동안 같은 요청이 헤더 유무로 갈리므로, 프론트가 재발급에 access 표를 함께 싣기 시작하면
     * 이 시험이 그 사실을 먼저 말해 준다.
     */
    @Test
    void 같은_재발급도_표를_실으면_필터가_막는다() throws Exception {
        User user = newUser();
        String token = bearer(user);
        TokenPair pair = tokenService.issue(user);
        withdraw(user);

        mockMvc.perform(refresh(pair.refreshToken()).header("Authorization", token))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 내부 창구는 {@code @Order(1)}의 별도 체인이고 <b>JWT 인증을 아예 안 한다</b> —
     * 필터가 볼 주체가 없으니 회원 번호가 본문에 실려 와도 지나간다.
     *
     * <p>🔴 <b>이 검사는 「빈으로 등록하지 마라」를 재지 못한다.</b> 실제로 {@code @Component}를
     * 붙여 전역 등록해 봤는데 <b>초록이었다</b>(주입 D) — 전역이 되어도 그 체인엔 주체가 없어
     * <b>여기서는</b> 막을 것이 없기 때문이다. 🔴 <b>「필터가 아무것도 안 한다」로 읽지 마라</b> —
     * 일반 인증 경로에서는 인스턴스 둘이 다 돌고 표 조회가 는다(2회차 감사 실측:
     * 진입 15→23 · 조회 13→19). 그 규칙을 재는 것은 아래
     * {@code 필터는_빈으로_등록되지_않는다}이고, 여기가 재는 것은
     * <b>「필터가 본문의 회원 번호를 보지 않는다」</b>이다 — 나중에 그렇게 고치면 여기가 빨간불이 된다.
     */
    @Test
    void 내부_창구는_탈퇴한_회원_번호로도_안_걸린다() throws Exception {
        User user = newUser();
        withdraw(user);

        mockMvc.perform(post("/internal/editor-delegations/accessible")
                        .header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + user.getId() + "}"))
                .andExpect(status().isOk());
    }

    /**
     * 🔴 <b>필터를 빈으로 두지 않는다</b>(PRD·계획 명시). {@code SecurityConfig}가 {@code new}로
     * 만들어 기본 체인에만 끼운다 — {@code InternalTokenFilter}가 같은 모양이다.
     *
     * <p>빈이 되면 서블릿이 전역 등록해 <b>보안 체인 밖에서도</b> 돌고, 끼우는 자리가 명시가 아니라
     * 등록 순서에 딸려 간다. 그 회귀는 <b>응답으로는 안 보인다</b>(위 내부 창구 검사가 초록이었다) —
     * 그래도 값이 없는 것은 아니다: 통과한 요청마다 회원 표 조회가 하나 더 나간다
     * (2회차 감사 실측 13→19). 응답으로 안 보이니 <b>빈 목록을 직접 센다.</b>
     */
    @Test
    void 필터는_빈으로_등록되지_않는다() {
        assertThat(context.getBeanNamesForType(WithdrawnAccountFilter.class)).isEmpty();
    }

    /**
     * 회원 행이 사라진 표는 <b>빈손</b>을 받는다 — 필터는 그것을 「막지 않음」으로 다룬다
     * (UserRepository.findDeletedAtById의 계약). 「토큰의 주인이 없다」는 각 창구가 자기 사유로 다룬다.
     *
     * <p>스트림키 상태 창구로 재는 이유: 회원 표를 안 읽어 <b>없는 번호에도 200</b>을 준다.
     * {@code /api/auth/me}로 재면 회원을 못 찾아 어차피 401이라 필터가 막았는지 구분되지 않는다.
     */
    @Test
    void 회원_행이_사라진_표는_필터가_통과시킨다() throws Exception {
        User user = newUser();
        String token = bearer(user);
        jdbc.update("DELETE FROM refresh_tokens WHERE user_id = ?", user.getId());
        jdbc.update("DELETE FROM users WHERE id = ?", user.getId());

        mockMvc.perform(get("/api/stream-keys").header("Authorization", token))
                .andExpect(status().isOk());
    }

    // ── 401의 모양과 로그 ───────────────────────────────────────────────

    /**
     * 401 본문이 비어 있어야 한다 — 토큰 없이 불렀을 때와 <b>글자까지 같은</b> 답이다.
     * 사유를 실어 보내면 「이 번호는 탈퇴한 계정이다」가 표만 가진 쪽에 새어 나간다.
     */
    @Test
    void 막힌_답은_토큰_없이_불렀을_때와_같은_모양이다() throws Exception {
        User user = newUser();
        String token = bearer(user);
        withdraw(user);

        String blocked = mockMvc.perform(get("/api/auth/me").header("Authorization", token))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String noToken = mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(blocked).isEqualTo(noToken);
        assertThat(blocked).isEmpty();
    }

    /**
     * 막았다는 사실이 로그 한 줄로 남아야 한다. 이 자리는 <b>정상 트래픽</b>이다 —
     * 탈퇴 직후 30분은 화면이 아직 옛 표를 들고 있어 계속 온다. 그래서 WARN이 아니라 INFO다.
     */
    @Test
    void 막을_때_INFO_로그를_남긴다() throws Exception {
        User user = newUser();
        String token = bearer(user);
        withdraw(user);

        try (LogCaptor logs = new LogCaptor()) {
            mockMvc.perform(get("/api/auth/me").header("Authorization", token))
                    .andExpect(status().isUnauthorized());

            assertThat(logs.messages())
                    .anyMatch(m -> m.equals("auth.withdrawn.blocked userId=" + user.getId()));
            assertThat(logs.levelOf("auth.withdrawn.blocked")).isEqualTo(Level.INFO);
        }
    }

    // ── 도구 ────────────────────────────────────────────────────────────

    private MockHttpServletRequestBuilder patchName(String token) {
        return patch("/api/auth/me")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"새 이름\"}");
    }

    private RequestBuilder putPhoto(String token) {
        byte[] body = new byte[512];
        System.arraycopy(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}, 0, body, 0, 8);
        return multipart("/api/auth/me/photo")
                .file(new MockMultipartFile("file", "me.png", "image/png", body))
                .header("Authorization", token)
                .with(r -> {
                    r.setMethod("PUT");
                    return r;
                });
    }

    /** 두 갈래가 <b>같은 요청</b>이어야 「헤더 하나로 갈린다」가 참이 된다. 그래서 한 자리에서 만든다. */
    private MockHttpServletRequestBuilder refresh(String refreshToken) {
        return post("/api/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}");
    }

    private MockHttpServletRequestBuilder postChzzkLink(String token) {
        return post("/api/chzzk-link")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"code-1\",\"state\":\"우리가-서명한-것이-아니다\"}");
    }

    /** 탈퇴 창구가 아직 없다. 필터가 읽는 칸만 직접 채운다. */
    private void withdraw(User user) {
        jdbc.update("UPDATE users SET deleted_at = ? WHERE id = ?",
                Timestamp.from(WITHDRAWN_AT), user.getId());
    }

    /** 이메일에도 유일 제약이 있다(V108). 한 검사가 계정을 여럿 만드므로 주소도 흩는다. */
    private User newUser() {
        String id = UUID.randomUUID().toString();
        User user = userService.findOrCreate("sub-block-" + id, id + "@example.com", "김태현", null);
        심은_회원.add(user.getId());
        return user;
    }

    private String bearer(User user) {
        return "Bearer " + tokenService.issue(user).accessToken();
    }
}
