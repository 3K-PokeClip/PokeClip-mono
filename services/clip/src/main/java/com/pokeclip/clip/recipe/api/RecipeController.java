package com.pokeclip.clip.recipe.api;

import com.pokeclip.clip.recipe.RecipeParser;
import com.pokeclip.clip.recipe.RecipeService;
import com.pokeclip.clip.recipe.RecipeSnapshot;
import com.pokeclip.clip.support.NotFoundFloor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * 편집 기록(레시피) 문 넷 — 저장·목록·하나 보기·고치기(POK-124). 지우는 문은 없다(영구 보존).
 *
 * <p><b>거절 판정이 여기 하나도 없다</b>({@code JumpCardListController}와 같은 규칙) — 자격도 본문 규칙도
 * {@link RecipeService}가 정한다. 여기서 하는 일은 셋뿐이다: 본문을 문자열로 받아 파서에 넘기고, 사용자 번호를
 * <b>토큰에서만</b> 꺼내고, <b>404의 기준 시각을 찍는다</b>({@link NotFoundFloor}).
 *
 * <p>본문을 {@code @RequestBody}가 아니라 <b>요청 스트림으로</b> 파서에 넘기는 이유는 {@link RecipeParser} 주석에
 * 있다 — 파싱 실패·빈 본문까지 이 문 안에서 400 {@code invalid_request} 봉투로 끝내고, 상한을 넘는 본문은 다 받기
 * 전에 끊으려는 것이다. {@code consumes}로 JSON만 받으므로 다른 형은 415다.
 */
@RestController
@RequestMapping("/api/clip/broadcasts/{streamId}/recipes")
public class RecipeController {

    private final RecipeService service;
    private final RecipeParser parser;

    RecipeController(RecipeService service, RecipeParser parser) {
        this.service = service;
        this.parser = parser;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RecipeSnapshot> create(@PathVariable String streamId,
                                                 @AuthenticationPrincipal Jwt jwt,
                                                 HttpServletRequest request) throws IOException {
        // 파싱이 자격 판정보다 앞인 것은 의도다 — 파싱 실패(400)는 방송의 존재와 무관하게 나가므로 감출 것이 없다.
        // 규칙 검증(400)은 서비스 안에서 자격 판정 뒤에 돈다(RecipeService 주석).
        NotFoundFloor.mark(request);
        RecipeSnapshot created = service.create(jwt.getSubject(), streamId, parser.parse(request.getInputStream()));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public RecipeListResponse list(@PathVariable String streamId,
                                   @AuthenticationPrincipal Jwt jwt,
                                   HttpServletRequest request) {
        NotFoundFloor.mark(request);
        return new RecipeListResponse(service.listOf(jwt.getSubject(), streamId));
    }

    @GetMapping("/{id}")
    public RecipeSnapshot get(@PathVariable String streamId,
                              @PathVariable long id,
                              @AuthenticationPrincipal Jwt jwt,
                              HttpServletRequest request) {
        NotFoundFloor.mark(request);
        return service.get(jwt.getSubject(), streamId, id);
    }

    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public RecipeSnapshot replace(@PathVariable String streamId,
                                  @PathVariable long id,
                                  @AuthenticationPrincipal Jwt jwt,
                                  HttpServletRequest request) throws IOException {
        NotFoundFloor.mark(request);
        return service.replace(jwt.getSubject(), streamId, id, parser.parse(request.getInputStream()));
    }
}
