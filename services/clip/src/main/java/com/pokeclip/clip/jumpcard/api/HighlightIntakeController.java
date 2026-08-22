package com.pokeclip.clip.jumpcard.api;

import com.pokeclip.clip.jumpcard.JumpCardErrors.InvalidHighlightException;
import com.pokeclip.clip.jumpcard.JumpCardService;
import com.pokeclip.clip.jumpcard.JumpCardService.RecordResult;
import com.pokeclip.clip.jumpcard.JumpCardSnapshot;
import com.pokeclip.clip.jumpcard.JumpCardSource;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 계약 2A — 판별기가 카드를 넣는 문. {@code /internal/**}이라 사람 토큰으로는 못 들어온다.
 *
 * <p>201과 200을 가르는 이유: 판별기가 재전송했을 때 "새로 만들었는지"를 알아야
 * 로그에서 중복 재전송과 진짜 신규를 구분할 수 있다.
 */
@RestController
public class HighlightIntakeController {

    private final JumpCardService service;

    HighlightIntakeController(JumpCardService service) {
        this.service = service;
    }

    @PostMapping("/internal/broadcasts/{streamId}/highlights")
    public ResponseEntity<JumpCardSnapshot> receive(@PathVariable String streamId,
                                                    @Valid @RequestBody HighlightRequest request) {
        if (!request.consistent()) {
            throw new InvalidHighlightException("window");
        }
        // 모르는 출처를 여기서 좁혀 던진다. 핸들러가 IllegalArgumentException을 통째로 400으로
        // 잡으면 내부 버그가 「요청이 잘못됐다」로 둔갑해 판별기가 재시도를 멈춘다.
        try {
            JumpCardSource.fromDbValue(request.source());
        } catch (IllegalArgumentException e) {
            throw new InvalidHighlightException("source");
        }

        RecordResult result = service.record(streamId, request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.card());
    }
}
