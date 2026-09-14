package com.pokeclip.clip.playback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.cloudfront.CloudFrontUtilities;
import software.amazon.awssdk.services.cloudfront.cookie.CookiesForCustomPolicy;
import software.amazon.awssdk.services.cloudfront.model.CustomSignerRequest;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * CloudFront 서명 쿠키를 만드는 유일한 자리. 자격 판정은 여기 없다 — 부르는 문이
 * {@code BroadcastAccessGuard}를 먼저 지난다.
 *
 * <p><b>범위는 그 스트리머의 영상 전부다</b>: {@code {base}/*&#47;{streamId}/*}. 계약3 7-1의
 * 경로 셋({@code /live} · {@code /dvr} · {@code /vod})이 전부 {@code /{종류}/{streamId}/…} 모양이라
 * 앞 자리를 와일드카드로 두면 하나로 덮인다. 카드 하나 구간만 열면 카드를 넘길 때마다 새로
 * 받아야 하고, 그 왕복마다 auth를 두드린다.
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

    private final PlaybackProperties properties;
    private final CloudFrontUtilities cloudFront = CloudFrontUtilities.create();

    PlaybackAccessSigner(PlaybackProperties properties) {
        this.properties = properties;
        if (!properties.enabled()) {
            log.warn("clip.playback.disabled — 출입증 재료(pokeclip.playback.*)가 비어 있어 "
                    + "POST …/playback-access는 503을 준다. 로컬은 정상, 운영이면 설정 누락이다");
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
        String resource = properties.resourceBaseUrl() + "/*/" + streamId + "/*";
        Instant expiresAt = now.plus(properties.ttl());

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
        return new PlaybackAccess(streamId, expiresAt, resource, cookies);
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
