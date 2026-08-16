package com.pokeclip.auth.chzzk.api;

import com.pokeclip.auth.chzzk.ChzzkChannelLink;
import com.pokeclip.auth.chzzk.ChzzkChannelLinkRepository;
import com.pokeclip.auth.chzzk.ChzzkLinkStateCodec;
import com.pokeclip.auth.chzzk.ChzzkLinkTestSupport;
import com.pokeclip.auth.chzzk.ChzzkCleanupExecutor;
import com.pokeclip.auth.chzzk.ChzzkLinkWriter;
import com.pokeclip.auth.chzzk.RevokeReason;
import com.pokeclip.auth.streamkey.secret.SecretStore;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChzzkRelinkTest extends ChzzkLinkTestSupport {

    ChzzkRelinkTest(MockMvc mockMvc, UserService userService, UserRepository userRepository,
                    TokenService tokenService, ChzzkLinkStateCodec codec,
                    ChzzkChannelLinkRepository linkRepository, SecretStore secretStore,
                    ChzzkLinkWriter writer, JdbcTemplate jdbc, ChzzkCleanupExecutor cleanup) {
        super(mockMvc, userService, userRepository, tokenService, codec, linkRepository, secretStore, writer, jdbc, cleanup);
    }

    @Test
    void 재동의하면_옛_행이_닫히고_새_행이_생기며_옛_토큰은_버려진다() throws Exception {
        User user = newUser();
        mockMvc.perform(link(user)).andExpect(status().isCreated());                 // at-1/rt-1
        ChzzkChannelLink old = linkRepository.findByUserIdAndRevokedAtIsNull(user.getId()).orElseThrow();
        String oldAccessRef = old.getAccessTokenRef();
        String oldRefreshRef = old.getRefreshTokenRef();
        CHZZK.reset();
        CHZZK.meResponds(200, "{\"code\":200,\"content\":{\"channelId\":\"chan-new\",\"channelName\":\"새채널\"}}");
        mockMvc.perform(link(user)).andExpect(status().isCreated()).andExpect(jsonPath("$.channelId").value("chan-new"));
        ChzzkChannelLink fresh = linkRepository.findByUserIdAndRevokedAtIsNull(user.getId()).orElseThrow();
        assertThat(fresh.getId()).isNotEqualTo(old.getId());
        ChzzkChannelLink closed = linkRepository.findById(old.getId()).orElseThrow();
        assertThat(closed.isRevoked()).isTrue();
        assertThat(closed.getRevokeReason()).isEqualTo(RevokeReason.USER_UNLINKED);
        awaitCleanup();   // 옛 secrets 삭제·revoke는 커밋 뒤 전용 스레드에서 — "결국" 된다
        assertThat(secretStore.get(oldAccessRef)).isEmpty();
        assertThat(secretStore.get(oldRefreshRef)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM secrets", Integer.class)).isEqualTo(2);
        assertThat(CHZZK.revokedTokens()).containsExactlyInAnyOrder("at-1", "rt-1");
    }

    /**
     * 두 번째 me가 다른 계정 채널이면 409 → 옛 것 무변경. 사전 조회(ChzzkLinkWriter.create)가 걸러
     * 트랜잭션이 롤백되고, afterCommit은 등록되기 전에 끝난다 — 이 테스트는 "옛 행·secrets가 그대로다"를
     * 재지 afterCommit 자체를 재지 않는다(감사 2회차). 사전 조회를 지운 DB 유니크 경로에서도 같은 결과다(실측).
     */
    @Test
    void 재동의가_롤백되면_옛_토큰은_그대로_산다() throws Exception {
        User a = newUser();
        User b = newUser();
        mockMvc.perform(link(a)).andExpect(status().isCreated());                    // a: chan-default (at-1)
        CHZZK.reset();
        CHZZK.meResponds(200, "{\"code\":200,\"content\":{\"channelId\":\"chan-b\",\"channelName\":\"b\"}}");
        mockMvc.perform(link(b)).andExpect(status().isCreated());                    // b: chan-b (at-1 after reset)
        CHZZK.reset();                                                                 // me → chan-default (a의 채널)
        mockMvc.perform(link(b)).andExpect(status().isConflict());                    // b가 a의 채널로 재연동 시도 → 롤백
        ChzzkChannelLink bLink = linkRepository.findByUserIdAndRevokedAtIsNull(b.getId()).orElseThrow();
        assertThat(bLink.getChannelId()).isEqualTo("chan-b");                         // 옛 행 살아 있음
        assertThat(secretStore.get(bLink.getAccessTokenRef())).isPresent();           // 옛 secrets 안 지워짐
        assertThat(jdbc.queryForObject("SELECT count(*) FROM secrets", Integer.class)).isEqualTo(4);   // a·b 것
        assertThat(CHZZK.revokedTokens()).containsExactlyInAnyOrder("at-1", "rt-1");  // 이번에 받은 고아만 버렸다(태스크 6)
    }
}
