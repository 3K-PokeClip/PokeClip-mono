package com.pokeclip.clip.broadcast.api;

import com.pokeclip.clip.broadcast.BroadcastListService;
import com.pokeclip.clip.broadcast.BroadcastState;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 편집자가 홈 화면을 열 때 처음 부르는 문.
 *
 * <p><b>거절 판정이 여기 하나도 없다</b>({@code SegmentController}와 같은 규칙) — 자격도,
 * 개수 상한도 {@link BroadcastListService}가 정한다. 컨트롤러로 끌어오면 그 서비스를 직접
 * 부르는 소비자가 생기는 날 그 경로만 무방비가 된다.
 *
 * <p>사용자 번호는 <b>토큰의 subject에서만</b> 온다 — 쿼리로 받으면 남의 번호로 남의 목록을 본다.
 *
 * <p>🔴 <b>{@code state}를 열거형이 아니라 {@code String}으로 받는다.</b> 열거형을 그대로
 * 바인딩하면 스프링 기본 변환기가 대소문자를 가려 <b>계약대로인 {@code state=live}가 400</b>이
 * 된다(계획 검증 실측). 옮기는 것은 {@link BroadcastState#fromParam}이다.
 *
 * <p>{@code limit}이 {@code Integer}(박스형)인 것은 <b>「안 줬다」와 「0을 줬다」를 갈라야</b>
 * 하기 때문이다 — 기본값을 여기 적으면 그 구분이 서비스에 도착하기 전에 사라진다.
 */
@RestController
@RequestMapping("/api/clip/broadcasts")
public class BroadcastListController {

    private final BroadcastListService service;

    BroadcastListController(BroadcastListService service) {
        this.service = service;
    }

    @GetMapping
    public BroadcastListResponse list(@RequestParam String state,
                                      @RequestParam(required = false) Integer limit,
                                      @RequestParam(required = false) String cursor,
                                      @AuthenticationPrincipal Jwt jwt) {
        return BroadcastListResponse.from(
                service.list(jwt.getSubject(), BroadcastState.fromParam(state), limit, cursor));
    }
}
