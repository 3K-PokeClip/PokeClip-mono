package com.pokeclip.clip.render.api;

import com.pokeclip.clip.render.SegmentAudioAccess;
import com.pokeclip.clip.render.SegmentAudioAccessService;
import com.pokeclip.clip.support.NotFoundFloor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 원본 조각 주소 문(POK-389). 거절 판정은 서비스에 있다. POST인 이유는 완성 영상 주소 문과 같다: 부를 때마다 새 출입증을 만들고,
 * GET이면 중간 캐시·브라우저 기록에 주소가 남는다.
 */
@RestController
public class SegmentAudioAccessController {

    private final SegmentAudioAccessService service;

    SegmentAudioAccessController(SegmentAudioAccessService service) {
        this.service = service;
    }

    @PostMapping("/api/clip/broadcasts/{streamId}/segments/audio-access")
    public SegmentAudioAccess issue(@PathVariable String streamId,
                                    @RequestParam long fromMs,
                                    @RequestParam long toMs,
                                    @AuthenticationPrincipal Jwt jwt,
                                    HttpServletRequest request) {
        NotFoundFloor.mark(request);
        return service.issue(jwt.getSubject(), streamId, fromMs, toMs);
    }
}
