package com.pokeclip.auth.chzzk;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
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
        // 교환 → me. 4xx(code 소모·만료·시크릿 오타·scope 없음)는 동의부터 다시, 5xx·타임아웃은 재시도.
        // 교환은 됐는데 그 뒤가 실패하면 받은 토큰이 치지직에 살아 있다 — 어떤 실패든 버린다(이 PR의 불변식).
        // 불변식의 예외 하나: 교환 응답에서 토큰조차 못 읽은 경우(content 없음·토큰 필드 깨짐)는 원문이 없어 물리적으로
        // 못 버린다. 토큰은 읽혔는데 그 뒤(expiresIn)가 깨진 경우는 예외에 실려 오므로 버린다.
        ChzzkTokens tokens = null;
        ChzzkMe me;
        try {
            tokens = oauthClient.exchange(code, state);
            me = oauthClient.fetchMe(tokens.accessToken());
        } catch (ChzzkRejectedException e) {
            discardIfIssued(userId, tokens);
            log.info("auth.chzzk.link.rejected userId={} status={}", userId, e.status());
            throw new ChzzkLinkException(ChzzkLinkFailure.INVALID_CODE, "치지직이 교환 또는 채널 조회를 거부했다");
        } catch (ChzzkUnavailableException e) {
            // 교환 응답에서 토큰까지는 읽혔는데 그 뒤가 깨진 경우, 토큰은 예외에 실려 온다 — 그것도 버린다.
            discardIfIssued(userId, tokens != null ? tokens : e.issuedTokens().orElse(null));
            log.warn("auth.chzzk.link.unavailable userId={} causeType={}", userId, e.causeType());
            throw new ChzzkLinkException(ChzzkLinkFailure.CHZZK_UNAVAILABLE, "치지직 응답 없음");
        } catch (RuntimeException e) {
            // 예상 밖 실패에도 대칭으로 버린다. 치지직 쪽은 커밋(발급)됐는데 우리만 응답을 못 받은 모호 실패라면
            // revoke가 그 토큰을 죽이고, 못 죽였더라도 다음 갱신이 4xx → BROKEN으로 수렴한다.
            discardIfIssued(userId, tokens);
            throw e;
        }
        ChzzkChannelLink saved;
        try {
            saved = writer.create(userId, me, tokens);   // 저장은 별도 빈(트랜잭션). 시각은 writer가 락 뒤에 잡는다
        } catch (ChzzkLinkException | DataIntegrityViolationException e) {
            // 둘 다 채널 중복이다(사전 조회 / 경합 시 DB 유니크 — ChzzkLinkWriter.create 주석).
            // 롤백됐다 — secrets도 같이(put이 REQUIRED). 받은 토큰은 치지직에 살아 있으므로 여기서 버린다.
            // afterCommit은 못 쓴다: 커밋이 없다. 트랜잭션 밖에서 부르고, 실패가 409를 막지 않게 한다.
            discarder.discard(userId, tokens.accessToken(), tokens.refreshToken());
            throw new ChzzkLinkException(ChzzkLinkFailure.CHANNEL_ALREADY_LINKED, "다른 계정에 묶인 채널이다");
        } catch (RuntimeException e) {
            // 채널 중복이 아닌 실패(풀 고갈·DB 다운)도 토큰은 버린다 — 안 버리면 치지직 고아. 예외는 그대로(500).
            discarder.discard(userId, tokens.accessToken(), tokens.refreshToken());
            throw e;
        }
        log.info("auth.chzzk.link.created userId={}", userId);
        return new LinkResult(me.channelId(), me.channelName(), saved.getCreatedAt());
    }

    private void discardIfIssued(Long userId, ChzzkTokens tokens) {
        if (tokens != null) {
            discarder.discard(userId, tokens.accessToken(), tokens.refreshToken());
        }
    }
}
