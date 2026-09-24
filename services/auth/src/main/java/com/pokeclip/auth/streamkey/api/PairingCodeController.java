package com.pokeclip.auth.streamkey.api;

import com.pokeclip.auth.streamkey.pairing.PairingCodeService;
import com.pokeclip.auth.streamkey.api.dto.ExchangeRequest;
import com.pokeclip.auth.streamkey.api.dto.ExchangeResponse;
import com.pokeclip.auth.streamkey.api.dto.PairingCodeResponse;
import jakarta.servlet.http.HttpServletRequest;
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
@RequestMapping("/api/stream-keys/pairing-codes")
@RequiredArgsConstructor
public class PairingCodeController {

    private final PairingCodeService pairingCodeService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PairingCodeResponse issue(@AuthenticationPrincipal Jwt jwt) {
        return PairingCodeResponse.from(
                pairingCodeService.issue(Long.valueOf(jwt.getSubject())));
    }

    /**
     * 플러그인이 부른다. 로그인 상태가 아니다 — 코드 자체가 자격증명이다(ADR-019).
     *
     * <p>IP는 getRemoteAddr()로 읽는다. 프록시 뒤에서는 그 값이 프록시 IP가 되므로
     * {@code server.forward-headers-strategy=native}(환경변수 FORWARD_HEADERS_STRATEGY)로 톰캣이
     * X-Forwarded-For를 여기 채워 넣게 한다(POK-91). 코드는 안 바뀐다 — 채택 규칙은 Valve 몫이고
     * {@code config/ForwardedHeaders*Test}·{@code RemoteIpValveTrustTest}가 잰다.
     */
    @PostMapping("/exchange")
    public ExchangeResponse exchange(@Valid @RequestBody ExchangeRequest request,
                                     HttpServletRequest httpRequest) {
        return ExchangeResponse.from(
                pairingCodeService.exchange(request.code(), httpRequest.getRemoteAddr()));
    }
}
