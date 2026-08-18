package com.pokeclip.auth.delegation.api;

import com.pokeclip.auth.delegation.DelegationTestSupport;
import com.pokeclip.auth.delegation.EditorDelegation;
import com.pokeclip.auth.delegation.EditorDelegationRepository;
import com.pokeclip.auth.delegation.EditorInvitation;
import com.pokeclip.auth.delegation.EditorInvitationRepository;
import com.pokeclip.auth.delegation.InvitationService;
import com.pokeclip.auth.delegation.InvitationStatus;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InvitationCreateTest extends DelegationTestSupport {

    private final InvitationService invitationService;

    InvitationCreateTest(MockMvc mockMvc, UserService userService, UserRepository userRepository,
                         TokenService tokenService, EditorInvitationRepository invitations,
                         EditorDelegationRepository delegations, JdbcTemplate jdbc,
                         InvitationService invitationService) {
        super(mockMvc, userService, userRepository, tokenService, invitations, delegations, jdbc);
        this.invitationService = invitationService;
    }

    @Test
    void 가입된_이메일로_초대하면_상대의_초대함에_들어간다() throws Exception {
        User streamer = newUser();
        User invitee = newUser();

        mockMvc.perform(invite(streamer, invitee.getEmail()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));

        assertThat(invitations.findByStreamerIdAndInviteeIdAndStatus(
                streamer.getId(), invitee.getId(), InvitationStatus.PENDING)).isPresent();
    }

    /** 카드 완료조건 — 왜 안 됐는지 응답에 남는다. */
    @Test
    void 가입_안_된_이메일이면_초대가_안_만들어지고_사유가_남는다() throws Exception {
        User streamer = newUser();

        mockMvc.perform(invite(streamer, "nobody@example.com"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.reason").value("INVITEE_NOT_FOUND"));

        assertThat(invitations.findByStreamerIdOrderByCreatedAtDesc(streamer.getId())).isEmpty();
    }

    @Test
    void 대문자를_섞어_쳐도_같은_계정을_찾는다() throws Exception {
        User streamer = newUser();
        User invitee = newUser();

        mockMvc.perform(invite(streamer, invitee.getEmail().toUpperCase()))
                .andExpect(status().isCreated());

        assertThat(invitations.findByStreamerIdAndInviteeIdAndStatus(
                streamer.getId(), invitee.getId(), InvitationStatus.PENDING)).isPresent();
    }

    @Test
    void 자기_자신은_초대할_수_없다() throws Exception {
        User streamer = newUser();

        mockMvc.perform(invite(streamer, streamer.getEmail()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("SELF_INVITE"));
    }

    /**
     * 재초대는 새 행이 아니라 기한 연장이다. 사물함에는 항상 한 장만 있다.
     *
     * <p><b>기준값을 과거로 심고 등호 없는 isAfter로 잰다.</b> 두 초대가 같은 밀리초에
     * 들어가면 기한이 같아서, isAfterOrEqualTo로는 extend가 0행이거나 아예 안 불려도
     * 초록이 된다 — 아무것도 재지 않는 단언이 된다.
     */
    @Test
    void 살아있는_초대가_있으면_새_행이_안_생기고_기한만_밀린다() throws Exception {
        User streamer = newUser();
        User invitee = newUser();
        mockMvc.perform(invite(streamer, invitee.getEmail())).andExpect(status().isCreated());
        EditorInvitation first = invitations.findByStreamerIdAndInviteeIdAndStatus(
                streamer.getId(), invitee.getId(), InvitationStatus.PENDING).orElseThrow();
        Instant staleExpiry = Instant.now().minusSeconds(3600);
        jdbc.update("UPDATE editor_invitations SET expires_at = ? WHERE id = ?",
                Timestamp.from(staleExpiry), first.getId());

        mockMvc.perform(invite(streamer, invitee.getEmail())).andExpect(status().isCreated());

        assertThat(invitations.findByStreamerIdOrderByCreatedAtDesc(streamer.getId())).hasSize(1);
        EditorInvitation after = invitations.findById(first.getId()).orElseThrow();
        assertThat(after.getExpiresAt()).isAfter(staleExpiry);
    }

    @Test
    void 이미_편집자인_사람은_다시_초대할_수_없다() throws Exception {
        User streamer = newUser();
        User editor = newUser();
        delegations.save(EditorDelegation.of(streamer.getId(), editor.getId(), seedInvitation(streamer, editor), Instant.now()));

        mockMvc.perform(invite(streamer, editor.getEmail()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("ALREADY_EDITOR"));
    }

    /**
     * <b>순차 경로만 잰다.</b> 「어떤 경우에도 20을 넘지 않는다」가 아니다 —
     * 상한 검사는 「세고 나서 쓴다」라 동시 요청에서는 넘는다(서로 다른 상대 8명 동시 초대 →
     * 살아있는 초대 27개, 실측). 그걸 주장하게 쓰면 순차는 초록인데 동시 경로는 넘으므로
     * <b>통과하는데 재지 않는 테스트</b>가 된다. 근거는 MAX_PENDING 주석에 있다.
     */
    @Test
    void 순차로_초대하면_스물한_번째가_거절된다() throws Exception {
        User streamer = newUser();
        for (int i = 0; i < 20; i++) {
            mockMvc.perform(invite(streamer, newUser().getEmail())).andExpect(status().isCreated());
        }

        mockMvc.perform(invite(streamer, newUser().getEmail()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("TOO_MANY_PENDING"));
    }

    /**
     * <b>만료된 초대를 되살려 상한을 넘을 수 없다.</b> pendingBetween은 status만 보므로
     * 만료된 행도 돌려주는데, 그 행을 연장하는 것도 <b>살아있는 자리를 새로 차지하는 것</b>이다.
     * 상한 검사를 안 지나가면 만료 행을 쌓아 두고 순차로 임의로 늘릴 수 있다
     * (codex PR #79 P2 — 상한이 무의미해진다).
     */
    @Test
    void 만료된_초대를_되살려_상한을_넘을_수_없다() throws Exception {
        User streamer = newUser();
        User stale = newUser();
        // 만료된 PENDING 하나를 먼저 만들어 둔다(상한에 안 세어진다).
        mockMvc.perform(invite(streamer, stale.getEmail())).andExpect(status().isCreated());
        Long staleId = invitations.findByStreamerIdAndInviteeIdAndStatus(
                streamer.getId(), stale.getId(), InvitationStatus.PENDING).orElseThrow().getId();
        jdbc.update("UPDATE editor_invitations SET expires_at = now() - interval '1 day' WHERE id = ?", staleId);
        for (int i = 0; i < 20; i++) {
            mockMvc.perform(invite(streamer, newUser().getEmail())).andExpect(status().isCreated());
        }

        mockMvc.perform(invite(streamer, stale.getEmail()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("TOO_MANY_PENDING"));
    }

    /**
     * <b>경계</b> — 살아있는 초대를 연장하는 것은 상한에 걸리지 않는다.
     * 그 초대가 이미 자기 자리를 세어져 있기 때문이다. 이게 없으면 위 수정이
     * 「20개 차면 재초대도 막힌다」로 과하게 조여진다.
     */
    @Test
    void 상한이_찼어도_살아있는_초대의_재초대는_된다() throws Exception {
        User streamer = newUser();
        User first = newUser();
        mockMvc.perform(invite(streamer, first.getEmail())).andExpect(status().isCreated());
        for (int i = 0; i < 19; i++) {
            mockMvc.perform(invite(streamer, newUser().getEmail())).andExpect(status().isCreated());
        }

        mockMvc.perform(invite(streamer, first.getEmail()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));

        assertThat(invitations.findByStreamerIdOrderByCreatedAtDesc(streamer.getId())).hasSize(20);
    }

    /**
     * 부분 유니크 인덱스가 하나만 통과시키고, 진 쪽은 상대가 만든 행을 읽어 기한을 민다.
     * <b>둘 다 성공해야 하고 사물함에는 한 장만 남아야 한다</b> — 연타·재전송과 결과가 같다.
     *
     * <p>MockMvc가 아니라 서비스를 직접 부른다. 여기서 재는 것은 HTTP가 아니라
     * 저장 경합이고, MockMvc 인스턴스를 여러 스레드에서 공유하지 않기 위해서다.
     */
    @Test
    void 같은_상대를_동시에_초대해도_둘_다_성공하고_행은_하나다() throws Exception {
        User streamer = newUser();
        User invitee = newUser();
        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    invitationService.invite(streamer.getId(), invitee.getEmail());
                } catch (Throwable t) {
                    failures.add(t);
                }
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        assertThat(failures).isEmpty();
        assertThat(invitations.findByStreamerIdOrderByCreatedAtDesc(streamer.getId())).hasSize(1);
    }

    /** 위임 행은 초대를 참조하므로 먼저 하나 만들어 둔다. */
    private Long seedInvitation(User streamer, User invitee) {
        return invitations.save(EditorInvitation.of(
                streamer.getId(), invitee.getId(), Instant.now().plusSeconds(60), Instant.now())).getId();
    }
}
