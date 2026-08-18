package com.pokeclip.auth.delegation.api;

import com.pokeclip.auth.delegation.DelegationService;
import com.pokeclip.auth.delegation.api.dto.DelegationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/editor-delegations")
@RequiredArgsConstructor
public class EditorDelegationController {

    private final DelegationService service;

    @GetMapping("/as-streamer")
    public List<DelegationResponse> asStreamer(@AuthenticationPrincipal Jwt jwt) {
        return service.asStreamer(userId(jwt));
    }

    @GetMapping("/as-editor")
    public List<DelegationResponse> asEditor(@AuthenticationPrincipal Jwt jwt) {
        return service.asEditor(userId(jwt));
    }

    /** 스트리머가 부르면 내보내기, 편집자가 부르면 나가기. 행은 남는다. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        service.revoke(userId(jwt), id);
    }

    private static Long userId(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }
}
