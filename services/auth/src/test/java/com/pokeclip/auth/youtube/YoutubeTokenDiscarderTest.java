package com.pokeclip.auth.youtube;

import ch.qos.logback.classic.Level;
import com.pokeclip.auth.support.FakeYoutubeServer;
import com.pokeclip.web.support.LogCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 구글 revoke는 「그 쌍」이 아니라 그 사용자가 이 프로젝트에 준 동의 <b>전부</b>를 죽인다.
 * 그래서 여기서 재는 것은 두 가지다 — <b>몇 번 부르는가</b>(한 번)와 <b>언제 안 부르는가</b>(살아있는 연동이 있을 때).
 *
 * <p>가짜 구글은 진짜 소켓이라 호출 횟수·받은 토큰이 실물처럼 관찰된다. 리포지토리만 mock이다 —
 * 「살아있는 행이 있나」 한 갈래를 쓰는데 실물 쿼리는 태스크 6의 통합 검사가 돌린다.
 */
class YoutubeTokenDiscarderTest {

    private FakeYoutubeServer google;
    private YoutubeChannelLinkRepository links;
    private YoutubeTokenDiscarder discarder;

    @BeforeEach
    void setUp() {
        google = FakeYoutubeServer.start();
        links = mock(YoutubeChannelLinkRepository.class);
        when(links.findByUserIdAndRevokedAtIsNull(any())).thenReturn(Optional.empty());
        discarder = new YoutubeTokenDiscarder(
                new YoutubeOAuthClient(RestClient.builder(), props(google)), links);
    }

    @AfterEach
    void tearDown() {
        google.close();
    }

    /** 치지직은 둘 다 불렀다. 구글은 한 번이면 grant 전체가 죽으므로 둘째 호출은 무의미하고 로그만 시끄럽다. */
    @Test
    void refresh가_있으면_그것만_한_번_버린다() {
        discarder.discard(7L, "at-x", "rt-x");

        assertThat(google.revokedTokens()).containsExactly("rt-x");
        assertThat(google.revokeCalls()).isEqualTo(1);
    }

    @Test
    void refresh가_없으면_access를_한_번_버린다() {
        discarder.discard(7L, "at-x", null);

        assertThat(google.revokedTokens()).containsExactly("at-x");
        assertThat(google.revokeCalls()).isEqualTo(1);
    }

    @Test
    void 버릴_토큰이_없으면_구글을_부르지_않는다() {
        discarder.discard(7L, null, null);

        assertThat(google.revokeCalls()).isZero();
    }

    /**
     * 🔴 결정 8의 핵심 — 실패 정리가 <b>살아있는 연동을 죽이지 않게</b> 한다.
     * 버려진 access는 1시간이면 스스로 죽지만, 멀쩡한 연동을 죽이면 사용자가 재동의해야 한다.
     */
    @Test
    void 살아있는_연동이_있으면_아예_버리지_않는다() {
        when(links.findByUserIdAndRevokedAtIsNull(7L)).thenReturn(Optional.of(aliveLink()));

        try (LogCaptor logs = new LogCaptor()) {
            discarder.discardIfNoLiveLink(7L, "at-x", "rt-x");

            assertThat(google.revokeCalls()).as("살아있는 연동이 있는데 revoke를 불렀다").isZero();
            assertThat(logs.messages()).contains("auth.youtube.link.discard_skipped userId=7 reason=LIVE_LINK");
            assertThat(logs.levelOf("auth.youtube.link.discard_skipped")).isEqualTo(Level.INFO);
        }
    }

    @Test
    void 살아있는_연동이_없으면_버린다() {
        discarder.discardIfNoLiveLink(7L, "at-x", "rt-x");

        assertThat(google.revokedTokens()).containsExactly("rt-x");
    }

    /**
     * 4xx = 구글이 이 토큰을 모르거나 이미 무효 = 무효화 목적 달성이라 INFO다.
     * 4xx까지 WARN이면 해제·갱신 거부마다 거짓 경보가 남아 진짜 고아를 못 가린다.
     */
    @Test
    void 이미_죽은_토큰은_INFO_구글_장애는_WARN이다() {
        google.revokeResponds(400, "{\"error\":\"invalid_token\"}");
        try (LogCaptor logs = new LogCaptor()) {
            discarder.discard(7L, "at-x", "rt-x");

            assertThat(logs.messages()).contains("auth.youtube.link.token_already_dead userId=7 status=400");
            assertThat(logs.levelOf("auth.youtube.link.token_already_dead")).isEqualTo(Level.INFO);
        }

        google.revokeResponds(503, "{}");
        try (LogCaptor logs = new LogCaptor()) {
            discarder.discard(8L, "at-y", "rt-y");

            assertThat(logs.messages()).contains("auth.youtube.link.orphan_token userId=8 causeType=Http503");
            assertThat(logs.levelOf("auth.youtube.link.orphan_token")).isEqualTo(Level.WARN);
        }
    }

    /** 로그·예외에 토큰 원문이 실리면 안 된다 — 버리기는 실패해도 조용하지만 유출은 남는다. */
    @Test
    void 실패_로그에_토큰_원문이_없다() {
        google.revokeResponds(503, "{}");
        try (LogCaptor logs = new LogCaptor()) {
            discarder.discard(7L, "LEAK-at-needle", "LEAK-rt-needle");

            assertThat(logs.messages()).noneMatch(m -> m.contains("LEAK-at-needle") || m.contains("LEAK-rt-needle"));
        }
    }

    private static YoutubeChannelLink aliveLink() {
        return YoutubeChannelLink.of(7L, "UC-a", "채널", null, "youtube-access:a", "youtube-refresh:r",
                Instant.now().plus(Duration.ofHours(1)), Instant.now());
    }

    private static YoutubeProperties props(FakeYoutubeServer google) {
        return new YoutubeProperties(
                new YoutubeProperties.App("ycid", "ycsecret", "http://localhost:8081/oauth/youtube/callback"),
                "https://accounts.google.com/o/oauth2/v2/auth",
                google.tokenUri(), google.revokeUri(), google.baseUrl(),
                Duration.ofMinutes(10), Duration.ofMinutes(30),
                new YoutubeProperties.Check(false, Duration.ofHours(1), Duration.ofHours(24)));
    }
}
