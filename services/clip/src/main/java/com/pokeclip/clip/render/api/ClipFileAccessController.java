package com.pokeclip.clip.render.api;

import com.pokeclip.clip.render.ClipFileAccess;
import com.pokeclip.clip.render.ClipFileAccessService;
import com.pokeclip.clip.support.NotFoundFloor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 완성 영상 주소 문(POK-247). 거절 판정은 서비스에 있다({@code RenderRequestController}와 같은 규칙).
 *
 * <p><b>POST인 이유</b>: 부를 때마다 새 출입증을 만든다(재생 출입증 문과 같다). GET이면 중간 캐시·브라우저 기록에 주소가 남는다.
 */
@RestController
public class ClipFileAccessController {

    private final ClipFileAccessService service;

    ClipFileAccessController(ClipFileAccessService service) {
        this.service = service;
    }

    @PostMapping("/api/clip/broadcasts/{streamId}/clips/{clipId}/file-access")
    public ClipFileAccess issue(@PathVariable String streamId,
                                @PathVariable long clipId,
                                @AuthenticationPrincipal Jwt jwt,
                                HttpServletRequest request) {
        NotFoundFloor.mark(request);
        return service.issue(jwt.getSubject(), streamId, clipId);
    }
}
