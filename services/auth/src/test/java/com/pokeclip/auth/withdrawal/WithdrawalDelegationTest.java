package com.pokeclip.auth.withdrawal;

import com.pokeclip.auth.delegation.DelegationService;
import com.pokeclip.auth.delegation.EditorInvitation;
import com.pokeclip.auth.delegation.InvitationService;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 탈퇴가 <b>이 회원이 낀 위임과 살아있는 초대를 양쪽 방향 모두</b> 닫는지 잰다(PRD D5·D9).
 *
 * <p>🔴 <b>양쪽 방향을 갈래마다 따로 잰다.</b> 두 쿼리의 조건이
 * {@code (streamerId = :userId OR editorId = :userId)}·{@code (streamerId = :userId OR inviteeId = :userId)}로
 * <b>한 줄에 두 갈래</b>라, 한 갈래만 재면 나머지 갈래를 지워도 초록이다. 태스크 4·5가 정확히 그 자리에서
 * 두 번 비어 있었다(스트림키·연동 둘의 {@code revokeAlive}에서 {@code userId} 조건을 지워도 전부 초록).
 * 주입으로 확인했다 — {@code OR d.editorId}만 지우면 <b>편집자 탈퇴 갈래만</b>,
 * {@code OR i.inviteeId}만 지우면 <b>받은 초대 갈래만</b> 빨간불이다.
 *
 * <p>🔴 <b>표만 재지 않고 clip이 묻는 창구까지 잰다.</b> 「행이 닫혔다」는 표의 사실이고, 우리가 막으려는
 * 것은 <b>탈퇴자가 남의 방송을 계속 편집하는 것</b>이다. 그 둘 사이에 {@code /internal/editor-delegations}이
 * 있다({@code WithdrawalStreamKeyTest}·{@code WithdrawalChannelLinkTest}가 같은 이유로 창구까지 갔다).
 *
 * <p>🔴 <b>본인 방송의 {@code OWNER}는 그대로인 것도 함께 못박는다.</b> 그 창구는 회원 표를 안 읽고
 * 번호가 같으면 OWNER를 준다(ADR-047) — <b>그것이 정상이다.</b> 탈퇴자를 막는 것은 그 판정이 아니라
 * 앞단의 전면 차단 필터고, 내부 창구는 서버끼리 쓰는 별도 체인이라 애초에 거기 안 걸린다.
 * 이 사실을 시험이 안 잡아 두면 다음 사람이 「탈퇴자에게 OWNER가 나온다」를 결함으로 읽고
 * 창구에 회원 표 조회를 더한다 — 그러면 clip 요청마다 조회가 하나 늘고 ADR-047이 깨진다.
 */
class WithdrawalDelegationTest extends WithdrawalTestSupport {

    private static final String INTERNAL_TOKEN = "test-only-internal-token-32bytes-long!!";

    private final InvitationService invitationService;
    private final DelegationService delegationService;

    WithdrawalDelegationTest(MockMvc mockMvc, UserService userService, TokenService tokenService,
                             JdbcTemplate jdbc, InvitationService invitationService,
                             DelegationService delegationService) {
        super(mockMvc, userService, tokenService, jdbc);
        this.invitationService = invitationService;
        this.delegationService = delegationService;
    }

    /**
     * 스트리머 갈래. 편집자를 <b>둘</b> 두는 이유는 「하나만 닫고 끝나는」 구현이 하나짜리 시험에서
     * 초록이기 때문이다 — 조건부 UPDATE가 아니라 단건 조회로 짜면 그렇게 된다.
     */
    @Test
    void 스트리머가_탈퇴하면_그_편집자들의_위임이_전부_닫히고_사유가_탈퇴다() throws Exception {
        User streamer = newUser();
        Long 위임1 = 위임(streamer, newUser());
        Long 위임2 = 위임(streamer, newUser());

        assertThat(위임_끊긴_시각(위임1))
                .as("탈퇴 전에 이미 닫혀 있으면 아래 「닫혔다」는 처음부터 참이라 아무것도 안 잰다").isNull();
        assertThat(위임_끊긴_시각(위임2)).isNull();

        withdraw(streamer);

        assertThat(위임_끊긴_시각(위임1)).as("🔴 스트리머가 탈퇴했는데 위임이 살아 있다").isNotNull();
        assertThat(위임_끊긴_시각(위임2)).as("🔴 편집자 둘 중 하나만 닫혔다").isNotNull();
        assertThat(위임_끊은_주체(위임1))
                .as("사람이 내보낸 것과 계정이 사라진 것은 다른 사건이다 — STREAMER로 적으면 편집자 화면에 「쫓겨남」으로 보인다")
                .isEqualTo("WITHDRAWAL");
        assertThat(위임_끊은_주체(위임2)).isEqualTo("WITHDRAWAL");
    }

    /**
     * 🔴 <b>편집자 갈래.</b> 이 갈래가 쿼리의 {@code OR d.editorId = :userId} 한 조각을 재는
     * 유일한 자리다 — 지우면 여기만 빨간불이 되고 위 스트리머 갈래는 초록으로 남는다(주입 확인).
     */
    @Test
    void 편집자가_탈퇴하면_그_스트리머들에_대한_위임이_전부_닫힌다() throws Exception {
        User editor = newUser();
        Long 위임1 = 위임(newUser(), editor);
        Long 위임2 = 위임(newUser(), editor);

        assertThat(위임_끊긴_시각(위임1)).isNull();
        assertThat(위임_끊긴_시각(위임2)).isNull();

        withdraw(editor);

        assertThat(위임_끊긴_시각(위임1)).as("🔴 편집자가 탈퇴했는데 위임이 살아 있다 — 남의 방송을 계속 편집한다").isNotNull();
        assertThat(위임_끊긴_시각(위임2)).as("🔴 스트리머 둘 중 하나만 닫혔다").isNotNull();
        assertThat(위임_끊은_주체(위임1))
                .as("편집자가 나간 것과 계정이 사라진 것도 다른 사건이다").isEqualTo("WITHDRAWAL");
        assertThat(위임_끊은_주체(위임2)).isEqualTo("WITHDRAWAL");
    }

    /** 보낸 쪽 갈래. 스트리머가 사라졌는데 초대함에 그 초대가 남아 있으면 수락 시 위임이 생긴다. */
    @Test
    void 탈퇴하면_보낸_초대가_거둬들여진다() throws Exception {
        User streamer = newUser();
        Long 초대1 = 초대(streamer, newUser());
        Long 초대2 = 초대(streamer, newUser());

        assertThat(초대_상태(초대1)).as("탈퇴 전에 PENDING이 아니면 아래 단언이 자동으로 참이 된다").isEqualTo("PENDING");
        assertThat(초대_상태(초대2)).isEqualTo("PENDING");

        withdraw(streamer);

        assertThat(초대_상태(초대1)).as("🔴 탈퇴한 스트리머의 초대가 초대함에 살아 있다").isEqualTo("CANCELED");
        assertThat(초대_상태(초대2)).as("🔴 보낸 초대 둘 중 하나만 거둬들여졌다").isEqualTo("CANCELED");
        assertThat(초대_응답_시각(초대1)).as("거둬들인 시각이 안 찍혔다").isNotNull();
    }

    /**
     * 🔴 <b>받은 쪽 갈래이고, 상태가 {@code DECLINED}가 아니라 {@code CANCELED}다</b>(PRD D9).
     * {@code DECLINED}로 적으면 스트리머 화면에 <b>「그 사람이 거절했다」</b>로 보이는데,
     * 사람이 한 응답과 계정이 사라진 것은 다른 사건이다 — 위임에 {@code WITHDRAWAL}을 만든 이유와 같다.
     *
     * <p>이 갈래가 쿼리의 {@code OR i.inviteeId = :userId} 한 조각을 재는 유일한 자리다.
     */
    @Test
    void 받은_초대도_거절이_아니라_거둬들여진다() throws Exception {
        User invitee = newUser();
        Long 초대1 = 초대(newUser(), invitee);
        Long 초대2 = 초대(newUser(), invitee);

        assertThat(초대_상태(초대1)).isEqualTo("PENDING");
        assertThat(초대_상태(초대2)).isEqualTo("PENDING");

        withdraw(invitee);

        assertThat(초대_상태(초대1)).as("🔴 탈퇴한 사람의 초대함에 초대가 살아 있다").isEqualTo("CANCELED");
        assertThat(초대_상태(초대2)).as("🔴 받은 초대 둘 중 하나만 거둬들여졌다").isEqualTo("CANCELED");
        assertThat(초대_상태(초대1))
                .as("🔴 DECLINED로 적으면 스트리머 화면에 「거절함」으로 보인다 — 사람이 안 한 응답이다")
                .isNotEqualTo("DECLINED");
    }

    /**
     * 🔴 <b>이미 끝난 이력을 덮어쓰지 않는다 — 두 쿼리가 같은 뿌리다.</b>
     *
     * <p>위임은 {@code revokedAt IS NULL}이, 초대는 {@code status = PENDING}이 막는다.
     * 한쪽만 막으면 쫓겨난 편집자가 탈퇴해서 「내가 나간 것」으로 이력을 고치거나,
     * <b>수락해서 위임까지 만든 초대가 「취소됨」으로 되돌아간다</b> — 그러면 살아있는 위임의
     * 부모 초대가 CANCELED인 모순이 남는다.
     */
    @Test
    void 이미_끝난_위임과_초대의_이력을_안_덮어쓴다() throws Exception {
        User streamer = newUser();
        User editor = newUser();
        Long invitationId = 초대(streamer, editor);
        invitationService.accept(editor.getId(), invitationId);
        Long delegationId = 위임_번호(streamer, editor);
        delegationService.revoke(streamer.getId(), delegationId);

        Timestamp 끊긴_시각 = 위임_끊긴_시각(delegationId);
        assertThat(끊긴_시각).as("먼저 끊어 두지 못하면 아래가 아무것도 안 잰다").isNotNull();
        assertThat(위임_끊은_주체(delegationId)).isEqualTo("STREAMER");
        assertThat(초대_상태(invitationId)).isEqualTo("ACCEPTED");

        withdraw(editor);

        assertThat(위임_끊은_주체(delegationId))
                .as("🔴 쫓겨난 편집자가 탈퇴해서 「내가 나갔다」로 이력을 고쳤다").isEqualTo("STREAMER");
        assertThat(위임_끊긴_시각(delegationId))
                .as("🔴 끊긴 시각이 탈퇴 시각으로 밀렸다 — 언제 권한이 사라졌는지가 거짓이 된다")
                .isEqualTo(끊긴_시각);
        assertThat(초대_상태(invitationId))
                .as("🔴 수락까지 끝난 초대가 「취소됨」으로 되돌아갔다").isEqualTo("ACCEPTED");
    }

    /**
     * clip이 묻는 판정이 실제로 갈리는지 본다. 표가 아니라 <b>창구의 답</b>이 바뀌어야 막힌 것이다.
     *
     * <p>🔴 <b>본인 방송의 OWNER는 그대로다</b> — 창구가 회원 표를 안 읽기 때문이고 그것이 정상이다
     * (클래스 주석 참고). 탈퇴자의 사람 요청은 앞단 필터가 401로 막는다.
     */
    @Test
    void 탈퇴하면_clip이_묻는_편집_자격만_사라지고_본인_방송의_주인은_그대로다() throws Exception {
        User streamer = newUser();
        User editor = newUser();
        위임(streamer, editor);

        assertThat(relationOf(editor, streamer))
                .as("탈퇴 전에 EDITOR가 아니면 아래 NONE은 처음부터 참이다").isEqualTo("EDITOR");
        assertThat(accessibleOf(editor)).contains("\"streamerUserId\":" + streamer.getId());

        withdraw(editor);

        assertThat(relationOf(editor, streamer))
                .as("🔴 탈퇴자가 clip에서 아직 편집자다 — 남의 방송을 계속 편집한다").isEqualTo("NONE");
        assertThat(accessibleOf(editor))
                .as("🔴 볼 수 있는 스트리머 목록에 남의 방송이 남았다")
                .doesNotContain("\"streamerUserId\":" + streamer.getId());
        assertThat(relationOf(editor, editor))
                .as("본인 방송의 OWNER는 그대로다 — 이 창구는 회원 표를 안 읽는다(ADR-047). 막는 것은 앞단 필터다")
                .isEqualTo("OWNER");
        assertThat(accessibleOf(editor))
                .as("본인 한 줄은 남는다 — 같은 이유다").contains("\"streamerUserId\":" + editor.getId());
    }

    /**
     * 🔴 <b>회원 조건이 통째로 빠지면 여기서만 잡힌다.</b> 탈퇴자 쪽 단언은 전부 초록인 채로
     * <b>전 회원의 위임과 초대가 닫힌다</b> — 보안 회귀가 아니라 가용성 회귀라 조용하다.
     * 태스크 4·5가 스트림키·연동에서 같은 그물을 쳤다(「같은 뿌리인데 한 자리만」).
     */
    @Test
    void 남의_위임과_초대는_안_건드린다() throws Exception {
        User user = newUser();
        위임(user, newUser());
        초대(user, newUser());

        User 남의_스트리머 = newUser();
        User 남의_편집자 = newUser();
        Long 남의_위임 = 위임(남의_스트리머, 남의_편집자);
        Long 남의_초대 = 초대(남의_스트리머, newUser());

        withdraw(user);

        assertThat(위임_끊긴_시각(남의_위임)).as("🔴 남의 위임이 닫혔다 — 회원 조건을 잃었다").isNull();
        assertThat(초대_상태(남의_초대)).as("🔴 남의 초대가 거둬들여졌다").isEqualTo("PENDING");
        assertThat(relationOf(남의_편집자, 남의_스트리머))
                .as("🔴 남의 편집자가 clip에서 자격을 잃었다").isEqualTo("EDITOR");
    }

    /** 관계가 없는 회원이 대부분이다. 정리가 그 갈래에서 터지면 <b>탈퇴 자체가 막힌다</b> — 0행은 무해해야 한다. */
    @Test
    void 관계가_하나도_없는_회원이_탈퇴해도_실패하지_않는다() throws Exception {
        User user = newUser();

        withdraw(user);

        assertThat(위임_수(user)).isZero();
        assertThat(초대_수(user)).isZero();
    }

    // ── 도구 ────────────────────────────────────────────────────────────

    private void withdraw(User user) throws Exception {
        mockMvc.perform(delete("/api/auth/me").header("Authorization", bearer(user)))
                .andExpect(status().isNoContent());
    }

    /** 프로덕션 경로로 만든다 — 표에 직접 INSERT하면 실물과 다른 모양을 재게 된다. */
    private Long 초대(User streamer, User invitee) {
        EditorInvitation row = invitationService.invite(streamer.getId(), invitee.getEmail());
        return row.getId();
    }

    private Long 위임(User streamer, User editor) {
        Long invitationId = 초대(streamer, editor);
        invitationService.accept(editor.getId(), invitationId);
        return 위임_번호(streamer, editor);
    }

    private Long 위임_번호(User streamer, User editor) {
        return jdbc.queryForObject(
                "SELECT id FROM editor_delegations WHERE streamer_id = ? AND editor_id = ?",
                Long.class, streamer.getId(), editor.getId());
    }

    private Timestamp 위임_끊긴_시각(Long delegationId) {
        return jdbc.queryForObject("SELECT revoked_at FROM editor_delegations WHERE id = ?",
                Timestamp.class, delegationId);
    }

    private String 위임_끊은_주체(Long delegationId) {
        return jdbc.queryForObject("SELECT revoked_by FROM editor_delegations WHERE id = ?",
                String.class, delegationId);
    }

    private String 초대_상태(Long invitationId) {
        return jdbc.queryForObject("SELECT status FROM editor_invitations WHERE id = ?",
                String.class, invitationId);
    }

    private Timestamp 초대_응답_시각(Long invitationId) {
        return jdbc.queryForObject("SELECT responded_at FROM editor_invitations WHERE id = ?",
                Timestamp.class, invitationId);
    }

    private int 위임_수(User user) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM editor_delegations WHERE streamer_id = ? OR editor_id = ?",
                Integer.class, user.getId(), user.getId());
    }

    private int 초대_수(User user) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM editor_invitations WHERE streamer_id = ? OR invitee_id = ?",
                Integer.class, user.getId(), user.getId());
    }

    private String relationOf(User user, User streamer) throws Exception {
        String body = mockMvc.perform(post("/internal/editor-delegations/resolve")
                        .header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + user.getId()
                                + ",\"streamerUserId\":" + streamer.getId() + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"relation\":\"([A-Z]+)\".*", "$1");
    }

    private String accessibleOf(User user) throws Exception {
        return mockMvc.perform(post("/internal/editor-delegations/accessible")
                        .header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + user.getId() + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
}
