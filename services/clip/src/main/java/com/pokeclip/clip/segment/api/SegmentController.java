package com.pokeclip.clip.segment.api;

import com.pokeclip.clip.segment.SegmentQueryService;
import com.pokeclip.clip.support.NotFoundFloor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 편집기가 「이 구간을 지금 볼 수 있나」를 묻는 문. clip이 편집기에 여는 첫 조회 API다.
 *
 * <p><b>거절 판정이 여기 하나도 없다.</b> 구간 검증까지 {@link SegmentQueryService}가 한다 —
 * 그 서비스의 소비자가 둘이고(이 컨트롤러 · 렌더 잡 POK-125) <b>뒤쪽은 컨트롤러를 안 거친다.</b>
 * 검증을 이 클래스로 끌어오면 그 경로만 무방비가 되고, 증상은 8시간 방송을 통째로 당기는
 * 조회로 나타난다(감사 1회차 결정).
 *
 * <p>그래서 이 클래스가 하는 일은 셋뿐이다 — 파라미터를 넘기고, 결과에서 {@code s3Key}를 뺀다
 * ({@link SegmentWindowResponse}), 그리고 <b>404가 언제 나갈지의 기준 시각을 찍는다</b>
 * ({@link NotFoundFloor}). 셋째는 판정이 아니라 시계다 — 무엇을 거절할지는 여전히 서비스가 정한다.
 *
 * <p>사용자 번호는 <b>토큰의 subject에서만</b> 온다({@code JumpCardController}와 같은 규칙) —
 * 쿼리로 받으면 남의 번호로 남의 방송을 열 수 있다.
 */
@RestController
@RequestMapping("/api/clip/broadcasts/{streamId}/segments")
public class SegmentController {

    private final SegmentQueryService service;

    SegmentController(SegmentQueryService service) {
        this.service = service;
    }

    @GetMapping
    public SegmentWindowResponse window(@PathVariable String streamId,
                                        @RequestParam long startMs, @RequestParam long endMs,
                                        @AuthenticationPrincipal Jwt jwt,
                                        HttpServletRequest request) {
        // 404 두 갈래가 갈리기 전에 기준 시각을 찍는다. 갈림은 아래 호출 안에서 시작되므로
        // 이 줄이 「둘 다 여기서 출발했다」를 보증한다 — 자세한 근거는 NotFoundFloor.
        //
        // 서비스 안에서 찍지 않는 이유는 그쪽 소비자가 둘이기 때문이다 — 렌더 잡(POK-125)은
        // HTTP 요청이 없고, 응답 시각을 감출 상대도 없다.
        NotFoundFloor.mark(request);
        return SegmentWindowResponse.from(service.previewWindow(jwt.getSubject(), streamId, startMs, endMs));
    }
}
