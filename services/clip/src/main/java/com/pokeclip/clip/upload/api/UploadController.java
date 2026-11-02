package com.pokeclip.clip.upload.api;

import com.pokeclip.clip.support.NotFoundFloor;
import com.pokeclip.clip.upload.UploadRequest;
import com.pokeclip.clip.upload.UploadResolution;
import com.pokeclip.clip.upload.UploadResolutionService;
import com.pokeclip.clip.upload.UploadRequestService;
import com.pokeclip.clip.upload.UploadRequestService.Requested;
import com.pokeclip.clip.upload.UploadSnapshot;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 유튜브 업로드 주문 문(POK-220). 거절 판정은 서비스에 있다({@code RenderRequestController}와 같은 규칙).
 * 201 새 주문 · 200 같은 영상 같은 벌의 살아 있는 업로드가 이미 있다(그것을 돌려준다: 두 번 눌러도 영상은 하나다).
 */
@RestController
public class UploadController {

    private final UploadRequestService service;
    private final UploadResolutionService resolutions;

    UploadController(UploadRequestService service, UploadResolutionService resolutions) {
        this.service = service;
        this.resolutions = resolutions;
    }

    @PostMapping("/api/clip/broadcasts/{streamId}/clips/{clipId}/uploads")
    public ResponseEntity<UploadSnapshot> request(@PathVariable String streamId,
                                                  @PathVariable long clipId,
                                                  @RequestBody(required = false) UploadRequest body,
                                                  @AuthenticationPrincipal Jwt jwt,
                                                  HttpServletRequest request) {
        NotFoundFloor.mark(request);
        Requested requested = service.request(jwt.getSubject(), streamId, clipId, body);
        return ResponseEntity.status(requested.created() ? HttpStatus.CREATED : HttpStatus.OK).body(requested.upload());
    }

    /**
     * 실패한 업로드를 저장된 정보로 다시 올린다(POK-291). 본문 없음. 201 새 업로드 · 200 최신 업로드가 아직 살아 있다(그것을 돌려준다)
     * · 409 {@code nothing_to_retry}·{@code already_uploaded}.
     */
    @PostMapping("/api/clip/broadcasts/{streamId}/clips/{clipId}/uploads/retry")
    public ResponseEntity<UploadSnapshot> retry(@PathVariable String streamId,
                                                @PathVariable long clipId,
                                                @AuthenticationPrincipal Jwt jwt,
                                                HttpServletRequest request) {
        NotFoundFloor.mark(request);
        Requested requested = service.retry(jwt.getSubject(), streamId, clipId);
        return ResponseEntity.status(requested.created() ? HttpStatus.CREATED : HttpStatus.OK).body(requested.upload());
    }

    /**
     * 「확인 필요」 업로드를 사람이 채널에서 본 결과로 푼다(POK-333). 200 푼 업로드(같은 결과의 재전송도 200) · 400 {@code result}·
     * {@code videoId} · 404 방송 · 영상 · 업로드 · 409 {@code upload_not_checking} · {@code resolution_too_early}.
     */
    @PostMapping("/api/clip/broadcasts/{streamId}/clips/{clipId}/uploads/{uploadId}/resolution")
    public UploadSnapshot resolve(@PathVariable String streamId,
                                  @PathVariable long clipId,
                                  @PathVariable long uploadId,
                                  @RequestBody(required = false) UploadResolution body,
                                  @AuthenticationPrincipal Jwt jwt,
                                  HttpServletRequest request) {
        NotFoundFloor.mark(request);
        return resolutions.resolve(jwt.getSubject(), streamId, clipId, uploadId, body);
    }
}
