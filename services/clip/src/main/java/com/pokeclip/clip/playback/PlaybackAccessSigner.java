package com.pokeclip.clip.playback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.cloudfront.CloudFrontUtilities;
import software.amazon.awssdk.services.cloudfront.cookie.CookiesForCustomPolicy;
import software.amazon.awssdk.services.cloudfront.model.CustomSignerRequest;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * CloudFront 서명 쿠키를 만드는 유일한 자리. 자격 판정은 여기 없다 — 부르는 문이
 * {@code BroadcastAccessGuard}를 먼저 지난다.
 *
 * <p><b>범위는 그 스트리머의 영상 전부다</b> — 계약3 7-1의 경로 종류({@code /live} · {@code /dvr} ·
 * {@code /vod})마다 정책 {@code {base}/{kind}/{streamId}/*} 하나, 쿠키 셋 하나. 카드 하나 구간만 열면
 * 카드를 넘길 때마다 새로 받아야 하고, 그 왕복마다 auth를 두드린다.
 *
 * <p>🔴 <b>종류를 앞 와일드카드 하나({@code {base}/*&#47;{streamId}/*})로 덮지 않는다</b>(봇 리뷰 2판).
 * CloudFront는 정책 {@code Resource}를 요청 URL 전체(쿼리스트링 포함)에 대한 문자열 와일드카드로 맞추고
 * 경로 구분자를 안 보므로, {@code {base}/live/{남의방송}/index.m3u8?x=/{내방송}/}이 그 범위에 맞는다 —
 * 방송 하나의 자격으로 모든 스트리머의 영상이 열린다. 정책이 셋이면 CloudFront 고정 쿠키 이름 셋을 세 번
 * 써야 하므로 쿠키 {@code Path}를 {@code /{kind}/{streamId}}로 갈라 브라우저가 경로에 맞는 셋만 보내게 한다
 * ({@link PlaybackAccess.Scope}).
 *
 * <p>🔴 <b>{@code streamId}는 명부 값이지만 정책 문자열에 그대로 들어간다.</b> 큐로 받은 값에
 * {@code *}·{@code /}가 섞여 있으면 정책 범위가 남의 방송까지 넓어진다. 그래서 명부 통과 뒤에도
 * 글자를 한 번 더 본다 — 허용은 계약9 좌표에 쓰이는 문자({@code [A-Za-z0-9._-]})뿐이다.
 *
 * <p>로그에 <b>정책·서명·키 값은 안 찍는다</b>. 서명은 그 자체가 출입증이라 한 줄 새면 그 스트리머
 * 영상이 60분 열린다.
 */
@Component
public class PlaybackAccessSigner {

    private static final Logger log = LoggerFactory.getLogger(PlaybackAccessSigner.class);

    /** 계약9 좌표(스트림키·회차 번호)가 쓰는 문자만. 와일드카드·구분자·공백은 전부 밖이다. */
    static final Pattern SAFE_STREAM_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    /** 계약3 7-1의 경로 종류. 순서가 곧 응답 순서다. 새 종류가 생기면 여기 한 줄이고, 쿠키는 셋씩 는다. */
    static final List<String> KINDS = List.of("live", "dvr", "vod");

    private final PlaybackProperties properties;
    private final CloudFrontUtilities cloudFront = CloudFrontUtilities.create();

    PlaybackAccessSigner(PlaybackProperties properties) {
        this.properties = properties;
        if (!properties.enabled()) {
            log.warn("clip.playback.disabled — 출입증 재료(pokeclip.playback.*)가 비어 있어 "
                    + "POST …/playback-access는 503을 준다. 로컬은 정상, 운영이면 설정 누락이다");
        } else if (properties.cookieDomain() == null) {
            // 부팅 거부는 안 한다 — 로컬(clip·미디어가 같은 호스트)은 비는 것이 정상이다. 대신 한 줄 남긴다:
            // 운영에서 비면 쿠키가 호스트 전용이 되어 미디어 도메인에 안 붙고, 서버 쪽은 200만 찍힌다(봇 리뷰 1판).
            log.warn("clip.playback.cookie_domain_empty — 켜졌는데 pokeclip.playback.cookie-domain이 비어 "
                    + "쿠키가 호스트 전용이다. clip API와 미디어가 다른 호스트면 CDN이 403을 준다. 운영은 .pokeclip.com");
        }
    }

    public boolean enabled() {
        return properties.enabled();
    }

    /**
     * @throws PlaybackErrors.SigningUnavailableException 재료가 없거나 방송 번호가 정책에 못 들어간다
     */
    public PlaybackAccess issue(String streamId, Instant now) {
        if (!properties.enabled()) {
            throw new PlaybackErrors.SigningUnavailableException("disabled");
        }
        if (!SAFE_STREAM_ID.matcher(streamId).matches()) {
            // 값은 안 찍는다 — 어떤 쓰레기인지가 아니라 어느 길이의 무엇이 명부에 있는지가 진단이다.
            log.error("clip.playback.stream_id_unsafe length={}", streamId.length());
            throw new PlaybackErrors.SigningUnavailableException("stream_id_unsafe");
        }
        Instant expiresAt = now.plus(properties.ttl());
        String basePath = basePath(properties.resourceBaseUrl());
        List<PlaybackAccess.Scope> scopes = new ArrayList<>(KINDS.size());
        for (String kind : KINDS) {
            // 🔴 앞 와일드카드 금지. `{base}/*/{id}/*`는 CloudFront가 경로 구분자를 안 보고 문자열로 맞춰
            // `{base}/live/남의방송/x?q=/{id}/`가 통과했다(봇 리뷰 2판). 종류를 고정하면 뿌리가 곧 방송이다.
            String resource = properties.resourceBaseUrl() + "/" + kind + "/" + streamId + "/*";
            CookiesForCustomPolicy signed = cloudFront.getCookiesForCustomPolicy(CustomSignerRequest.builder()
                    .resourceUrl(resource)
                    .privateKey(properties.privateKey())
                    .keyPairId(properties.keyPairId())
                    .expirationDate(expiresAt)
                    .build());
            Map<String, String> cookies = new LinkedHashMap<>();
            put(cookies, signed.policyHeaderValue());
            put(cookies, signed.signatureHeaderValue());
            put(cookies, signed.keyPairIdHeaderValue());
            scopes.add(new PlaybackAccess.Scope(kind, basePath + "/" + kind + "/" + streamId, resource, cookies));
        }
        return new PlaybackAccess(streamId, expiresAt, List.copyOf(scopes));
    }

    /**
     * 쿠키 {@code Path}의 뿌리 = 미디어 주소의 경로 부분. 보통 비어 있다({@code https://media.pokeclip.com}).
     * 주소에 경로가 있으면({@code https://cdn.example/media}) 쿠키 경로도 거기서 시작해야 브라우저가 붙인다.
     */
    static String basePath(String resourceBaseUrl) {
        String path = URI.create(resourceBaseUrl).getPath();
        return path == null ? "" : path;
    }

    /** SDK는 {@code "CloudFront-Policy=<값>"} 한 줄로 준다. 첫 {@code =}에서 가른다 — 값에도 {@code =}는 없다(CloudFront base64는 {@code _}로 바꾼다). */
    private static void put(Map<String, String> into, String headerValue) {
        int eq = headerValue.indexOf('=');
        if (eq <= 0) {
            throw new IllegalStateException("CloudFront 쿠키 헤더 모양이 예상과 다르다");
        }
        into.put(headerValue.substring(0, eq), headerValue.substring(eq + 1));
    }
}
