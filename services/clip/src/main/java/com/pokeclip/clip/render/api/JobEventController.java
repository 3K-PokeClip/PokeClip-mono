package com.pokeclip.clip.render.api;

import com.pokeclip.clip.render.JobEventRequest;
import com.pokeclip.clip.render.JobEventService;
import com.pokeclip.clip.render.JobEventService.Reply;
import com.pokeclip.clip.render.RenderErrors.InvalidJobEventException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * 계약1 4절 — 일꾼(렌더 워커)이 진행 보고를 넣는 문. {@code /internal/**}이라 사람 토큰으로는 못 들어온다.
 *
 * <p>상태 코드와 본문을 {@link JobEventService}가 통째로 정한다(200·400·409) — 같은 보고가 다시 오면 그때 준 것을
 * 그대로 다시 줘야 해서 컨트롤러가 손대지 않는다. 404(모르는 잡)만 예외 조언이 낸다.
 */
@RestController
public class JobEventController {

    private final JobEventService service;

    JobEventController(JobEventService service) {
        this.service = service;
    }

    @PostMapping(value = "/internal/jobs/{jobId}/events", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> receive(@PathVariable String jobId, @RequestBody JsonNode body) {
        UUID id;
        try {
            id = UUID.fromString(jobId);
        } catch (IllegalArgumentException e) {
            throw new InvalidJobEventException("jobId");
        }
        Reply reply = service.handle(id, JobEventRequest.parse(body));
        return ResponseEntity.status(reply.status()).contentType(MediaType.APPLICATION_JSON).body(reply.body());
    }
}
