package com.pokeclip.clip.jumpcard.api;

import com.pokeclip.clip.jumpcard.JumpCardService;
import com.pokeclip.clip.jumpcard.JumpCardSnapshot;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 편집자가 쓰는 문 넷. POST가 「건다」, DELETE가 「푼다」다.
 *
 * <p>사용자 번호는 <b>토큰의 subject에서만</b> 온다 — 본문이나 쿼리로 받으면 남의 번호로 집을 수 있다.
 * 편집자 <b>이름</b>은 응답에 안 넣는다(POK-175 — 이름표는 auth가 갖고 있고 물어볼 창구가 없다).
 */
@RestController
@RequestMapping("/api/clip/jump-cards/{id}")
public class JumpCardController {

    private final JumpCardService service;

    JumpCardController(JumpCardService service) {
        this.service = service;
    }

    @PostMapping("/claim")
    public JumpCardSnapshot claim(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        return service.claim(id, jwt.getSubject());
    }

    @DeleteMapping("/claim")
    public ResponseEntity<Void> release(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        service.release(id, jwt.getSubject());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/hide")
    public JumpCardSnapshot hide(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        return service.hide(id, jwt.getSubject());
    }

    /** 되돌리기는 누구나 한다 — 숨긴 사람만 되돌릴 수 있으면 그 사람이 자리를 비웠을 때 막힌다. */
    @DeleteMapping("/hide")
    public JumpCardSnapshot unhide(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        return service.unhide(id, jwt.getSubject());
    }
}
