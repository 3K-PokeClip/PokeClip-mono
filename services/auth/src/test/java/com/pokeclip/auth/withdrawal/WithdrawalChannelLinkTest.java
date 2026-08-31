package com.pokeclip.auth.withdrawal;

import com.pokeclip.auth.chzzk.ChzzkChannelLink;
import com.pokeclip.auth.chzzk.ChzzkCleanupExecutor;
import com.pokeclip.auth.chzzk.ChzzkLinkWriter;
import com.pokeclip.auth.chzzk.ChzzkMe;
import com.pokeclip.auth.chzzk.ChzzkTokens;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserService;
import com.pokeclip.auth.youtube.YoutubeChannel;
import com.pokeclip.auth.youtube.YoutubeChannelLink;
import com.pokeclip.auth.youtube.YoutubeCleanupExecutor;
import com.pokeclip.auth.youtube.YoutubeLinkWriter;
import com.pokeclip.auth.youtube.YoutubeTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 탈퇴가 <b>채널 연동 둘</b>을 닫는지 잰다(PRD D8·D9).
 *
 * <p><b>새 해제 코드를 쓰지 않는다</b> — 기존 {@code ChzzkLinkWriter.revoke}·{@code YoutubeLinkWriter.revoke}를
 * 그대로 부른다. 그래야 <b>각자의 정책이 자동으로 따라온다</b>: 치지직은 커밋 뒤 토큰 무효화를 보내고,
 * 유튜브는 안 보낸다(구글 revoke가 계정 단위라 남의 동의까지 죽인다 — POK-121 결정).
 * 비밀값 삭제와 정리 스레드도 그쪽 것이 이미 등록한다.
 *
 * <p>🔴 <b>표만 재지 않고 창구까지 잰다.</b> 「행이 닫혔다」는 표의 사실이고, 우리가 막으려는 것은
 * <b>워커가 탈퇴자의 채널 토큰을 계속 받아 가는 것</b>이다. 그 둘 사이에 {@code resolve}가 있어서
 * 표를 닫아도 창구가 열려 있으면 아무것도 안 막힌다 — {@code WithdrawalStreamKeyTest}가
 * 스트림키에서 같은 이유로 {@code /internal/stream-keys/resolve}까지 갔다.
 *
 * <p><b>탈퇴 전을 먼저 잰다.</b> 「토큰이 안 나온다」만 재면 연동이 심어지지 않았을 때도 초록이다.
 *
 * <p>🔴 <b>{@code jsonPath().doesNotExist()}를 쓰지 않는다</b> — 값이 null인 키도 통과한다(POK-121 실측).
 * 응답을 <b>문자열로</b> 받아 토큰 원문이 들어 있는지 본다.
 *
 * <p>정리는 각자의 전용 스레드에서 돈다 — 비밀값을 세기 전에 <b>둘 다</b> 기다린다.
 */
class WithdrawalChannelLinkTest extends WithdrawalTestSupport {

    private static final String INTERNAL_TOKEN = "test-only-internal-token-32bytes-long!!";

    /** 치지직 resolve는 남은 수명이 12시간 미만이면 즉석 갱신을 시도한다. 그 갈래를 안 타게 넉넉히 준다. */
    private static final Duration CHZZK_ACCESS_TTL = Duration.ofHours(24);
    /** 유튜브 resolve의 요구 수명은 30분이다. 1시간이면 즉석 갱신을 안 탄다. */
    private static final Duration YOUTUBE_ACCESS_TTL = Duration.ofHours(1);

    private final ChzzkLinkWriter chzzkWriter;
    private final YoutubeLinkWriter youtubeWriter;
    private final ChzzkCleanupExecutor chzzkCleanup;
    private final YoutubeCleanupExecutor youtubeCleanup;

    WithdrawalChannelLinkTest(MockMvc mockMvc, UserService userService, TokenService tokenService,
                              JdbcTemplate jdbc, ChzzkLinkWriter chzzkWriter, YoutubeLinkWriter youtubeWriter,
                              ChzzkCleanupExecutor chzzkCleanup, YoutubeCleanupExecutor youtubeCleanup) {
        super(mockMvc, userService, tokenService, jdbc);
        this.chzzkWriter = chzzkWriter;
        this.youtubeWriter = youtubeWriter;
        this.chzzkCleanup = chzzkCleanup;
        this.youtubeCleanup = youtubeCleanup;
    }

    /** 가짜 서버는 static으로 공유된다 — 앞 클래스가 건 응답·카운터가 남아 있으면 「몇 번 갔나」가 오염된다. */
    @BeforeEach
    void 가짜_서버를_비운다() {
        CHZZK.reset();
        YOUTUBE.reset();
    }

    /**
     * 하위의 {@code @AfterEach}는 상위({@link WithdrawalTestSupport#심은_행을_거둔다})보다 <b>먼저</b> 돈다.
     * 그래서 여기서 기다리면 정리 스레드가 secrets를 지운 뒤에 행이 사라진다 — 반대면 정리가
     * 다음 클래스로 새어 그쪽 카운터를 흔든다.
     */
    @AfterEach
    void 정리가_끝나기를_기다린다() {
        awaitCleanup();
    }

    /**
     * 치지직 갈래. <b>토큰 무효화가 실제로 나가는 것</b>까지 잰다 — 우리 표만 닫고 끝내면
     * 그 토큰은 치지직에 살아남아 우리가 아닌 누구도 회수하지 않는다.
     *
     * <p>revoke가 <b>둘</b>인 이유: 치지직은 access·refresh를 한 세트로 무효화하지만 그것이 문서화된
     * 동작이 아니라 {@code ChzzkTokenDiscarder}가 둘 다 부른다.
     */
    @Test
    void 탈퇴하면_치지직_연동이_닫히고_수집기가_토큰을_더는_못_받는다() throws Exception {
        User user = newUser();
        ChzzkChannelLink link = linkChzzk(user);

        assertThat(resolveBody(chzzkResolve(user)))
                .as("탈퇴 전에 토큰이 안 나오면 아래 「안 나온다」는 처음부터 참이라 아무것도 안 잰다")
                .contains("\"valid\":true").contains("at-old");

        withdraw(user);
        awaitCleanup();

        assertThat(revokedAt("chzzk_channel_links", link.getId()))
                .as("🔴 치지직 연동이 안 닫혔다").isNotNull();
        assertThat(revokeReason("chzzk_channel_links", link.getId()))
                .as("사유는 기존 해제와 같아야 한다 — 새 사유를 만들면 그쪽 정리·상태 파생이 갈린다")
                .isEqualTo("USER_UNLINKED");
        assertThat(resolveBody(chzzkResolve(user)))
                .as("🔴 탈퇴자의 치지직 토큰이 아직 수집기에 나간다")
                .contains("\"valid\":false").contains("UNLINKED").doesNotContain("at-old");
        assertThat(CHZZK.revokedTokens())
                .as("🔴 우리 표만 닫혔고 토큰은 치지직에 살아 있다")
                .containsExactlyInAnyOrder("at-old", "rt-old");
    }

    /**
     * 유튜브 갈래. 치지직과 <b>따로</b> 재는 이유는 해제 줄 하나를 지웠을 때 그 갈래만 빨간불이어야
     * 「둘이 각각 재어진다」가 성립하기 때문이다.
     *
     * <p>⚠️ <b>「철회 0건」은 이 카드의 코드를 재지 않는다.</b> {@code YoutubeLinkWriter}는 <b>어느 경로에서도</b>
     * 구글 revoke를 안 부른다(POK-121 결정) — 이 단언은 짝인 치지직 단언의 <b>대조군</b>일 뿐이고,
     * 「갈래가 따로 재어진다」의 근거로 쓰면 안 된다. 그 근거는 위의 표·창구 단언이다.
     */
    @Test
    void 탈퇴하면_유튜브_연동이_닫히고_업로드_워커가_토큰을_더는_못_받는다() throws Exception {
        User user = newUser();
        YoutubeChannelLink link = linkYoutube(user);

        assertThat(resolveBody(youtubeResolve(user)))
                .as("탈퇴 전에 토큰이 안 나오면 아래 「안 나온다」는 처음부터 참이라 아무것도 안 잰다")
                .contains("\"valid\":true").contains("yt-at-old");

        withdraw(user);
        awaitCleanup();

        assertThat(revokedAt("youtube_channel_links", link.getId()))
                .as("🔴 유튜브 연동이 안 닫혔다").isNotNull();
        assertThat(revokeReason("youtube_channel_links", link.getId()))
                .as("사유는 기존 해제와 같아야 한다").isEqualTo("USER_UNLINKED");
        assertThat(resolveBody(youtubeResolve(user)))
                .as("🔴 탈퇴자의 유튜브 토큰이 아직 업로드 워커에 나간다")
                .contains("\"valid\":false").contains("UNLINKED").doesNotContain("yt-at-old");
        assertThat(YOUTUBE.revokeCalls())
                .as("구글에 철회를 보내면 그 계정이 우리 앱에 준 동의 전부가 죽는다 — 남의 연동까지 끊긴다")
                .isZero();
    }

    /**
     * 🔴 <b>연동 넷의 비밀값이 한 톨도 안 남는다.</b> {@code secrets} 표에는 회원 칸이 없어
     * {@code SELECT count(*)}로 재면 다른 시험이 남긴 값에 섞인다 — <b>탈퇴 전에 그 회원의 ref를
     * 읽어 두고</b> 그 ref들로만 센다.
     *
     * <p>「있었다」를 먼저 재는 것이 요지다. ref를 못 읽었거나 연동이 안 심어졌으면 「0건」은
     * 자동으로 참이다.
     *
     * <p><b>스트림키 비밀값은 여기서 안 센다</b> — 탈퇴는 그것을 아직 안 지운다(태스크 7).
     */
    @Test
    void 탈퇴하면_두_연동의_비밀값이_한_톨도_안_남는다() throws Exception {
        User user = newUser();
        linkChzzk(user);
        linkYoutube(user);
        List<String> refs = linkSecretRefs(user);

        assertThat(refs).as("연동 둘이 비밀값 넷을 만든다 — 못 읽으면 아래가 자동으로 참이 된다").hasSize(4);
        assertThat(remainingSecrets(refs)).as("심은 비밀값이 표에 없다 — 아래 「0건」이 아무것도 안 잰다").isEqualTo(4);

        withdraw(user);
        awaitCleanup();

        assertThat(remainingSecrets(refs))
                .as("🔴 탈퇴한 회원의 채널 토큰 원문이 창고에 남았다")
                .isZero();
    }

    /**
     * 치지직은 access·refresh를 <b>한 세트로</b> 무효화한다 — 첫 revoke가 성공하면 둘째는
     * {@code 401 INVALID_TOKEN}을 받는다. 그것은 「무효화 목적 달성」이지 실패가 아니다.
     *
     * <p>여기서는 <b>둘 다</b> 401로 만들어 「이미 죽어 있던 토큰」을 재현한다. 탈퇴는 끝나야 하고
     * 표·창구·비밀값 어느 것도 그 4xx에 끌려가면 안 된다 — 무효화는 커밋 뒤 best-effort다.
     */
    @Test
    void 이미_죽은_치지직_토큰이라_무효화가_거절돼도_탈퇴는_끝난다() throws Exception {
        User user = newUser();
        ChzzkChannelLink link = linkChzzk(user);
        List<String> refs = linkSecretRefs(user);
        CHZZK.revokeResponds(401, "{\"code\":401,\"message\":\"INVALID_TOKEN\"}");

        withdraw(user);
        awaitCleanup();

        assertThat(CHZZK.revokeCalls()).as("무효화를 시도조차 안 했다면 4xx 갈래가 안 재어졌다").isEqualTo(2);
        assertThat(revokedAt("chzzk_channel_links", link.getId()))
                .as("🔴 무효화의 4xx가 표 변경을 되돌렸다 — 커밋 뒤 best-effort여야 한다").isNotNull();
        assertThat(remainingSecrets(refs)).as("🔴 무효화가 거절되자 비밀값 삭제까지 건너뛰었다").isZero();
    }

    /**
     * 🔴 <b>남의 연동은 한 톨도 안 건드린다.</b> 두 {@code revokeAlive}가 회원 범위를 잃으면
     * <b>탈퇴 한 건이 전 회원의 채널 연동을 끊는다</b> — 응답은 204고 탈퇴자 쪽 단언은 전부 초록이라
     * <b>조용하다.</b> 스트림키·페어링에서 같은 자리를 이미 한 번 메웠다
     * ({@code WithdrawalStreamKeyTest.남의_스트림키와_코드는_안_건드린다}).
     *
     * <p>기존 시험이 <b>간접적으로는</b> 잡는다 — 주입 실측(2026-08-31)에서 두 쿼리의 {@code userId}
     * 조건을 지우자 풀 고갈 시험 둘이 「고아 secret 30건」으로 깨졌다(첫 해제가 25명 전부의 행을 닫아
     * 나머지 24명분 정리가 등록되지 않는다). <b>증상이 원인에서 멀어</b> 읽는 사람이 회원 범위를
     * 떠올리기 어렵고, <b>탈퇴 경로에는 그 그물이 하나도 없었다.</b>
     *
     * <p>마지막 두 갈래로 <b>남의 워커가 아직 토큰을 받는다</b>까지 잰다. 표의 {@code revoked_at}이
     * 비어 있는 것만으로는 「안 건드렸다」와 「원래 그렇다」가 갈리지 않는다.
     */
    @Test
    void 남의_채널_연동은_안_건드린다() throws Exception {
        User withdrawing = newUser();
        linkChzzk(withdrawing);
        linkYoutube(withdrawing);
        User bystander = newUser();
        ChzzkChannelLink otherChzzk = linkChzzk(bystander, "at-other", "rt-other");
        YoutubeChannelLink otherYoutube = linkYoutube(bystander, "yt-at-other", "yt-rt-other");

        withdraw(withdrawing);
        awaitCleanup();

        assertThat(revokedAt("chzzk_channel_links", otherChzzk.getId()))
                .as("🔴 남의 치지직 연동이 닫혔다 — revokeAlive가 회원 범위를 잃었다").isNull();
        assertThat(revokedAt("youtube_channel_links", otherYoutube.getId()))
                .as("🔴 남의 유튜브 연동이 닫혔다 — revokeAlive가 회원 범위를 잃었다").isNull();
        assertThat(resolveBody(chzzkResolve(bystander)))
                .as("🔴 남의 수집기가 토큰을 못 받게 됐다").contains("\"valid\":true").contains("at-other");
        assertThat(resolveBody(youtubeResolve(bystander)))
                .as("🔴 남의 업로드 워커가 토큰을 못 받게 됐다").contains("\"valid\":true").contains("yt-at-other");
    }

    /**
     * 연동이 없는 회원이 대부분이다. 해제가 그 갈래에서 터지면 <b>탈퇴 자체가 막힌다</b> —
     * 두 {@code revoke}는 살아있는 행이 없으면 아무것도 안 하는 것이 계약이다(204 멱등).
     */
    @Test
    void 연동이_하나도_없는_회원이_탈퇴해도_실패하지_않는다() throws Exception {
        User user = newUser();

        withdraw(user);
        awaitCleanup();

        assertThat(CHZZK.revokeCalls()).as("없는 연동을 무효화하려 들었다").isZero();
        assertThat(YOUTUBE.revokeCalls()).isZero();
    }

    // ── 도구 ────────────────────────────────────────────────────────────

    private void withdraw(User user) throws Exception {
        mockMvc.perform(delete("/api/auth/me").header("Authorization", bearer(user)))
                .andExpect(status().isNoContent());
    }

    /** 정리 스레드가 <b>둘</b>이다. 태스크 7이 탈퇴 전용 스레드를 더하면 여기에 한 줄이 는다. */
    private void awaitCleanup() {
        assertThat(chzzkCleanup.awaitIdle(Duration.ofSeconds(5)))
                .as("치지직 커밋 뒤 정리가 5초 안에 안 끝났다").isTrue();
        assertThat(youtubeCleanup.awaitIdle(Duration.ofSeconds(5)))
                .as("유튜브 커밋 뒤 정리가 5초 안에 안 끝났다").isTrue();
    }

    /** 치지직을 거치지 않고 「이미 연동된 회원」을 만든다. 채널은 매번 다르다(살아있는 채널에 유일 제약). */
    private ChzzkChannelLink linkChzzk(User user) {
        return linkChzzk(user, "at-old", "rt-old");
    }

    /**
     * 토큰을 인자로 받는 갈래. <b>남의 연동과 내 연동에 같은 토큰을 주면 안 된다</b> —
     * 창구 응답에서 「누구 것이 나왔나」가 갈리지 않아 「남의 것은 그대로다」가 자동으로 참이 된다.
     */
    private ChzzkChannelLink linkChzzk(User user, String accessToken, String refreshToken) {
        return chzzkWriter.create(user.getId(), new ChzzkMe("chan-" + UUID.randomUUID(), "채널"),
                new ChzzkTokens(accessToken, refreshToken, CHZZK_ACCESS_TTL, "chat"));
    }

    private YoutubeChannelLink linkYoutube(User user) {
        return linkYoutube(user, "yt-at-old", "yt-rt-old");
    }

    private YoutubeChannelLink linkYoutube(User user, String accessToken, String refreshToken) {
        return youtubeWriter.create(user.getId(), new YoutubeChannel("UC-" + UUID.randomUUID(), "채널"),
                new YoutubeTokens(accessToken, refreshToken, YOUTUBE_ACCESS_TTL, "upload"));
    }

    /** 이 회원의 연동 표 둘이 가리키는 비밀값 자리 넷. 폐기된 행도 센다 — 「안 지웠다」가 거기서 보인다. */
    private List<String> linkSecretRefs(User user) {
        return jdbc.queryForList("""
                SELECT access_token_ref AS ref FROM chzzk_channel_links WHERE user_id = ?
                UNION ALL SELECT refresh_token_ref FROM chzzk_channel_links WHERE user_id = ?
                UNION ALL SELECT access_token_ref FROM youtube_channel_links WHERE user_id = ?
                UNION ALL SELECT refresh_token_ref FROM youtube_channel_links WHERE user_id = ?
                """, String.class, user.getId(), user.getId(), user.getId(), user.getId());
    }

    /** 미리 읽어 둔 ref로만 센다. 호출부가 {@code refs}의 개수를 먼저 단언하므로 빈 목록으로 오지 않는다. */
    private int remainingSecrets(List<String> refs) {
        String placeholders = String.join(",", Collections.nCopies(refs.size(), "?"));
        return jdbc.queryForObject("SELECT count(*) FROM secrets WHERE ref IN (" + placeholders + ")",
                Integer.class, refs.toArray());
    }

    private Timestamp revokedAt(String table, Long linkId) {
        return jdbc.queryForObject("SELECT revoked_at FROM " + table + " WHERE id = ?", Timestamp.class, linkId);
    }

    private String revokeReason(String table, Long linkId) {
        return jdbc.queryForObject("SELECT revoke_reason FROM " + table + " WHERE id = ?", String.class, linkId);
    }

    private ResultActions chzzkResolve(User user) throws Exception {
        return internalResolve("/internal/chzzk-link/resolve", user);
    }

    private ResultActions youtubeResolve(User user) throws Exception {
        return internalResolve("/internal/youtube-link/resolve", user);
    }

    private ResultActions internalResolve(String path, User user) throws Exception {
        return mockMvc.perform(post(path)
                .header("X-Internal-Token", INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":" + user.getId() + "}"));
    }

    private String resolveBody(ResultActions actions) throws Exception {
        return actions.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
}
