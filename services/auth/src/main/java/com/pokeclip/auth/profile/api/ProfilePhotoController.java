package com.pokeclip.auth.profile.api;

import com.pokeclip.auth.api.dto.MeResponse;
import com.pokeclip.auth.profile.ProfilePhotoService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 사진은 파일이라 이름 수정({@code PATCH /api/auth/me})과 창구를 나눴다 — 본문 형식이 다르고,
 * 한쪽만 실패했을 때 무엇이 저장됐는지가 응답 하나로는 말해지지 않는다.
 *
 * <p>PUT인 이유: 회원마다 사진이 하나이고 같은 이름에 덮어쓴다. 여러 번 눌러도 결과가 같다.
 */
@RestController
@RequestMapping("/api/auth/me/photo")
@RequiredArgsConstructor
public class ProfilePhotoController {

    private final ProfilePhotoService service;

    /** 회원 번호를 받지 않는다 — 토큰의 주인만 자기 것을 고친다. */
    @PutMapping
    public MeResponse upload(@AuthenticationPrincipal Jwt jwt, @RequestParam("file") MultipartFile file) {
        return MeResponse.from(service.upload(userId(jwt), file));
    }

    private static Long userId(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }
}
