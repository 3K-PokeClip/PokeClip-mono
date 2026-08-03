package com.pokeclip.auth.streamkey.api;

import com.pokeclip.auth.streamkey.PairingCodeService;
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
     * <p>IP는 getRemoteAddr()로 읽는다. ALB 뒤로 가면 전부 같은 값이 되므로
     * X-Forwarded-For 처리가 필요해진다(알려진 구멍).
     */
    @PostMapping("/exchange")
    public ExchangeResponse exchange(@Valid @RequestBody ExchangeRequest request,
                                     HttpServletRequest httpRequest) {
        return ExchangeResponse.from(
                pairingCodeService.exchange(request.code(), httpRequest.getRemoteAddr()));
    }
}
