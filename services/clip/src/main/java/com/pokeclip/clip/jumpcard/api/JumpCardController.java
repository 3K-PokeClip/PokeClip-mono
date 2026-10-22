package com.pokeclip.clip.jumpcard.api;

import com.pokeclip.clip.jumpcard.JumpCardService;
import com.pokeclip.clip.support.NotFoundFloor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 편집자가 카드에 쓰는 문. 집기·놓기·숨기기·되돌리기는 POK-393에서 없앴고 열기 하나가 남았다.
 *
 * <p>사용자 번호는 <b>토큰의 subject에서만</b> 온다: 본문이나 쿼리로 받으면 남의 번호로 기록된다.
 * 편집자 <b>이름</b>은 응답에 안 넣는다(POK-175 — 이름표는 auth가 갖고 있고 물어볼 창구가 없다).
 *
 * <p><b>거절 판정이 여기 하나도 없다</b>({@code JumpCardListController}와 같은 규칙) — 자격은
 * {@link JumpCardService}가 정한다. 이 클래스가 하는 일은 파라미터를 넘기는 것과
 * <b>404가 언제 나갈지의 기준 시각을 찍는 것</b>({@link NotFoundFloor})뿐이고, 둘째는 판정이 아니라 시계다.
 */
@RestController
@RequestMapping("/api/clip/jump-cards/{id}")
public class JumpCardController {

    private final JumpCardService service;

    JumpCardController(JumpCardService service) {
        this.service = service;
    }

    /**
     * 편집기가 카드를 열었다(POK-393). 행동 기록에 한 줄을 남기고 본문 없이 204.
     *
     * <p>404 두 갈래가 갈리기 <b>전</b>에 기준 시각을 찍는다: 「없는 카드」는 DB 조회 하나로 끝나고
     * 「볼 자격이 없다」는 auth 왕복을 태운다. 갈림은 아래 호출 안(자격 판정)에서 시작하므로
     * 이 줄이 「둘 다 여기서 출발했다」를 보증한다. 빠지면 이 문만 조용히 안 늦는다:
     * {@code awaitFloorIfMarked}는 기준이 없으면 즉시 내보낸다. 자세한 근거는 {@link NotFoundFloor}.
     */
    @PostMapping("/open")
    public ResponseEntity<Void> open(@PathVariable long id, @AuthenticationPrincipal Jwt jwt,
                                     HttpServletRequest request) {
        NotFoundFloor.mark(request);
        service.open(id, jwt.getSubject());
        return ResponseEntity.noContent().build();
    }
}
