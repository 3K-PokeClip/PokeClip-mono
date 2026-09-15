package com.pokeclip.clip.library.api;

import com.pokeclip.clip.library.LibraryDetail;
import com.pokeclip.clip.library.LibraryService;
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
 * 보관함 문 둘(POK-243) — 목록·상세. 방송 경로 아래가 아니다: 보관함 화면은 방송을 고르지 않고 내 편집본 전부를 본다.
 *
 * <p>거절 판정이 여기 하나도 없다({@code RecipeController}와 같은 규칙). {@code limit}이 박스형인 이유는
 * {@code BroadcastListController}와 같다 — 「안 줬다」와 「0을 줬다」를 가른다.
 */
@RestController
@RequestMapping("/api/clip/library")
public class LibraryController {

    private final LibraryService service;

    LibraryController(LibraryService service) {
        this.service = service;
    }

    @GetMapping
    public LibraryListResponse list(@RequestParam(required = false) String status,
                                    @RequestParam(required = false) Integer limit,
                                    @RequestParam(required = false) String cursor,
                                    @AuthenticationPrincipal Jwt jwt) {
        return LibraryListResponse.from(service.list(jwt.getSubject(), status, limit, cursor));
    }

    /** 404 두 갈래(없다·남의 것)가 같은 본문·같은 바닥 시간이다 — {@link NotFoundFloor}. */
    @GetMapping("/{recipeId}")
    public LibraryDetail get(@PathVariable long recipeId,
                             @AuthenticationPrincipal Jwt jwt,
                             HttpServletRequest request) {
        NotFoundFloor.mark(request);
        return service.get(jwt.getSubject(), recipeId);
    }
}
