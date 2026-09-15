package com.pokeclip.clip.playback.api;

import com.pokeclip.clip.delegation.BroadcastAccessGuard;
import com.pokeclip.clip.playback.PlaybackAccess;
import com.pokeclip.clip.playback.PlaybackAccessSigner;
import com.pokeclip.clip.playback.PlaybackProperties;
import com.pokeclip.clip.support.NotFoundFloor;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 영상 출입증(POK-122) — 카드를 누른 사람이 그 방송 영상을 받을 수 있게 CloudFront 서명 쿠키를
 * 브라우저에 붙여 준다. 영상 바이트는 이 서버를 안 지난다(계약3).
 *
 * <p><b>순서가 계약이다: {@code mark} → {@code requireViewable} → 서명.</b> 판정이 서명보다 뒤면
 * 남남이 남의 방송 출입증을 받는다. 404 두 갈래(「없는 방송」·「자격 없음」)는 다른 문 열하나와
 * 같은 본문·같은 바닥이다.
 *
 * <p><b>POST인 이유</b>: 응답이 브라우저 상태(쿠키)를 바꾼다. 갱신도 같은 문을 다시 부른다 —
 * 새 쿠키가 옛 쿠키를 덮는다(이름이 같다).
 *
 * <p><b>쿠키 속성</b>: {@code Path=/{kind}/{streamId}}(종류마다 셋 — 아홉 장. 브라우저가 요청 경로에 맞는 셋만 보낸다.
 * {@code Path=/} 하나로 덮으면 정책에 앞 와일드카드가 필요하고 그것이 남의 방송을 열었다, 봇 리뷰 2판) ·
 * {@code Secure} · {@code HttpOnly}(hls.js는 쿠키를 읽지 않는다, 브라우저가 붙인다) ·
 * {@code SameSite=Lax}(앱과 미디어가 같은 사이트 {@code pokeclip.com} 아래다) ·
 * {@code Max-Age}=수명 · {@code Domain}은 설정값(비면 호스트 전용).
 * 🔴 앱이 다른 오리진에서 이 문을 부르므로 브라우저가 쿠키를 받으려면 요청에
 * {@code credentials: 'include'}가 있어야 하고, 그래서 clip만 CORS {@code allow-credentials}를 켠다.
 */
@RestController
public class PlaybackAccessController {

    private static final Logger log = LoggerFactory.getLogger(PlaybackAccessController.class);

    private final BroadcastAccessGuard guard;
    private final PlaybackAccessSigner signer;
    private final PlaybackProperties properties;

    PlaybackAccessController(BroadcastAccessGuard guard, PlaybackAccessSigner signer, PlaybackProperties properties) {
        this.guard = guard;
        this.signer = signer;
        this.properties = properties;
    }

    @PostMapping(value = "/api/clip/broadcasts/{streamId}/playback-access", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> issue(@PathVariable String streamId,
                                              @AuthenticationPrincipal Jwt jwt,
                                              HttpServletRequest request) {
        NotFoundFloor.mark(request);
        guard.requireViewable(jwt.getSubject(), streamId);

        PlaybackAccess access = signer.issue(streamId, Instant.now());

        HttpHeaders headers = new HttpHeaders();
        List<String> resources = new ArrayList<>(access.scopes().size());
        for (PlaybackAccess.Scope scope : access.scopes()) {
            scope.cookies().forEach((name, value) ->
                    headers.add(HttpHeaders.SET_COOKIE, cookie(name, value, scope.cookiePath())));
            resources.add(scope.resource());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("streamId", access.streamId());
        body.put("expiresAt", access.expiresAt().toString());
        body.put("resources", resources);

        // 서명·정책 값은 안 찍는다 — 그 값이 곧 출입증이다.
        log.info("clip.playback.issued streamId={} userId={} expiresAt={}", streamId, jwt.getSubject(), access.expiresAt());
        return ResponseEntity.ok().headers(headers).contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private String cookie(String name, String value, String path) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(name, value)
                .path(path)
                .secure(true)
                .httpOnly(true)
                .sameSite("Lax")
                .maxAge(properties.ttl());
        if (properties.cookieDomain() != null) {
            builder.domain(properties.cookieDomain());
        }
        return builder.build().toString();
    }
}
