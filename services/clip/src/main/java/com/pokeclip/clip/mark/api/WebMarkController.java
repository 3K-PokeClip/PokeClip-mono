package com.pokeclip.clip.mark.api;

import com.pokeclip.clip.jumpcard.JumpCardService.RecordResult;
import com.pokeclip.clip.jumpcard.JumpCardSnapshot;
import com.pokeclip.clip.mark.WebMarkRequest;
import com.pokeclip.clip.mark.WebMarkService;
import com.pokeclip.clip.support.NotFoundFloor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.time.Instant;

/**
 * 웹 방송 화면의 수동 마킹(POK-392). 로그인(JWT) 문이고 자격은 다른 카드 문과 같다(주인·편집자 모두).
 * 201은 새 카드, 200은 같은 eventId이거나 20초 안의 앞 카드다. 본문은 목록·통로와 같은 카드 모양.
 *
 * <p>거절 봉투는 전역 {@code JumpCardExceptionHandler}의 {@code {"error":…}}다. OBS 문({@code MarkController})의
 * {@code {"reason":…}}과 다르다: 이 문은 OBS 거절 타입을 안 던진다.
 */
@RestController
public class WebMarkController {

    private final WebMarkService service;

    WebMarkController(WebMarkService service) {
        this.service = service;
    }

    @PostMapping("/api/clip/broadcasts/{streamId}/marks")
    public ResponseEntity<JumpCardSnapshot> mark(@PathVariable String streamId, @AuthenticationPrincipal Jwt jwt,
                                                 HttpServletRequest request, InputStream body) {
        // 받은 시각은 맨 처음 한 번(계획 검증 m1). auth 왕복 뒤에 읽으면 그만큼 누른 시각이 밀린다.
        Instant receivedAt = service.now();
        // 404 두 갈래(없는 방송 · 자격 없음)가 갈리기 전에 기준 시각을 찍는다. 근거는 NotFoundFloor.
        NotFoundFloor.mark(request);
        String text = WebMarkRequest.readBody(body);
        RecordResult result = service.mark(jwt.getSubject(), streamId, text, receivedAt);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.card());
    }
}
