package com.pokeclip.clip.broadcast.api;

import com.pokeclip.clip.broadcast.LiveBroadcastService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 수집기가 재시작한 뒤 「지금 어느 방송에 붙어야 하나」를 묻는 문(POK-218).
 *
 * <p><b>자격 판정이 없다</b> — 부르는 쪽이 사람이 아니라 우리 서버다. 여기에 판정을 붙이면
 * 없는 회원 번호로 auth를 두드리게 된다. 감출 상대가 없으므로 404 바닥({@code NotFoundFloor})도
 * 안 문다({@code HighlightIntakeController}와 같은 자리).
 *
 * <p><b>요청 칸이 하나도 없다.</b> 그래서 400이 없고 거절은 401 하나다.
 * 시큐리티는 {@code InternalSecurityConfig}가 {@code /internal/**} 접두로 가져가므로
 * <b>이 파일에 설정이 없다.</b>
 *
 * <p>상한·잘림·잘림 로그는 {@link LiveBroadcastService}가 정한다 — 컨트롤러로 끌어오면
 * 그 서비스를 직접 부르는 소비자가 생기는 날 그 경로만 무방비가 된다
 * ({@code BroadcastListController}와 같은 규칙).
 */
@RestController
public class LiveBroadcastController {

    private final LiveBroadcastService service;

    LiveBroadcastController(LiveBroadcastService service) {
        this.service = service;
    }

    @GetMapping("/internal/broadcasts/live")
    public LiveBroadcastsResponse live() {
        return LiveBroadcastsResponse.from(service.list());
    }
}
