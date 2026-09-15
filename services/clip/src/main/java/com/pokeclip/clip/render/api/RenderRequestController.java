package com.pokeclip.clip.render.api;

import com.pokeclip.clip.render.ClipSnapshot;
import com.pokeclip.clip.render.RenderRequestService;
import com.pokeclip.clip.render.RenderRequestService.Requested;
import com.pokeclip.clip.support.NotFoundFloor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 영상 만들기 주문 문 둘(POK-125) — 주문·하나 보기. 목록은 POK-243. 본문이 없다: 무엇을 만들지는 편집본 번호가 말한다.
 *
 * <p>거절 판정이 여기 하나도 없다({@code RecipeController}와 같은 규칙). 하는 일은 사용자 번호를 토큰에서 꺼내고
 * 404 기준 시각을 찍는 것뿐이다.
 */
@RestController
@RequestMapping("/api/clip/broadcasts/{streamId}")
public class RenderRequestController {

    private final RenderRequestService service;

    RenderRequestController(RenderRequestService service) {
        this.service = service;
    }

    /** 201 새 주문 · 200 같은 편집본 같은 판이 이미 진행 중(그것을 돌려준다 — 더블클릭이 주문을 두 번 안 만든다). */
    @PostMapping("/recipes/{recipeId}/renders")
    public ResponseEntity<ClipSnapshot> request(@PathVariable String streamId,
                                                @PathVariable long recipeId,
                                                @AuthenticationPrincipal Jwt jwt,
                                                HttpServletRequest request) {
        NotFoundFloor.mark(request);
        Requested requested = service.request(jwt.getSubject(), streamId, recipeId);
        return ResponseEntity.status(requested.created() ? HttpStatus.CREATED : HttpStatus.OK).body(requested.clip());
    }

    @GetMapping("/clips/{clipId}")
    public ClipSnapshot get(@PathVariable String streamId,
                            @PathVariable long clipId,
                            @AuthenticationPrincipal Jwt jwt,
                            HttpServletRequest request) {
        NotFoundFloor.mark(request);
        return service.get(jwt.getSubject(), streamId, clipId);
    }
}
