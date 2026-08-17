package com.pokeclip.auth.chzzk.api;

import com.pokeclip.auth.chzzk.ChzzkChannelLink;
import com.pokeclip.auth.chzzk.ChzzkChannelLinkRepository;
import com.pokeclip.auth.chzzk.ChzzkLinkStateCodec;
import com.pokeclip.auth.chzzk.ChzzkLinkTestSupport;
import com.pokeclip.auth.chzzk.ChzzkCleanupExecutor;
import com.pokeclip.auth.chzzk.ChzzkLinkWriter;
import com.pokeclip.auth.chzzk.ChzzkMe;
import com.pokeclip.auth.chzzk.ChzzkTokens;
import com.pokeclip.auth.chzzk.RevokeReason;
import com.pokeclip.auth.streamkey.secret.SecretStore;
import com.pokeclip.auth.token.TokenService;
import com.pokeclip.auth.user.User;
import com.pokeclip.auth.user.UserRepository;
import com.pokeclip.auth.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

    /**
     * 재연동 요청 A가 치지직 HTTP에 걸려 있는 동안 다른 경로 B가 같은 회원의 행을 먼저 만들면, A의 새 행 created_at이
     * B보다 앞서면 안 된다 — GET 상태·resolve NOT_LINKED가 "회원별 최신 행"(created_at DESC)을 보므로 살아있는 행이
     * 최신 행이 아니게 된다(역전). created_at은 요청 시작 시각이 아니라 <b>락을 잡은 뒤</b>의 시각이어야 한다.
     */
    @Test
    void 재연동이_늦게_커밋돼도_살아있는_행이_회원별_최신_행이다() throws Exception {
        User user = newUser();
        mockMvc.perform(link(user)).andExpect(status().isCreated());
        CHZZK.reset();
        CHZZK.tokenDelays(Duration.ofMillis(600));   // A의 HTTP를 늦춘다 — A의 요청 시작 시각은 B보다 앞선다
        CHZZK.meResponds(200, "{\"code\":200,\"content\":{\"channelId\":\"chan-a\",\"channelName\":\"a\"}}");
        MockHttpServletRequestBuilder a = link(user);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<Integer> aStatus = pool.submit(() -> mockMvc.perform(a).andReturn().getResponse().getStatus());
        Thread.sleep(200);                            // A가 exchange 지연 중 — B가 끼어든다
        writer.create(user.getId(), new ChzzkMe("chan-b", "b"), new ChzzkTokens("at-b", "rt-b", Duration.ofHours(24), null));
        assertThat(aStatus.get(30, TimeUnit.SECONDS)).isEqualTo(201);
        pool.shutdown();
        awaitCleanup();
        ChzzkChannelLink alive = linkRepository.findByUserIdAndRevokedAtIsNull(user.getId()).orElseThrow();
        assertThat(alive.getChannelId()).isEqualTo("chan-a");   // A가 마지막에 커밋했다
        ChzzkChannelLink latest = linkRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId()).orElseThrow();
        assertThat(latest.getId()).as("살아있는 행 = 회원별 최신 행").isEqualTo(alive.getId());
    }
}
