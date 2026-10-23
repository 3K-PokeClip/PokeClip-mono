package com.pokeclip.auth.soop.api;

import com.pokeclip.auth.soop.SoopChannelLink;
import com.pokeclip.auth.soop.SoopChannelLinkRepository;
import com.pokeclip.auth.soop.SoopCleanupExecutor;
import com.pokeclip.auth.soop.SoopLinkStateCodec;
import com.pokeclip.auth.soop.SoopLinkTestSupport;
import com.pokeclip.auth.soop.SoopIdentity;
import com.pokeclip.auth.soop.SoopLinkException;
import com.pokeclip.auth.soop.SoopLinkWriter;
import com.pokeclip.auth.soop.SoopRefreshBrake;
import com.pokeclip.auth.soop.SoopTokens;
import com.pokeclip.auth.soop.RevokeReason;
import com.pokeclip.auth.streamkey.secret.SecretStore;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SoopLinkConflictTest extends SoopLinkTestSupport {

    SoopLinkConflictTest(MockMvc mockMvc, UserService userService, UserRepository userRepository,
                         TokenService tokenService, SoopLinkStateCodec codec,
                         SoopChannelLinkRepository linkRepository, SecretStore secretStore,
                         SoopLinkWriter writer, JdbcTemplate jdbc, SoopCleanupExecutor cleanup,
                    SoopRefreshBrake brake) {
        super(mockMvc, userService, userRepository, tokenService, codec, linkRepository, secretStore, writer, jdbc, cleanup, brake);
    }

    /** 다른 계정 중복은 앞선 조회(로그 위생)와 DB 부분 유니크가 막는다. 롤백돼 우리 표·secrets는 그대로다. */
    @Test
    void 다른_계정에_묶인_방송국이면_409이고_우리_표와_secrets는_그대로다() throws Exception {
        User first = newUser();
        User second = newUser();
        mockMvc.perform(link(first)).andExpect(status().isCreated());   // 기본 아이디가 first에 묶임
        List<Map<String, Object>> before = jdbc.queryForList("SELECT * FROM soop_channel_links ORDER BY id");
        List<Map<String, Object>> secretsBefore = jdbc.queryForList("SELECT ref FROM secrets ORDER BY ref");

        try (LogCaptor logs = new LogCaptor()) {
            mockMvc.perform(link(second)).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.reason").value("CHANNEL_ALREADY_LINKED"));
            assertThat(logs.messages()).contains("auth.soop.link.token_orphaned userId=" + second.getId());
        }
        awaitCleanup();
        assertThat(jdbc.queryForList("SELECT * FROM soop_channel_links ORDER BY id")).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT ref FROM secrets ORDER BY ref")).isEqualTo(secretsBefore);
        assertThat(linkRepository.findByUserIdAndRevokedAtIsNull(second.getId())).isEmpty();
    }

    /**
     * 중복은 DB 유니크까지 가기 전에 조회로 걸러야 한다: 유니크 위반이면 Hibernate가 {@code Key (soop_user_id)=(…)}를
     * 로그에 찍는다(그 로거를 켜는 순간). HTTP 결과는 둘 다 409라 같으므로 writer가 무엇을 던지는지로 잰다.
     */
    @Test
    void 중복은_DB_유니크가_아니라_사전_조회가_거른다() {
        User first = newUser();
        User second = newUser();
        SoopChannelLink taken = linked(first, Duration.ofHours(8));

        assertThatThrownBy(() -> writer.create(second.getId(), new SoopIdentity(taken.getSoopUserId(), "닉네임"),
                new SoopTokens("at", "rt", Duration.ofHours(8), null)))
                .isInstanceOf(SoopLinkException.class);
    }

    /**
     * put 뒤에 저장이 실패하면 secrets도 같이 롤백된다(put이 REQUIRED라 이 트랜잭션에 딸려 있다, 감사 1회차 경미 2).
     * 사전 조회 409는 put 전에 던져 이 성질을 안 지나므로, INSERT 자체를 DB에서 실패시킨다: 닉네임 없는 살아 있는
     * 행은 V116 CHECK가 막는다. 경합 409(DB 유니크)와 같은 자리, 같은 롤백이다.
     */
    @Test
    void put_뒤_저장이_실패하면_secrets도_롤백된다() {
        User user = newUser();
        int secretsBefore = jdbc.queryForObject("SELECT count(*) FROM secrets", Integer.class);

        assertThatThrownBy(() -> writer.create(user.getId(), new SoopIdentity("soop-rollback", null),
                new SoopTokens("at", "rt", Duration.ofHours(8), null)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM secrets", Integer.class))
                .as("🔴 저장은 실패했는데 토큰 원문이 secrets에 남았다: 가리키는 행이 없어 영영 안 지워진다")
                .isEqualTo(secretsBefore);
        assertThat(linkRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId())).isEmpty();
    }

    /**
     * 같은 회원 재연동: 옛 행은 USER_UNLINKED로 닫히고 같은 문장에서 아이디·닉네임이 비고(약관 ⑰), 옛 secrets는
     * 커밋 뒤 정리에서 사라진다. 같은 아이디로 다시 붙어도 유니크에 안 걸린다(닫기가 INSERT보다 먼저 나간다, C1).
     */
    @Test
    void 같은_회원이_다시_연동하면_옛_행은_닫히고_비워지며_옛_secrets는_정리된다() throws Exception {
        User user = newUser();
        mockMvc.perform(link(user)).andExpect(status().isCreated());
        SoopChannelLink old = linkRepository.findByUserIdAndRevokedAtIsNull(user.getId()).orElseThrow();

        try (LogCaptor logs = new LogCaptor()) {
            mockMvc.perform(link(user)).andExpect(status().isCreated());
            awaitCleanup();
            assertThat(logs.messages()).contains("auth.soop.link.relinked userId=" + user.getId());
        }

        Map<String, Object> closed = jdbc.queryForMap(
                "SELECT soop_user_id, nickname, revoked_at, revoke_reason FROM soop_channel_links WHERE id = ?",
                old.getId());
        assertThat(closed.get("revoke_reason")).isEqualTo(RevokeReason.USER_UNLINKED.name());
        assertThat(closed.get("revoked_at")).isNotNull();
        assertThat(closed.get("soop_user_id")).isNull();
        assertThat(closed.get("nickname")).isNull();
        assertThat(secretStore.get(old.getAccessTokenRef())).isEmpty();
        assertThat(secretStore.get(old.getRefreshTokenRef())).isEmpty();

        SoopChannelLink now = linkRepository.findByUserIdAndRevokedAtIsNull(user.getId()).orElseThrow();
        assertThat(now.getId()).isNotEqualTo(old.getId());
        assertThat(secretStore.get(now.getAccessTokenRef())).contains("at-2");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM secrets", Integer.class)).isEqualTo(2);
    }
}
