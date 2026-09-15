package com.pokeclip.auth.audiotrack.api;

import com.pokeclip.auth.AuthException;
import com.pokeclip.auth.AuthFailure;
import com.pokeclip.auth.audiotrack.AudioTrackLabelService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 오디오 트랙 이름 창구 셋(POK-240). 편집 화면의 오디오 탭이 「트랙 3」 대신 「디스코드」를 보이게 한다.
 *
 * <p>쓰는 문은 {@code /api/auth/me/...} 하나다 — 토큰의 주인만 자기 것을 고친다(사진·이름과 같은 자리).
 * 읽는 문은 둘이다: 본인 것과 <b>스트리머 것</b>(편집자가 부른다). 뒤엣것에 회원 번호가 들어가므로
 * 자격 판정이 붙고, 거절은 404 하나다.
 */
@RestController
public class AudioTrackLabelController {

    /** 요청·응답이 같은 모양이다 — 화면이 받은 것을 고쳐 그대로 돌려보낸다. 칸은 여섯 고정, 빈 칸은 {@code null}. */
    public record Labels(List<String> labels) {
    }

    private final AudioTrackLabelService service;

    AudioTrackLabelController(AudioTrackLabelService service) {
        this.service = service;
    }

    @GetMapping("/api/auth/me/audio-tracks")
    public Labels mine(@AuthenticationPrincipal Jwt jwt) {
        return new Labels(service.mine(userId(jwt)));
    }

    /** PUT인 이유: 여섯 칸을 통째로 덮는다. 여러 번 눌러도 결과가 같다. */
    @PutMapping("/api/auth/me/audio-tracks")
    public Labels update(@AuthenticationPrincipal Jwt jwt, @RequestBody Labels body) {
        return new Labels(service.update(userId(jwt), body == null ? null : body.labels(), Instant.now()));
    }

    @GetMapping("/api/streamers/{streamerUserId}/audio-tracks")
    public Labels ofStreamer(@AuthenticationPrincipal Jwt jwt, @PathVariable long streamerUserId) {
        return new Labels(service.of(userId(jwt), streamerUserId));
    }

    /**
     * {@code ProfilePhotoController.userId}와 같은 모양으로 감싼다 — 전수 명부는 그 javadoc,
     * 개수 대조는 {@code TokenSubjectRegistryTest}, 같은 사유로 거절하는지는 {@code TokenSubjectRejectionTest}.
     */
    private static Long userId(Jwt jwt) {
        try {
            return Long.valueOf(jwt.getSubject());
        } catch (NumberFormatException e) {
            throw new AuthException(AuthFailure.ACCESS_TOKEN_SUBJECT_INVALID, "토큰의 주체를 읽을 수 없다", e);
        }
    }
}
