package com.pokeclip.auth.withdrawal;

import ch.qos.logback.classic.Level;
import com.pokeclip.auth.token.TokenPair;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserService;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code DELETE /api/auth/me}가 회원 행을 익명화하고 갱신 표를 전부 폐기하는지 잰다(PRD D1·D2).
 *
 * <p>🔴 <b>표를 읽을 때 반드시 {@code JdbcTemplate}을 쓴다.</b> 엔티티나 리포지토리로 읽으면
 * 영속성 컨텍스트가 <b>메모리에 있는 객체</b>를 돌려줄 수 있어, 커밋에 아무것도 안 실린 경우
 * (계획 검증 치명-1: 익명화 유실)를 <b>못 본다.</b> 이 클래스가 그 결함을 잡는 유일한 그물이다.
 *
 * <p><b>발급물 회수(스트림키·연동·위임)는 여기서 안 잰다</b> — 태스크 4~6의 몫이다.
 * 여기서 미리 재면 아직 없는 코드를 두고 「무엇이 왜 실패하는지」가 흐려진다.
 */
class WithdrawalTest extends WithdrawalTestSupport {

    private final WithdrawalService withdrawalService;

    WithdrawalTest(MockMvc mockMvc, UserService userService, TokenService tokenService, JdbcTemplate jdbc,
                   WithdrawalService withdrawalService) {
        super(mockMvc, userService, tokenService, jdbc);
        this.withdrawalService = withdrawalService;
    }

    @Test
    void 탈퇴하면_204다() throws Exception {
        User user = newUser();

        mockMvc.perform(delete("/api/auth/me").header("Authorization", bearer(user)))
                .andExpect(status().isNoContent());
    }

    /**
     * 네 칸에 같은 표식을 심어 두고 <b>탈퇴 전후를 둘 다 잰다.</b> 뒤엣것만 재면
     * 「안 남았다」가 <b>지워져서인지 처음부터 없어서인지</b> 구분되지 않는다.
     */
    @Test
    void 탈퇴하면_표에_옛_개인정보가_한_글자도_안_남는다() throws Exception {
        String marker = marker();
        User user = newUser(marker);

        assertThat(personalDataOf(user))
                .as("심은 표식이 표에 없다면 이 시험은 아무것도 안 재고 있다")
                .contains(marker);

        mockMvc.perform(delete("/api/auth/me").header("Authorization", bearer(user)))
                .andExpect(status().isNoContent());

        assertThat(personalDataOf(user))
                .as("구글 식별자·이메일·이름·사진 주소 중 하나라도 안 지워졌다")
                .doesNotContain(marker);
        assertThat(jdbc.queryForObject(
                "SELECT deleted_at IS NOT NULL FROM users WHERE id = ?", Boolean.class, user.getId()))
                .as("탈퇴 시각이 안 찍혔다 — 익명화가 커밋에 안 실렸을 때 이 칸이 먼저 비어 있다")
                .isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT profile_photo_key IS NULL AND profile_photo_updated_at IS NULL FROM users WHERE id = ?",
                Boolean.class, user.getId()))
                .isTrue();
    }

    /**
     * <b>지우지 않고 죽인다</b> — 행 수는 그대로고 살아있는 것만 0이 된다.
     *
     * <p>마지막 갈래를 <b>Authorization 헤더 없이</b> 부른다. 헤더를 실으면 전면 차단 필터가
     * 먼저 401을 내므로(2회차 감사 실측) <b>표가 죽어서 실패한 것인지 필터가 막은 것인지</b>
     * 구분되지 않는다.
     */
    @Test
    void 탈퇴하면_그_사람의_갱신_표가_전부_죽는다() throws Exception {
        User user = newUser();
        TokenPair first = tokenService.issue(user);
        tokenService.issue(user);
        String token = bearer(user);   // 이것도 갱신 표를 하나 더 만든다

        assertThat(aliveRefreshTokens(user))
                .as("살아있는 표가 없으면 「전부 죽는다」는 아무것도 안 재는 문장이다")
                .isEqualTo(3);

        mockMvc.perform(delete("/api/auth/me").header("Authorization", token))
                .andExpect(status().isNoContent());

        assertThat(aliveRefreshTokens(user)).isZero();
        assertThat(allRefreshTokens(user))
                .as("행을 지우면 재사용 감지가 「모르는 토큰」과 「죽은 토큰」을 구분 못 한다")
                .isEqualTo(3);
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + first.refreshToken() + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 익명화가 {@code google_sub}·{@code email}을 둘 다 비켜 주므로 유일 제약(V101·V108)에 안 걸린다.
     * <b>새 번호가 나오는 것이 요지다</b> — 옛 번호로 돌아가면 지운 사람의 발급물·관계가 되살아난다.
     */
    @Test
    void 탈퇴한_계정과_같은_구글_식별자로_다시_로그인하면_새_회원이_된다() throws Exception {
        String marker = marker();
        User before = newUser(marker);

        mockMvc.perform(delete("/api/auth/me").header("Authorization", bearer(before)))
                .andExpect(status().isNoContent());

        User after = newUser(marker);

        assertThat(after.getId()).isNotEqualTo(before.getId());
        assertThat(after.isWithdrawn()).isFalse();
    }

    /**
     * ⚠️ <b>이 한 건은 창구가 없던 red 실행에서도 초록이었다</b>(매핑이 없어도 미인증은 401이다).
     * 그래서 혼자서는 창구의 존재를 못 잰다 — 짝은 {@code 탈퇴하면_204다}이고, 여기가 재는 것은
     * <b>이 주소가 {@code permitAll}로 열리지 않는다</b>는 것이다.
     */
    @Test
    void 로그인_없이_부르면_401이다() throws Exception {
        mockMvc.perform(delete("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    /**
     * 창구가 회원 번호를 <b>받지 않는다</b> — 표의 주인만 자기 것을 지운다. 번호를 받는 모양으로
     * 바뀌면 이 시험이 먼저 막는다.
     */
    @Test
    void 남의_계정은_못_지운다() throws Exception {
        User a = newUser();
        String bMarker = marker();
        User b = newUser(bMarker);

        mockMvc.perform(delete("/api/auth/me").header("Authorization", bearer(a)))
                .andExpect(status().isNoContent());

        assertThat(personalDataOf(b)).contains(bMarker);
        assertThat(jdbc.queryForObject(
                "SELECT deleted_at IS NULL FROM users WHERE id = ?", Boolean.class, b.getId()))
                .isTrue();
    }

    /**
     * 두 번째 요청은 <b>창구까지 오지 않는다</b> — 전면 차단 필터가 401로 막는다(태스크 2).
     * 서비스 자체도 멱등이라 필터를 우회해 직접 불러도 안전하다. 둘은 겹치는 방어다.
     */
    @Test
    void 두_번째_탈퇴_요청은_필터가_401로_막는다() throws Exception {
        User user = newUser();
        String token = bearer(user);

        mockMvc.perform(delete("/api/auth/me").header("Authorization", token))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/auth/me").header("Authorization", token))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 필터는 회원 행이 없으면 <b>통과시킨다</b>(「토큰의 주인이 없다」는 각 창구가 자기 사유로 다룬다).
     * 그래서 이 갈래는 창구까지 와서 {@code DataInconsistencyException}으로 401이 된다 —
     * <b>500이 아니다.</b>
     */
    @Test
    void 토큰의_주인이_없으면_401이다() throws Exception {
        User user = newUser();
        String token = bearer(user);
        jdbc.update("DELETE FROM refresh_tokens WHERE user_id = ?", user.getId());
        jdbc.update("DELETE FROM users WHERE id = ?", user.getId());

        mockMvc.perform(delete("/api/auth/me").header("Authorization", token))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 🔴 이 줄의 뜻은 <b>「표 변경이 끝났다」</b>이지 「정리까지 끝났다」가 아니다.
     * 정리 잡의 로그({@code auth.withdrawal.cleanup.*}, 태스크 7)와 <b>이름이 갈려야</b>
     * 「표는 바뀌었는데 사진 파일이 안 지워진 회원」을 짝으로 찾을 수 있다.
     *
     * <p>커밋 뒤에 찍는다 — 롤백된 탈퇴가 이 줄을 남기면 「지웠다」는 거짓 알리바이가 된다.
     */
    @Test
    void 표_변경이_끝나면_INFO_로그를_남긴다() throws Exception {
        User user = newUser();
        String token = bearer(user);

        try (LogCaptor logs = new LogCaptor()) {
            mockMvc.perform(delete("/api/auth/me").header("Authorization", token))
                    .andExpect(status().isNoContent());

            assertThat(logs.messages())
                    .anyMatch(m -> m.equals("auth.withdrawal.completed userId=" + user.getId()));
            assertThat(logs.levelOf("auth.withdrawal.completed")).isEqualTo(Level.INFO);
        }
    }

    /**
     * 창구가 아니라 <b>서비스를 직접</b> 두 번 부른다 — 전면 차단 필터를 우회한 자리에서도 멱등이어야
     * 겹치는 방어가 성립한다. HTTP로는 이 갈래를 만들 수 없다(두 번째가 필터에서 401로 끝난다).
     *
     * <p>재는 것이 둘인 이유: 로그 부재는 <b>결정적</b>이고, 탈퇴 시각은 <b>무엇이 나빠지는지</b>를
     * 말한다 — 두 번째 호출이 시각을 지금으로 밀면 보관 기한을 세는 쪽이 잘못된 날짜를 본다.
     * 시각만 재면 두 호출이 같은 마이크로초에 떨어질 때 헛통과할 수 있다.
     */
    @Test
    void 서비스를_직접_두_번_불러도_탈퇴_시각이_안_바뀐다() {
        User user = newUser();

        withdrawalService.withdraw(user.getId());
        Timestamp first = deletedAtOf(user);
        assertThat(first).as("첫 탈퇴가 안 됐으면 아래 단언은 아무것도 안 잰다").isNotNull();

        try (LogCaptor logs = new LogCaptor()) {
            withdrawalService.withdraw(user.getId());

            assertThat(logs.messages())
                    .as("두 번째 호출이 표를 또 고쳤다 — 멱등이 아니다")
                    .noneMatch(m -> m.startsWith("auth.withdrawal.completed"));
        }
        assertThat(deletedAtOf(user)).isEqualTo(first);
    }

    /**
     * 🔴 <b>롤백된 탈퇴는 「지웠다」를 남기지 않는다.</b> 그 줄은 커밋 뒤에만 찍혀야 한다 —
     * 개인정보 삭제 문의에서 이 한 줄이 <b>거짓 알리바이</b>가 되면 조사가 그 자리에서 멈춘다.
     *
     * <p><b>이 그물이 없으면 커밋 뒤 등록을 지워도 전부 초록이다</b>(주입 H로 확인). 다른 시험은
     * 전부 커밋이 성공하는 갈래라 「언제 찍히나」가 결과에 안 나타난다.
     *
     * <p>롤백을 만드는 법: 익명화가 쓰려는 {@code google_sub}({@code withdrawn:<번호>})를 다른 행이
     * 미리 차지하게 둔다. V101의 유일 제약이 <b>커밋 시 flush에서</b> 터지므로 등록해 둔 커밋 뒤
     * 콜백이 안 돈다. <b>운영에서 열리는 상태가 아니라</b> 롤백을 결정적으로 만드는 유일한 수단이다 —
     * 그래서 어떤 예외가 나가는지는 안 굳힌다(그것은 이 시험의 관심사가 아니다).
     */
    @Test
    void 롤백되면_지웠다는_로그가_안_남는다() {
        User victim = newUser();
        User squatter = newUser();
        jdbc.update("UPDATE users SET google_sub = ? WHERE id = ?",
                "withdrawn:" + victim.getId(), squatter.getId());

        try (LogCaptor logs = new LogCaptor()) {
            assertThatThrownBy(() -> withdrawalService.withdraw(victim.getId()))
                    .as("유일 제약이 안 터졌다 — 롤백이 없으면 아래 두 단언은 아무것도 안 잰다")
                    .isNotNull();

            assertThat(logs.messages())
                    .as("롤백됐는데 「지웠다」가 남았다 — 커밋 전에 찍고 있다")
                    .noneMatch(m -> m.startsWith("auth.withdrawal.completed"));
        }
        assertThat(deletedAtOf(victim))
                .as("롤백이 실제로 일어났는지를 표로 확인한다")
                .isNull();
    }

    /**
     * 🔴 <b>시각은 회원 행 락을 얻은 <u>뒤</u>에 잡아야 한다</b>(로컬 리뷰 라운드 1 R1-1).
     *
     * <p>락 앞에서 잡으면 <b>대기한 시간만큼 과거인 시각</b>이 회수 쿼리 넷과 익명화에 그대로 쓰인다.
     * 운영에서 그 대기를 만드는 것은 {@code ChzzkTokenRefresher}가 같은 회원 행 락을 쥔 채 치지직
     * HTTP(최대 7초)를 기다리는 자리다(auth/CLAUDE.md 「알려진 구멍」 10).
     *
     * <p><b>같은 저장소가 이미 고친 자리다</b> — {@code ChzzkLinkWriter.create}가 같은 이유로
     * {@code users.findByIdForUpdate} 뒤에서 {@code Instant.now()}를 잡는다. 그쪽 대가는
     * 「최신 행 조회가 살아있는 행을 안 준다」이고 여기 대가는 아래 둘이다.
     *
     * <p>여기서는 <b>{@code deleted_at} 하나</b>로 그 시각을 본다 — 회수 쿼리 넷이 모두 같은
     * {@code now}를 받으므로 한 자리만 재면 전부가 재어진다. 대가 쪽(만료된 코드를 소비 표시하는 것)은
     * {@code WithdrawalStreamKeyTest.락을_기다리는_사이_만료된_코드는_소비_표시하지_않는다}가 따로 잰다.
     *
     * <p>락을 1.5초 쥐고 여유를 1초로 잡았다. 고치기 전에는 {@code deleted_at}이 호출 직전 시각과
     * <b>몇 ms 안</b>이라 결정적으로 빨간불이고, 고친 뒤에는 1.5초 뒤라 결정적으로 초록이다.
     */
    @Test
    void 탈퇴_시각은_회원_행_락을_얻은_뒤에_잡는다() throws Exception {
        User user = newUser();
        AtomicReference<Instant> 부르기_직전 = new AtomicReference<>();

        회원_행을_잠근_채(user.getId(), Duration.ofMillis(1500), () -> {
            부르기_직전.set(Instant.now());
            withdrawalService.withdraw(user.getId());
        });

        assertThat(deletedAtOf(user))
                .as("탈퇴가 롤백됐다 — 아래 시각 단언이 아무것도 안 잰다")
                .isNotNull();
        assertThat(deletedAtOf(user).toInstant())
                .as("🔴 탈퇴 시각이 락을 기다린 만큼 과거다 — 시각을 락 앞에서 잡고 있다")
                .isAfter(부르기_직전.get().plusMillis(1000));
    }

    // ── 도구 ────────────────────────────────────────────────────────────

    private Timestamp deletedAtOf(User user) {
        return jdbc.queryForObject(
                "SELECT deleted_at FROM users WHERE id = ?", Timestamp.class, user.getId());
    }

    /** 개인정보 네 칸을 한 덩어리로 읽는다. 칸이 늘면 여기 한 줄만 더하면 된다. */
    private String personalDataOf(User user) {
        return jdbc.queryForObject("""
                SELECT coalesce(google_sub, '') || '|' || coalesce(email, '') || '|'
                       || coalesce(name, '') || '|' || coalesce(profile_image_url, '')
                FROM users WHERE id = ?
                """, String.class, user.getId());
    }

    private int aliveRefreshTokens(User user) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class, user.getId());
    }

    private int allRefreshTokens(User user) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE user_id = ?", Integer.class, user.getId());
    }
}
