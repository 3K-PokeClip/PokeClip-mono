package com.pokeclip.auth.withdrawal;

import com.pokeclip.auth.AuthException;
import com.pokeclip.auth.chzzk.ChzzkCleanupExecutor;
import com.pokeclip.auth.chzzk.ChzzkLinkWriter;
import com.pokeclip.auth.chzzk.ChzzkMe;
import com.pokeclip.auth.chzzk.ChzzkTokens;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserService;
import com.pokeclip.auth.youtube.YoutubeChannel;
import com.pokeclip.auth.youtube.YoutubeCleanupExecutor;
import com.pokeclip.auth.youtube.YoutubeLinkWriter;
import com.pokeclip.auth.youtube.YoutubeTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * <b>탈퇴한 뒤에 도착한 채널 연동 저장</b>을 잰다(PR #148 codex C3).
 *
 * <p>🔴 <b>넷 중 창이 가장 넓다.</b> {@code ChzzkLinkService}가 {@code writer.create} <b>앞에서</b>
 * 외부 HTTP를 둘 부른다(교환 → me). 각각 connect 2s + read 5s라 <b>최대 십수 초</b>이고,
 * 그동안 탈퇴가 커밋되면 그 뒤에 저장이 도달한다.
 *
 * <p>결과가 나쁜 방식이 특히 고약하다 — 그 연동은 <b>표 기준으로 살아있는 행</b>이라
 * {@code /internal/**} resolve 계열이 그대로 보고, 그 행이 가리키는 {@code secrets}(OAuth 원문 둘)는
 * 탈퇴 정리가 이미 지나가서 <b>영구 고아</b>다. 게다가 회원은 그 연동을 <b>볼 수도 끊을 수도 없다</b> —
 * {@code GET}·{@code DELETE /api/chzzk-link}가 전면 차단 필터에 401로 막힌다.
 *
 * <p>🔴 <b>여기는 「창이 남는다」가 없다.</b> 두 writer가 이미 회원 행 락을 잡고 있어서
 * 락과 함께 확인하는 것으로 바꾸면 탈퇴와 <b>직렬화</b>된다({@code FOR NO KEY UPDATE}끼리는 충돌한다) —
 * 조회가 하나도 안 늘고 경합 창도 안 남는 유일한 갈래다.
 *
 * <p>🔴 <b>{@code revoke}에는 같은 확인을 넣으면 안 된다.</b> 탈퇴가 {@code withdraw(now)} <b>전에</b>
 * 그 둘을 부르므로 지금은 {@code deleted_at}이 아직 비어 통과하지만, 익명화를 앞으로 옮기는 변경이
 * 오면 <b>탈퇴가 자기 가드에 막힌다.</b> 아래 마지막 검사가 그 의존을 못박는다.
 */
class WithdrawnWriteGuardChannelLinkTest extends WithdrawalTestSupport {

    private static final Duration ACCESS_TTL = Duration.ofHours(24);

    /** 스파이 빈은 컨텍스트 수준에서 교체되므로 필드로 둔다 — 생성자 주입 대상이 아니다. */
    @MockitoSpyBean ChzzkLinkWriter chzzkWriterSpy;

    private final ChzzkLinkWriter chzzkWriter;
    private final YoutubeLinkWriter youtubeWriter;
    private final ChzzkCleanupExecutor chzzkCleanup;
    private final YoutubeCleanupExecutor youtubeCleanup;
    private final WithdrawalCleanupExecutor cleanup;

    WithdrawnWriteGuardChannelLinkTest(MockMvc mockMvc, UserService userService, TokenService tokenService,
                                       JdbcTemplate jdbc, ChzzkLinkWriter chzzkWriter,
                                       YoutubeLinkWriter youtubeWriter, ChzzkCleanupExecutor chzzkCleanup,
                                       YoutubeCleanupExecutor youtubeCleanup,
                                       WithdrawalCleanupExecutor cleanup) {
        super(mockMvc, userService, tokenService, jdbc);
        this.chzzkWriter = chzzkWriter;
        this.youtubeWriter = youtubeWriter;
        this.chzzkCleanup = chzzkCleanup;
        this.youtubeCleanup = youtubeCleanup;
        this.cleanup = cleanup;
    }

    /** 상위의 행 거두기보다 먼저 돈다 — 정리 스레드가 secrets를 지운 뒤에 행이 사라져야 한다. */
    @AfterEach
    void 정리_스레드를_기다린다() {
        chzzkCleanup.awaitIdle(Duration.ofSeconds(20));
        youtubeCleanup.awaitIdle(Duration.ofSeconds(20));
        cleanup.awaitIdle(Duration.ofSeconds(20));
    }

    @Test
    void 탈퇴한_회원에게는_치지직_연동을_안_만들어_준다() throws Exception {
        User user = newUser();
        withdraw(user);

        assertThatThrownBy(() -> chzzkWriter.create(user.getId(),
                new ChzzkMe("chan-" + UUID.randomUUID(), "채널"),
                new ChzzkTokens("at-late", "rt-late", ACCESS_TTL, "chat")))
                .isInstanceOf(AuthException.class);

        assertThat(aliveLinks("chzzk_channel_links", user))
                .as("🔴 탈퇴한 계정에 살아있는 치지직 연동이 생겼다 — resolve가 그것을 그대로 본다")
                .isZero();
        assertThat(linkSecrets(user))
                .as("🔴 OAuth 원문이 secrets에 남았다 — 탈퇴 정리는 이미 지나가 영구 고아다")
                .isZero();
    }

    /** 쌍둥이다. 한쪽만 고치면 갈린다 — 두 writer의 그 두 줄은 글자까지 같았다. */
    @Test
    void 탈퇴한_회원에게는_유튜브_연동을_안_만들어_준다() throws Exception {
        User user = newUser();
        withdraw(user);

        assertThatThrownBy(() -> youtubeWriter.create(user.getId(),
                new YoutubeChannel("UC-" + UUID.randomUUID(), "채널"),
                new YoutubeTokens("yt-at-late", "yt-rt-late", ACCESS_TTL, "upload")))
                .isInstanceOf(AuthException.class);

        assertThat(aliveLinks("youtube_channel_links", user))
                .as("🔴 탈퇴한 계정에 살아있는 유튜브 연동이 생겼다")
                .isZero();
        assertThat(linkSecrets(user))
                .as("🔴 OAuth 원문이 secrets에 남았다")
                .isZero();
    }

    /**
     * 🔴 <b>「{@code revoke}에 가드를 넣으면 안 된다」의 <u>전제</u>를 잰다 — 가드 자체가 아니다.</b>
     *
     * <p>처음에는 「{@code revoke}에도 넣으면 이 검사가 빨간불」이라고 적었는데 <b>주입해 보니 초록이었다.</b>
     * 이유가 분명하다: 탈퇴가 {@code revoke}를 부르는 시점에는 {@code deleted_at}이 <b>아직 비어 있어</b>
     * 가드가 통과시킨다. 즉 위험은 <b>오늘이 아니라 익명화를 앞으로 옮기는 날</b>에 온다.
     * <b>그래서 오늘 잴 수 있는 것은 그 순서뿐이다</b> — 그것을 여기서 잰다.
     *
     * <p>연동 해제가 불리는 <b>바로 그 순간</b> 표의 {@code deleted_at}을 읽는다. 같은 스레드·같은
     * 트랜잭션이라 아직 커밋되지 않은 값도 보인다. 이 값이 비어 있다는 것이
     * {@code ChzzkLinkWriter.revoke}·{@code YoutubeLinkWriter.revoke}의 「가드를 넣지 않는다」가
     * 기대고 있는 사실 전부다.
     *
     * <p>주입으로 확인했다: 탈퇴가 락 직후에 익명화를 한 번 더 하게 만들면 이 검사가 빨간불이다.
     */
    @Test
    void 연동_해제가_불릴_때_탈퇴_시각은_아직_안_찍혀_있다() throws Exception {
        User user = newUser();
        chzzkWriter.create(user.getId(), new ChzzkMe("chan-" + UUID.randomUUID(), "채널"),
                new ChzzkTokens("at-1", "rt-1", ACCESS_TTL, "chat"));
        youtubeWriter.create(user.getId(), new YoutubeChannel("UC-" + UUID.randomUUID(), "채널"),
                new YoutubeTokens("yt-at-1", "yt-rt-1", ACCESS_TTL, "upload"));
        assertThat(aliveLinks("chzzk_channel_links", user)).as("전제: 치지직 연동이 있다").isEqualTo(1);
        assertThat(aliveLinks("youtube_channel_links", user)).as("전제: 유튜브 연동이 있다").isEqualTo(1);

        AtomicReference<Timestamp> 해제_시점의_탈퇴_시각 = new AtomicReference<>();
        AtomicBoolean 불렸다 = new AtomicBoolean();
        doAnswer(call -> {
            불렸다.set(true);
            해제_시점의_탈퇴_시각.set(deletedAt(user));
            return call.callRealMethod();
        }).when(chzzkWriterSpy).revoke(eq(user.getId()), any());

        withdraw(user);

        assertThat(불렸다)
                .as("연동 해제가 아예 안 불렸다 — 아래 「비어 있다」는 처음부터 참이라 아무것도 안 잰다")
                .isTrue();
        assertThat(해제_시점의_탈퇴_시각.get())
                .as("🔴 연동 해제보다 익명화가 먼저 돌았다 — 그러면 두 writer의 revoke에 「살아있는 회원만」을 "
                        + "넣는 순간 탈퇴가 자기 가드에 막힌다")
                .isNull();
        assertThat(aliveLinks("chzzk_channel_links", user)).isZero();
        assertThat(aliveLinks("youtube_channel_links", user)).isZero();
    }

    // ── 도구 ────────────────────────────────────────────────────────────

    private void withdraw(User user) throws Exception {
        mockMvc.perform(delete("/api/auth/me").header("Authorization", bearer(user)))
                .andExpect(status().isNoContent());
    }

    /** 같은 스레드·같은 트랜잭션에서 읽으므로 아직 커밋되지 않은 값도 보인다. */
    private Timestamp deletedAt(User user) {
        return jdbc.queryForObject("SELECT deleted_at FROM users WHERE id = ?",
                Timestamp.class, user.getId());
    }

    private int aliveLinks(String table, User user) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class, user.getId());
    }

    /** 이 회원의 연동 표 둘이 가리키는 비밀값 수. 폐기된 행도 센다 — 「안 지웠다」가 거기서 보인다. */
    private int linkSecrets(User user) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM secrets s WHERE s.ref IN (
                    SELECT access_token_ref FROM chzzk_channel_links WHERE user_id = ?
                    UNION SELECT refresh_token_ref FROM chzzk_channel_links WHERE user_id = ?
                    UNION SELECT access_token_ref FROM youtube_channel_links WHERE user_id = ?
                    UNION SELECT refresh_token_ref FROM youtube_channel_links WHERE user_id = ?)
                """, Integer.class, user.getId(), user.getId(), user.getId(), user.getId());
    }
}
