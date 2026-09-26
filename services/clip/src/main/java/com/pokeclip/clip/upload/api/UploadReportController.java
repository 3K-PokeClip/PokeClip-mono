package com.pokeclip.clip.upload.api;

import com.pokeclip.clip.upload.UploadReportService;
import com.pokeclip.clip.upload.UploadReportService.Reply;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 업로드 일꾼의 문 셋(POK-220). {@code /internal/**}이라 {@code X-Internal-Token}으로만 들어온다.
 * 상태 코드와 본문은 {@link UploadReportService}가 정한다(200·409). 404(모르는 번호)·400(모양)만 예외 조언이 낸다.
 */
@RestController
public class UploadReportController {

    private final UploadReportService service;

    UploadReportController(UploadReportService service) {
        this.service = service;
    }

    public record SessionBody(String sessionUri) {
    }

    public record ResultBody(String outcome, String videoId, String errorCode, String errorMessage) {
    }

    @PostMapping("/internal/uploads/{uploadId}/start")
    public ResponseEntity<Map<String, Object>> start(@PathVariable long uploadId) {
        return reply(service.start(uploadId));
    }

    @PostMapping("/internal/uploads/{uploadId}/session")
    public ResponseEntity<Map<String, Object>> session(@PathVariable long uploadId, @RequestBody SessionBody body) {
        return reply(service.session(uploadId, body.sessionUri()));
    }

    @PostMapping("/internal/uploads/{uploadId}/result")
    public ResponseEntity<Map<String, Object>> result(@PathVariable long uploadId, @RequestBody ResultBody body) {
        return reply(service.result(uploadId, body.outcome(), body.videoId(), body.errorCode(), body.errorMessage()));
    }

    private static ResponseEntity<Map<String, Object>> reply(Reply reply) {
        return ResponseEntity.status(reply.status()).body(reply.body());
    }
}
