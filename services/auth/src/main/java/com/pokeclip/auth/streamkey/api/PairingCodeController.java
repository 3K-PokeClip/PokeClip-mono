package com.pokeclip.auth.streamkey.api;

import com.pokeclip.auth.streamkey.PairingCodeService;
import com.pokeclip.auth.streamkey.api.dto.PairingCodeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
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
}
