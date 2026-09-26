package com.pokeclip.clip.upload.api;

import com.pokeclip.clip.support.NotFoundFloor;
import com.pokeclip.clip.upload.UploadRequest;
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

    UploadController(UploadRequestService service) {
        this.service = service;
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
}
