package com.pokeclip.clip.jumpcard.api;

import com.pokeclip.clip.jumpcard.JumpCardService;
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
 * 방송 화면을 열 때 카드를 받아 가는 문.
 *
 * <p><b>거절 판정이 여기 하나도 없다</b>({@code SegmentController}·{@code BroadcastListController}와
 * 같은 규칙) — 자격도 개수 상한도 {@link JumpCardService}가 정한다. 컨트롤러로 끌어오면 그 서비스를
 * 직접 부르는 소비자가 생기는 날 그 경로만 무방비가 된다.
 *
 * <p>그래서 이 클래스가 하는 일은 둘뿐이다 — 파라미터를 넘기고, <b>404가 언제 나갈지의 기준 시각을
 * 찍는다</b>({@link NotFoundFloor}). 둘째는 판정이 아니라 시계다.
 *
 * <p>사용자 번호는 <b>토큰의 subject에서만</b> 온다 — 쿼리로 받으면 남의 번호로 남의 방송을 연다.
 *
 * <p>{@code limit}이 {@code Integer}(박스형)인 것은 <b>「안 줬다」와 「0을 줬다」를 갈라야</b> 하기
 * 때문이다. {@code includeHidden}은 반대로 기본값이 있어도 잃을 구분이 없다 — 안 주면 「빼 달라」다.
 */
@RestController
@RequestMapping("/api/clip/broadcasts/{streamId}/jump-cards")
public class JumpCardListController {

    private final JumpCardService service;

    JumpCardListController(JumpCardService service) {
        this.service = service;
    }

    @GetMapping
    public JumpCardListResponse list(@PathVariable String streamId,
                                     @RequestParam(defaultValue = "false") boolean includeHidden,
                                     @RequestParam(required = false) Integer limit,
                                     @RequestParam(required = false) String cursor,
                                     @AuthenticationPrincipal Jwt jwt,
                                     HttpServletRequest request) {
        // 404 두 갈래가 갈리기 전에 기준 시각을 찍는다. 갈림은 아래 호출 안(자격 판정)에서
        // 시작하므로 이 줄이 「둘 다 여기서 출발했다」를 보증한다 — 자세한 근거는 NotFoundFloor.
        //
        // 서비스 안에서 안 찍는 이유는 그 자리에 HttpServletRequest가 없기 때문이고,
        // 더 근본적으로는 감출 상대가 있는 것이 <b>사람 문</b>뿐이기 때문이다.
        NotFoundFloor.mark(request);
        return JumpCardListResponse.from(
                service.listOf(jwt.getSubject(), streamId, includeHidden, limit, cursor));
    }
}
