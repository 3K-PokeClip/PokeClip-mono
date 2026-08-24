package com.pokeclip.auth.youtube.api;

import com.pokeclip.auth.youtube.YoutubeLinkService;
import com.pokeclip.auth.youtube.api.dto.LinkRequest;
import com.pokeclip.auth.youtube.api.dto.LinkResponse;
import com.pokeclip.auth.youtube.api.dto.LinkStatusResponse;
import com.pokeclip.auth.youtube.api.dto.StartResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사용자 문 넷. 기본 시큐리티 체인의 {@code anyRequest().authenticated()}에 걸린다 — 시큐리티 설정 변경 0.
 *
 * <p><b>채널 목록·재선택 문이 없다.</b> 구글은 동의 시점에 채널을 확정하고 그 토큰의 channels.list는
 * 고른 채널 하나만 준다(2026-08-24 실측 — 브랜드 계정도 개인 계정도 totalResults:1).
 * 채널을 바꾸는 수단은 <b>재연동</b>뿐이고 POST 하나가 그것을 이미 한다.
 */
@RestController
@RequestMapping("/api/youtube-link")
@RequiredArgsConstructor
public class YoutubeLinkController {

    private final YoutubeLinkService service;

    /** 동의 URL. 로그인한 사용자 것으로 서명된 state가 들어 있다. */
    @PostMapping("/start")
    public StartResponse start(@AuthenticationPrincipal Jwt jwt) {
        return new StartResponse(service.startUrl(userId(jwt)));
    }

    /** 동의 콜백이 받은 code·state로 완료. 채널은 본문이 아니라 구글 channels.list로 확정한다. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LinkResponse link(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody LinkRequest request) {
        YoutubeLinkService.LinkResult r = service.link(userId(jwt), request.code(), request.state());
        return new LinkResponse(r.channelId(), r.channelName(), r.linkedAt());
    }

    /** 마지막 행 기준. 끊긴 것(BROKEN·UNLINKED)도 status·channelName은 준다. */
    @GetMapping
    public LinkStatusResponse status(@AuthenticationPrincipal Jwt jwt) {
        return service.latest(userId(jwt))
                .map(LinkStatusResponse::of)
                .orElseGet(LinkStatusResponse::none);
    }

    /**
     * 살아있는 연동이 없어도 204 — 멱등. 행은 남고(`revoked_at`) <b>토큰 시크릿만 지운다</b>.
     * <b>구글에 revoke는 보내지 않는다</b> — 계정 단위라 남의 연동까지 끊기 때문이다
     * (근거는 {@code YoutubeLinkWriter.closeAlive} javadoc). 사용자가 구글 쪽 허락까지 지우려면
     * 구글 계정 화면에서 직접 한다 — 웹이 그 링크를 안내한다.
     */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlink(@AuthenticationPrincipal Jwt jwt) {
        service.unlink(userId(jwt));
    }

    private static Long userId(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }
}
