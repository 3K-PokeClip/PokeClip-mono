package com.pokeclip.auth.chzzk.api;

import com.pokeclip.auth.chzzk.ChzzkLinkService;
import com.pokeclip.auth.chzzk.api.dto.LinkRequest;
import com.pokeclip.auth.chzzk.api.dto.LinkResponse;
import com.pokeclip.auth.chzzk.api.dto.StartResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chzzk-link")
@RequiredArgsConstructor
public class ChzzkLinkController {

    private final ChzzkLinkService service;

    /** 동의 URL. 로그인한 사용자 것으로 서명된 state가 들어 있다. */
    @PostMapping("/start")
    public StartResponse start(@AuthenticationPrincipal Jwt jwt) {
        return new StartResponse(service.startUrl(userId(jwt)));
    }

    /** 동의 콜백이 받은 code·state로 완료. 채널은 본문이 아니라 치지직 me로 확정한다. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LinkResponse link(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody LinkRequest request) {
        ChzzkLinkService.LinkResult r = service.link(userId(jwt), request.code(), request.state());
        return new LinkResponse(r.channelId(), r.channelName(), r.linkedAt());
    }

    private static Long userId(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }
}
