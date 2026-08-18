package com.pokeclip.auth.delegation.api;

import com.pokeclip.auth.delegation.EditorInvitation;
import com.pokeclip.auth.delegation.InvitationService;
import com.pokeclip.auth.delegation.api.dto.InviteRequest;
import com.pokeclip.auth.delegation.api.dto.ReceivedInvitationResponse;
import com.pokeclip.auth.delegation.api.dto.SentInvitationResponse;
import com.pokeclip.auth.user.UserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/editor-invitations")
@RequiredArgsConstructor
public class EditorInvitationController {

    private final InvitationService service;
    private final UserRepository users;

    /** 새 초대든 기한 연장이든 201이다. 클라이언트에게 결과는 "초대가 있다"로 같다. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SentInvitationResponse invite(@AuthenticationPrincipal Jwt jwt,
                                         @Valid @RequestBody InviteRequest request) {
        EditorInvitation invitation = service.invite(userId(jwt), request.email());
        var invitee = users.findById(invitation.getInviteeId()).orElseThrow();
        return SentInvitationResponse.of(invitation, invitee.getName(), invitee.getEmail(), Instant.now());
    }

    @GetMapping("/sent")
    public List<SentInvitationResponse> sent(@AuthenticationPrincipal Jwt jwt) {
        return service.sentBy(userId(jwt));
    }

    @GetMapping("/received")
    public List<ReceivedInvitationResponse> received(@AuthenticationPrincipal Jwt jwt) {
        return service.receivedBy(userId(jwt));
    }

    /** 없는 초대에도 404다. 존재 여부를 알려주지 않는다. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        service.cancel(userId(jwt), id);
    }

    private static Long userId(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }
}
