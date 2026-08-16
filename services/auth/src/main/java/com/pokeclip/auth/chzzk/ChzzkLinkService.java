package com.pokeclip.auth.chzzk;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;

/**
 * 치지직 연동의 조합부. 트랜잭션이 없다 — 외부 HTTP를 트랜잭션 밖에서 하려고.
 * 저장은 {@link ChzzkLinkWriter}, 버리기는 {@link ChzzkTokenDiscarder}.
 */
@Service
@RequiredArgsConstructor
public class ChzzkLinkService {

    private static final Logger log = LoggerFactory.getLogger(ChzzkLinkService.class);

    private final ChzzkProperties properties;
    private final ChzzkLinkStateCodec stateCodec;
    private final ChzzkOAuthClient oauthClient;
    private final ChzzkLinkWriter writer;
    private final ChzzkTokenDiscarder discarder;

    /** {@code {}}에 넣지 않는다 — channelId. */
    public record LinkResult(String channelId, String channelName, Instant linkedAt) {
    }

    public String startUrl(Long userId) {
        String state = stateCodec.issue(userId, Instant.now());
        return UriComponentsBuilder.fromUriString(properties.authorizeUri())
                .queryParam("clientId", properties.app().clientId())
                .queryParam("redirectUri", properties.app().redirectUri())
                .queryParam("state", state)
                .encode().build().toUriString();
    }

    public LinkResult link(Long userId, String code, String state) {
        Instant now = Instant.now();
        if (!stateCodec.matches(state, userId, now)) {
            throw new ChzzkLinkException(ChzzkLinkFailure.INVALID_STATE, "state가 이 사용자 것이 아니거나 만료됐다");
        }
        ChzzkTokens tokens;
        try {
            tokens = oauthClient.exchange(code, state);      // 4xx: code 소모·만료·시크릿 오타 — 동의부터 다시
        } catch (ChzzkRejectedException e) {
            log.info("auth.chzzk.link.rejected userId={} status={}", userId, e.status());
            throw new ChzzkLinkException(ChzzkLinkFailure.INVALID_CODE, "치지직이 교환을 거부했다");
        } catch (ChzzkUnavailableException e) {
            // 교환 응답에서 토큰까지는 읽혔는데 그 뒤가 깨진 경우, 토큰은 예외에 실려 온다 — 치지직엔 발급됐으니 버린다.
            e.issuedTokens().ifPresent(t -> discarder.discard(userId, t.accessToken(), t.refreshToken()));
            log.warn("auth.chzzk.link.unavailable userId={} causeType={}", userId, e.causeType());
            throw new ChzzkLinkException(ChzzkLinkFailure.CHZZK_UNAVAILABLE, "치지직 응답 없음");
        }
        ChzzkMe me;
        try {
            me = oauthClient.fetchMe(tokens.accessToken());  // 4xx: scope 없음 등 — 같은 처리
        } catch (ChzzkRejectedException e) {
            // 교환은 됐다 — 받은 토큰이 치지직에 살아 있으므로 버리고 실패한다.
            discarder.discard(userId, tokens.accessToken(), tokens.refreshToken());
            log.info("auth.chzzk.link.rejected userId={} status={}", userId, e.status());
            throw new ChzzkLinkException(ChzzkLinkFailure.INVALID_CODE, "치지직이 채널 조회를 거부했다");
        } catch (ChzzkUnavailableException e) {
            discarder.discard(userId, tokens.accessToken(), tokens.refreshToken());
            log.warn("auth.chzzk.link.unavailable userId={} causeType={}", userId, e.causeType());
            throw new ChzzkLinkException(ChzzkLinkFailure.CHZZK_UNAVAILABLE, "치지직 응답 없음");
        }
        ChzzkChannelLink saved = writer.create(userId, me, tokens);   // 저장은 별도 빈(트랜잭션). 시각은 writer가 락 뒤에 잡는다
        log.info("auth.chzzk.link.created userId={}", userId);
        return new LinkResult(me.channelId(), me.channelName(), saved.getCreatedAt());
    }
}
