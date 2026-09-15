package com.pokeclip.auth.audiotrack.api;

import com.pokeclip.auth.AuthException;
import com.pokeclip.auth.AuthFailure;
import com.pokeclip.auth.audiotrack.AudioTrackLabelRepository;
import com.pokeclip.auth.audiotrack.AudioTrackLabelService;
import com.pokeclip.auth.delegation.DelegationTestSupport;
import com.pokeclip.auth.delegation.EditorDelegationRepository;
import com.pokeclip.auth.delegation.EditorInvitationRepository;
import com.pokeclip.auth.delegation.InvitationStatus;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 오디오 트랙 이름 창구 셋(POK-240). 위임 시험 지원을 쓴다 — 읽는 자격이 위임 표이고, 그 표를 만드는
 * 헬퍼가 거기 있다.
 */
class AudioTrackLabelControllerTest extends DelegationTestSupport {

    private final AudioTrackLabelService service;
    private final AudioTrackLabelRepository repository;
    private final TransactionTemplate tx;

    AudioTrackLabelControllerTest(MockMvc mockMvc, UserService userService, UserRepository userRepository,
                                  TokenService tokenService, EditorInvitationRepository invitations,
                                  EditorDelegationRepository delegations, JdbcTemplate jdbc,
                                  AudioTrackLabelService service, AudioTrackLabelRepository repository,
                                  TransactionTemplate tx) {
        super(mockMvc, userService, userRepository, tokenService, invitations, delegations, jdbc);
        this.service = service;
        this.repository = repository;
        this.tx = tx;
    }

    @Test
    void 처음엔_여섯_칸이_전부_비어_있다() throws Exception {
        User streamer = newUser();

        mockMvc.perform(get("/api/auth/me/audio-tracks").header("Authorization", "Bearer " + accessTokenOf(streamer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.labels.length()").value(6))
                .andExpect(jsonPath("$.labels[0]").doesNotExist());
    }

    /** 저장한 것이 그대로 읽힌다. 앞뒤 공백은 잘리고, 잘라서 비면 이름 없음(null)이다 — 오류가 아니다. */
    @Test
    void 저장하고_다시_읽는다() throws Exception {
        User streamer = newUser();

        mockMvc.perform(저장한다(streamer, "[\" 마이크 \", \"게임 사운드\", null, \"\", \"　\", \"디스코드\"]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.labels[0]").value("마이크"))
                .andExpect(jsonPath("$.labels[1]").value("게임 사운드"))
                .andExpect(jsonPath("$.labels[2]").doesNotExist())
                .andExpect(jsonPath("$.labels[3]").doesNotExist())
                .andExpect(jsonPath("$.labels[4]").doesNotExist())
                .andExpect(jsonPath("$.labels[5]").value("디스코드"));

        mockMvc.perform(get("/api/auth/me/audio-tracks").header("Authorization", "Bearer " + accessTokenOf(streamer)))
                .andExpect(jsonPath("$.labels[0]").value("마이크"))
                .andExpect(jsonPath("$.labels[5]").value("디스코드"));
        assertThat(rows(streamer)).as("이름 없는 칸은 행이 없다 — 세 개만").isEqualTo(3);
    }

    /** PUT은 여섯을 통째로 덮는다 — 비운 칸의 행이 실제로 사라져야 「덮는다」다. */
    @Test
    void 다시_저장하면_전부_덮고_비운_칸의_행은_사라진다() throws Exception {
        User streamer = newUser();
        mockMvc.perform(저장한다(streamer, "[\"마이크\", \"게임\", \"디스코드\", null, null, null]")).andExpect(status().isOk());

        mockMvc.perform(저장한다(streamer, "[\"마이크\", null, null, null, null, \"BGM\"]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.labels[1]").doesNotExist())
                .andExpect(jsonPath("$.labels[5]").value("BGM"));

        assertThat(rows(streamer)).isEqualTo(2);
    }

    @Test
    void 여섯_칸이_아니면_400이다() throws Exception {
        User streamer = newUser();

        mockMvc.perform(저장한다(streamer, "[\"a\", \"b\", \"c\", \"d\", \"e\"]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("LABELS_SIZE"));
        mockMvc.perform(저장한다(streamer, "[\"a\", \"b\", \"c\", \"d\", \"e\", \"f\", \"g\"]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("LABELS_SIZE"));
        mockMvc.perform(저장한다(streamer, "\"배열이 아니다\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("LABELS_SIZE"));
        assertThat(rows(streamer)).as("거절된 요청이 표를 건드리면 안 된다").isZero();
    }

    /** 길이는 코드 포인트다 — 이모지 32개는 통과하고 33개는 거절. {@code String.length}로 재면 16개에서 막힌다. */
    @Test
    void 길이는_코드_포인트_32자다() throws Exception {
        User streamer = newUser();
        String 서른둘 = "😀".repeat(32);
        String 서른셋 = "😀".repeat(33);

        mockMvc.perform(저장한다(streamer, "[\"" + 서른둘 + "\", null, null, null, null, null]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.labels[0]").value(서른둘));
        mockMvc.perform(저장한다(streamer, "[\"" + 서른셋 + "\", null, null, null, null, null]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("LABEL_TOO_LONG"));
    }

    /**
     * 🔴 가운데 NUL은 트림에 안 걸리고 길이도 통과한 뒤 <b>PostgreSQL이 저장을 거절해 500</b>이 된다
     * (PR #184 codex P2 — 표시 이름이 PR #135에서 겪은 것과 같은 자리). 개행도 같은 판정이다(목록 화면이 깨진다).
     * 앞뒤 제어문자는 트림이 자르므로 오류가 아니다 — 그것까지 거절하면 표시 이름과 판정이 갈린다.
     */
    @Test
    void 가운데_제어문자는_400이고_앞뒤는_잘린다() throws Exception {
        User streamer = newUser();

        mockMvc.perform(저장한다(streamer, "[\"마이\\u0000크\", null, null, null, null, null]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("LABEL_INVALID"));
        mockMvc.perform(저장한다(streamer, "[\"마이\\n크\", null, null, null, null, null]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("LABEL_INVALID"));
        assertThat(rows(streamer)).isZero();

        mockMvc.perform(저장한다(streamer, "[\"\\t마이크\\n\", null, null, null, null, null]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.labels[0]").value("마이크"));
    }

    /**
     * 🔴 <b>같은 회원의 첫 저장 둘이 겹치면 「지우고 넣는다」만으로는 직렬화되지 않는다</b>(PR #184 codex P2).
     * DELETE가 둘 다 0행이라 서로를 안 막는다 — 같은 트랙이면 뒤쪽이 PK 위반(500), 다른 트랙이면 둘의 칸이
     * <b>섞인 채</b> 커밋된다. 문서가 약속한 것은 「뒤에 커밋한 쪽이 통째로 이긴다」다.
     *
     * <p>앞 저장을 <b>커밋 직전에 멈춰 세운 채</b> 뒤 저장을 보낸다 — 타이밍에 기대는 반복 대신 결정적으로 겹친다.
     * 앞은 트랙 1·2에, 뒤는 트랙 1·3에 적으므로 락이 없으면 트랙 1에서 PK 위반이 나거나 트랙 2가 섞여 남는다.
     * 주입으로 확인: 리포지토리의 어드바이저리 락 한 줄을 지우면 이 시험이 {@code DuplicateKeyException}으로 빨간불이다.
     */
    @Test
    void 겹친_저장은_뒤에_커밋한_쪽이_통째로_이긴다() throws Exception {
        User streamer = newUser();
        List<String> 앞 = Arrays.asList("앞-마이크", "앞-게임", null, null, null, null);
        List<String> 뒤 = Arrays.asList("뒤-마이크", null, "뒤-디스코드", null, null, null);
        CountDownLatch 앞이_썼다 = new CountDownLatch(1);
        CountDownLatch 앞을_커밋해라 = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> 앞_저장 = pool.submit(() -> tx.executeWithoutResult(status -> {
                repository.replace(streamer.getId(), 앞, Instant.now());
                앞이_썼다.countDown();
                try {
                    if (!앞을_커밋해라.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("커밋 신호가 안 왔다");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }));
            assertThat(앞이_썼다.await(10, TimeUnit.SECONDS)).as("앞 저장이 표에 쓰지 못했다").isTrue();

            Future<List<String>> 뒤_저장 = pool.submit(() -> service.update(streamer.getId(), 뒤, Instant.now()));
            // 뒤 저장이 락 앞에서 기다리는 동안 앞을 커밋한다. 잠깐 두는 이유는 뒤가 실제로 락 자리까지 갔는지
            // 확인할 길이 없어서다 — 이 대기가 없어도 결과 단언은 같다(락이 없으면 어느 순서든 깨진다).
            Thread.sleep(Duration.ofMillis(300));
            앞을_커밋해라.countDown();
            앞_저장.get(30, TimeUnit.SECONDS);

            assertThat(뒤_저장.get(30, TimeUnit.SECONDS)).isEqualTo(뒤);
        } finally {
            앞을_커밋해라.countDown();
            pool.shutdownNow();
        }

        List<String> 남은_것 = repository.find(streamer.getId());
        assertThat(남은_것).as("앞의 트랙 2가 남아 있으면 둘이 섞인 것이다").isEqualTo(뒤);
        assertThat(rows(streamer)).isEqualTo(2);
    }

    /**
     * 🔴 <b>이 카드가 막는 것: 로그인만 하면 남의 트랙 이름을 읽는 것.</b> 남남과 없는 회원이 같은 404다 —
     * 갈리면 번호를 넣어 보는 것만으로 회원의 실재를 안다. 양성 대조: 위임을 주면 같은 주소가 200이다.
     */
    @Test
    void 편집자는_읽고_남남은_없는_회원과_같은_404다() throws Exception {
        User streamer = newUser();
        User editor = newUser();
        User stranger = newUser();
        mockMvc.perform(저장한다(streamer, "[\"마이크\", null, null, null, null, null]")).andExpect(status().isOk());

        MvcResult 남남 = mockMvc.perform(get("/api/streamers/" + streamer.getId() + "/audio-tracks")
                        .header("Authorization", "Bearer " + accessTokenOf(stranger)))
                .andExpect(status().isNotFound()).andReturn();
        MvcResult 없는_회원 = mockMvc.perform(get("/api/streamers/999999999/audio-tracks")
                        .header("Authorization", "Bearer " + accessTokenOf(stranger)))
                .andExpect(status().isNotFound()).andReturn();
        assertThat(남남.getResponse().getContentAsString())
                .as("두 404가 갈리면 번호 탐색으로 회원의 실재를 안다")
                .isEqualTo(없는_회원.getResponse().getContentAsString()).contains("STREAMER_NOT_FOUND");

        grant(streamer, editor);
        mockMvc.perform(get("/api/streamers/" + streamer.getId() + "/audio-tracks")
                        .header("Authorization", "Bearer " + accessTokenOf(editor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.labels[0]").value("마이크"));

        // 본인도 이 문으로 자기 것을 읽을 수 있다(relationOf가 OWNER).
        mockMvc.perform(get("/api/streamers/" + streamer.getId() + "/audio-tracks")
                        .header("Authorization", "Bearer " + accessTokenOf(streamer)))
                .andExpect(status().isOk());
    }

    /** 위임이 끊기면 그 순간부터 못 읽는다 — 자격이 표를 매번 본다는 증거. */
    @Test
    void 위임이_끊기면_편집자도_404다() throws Exception {
        User streamer = newUser();
        User editor = newUser();
        Long id = grant(streamer, editor);
        mockMvc.perform(get("/api/streamers/" + streamer.getId() + "/audio-tracks")
                .header("Authorization", "Bearer " + accessTokenOf(editor))).andExpect(status().isOk());

        jdbc.update("UPDATE editor_delegations SET revoked_at = now() WHERE id = ?", id);

        mockMvc.perform(get("/api/streamers/" + streamer.getId() + "/audio-tracks")
                        .header("Authorization", "Bearer " + accessTokenOf(editor)))
                .andExpect(status().isNotFound());
    }

    /**
     * 입구 필터를 지난 뒤 탈퇴가 커밋되는 창을 재현한다 — 서비스를 직접 부른다(창구로는 필터가 먼저 401을
     * 내서 이 갈래가 안 밟힌다). POK-171의 「회원에게 무언가를 새로 만들어 주는 경로」 규칙.
     */
    @Test
    void 탈퇴한_회원은_쓰기_직전에도_막힌다() {
        User leaving = newUser();
        jdbc.update("UPDATE users SET deleted_at = now() WHERE id = ?", leaving.getId());

        assertThatThrownBy(() -> service.update(leaving.getId(), Arrays.asList("마이크", null, null, null, null, null), Instant.now()))
                .isInstanceOf(AuthException.class)
                .extracting(e -> ((AuthException) e).failure())
                .isEqualTo(AuthFailure.WITHDRAWN_ACCOUNT);
        assertThat(rows(leaving)).isZero();
    }

    @Test
    void 토큰이_없으면_401이다() throws Exception {
        mockMvc.perform(get("/api/auth/me/audio-tracks")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/streamers/1/audio-tracks")).andExpect(status().isUnauthorized());
    }

    // ── 도우미 ──────────────────────────────────────────────────

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder 저장한다(User user, String labelsJson) {
        return put("/api/auth/me/audio-tracks")
                .header("Authorization", "Bearer " + accessTokenOf(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"labels\":" + labelsJson + "}");
    }

    /** {@code DelegationListAndRevokeTest.grant}와 같다 — 초대 → 수락으로 위임을 만든다. */
    private Long grant(User streamer, User editor) throws Exception {
        mockMvc.perform(invite(streamer, editor.getEmail())).andExpect(status().isCreated());
        Long invitationId = invitations.findByStreamerIdAndInviteeIdAndStatus(
                streamer.getId(), editor.getId(), InvitationStatus.PENDING).orElseThrow().getId();
        mockMvc.perform(post("/api/editor-invitations/" + invitationId + "/accept")
                .header("Authorization", "Bearer " + accessTokenOf(editor))).andExpect(status().isNoContent());
        return delegations.findByStreamerIdAndRevokedAtIsNullOrderByGrantedAtDesc(streamer.getId())
                .get(0).getId();
    }

    private int rows(User user) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM audio_track_labels WHERE user_id = ?", Integer.class, user.getId());
        return n == null ? 0 : n;
    }
}
