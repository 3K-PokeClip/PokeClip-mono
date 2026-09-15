package com.pokeclip.auth.withdrawal;

import com.pokeclip.auth.audiotrack.AudioTrackLabelService;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 탈퇴가 <b>오디오 트랙 이름</b>(POK-240)을 지우는지 잰다(PR #184 codex P1).
 *
 * <p>🔴 표의 {@code ON DELETE CASCADE}만 믿으면 안 된다 — 탈퇴는 회원 행을 <b>지우지 않고 익명화</b>하므로
 * 그 CASCADE는 영영 안 돈다. 이 시험이 없으면 「CASCADE가 있으니 된다」로 읽고 회수 줄을 지워도 초록이다.
 * 실제로 첫 판이 그렇게 적혀 있었다(README 「탈퇴가 이 표를 지우지는 않는다」).
 *
 * <p>표식이 든 이름을 심는 이유는 {@code WithdrawalTest}와 같다 — 트랙 이름은 자유 입력이라 개인정보가
 * 들어올 수 있고, 탈퇴 뒤 그 글자가 표 어디에도 없어야 한다. 남을 회원(대조군)도 하나 둔다 —
 * {@code WHERE user_id}가 빠진 DELETE는 이 대조군이 잡는다.
 */
class WithdrawalAudioTrackTest extends WithdrawalTestSupport {

    private final AudioTrackLabelService audioTracks;

    WithdrawalAudioTrackTest(MockMvc mockMvc, UserService userService, TokenService tokenService,
                             JdbcTemplate jdbc, AudioTrackLabelService audioTracks) {
        super(mockMvc, userService, tokenService, jdbc);
        this.audioTracks = audioTracks;
    }

    @Test
    void 탈퇴하면_트랙_이름이_전부_지워지고_남은_회원_것은_그대로다() throws Exception {
        User leaving = newUser();
        User staying = newUser();
        // 이름은 32자 상한이라 UUID 전체가 안 들어간다 — 앞 12자면 우연히 겹칠 만큼 짧지 않다.
        String marker = marker().substring(0, 12);
        audioTracks.update(leaving.getId(), Arrays.asList("마이크 " + marker, null, "디스코드 " + marker, null, null, null), Instant.now());
        audioTracks.update(staying.getId(), Arrays.asList("남는 마이크", null, null, null, null, null), Instant.now());
        assertThat(rows(leaving)).isEqualTo(2);

        mockMvc.perform(delete("/api/auth/me").header("Authorization", bearer(leaving)))
                .andExpect(status().isNoContent());

        assertThat(rows(leaving)).as("탈퇴한 회원의 트랙 이름 행").isZero();
        Integer 표식_잔존 = jdbc.queryForObject(
                "SELECT count(*) FROM audio_track_labels WHERE label LIKE ?", Integer.class, "%" + marker + "%");
        assertThat(표식_잔존).as("탈퇴자가 적은 글자가 표에 남았다").isZero();
        assertThat(rows(staying)).as("남은 회원 것까지 지우면 WHERE가 빠진 것이다").isEqualTo(1);
        assertThat(audioTracks.mine(staying.getId()).get(0)).isEqualTo("남는 마이크");
    }

    private int rows(User user) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM audio_track_labels WHERE user_id = ?", Integer.class, user.getId());
        return n == null ? 0 : n;
    }
}
