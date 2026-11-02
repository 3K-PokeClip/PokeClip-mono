package com.pokeclip.auth.soop.api;

import com.pokeclip.auth.soop.SoopLinkService;
import com.pokeclip.auth.soop.api.dto.LinkRequest;
import com.pokeclip.auth.soop.api.dto.LinkResponse;
import com.pokeclip.auth.soop.api.dto.LinkStatusResponse;
import com.pokeclip.auth.soop.api.dto.StartResponse;
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

import java.time.Instant;

/** {@code chzzk/api/ChzzkLinkController}의 쌍둥이. 응답에 SOOP 아이디를 싣지 않는다(닉네임만). */
@RestController
@RequestMapping("/api/soop-link")
@RequiredArgsConstructor
public class SoopLinkController {

    private final SoopLinkService service;

    /** 동의 URL. 로그인한 사용자 것으로 서명된 state가 들어 있다. */
    @PostMapping("/start")
    public StartResponse start(@AuthenticationPrincipal Jwt jwt) {
        return new StartResponse(service.startUrl(userId(jwt)));
    }

    /** 동의 콜백이 받은 code·state로 완료. 방송국은 본문이 아니라 SOOP 조회로 확정한다. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LinkResponse link(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody LinkRequest request) {
        SoopLinkService.LinkResult r = service.link(userId(jwt), request.code(), request.state());
        return new LinkResponse(r.nickname(), r.linkedAt());
    }

    /** 마지막 행 기준. 끊긴 것(BROKEN·UNLINKED)도 status는 준다(닉네임은 파기돼 없다). */
    @GetMapping
    public LinkStatusResponse status(@AuthenticationPrincipal Jwt jwt) {
        return service.latest(userId(jwt))
                .map(link -> LinkStatusResponse.of(link, Instant.now()))
                .orElseGet(LinkStatusResponse::none);
    }

    /** 살아 있는 연동이 없어도 204(멱등). 행은 남고 토큰·아이디·닉네임은 바로 지운다(약관 제11조 ⑰). */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlink(@AuthenticationPrincipal Jwt jwt) {
        service.unlink(userId(jwt));
    }

    /** 치지직과 같이 감싸지 않는다: 토큰 주체 명부(ProfilePhotoController.userId)의 「안 감싼 자리」. */
    private static Long userId(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }
}
