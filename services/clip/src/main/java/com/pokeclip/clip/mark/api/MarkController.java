package com.pokeclip.clip.mark.api;

import com.pokeclip.clip.jumpcard.JumpCardService.RecordResult;
import com.pokeclip.clip.jumpcard.JumpCardSnapshot;
import com.pokeclip.clip.mark.MarkService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import com.pokeclip.clip.mark.MarkRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.time.Instant;

/**
 * 계약4 4B: OBS 플러그인의 수동 마킹. <b>로그인(JWT) 밖</b>이다: 스트림키(본문 {@code streamid})와 그 파생 값
 * ({@code Authorization: Bearer})이 자격이다. 보안 체인은 {@code MarkSecurityConfig}.
 *
 * <p>헤더를 {@code required=false}로 받고 본문을 스트림으로 받아 상한까지만 읽는다(계획 검증 E1 · 감사 2회차 B2) :
 * 빠졌을 때 스프링 예외가 아니라 서비스가 400·401을 가르고, 인증 전에 큰 본문을 통째로 읽지 않는다. 201은 새 카드, 200은 같은 eventId이거나 20초 안의 앞 카드다. 본문은 2A와 같은 카드 모양.
 */
@RestController
public class MarkController {

    private final MarkService service;

    MarkController(MarkService service) {
        this.service = service;
    }

    @PostMapping("/api/clip/marks")
    public ResponseEntity<JumpCardSnapshot> mark(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            InputStream body) {
        // 받은 시각은 여기서 한 번(계획 검증 E5). auth 왕복 뒤에 읽으면 그만큼 누른 시각이 밀린다.
        Instant receivedAt = service.now();
        String text = MarkRequest.readBody(body);
        RecordResult result = service.mark(authorization, idempotencyKey, text, receivedAt);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.card());
    }
}
