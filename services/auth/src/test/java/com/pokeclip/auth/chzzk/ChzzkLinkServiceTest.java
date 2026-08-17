package com.pokeclip.auth.chzzk;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.CannotCreateTransactionException;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 통합 테스트로 만들기 어려운 갈래 — 저장(writer)이 채널 중복이 아닌 이유(풀 고갈·DB 다운)로 터질 때.
 * "받은 토큰은 버린다"는 이 PR의 불변식이라 어떤 실패에서도 지켜야 한다.
 */
class ChzzkLinkServiceTest {

    private final ChzzkProperties properties = new ChzzkProperties(
            new ChzzkProperties.App("cid", "csecret", "http://localhost:8081/oauth/chzzk/callback"),
            "https://chzzk.naver.com/account-interlock", "http://127.0.0.1:1", Duration.ofMinutes(10),
            Duration.ofHours(6), Duration.ofHours(12), new ChzzkProperties.Refresh(false, Duration.ofMinutes(10)));
    private final ChzzkLinkStateCodec codec = mock(ChzzkLinkStateCodec.class);
    private final ChzzkOAuthClient oauthClient = mock(ChzzkOAuthClient.class);
    private final ChzzkLinkWriter writer = mock(ChzzkLinkWriter.class);
    private final ChzzkTokenDiscarder discarder = mock(ChzzkTokenDiscarder.class);
    private final ChzzkTokenRefresher refresher = mock(ChzzkTokenRefresher.class);
    private final ChzzkChannelLinkRepository links = mock(ChzzkChannelLinkRepository.class);

    private final ChzzkLinkService service =
            new ChzzkLinkService(properties, codec, oauthClient, writer, discarder, refresher, links);

    @Test
    void 저장이_채널_중복이_아닌_이유로_터져도_받은_토큰은_버리고_예외는_그대로_올린다() {
        when(codec.matches(anyString(), anyLong(), any(Instant.class))).thenReturn(true);
        when(oauthClient.exchange("c", "s")).thenReturn(new ChzzkTokens("at-x", "rt-x", Duration.ofHours(24), null));
        when(oauthClient.fetchMe("at-x")).thenReturn(new ChzzkMe("chan", "채널"));
        when(writer.create(anyLong(), any(), any())).thenThrow(new CannotCreateTransactionException("풀 고갈"));

        assertThatThrownBy(() -> service.link(7L, "c", "s")).isInstanceOf(CannotCreateTransactionException.class);
        verify(discarder).discard(7L, "at-x", "rt-x");
    }

    /** 교환은 됐는데 me가 Rejected/Unavailable이 아닌 이유로 터져도(대칭) 받은 토큰은 버린다. */
    @Test
    void 채널_조회가_예상_밖_예외로_터져도_받은_토큰은_버리고_예외는_그대로_올린다() {
        when(codec.matches(anyString(), anyLong(), any(Instant.class))).thenReturn(true);
        when(oauthClient.exchange("c", "s")).thenReturn(new ChzzkTokens("at-x", "rt-x", Duration.ofHours(24), null));
        when(oauthClient.fetchMe("at-x")).thenThrow(new IllegalStateException("예상 밖"));

        assertThatThrownBy(() -> service.link(7L, "c", "s")).isInstanceOf(IllegalStateException.class);
        verify(discarder).discard(7L, "at-x", "rt-x");
    }
}
