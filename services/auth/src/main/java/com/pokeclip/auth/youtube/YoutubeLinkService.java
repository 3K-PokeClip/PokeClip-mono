package com.pokeclip.auth.youtube;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 유튜브 연동의 조합부. {@code ChzzkLinkService}와 같은 이유로 <b>&#64;Transactional이 없다</b> —
 * 구글 HTTP(교환·채널 목록)를 트랜잭션 밖에서 하려고. 저장은 {@link YoutubeLinkWriter},
 * 버리기는 {@link YoutubeTokenDiscarder}.
 *
 * <p>호출부에 트랜잭션을 붙이면 실패 정리(revoke·secrets 삭제)가 상위 롤백에 딸려간다.
 */
@Service
@RequiredArgsConstructor
public class YoutubeLinkService {

    private static final Logger log = LoggerFactory.getLogger(YoutubeLinkService.class);

    /** 영상 업로드. 이것이 이 기능의 목적이라 없으면 연동을 만들지 않는다. */
    private static final String SCOPE_UPLOAD = "https://www.googleapis.com/auth/youtube.upload";
    /**
     * 채널 조회. <b>실측(2026-08-24)</b>: upload 하나만으로 {@code channels.list}를 부르면
     * 403 {@code insufficientPermissions}다 — 어느 채널에 올릴지 확정할 수 없으므로 필수다.
     */
    private static final String SCOPE_READONLY = "https://www.googleapis.com/auth/youtube.readonly";

    private final YoutubeProperties properties;
    private final YoutubeLinkStateCodec stateCodec;
    private final YoutubeOAuthClient oauthClient;
    private final YoutubeLinkWriter writer;
    private final YoutubeTokenRefresher refresher;
    private final YoutubeChannelLinkRepository links;

    /** {@code {}}에 넣지 않는다 — channelId. */
    public record LinkResult(String channelId, String channelName, Instant linkedAt) {
    }

    /**
     * 동의 URL. 파라미터 이름이 snake_case다(치지직은 camelCase였다).
     *
     * <p>{@code access_type=offline}과 {@code prompt=consent}가 <b>둘 다</b> 있어야 refresh_token이 온다 —
     * 이미 동의한 계정의 재동의에서 특히 그렇다(실측 A ②에서 2·3차 동의 모두 refresh를 받았다).
     * 하나라도 빠지면 갱신할 수 없는 반쪽 연동이 되고, 그것은 교환 단계에서 502로 거절된다.
     */
    public String startUrl(Long userId) {
        String state = stateCodec.issue(userId, Instant.now());
        return UriComponentsBuilder.fromUriString(properties.authorizeUri())
                .queryParam("client_id", properties.app().clientId())
                .queryParam("redirect_uri", properties.app().redirectUri())
                .queryParam("response_type", "code")
                .queryParam("scope", SCOPE_UPLOAD + " " + SCOPE_READONLY)
                .queryParam("access_type", "offline")
                .queryParam("prompt", "consent")
                .queryParam("state", state)
                .encode().build().toUriString();
    }

    /**
     * 동의 콜백 완료. state 검증 → 교환 → scope 대조 → 채널 목록 → 채널 확정 → 저장.
     *
     * <p>🔴 <b>실패해도 받은 토큰을 버리지 않는다 — 치지직과 정반대다.</b> 치지직은 「교환에 성공한 뒤
     * 실패하면 무조건 버린다」가 불변식이었지만({@code ChzzkLinkService.link}), 구글 revoke는
     * <b>계정+프로젝트 단위</b>라 한 번 부르면 그 구글 계정이 우리 앱에 준 동의가 전부 죽는다(실측 A ⑥).
     *
     * <p><b>조건을 붙여 막으려다 세 판을 썼다.</b> 「이 회원에게 살아있는 행이 있나」→ 「이 채널을 남이 쓰나」→
     * 「회원 행 락으로 직렬화」까지 갔는데도 <b>두 구멍이 남았다</b>: ① 다른 회원의 재연동이 <b>교환은 끝나고
     * 저장은 전</b>인 순간(우리 표에 아직 없다) ② scope 미달처럼 <b>채널을 읽기도 전에</b> 실패하는 경로
     * (판별자 자체가 없다). 근본 원인은 하나다 — <b>revoke의 영향 범위는 「그 구글 계정」인데 우리가 가진
     * 판별자는 「회원·채널」뿐</b>이다.
     *
     * <p>그래서 조건을 더 붙이는 대신 <b>이 경로에서 revoke를 없앴다</b>(2026-08-24 사용자 결정).
     * 대가는 실패한 시도의 grant가 구글에 남는 것 하나다 — access는 1시간이면 죽고, refresh는
     * <b>우리가 참조를 저장하지 않아 아무도 못 쓰며</b>(실패 경로는 저장 전이거나 롤백된 뒤다),
     * 사용자는 구글 계정 화면에서 직접 철회할 수 있다.
     *
     * <p><b>revoke가 남아 있는 자리는 하나뿐이다</b> — 갱신 거부({@code YoutubeTokenRefresher.reject}).
     * <b>사용자 해제도 보내지 않는다</b>(같은 이유로 뺐다 — {@code YoutubeLinkWriter.closeAlive} javadoc).
     * 그 하나가 남은 이유는 「그 토큰이 이미 죽어서」다 — 살아있는 grant에 닿지 않으므로 남을 해칠 수 없다.
     */
    public LinkResult link(Long userId, String code, String state) {
        Instant now = Instant.now();
        if (!stateCodec.matches(state, userId, now)) {
            throw new YoutubeLinkException(YoutubeLinkFailure.INVALID_STATE, "state가 이 사용자 것이 아니거나 만료됐다");
        }
        YoutubeTokens tokens;
        try {
            tokens = oauthClient.exchange(code);
        } catch (YoutubeRejectedException e) {
            // 4xx(code 소모·만료·시크릿 오타)는 동의부터 다시. 토큰을 못 받았으니 버릴 것도 없다.
            log.info("auth.youtube.link.rejected userId={} status={}", userId, e.status());
            throw new YoutubeLinkException(YoutubeLinkFailure.INVALID_CODE, "구글이 교환을 거부했다");
        } catch (YoutubeUnavailableException e) {
            // 응답에서 토큰까지는 읽혔는데 그 뒤가 깨진 경우(refresh 부재 포함)는 토큰이 예외에 실려 온다.
            // 그래도 버리지 않는다 — 아래 「왜 실패 정리가 revoke를 안 하나」 참고. 값은 어디에도 옮기지 않는다.
            log.warn("auth.youtube.link.unavailable userId={} causeType={}", userId, e.causeType());
            throw new YoutubeLinkException(YoutubeLinkFailure.YOUTUBE_UNAVAILABLE, "구글 응답 없음");
        }
        // 채널 조회보다 먼저 본다 — 업로드 권한이 없으면 어차피 만들 수 없는 연동이라 구글을 더 부르지 않는다.
        requireUploadScope(userId, tokens);
        List<YoutubeChannel> channels;
        try {
            channels = oauthClient.listChannels(tokens.accessToken());
        } catch (YoutubeRejectedException e) {
            // 403 insufficientPermissions(readonly 없음)가 여기로 온다 — 영구라 동의부터 다시(실측 A ④).
            log.info("auth.youtube.link.rejected userId={} status={}", userId, e.status());
            throw new YoutubeLinkException(YoutubeLinkFailure.INVALID_CODE, "구글이 채널 조회를 거부했다");
        } catch (YoutubeUnavailableException e) {
            // 5xx·타임아웃·403 할당량 — 재시도하면 되는 자리다.
            log.warn("auth.youtube.link.unavailable userId={} causeType={}", userId, e.causeType());
            throw new YoutubeLinkException(YoutubeLinkFailure.YOUTUBE_UNAVAILABLE, "구글 응답 없음");
        }
        if (channels.isEmpty()) {
            // items 키 부재도 빈 배열도 여기로 온다 — 「채널을 먼저 만드세요」지 형식 붕괴가 아니다.
            log.info("auth.youtube.link.no_channel userId={}", userId);
            throw new YoutubeLinkException(YoutubeLinkFailure.NO_CHANNEL, "구글 계정에 유튜브 채널이 없다");
        }
        // 목록 첫 번째로 확정한다. 실물은 항상 1개다(2026-08-24 실측 — 동의 시점에 채널이 확정되고
        // 그 토큰의 channels.list?mine=true는 고른 채널만 준다. 브랜드 계정도 개인 계정도 totalResults:1).
        // N개 분기는 방어일 뿐 오늘 열리는 경로가 아니다. 채널을 바꾸는 수단은 재연동뿐이다.
        YoutubeChannel selected = channels.get(0);
        YoutubeChannelLink saved;
        try {
            saved = writer.create(userId, selected, tokens);   // 저장은 별도 빈(트랜잭션). 시각은 writer가 락 뒤에 잡는다
        } catch (YoutubeLinkException | DataIntegrityViolationException e) {
            // 둘 다 채널 중복이다(사전 조회 / 경합 시 DB 유니크 — YoutubeLinkWriter.create 주석).
            // 롤백됐다 — secrets도 같이(put이 REQUIRED). 커밋이 없으니 afterCommit도 못 쓴다.
            throw new YoutubeLinkException(YoutubeLinkFailure.CHANNEL_ALREADY_LINKED, "다른 계정에 묶인 채널이다");
        }
        log.info("auth.youtube.link.created userId={}", userId);
        return new LinkResult(saved.getChannelId(), saved.getChannelName(), saved.getCreatedAt());
    }

    /**
     * 받은 scope에 업로드가 들어 있는지. <b>포함 여부로 본다</b> — 응답의 scope 순서는 요청과 다르다
     * (실측 2026-08-24: 요청 {@code upload readonly} → 응답 {@code readonly upload}). 순서·전체 일치로 보면
     * 정상 동의가 SCOPE_MISSING이 된다.
     *
     * <p>readonly는 여기서 안 본다 — 없으면 바로 다음 줄의 채널 조회가 403으로 갈린다(실측 A ④).
     */
    private void requireUploadScope(Long userId, YoutubeTokens tokens) {
        String scope = tokens.scope();
        boolean granted = scope != null && List.of(scope.trim().split("\\s+")).contains(SCOPE_UPLOAD);
        if (!granted) {
            log.info("auth.youtube.link.scope_missing userId={}", userId);
            throw new YoutubeLinkException(YoutubeLinkFailure.SCOPE_MISSING, "동의에 업로드 권한이 없다");
        }
    }

    /**
     * 살아있는 연동이 없는 이유를 워커가 읽을 사유로 옮긴다. 인자는 <b>갱신기가 락 안에서 본</b> 마지막 행의
     * 상태다(행 자체가 없으면 null). ACTIVE는 올 수 없다 — 락 안에서 「살아있는 행이 없다」고 판정한 뒤라
     * 같은 스냅샷에서 살아있는 행이 마지막 행일 수 없기 때문이다.
     */
    private static String reasonOf(LinkStatus lastStatus) {
        if (lastStatus == null) {
            return "NOT_LINKED";
        }
        return lastStatus == LinkStatus.BROKEN ? "BROKEN" : "UNLINKED";
    }

    /** 화면용. 닫힌 행도 준다 — 「끊겼다」를 보여줘야 하므로. */
    public Optional<YoutubeChannelLink> latest(Long userId) {
        return links.findFirstByUserIdOrderByCreatedAtDesc(userId);
    }

    /** 트랜잭션은 writer에 있다(자기 호출 함정). 살아있는 행이 없으면 아무것도 안 하고 조용히 끝. */
    public void unlink(Long userId) {
        writer.revoke(userId, Instant.now());
    }

    /**
     * 업로드 워커용. 남은 수명이 resolveMinRemaining(30분)보다 짧으면 즉석 갱신하고 새 토큰을 준다 —
     * 구글 access는 1시간짜리라 워커가 받은 토큰으로 긴 업로드를 시작하면 도중에 죽는다.
     *
     * <p><b>트랜잭션이 없다</b> — refresher가 최상단이어야 한다. 갱신 뒤 행도 secrets도 두 번째로 읽지 않고
     * refresher가 락 안에서 만든 스냅샷(access 원문 포함)만 쓴다. 두 읽기 사이에 해제·재연동과 정리 스레드의
     * delete가 끼면 「행은 있는데 secret 없음」(500)이 된다.
     *
     * <p>일시 실패에 임박한 토큰을 대신 주지 않는다 — 워커가 곧 죽을 토큰으로 업로드를 시작하는 것보다
     * 「지금은 안 된다」가 낫다.
     */
    public YoutubeResolveResult resolve(Long userId) {
        RefreshResult r = refresher.refreshIfExpiringWithin(userId, properties.resolveMinRemaining());
        return switch (r.outcome()) {
            case REFRESHED, SKIPPED_FRESH -> {
                RefreshResult.LinkSnapshot s = r.snapshot();
                yield new YoutubeResolveResult(true, s.channelId(), s.accessToken(), s.accessExpiresAt(), null);
            }
            case REJECTED -> YoutubeResolveResult.rejected("BROKEN");
            case UNAVAILABLE -> YoutubeResolveResult.rejected("REFRESH_UNAVAILABLE");
            // 살아있는 행이 없다 — 애초에 안 한 것(NOT_LINKED)과 사용자가 끊은 것(UNLINKED)과
            // 갱신이 거부된 것(BROKEN)은 호출자에게 다른 사건이라 마지막 행으로 가른다.
            // 🔴 그 마지막 행은 갱신기가 락 안에서 본 것이다. 여기서 리포지토리를 다시 부르지 않는다 —
            // 락 밖에서 읽으면 판정과 읽기 사이에 커밋된 새 연동(ACTIVE)을 집어 UNLINKED로 오분류한다
            // (로컬 리뷰 2026-08-24). 「조회를 여기로 되돌리면 더 단순한데」라고 생각되는 자리다.
            case NOT_LINKED -> YoutubeResolveResult.rejected(reasonOf(r.lastStatus()));
        };
    }
}
