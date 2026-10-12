package com.pokeclip.clip.upload.api;

import com.pokeclip.clip.upload.UploadReportService;
import com.pokeclip.clip.upload.UploadReportService.Reply;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

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

    /**
     * 썸네일 칸 둘(POK-291)은 JSON 나무로 받는다: 글자가 아닌 값(숫자·객체)이 와도 본문 읽기가 400으로 끝나지 않게 한다.
     * 그 400은 일꾼이 같은 보고를 되풀이하다 실패 큐로 가는 길이다(UploadReportService.result 주석).
     */
    public record ResultBody(String outcome, String videoId, String errorCode, String errorMessage,
                             JsonNode thumbnailOutcome, JsonNode thumbnailErrorCode) {
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
        return reply(service.result(uploadId, body.outcome(), body.videoId(), body.errorCode(), body.errorMessage(),
                text(body.thumbnailOutcome()), text(body.thumbnailErrorCode())));
    }

    private static String text(JsonNode node) {
        return node != null && node.isString() ? node.stringValue() : null;
    }

    private static ResponseEntity<Map<String, Object>> reply(Reply reply) {
        return ResponseEntity.status(reply.status()).body(reply.body());
    }
}
