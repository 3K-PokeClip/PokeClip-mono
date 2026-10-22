package com.pokeclip.auth.soop;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.Optional;

/**
 * SOOP 연동의 조합부. 트랜잭션이 없다: 외부 HTTP를 트랜잭션 밖에서 하려고. 저장은 {@link SoopLinkWriter}.
 * {@code chzzk/ChzzkLinkService}의 쌍둥이다.
 */
@Service
@RequiredArgsConstructor
public class SoopLinkService {

    private static final Logger log = LoggerFactory.getLogger(SoopLinkService.class);

    private final SoopProperties properties;
    private final SoopLinkStateCodec stateCodec;
    private final SoopOAuthClient oauthClient;
    private final SoopLinkWriter writer;
    private final SoopTokenRefresher refresher;
    private final SoopChannelLinkRepository links;

    /** SOOP 아이디는 싣지 않는다: 화면에 필요 없고 응답에 실리면 그만큼 새는 자리가 는다. */
    public record LinkResult(String nickname, Instant linkedAt) {
    }

    /**
     * 동의 주소. SOOP 문서대로 {@code client_id}·{@code state}만 싣는다(redirect_uri는 콘솔에 등록한 값을 SOOP이 쓴다,
     * 계획 검증 M10). 치지직의 camelCase 키({@code clientId})가 아니다.
     */
    public String startUrl(Long userId) {
        String state = stateCodec.issue(userId, Instant.now());
        return UriComponentsBuilder.fromUriString(properties.authorizeUri())
                .queryParam("client_id", properties.app().clientId())
                .queryParam("state", state)
                .encode().build().toUriString();
    }

    /**
     * 교환 → 아이디·닉네임 → 저장. 치지직과 달리 <b>실패 뒤 받은 토큰을 버리는 HTTP가 없다</b>(SOOP 폐기 API 없음).
     * 버리지 못한 사실만 {@code auth.soop.link.token_orphaned} INFO로 남기고 원문은 메모리에서 놓는다.
     * SOOP 쪽에서는 토큰 수명(문서 예시 8시간)이 지나면 죽는다.
     */
    public LinkResult link(Long userId, String code, String state) {
        if (!stateCodec.matches(state, userId, Instant.now())) {
            throw new SoopLinkException(SoopLinkFailure.INVALID_STATE, "state가 이 사용자 것이 아니거나 만료됐다");
        }
        SoopTokens tokens = null;
        SoopIdentity identity;
        try {
            tokens = oauthClient.exchange(code);
            identity = oauthClient.fetchIdentity(tokens.accessToken());
        } catch (SoopRejectedException e) {
            orphaned(userId, tokens);
            log.info("auth.soop.link.rejected userId={} status={}", userId, e.status());
            throw new SoopLinkException(SoopLinkFailure.INVALID_CODE, "SOOP이 교환 또는 방송국 조회를 거부했다");
        } catch (SoopUnavailableException e) {
            orphaned(userId, tokens);
            log.warn("auth.soop.link.unavailable userId={} causeType={}", userId, e.causeType());
            throw new SoopLinkException(SoopLinkFailure.SOOP_UNAVAILABLE, "SOOP 응답 없음");
        } catch (RuntimeException e) {
            orphaned(userId, tokens);
            throw e;
        }
        SoopChannelLink saved;
        try {
            saved = writer.create(userId, identity, tokens);   // 저장은 별도 빈(트랜잭션). 시각은 writer가 락 뒤에 잡는다
        } catch (SoopLinkException | DataIntegrityViolationException e) {
            // 둘 다 방송국 중복이다(사전 조회 / 경합 시 DB 유니크, SoopLinkWriter.create 주석). 롤백됐다(secrets 포함).
            orphaned(userId, tokens);
            throw new SoopLinkException(SoopLinkFailure.CHANNEL_ALREADY_LINKED, "다른 계정에 묶인 방송국이다");
        } catch (RuntimeException e) {
            orphaned(userId, tokens);   // 풀 고갈·DB 다운·탈퇴 가드. 예외는 그대로
            throw e;
        }
        log.info("auth.soop.link.created userId={}", userId);
        return new LinkResult(identity.nickname(), saved.getCreatedAt());
    }

    /** SOOP에 발급된 토큰을 우리가 버릴 수 없다는 사실만 남긴다. 토큰을 못 받은 실패(교환 실패)면 남길 것이 없다. */
    private static void orphaned(Long userId, SoopTokens tokens) {
        if (tokens != null) {
            log.info("auth.soop.link.token_orphaned userId={}", userId);
        }
    }

    public Optional<SoopChannelLink> latest(Long userId) {
        return links.findFirstByUserIdOrderByCreatedAtDesc(userId);
    }

    /** 트랜잭션은 writer에 있다(자기 호출 함정). 살아 있는 행이 없으면 아무것도 안 하고 조용히 끝. */
    public void unlink(Long userId) {
        writer.revoke(userId, Instant.now());
    }

    /**
     * 수집기용. 남은 수명이 resolveMinRemaining(1시간)보다 짧으면 즉석 갱신하고 새 토큰을 준다.
     *
     * <p>🔴 치지직과 달리 「한 번 받은 토큰으로 방송 끝까지」를 보장하지 못한다: SOOP access는 약 8시간이다(문서 예시).
     * 수집기는 {@code expiresAt} 전에 다시 부른다(계획 검증 M12, README resolve 계약).
     *
     * <p>트랜잭션이 없다: refresher가 최상단이어야 한다. 갱신 뒤 행도 secrets도 두 번째로 읽지 않고 refresher가 락 안에서
     * 만든 스냅샷만 쓴다(그 사이 해제가 끼면 500이 되는 분기를 없앤다, 치지직과 같다).
     * 즉석 갱신의 4xx는 브레이크 없이 그대로 파기된다(한 사람 일이다).
     */
    public SoopResolveResult resolve(Long userId) {
        RefreshResult r = refresher.refreshIfExpiringWithin(userId, properties.resolveMinRemaining());
        return switch (r.outcome()) {
            case REFRESHED, SKIPPED_FRESH -> {
                RefreshResult.LinkSnapshot s = r.snapshot();
                yield new SoopResolveResult(true, s.soopUserId(), s.accessToken(), s.accessExpiresAt(), null);
            }
            case REJECTED -> SoopResolveResult.rejected("BROKEN");
            case UNAVAILABLE -> SoopResolveResult.rejected("REFRESH_UNAVAILABLE");
            // WITHDRAWN도 UNLINKED다(BROKEN만 다르다): 탈퇴한 회원에게 「다시 연동」을 권할 일이 없다(계획 검증 M11).
            case NOT_LINKED -> SoopResolveResult.rejected(links.findFirstByUserIdOrderByCreatedAtDesc(userId)
                    .map(last -> last.status(Instant.now()) == LinkStatus.BROKEN ? "BROKEN" : "UNLINKED")
                    .orElse("NOT_LINKED"));
        };
    }
}
