package com.pokeclip.auth.youtube.api;

import com.pokeclip.auth.youtube.YoutubeLinkService;
import com.pokeclip.auth.youtube.YoutubeResolveResult;
import com.pokeclip.auth.youtube.api.dto.YoutubeLinkCheckResponse;
import com.pokeclip.auth.youtube.api.dto.YoutubeResolveRequest;
import com.pokeclip.auth.youtube.api.dto.YoutubeResolveResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * clip·업로드 워커가 부른다. {@code /internal/**} 체인이 X-Internal-Token을 본다 — 시큐리티 변경 없음.
 * 항상 200 — "연동 안 됨/끊김"(업로드를 안 한다)과 "Auth 장애"(판단 불가)는 조치가 정반대라
 * 둘 다 4xx면 호출자가 구분할 수 없다(ChzzkLinkResolveController와 같은 이유).
 */
@RestController
@RequestMapping("/internal/youtube-link")
@RequiredArgsConstructor
public class YoutubeLinkResolveController {

    private static final Logger log = LoggerFactory.getLogger(YoutubeLinkResolveController.class);

    private final YoutubeLinkService service;

    @PostMapping("/resolve")
    public YoutubeResolveResponse resolve(@Valid @RequestBody YoutubeResolveRequest request) {
        YoutubeResolveResult result = service.resolve(request.userId());
        if (!result.valid()) {
            // WARN이 아닌 이유는 미연동 회원의 클립 생성이 정상 트래픽이기 때문이다 — 건수 알람을 걸 자리가 아니다.
            log.info("auth.youtube.link.resolve_rejected userId={} reason={}", request.userId(), result.reason());
        }
        return YoutubeResolveResponse.from(result);
    }

    /**
     * clip이 렌더 주문 전에 「이 스트리머가 유튜브를 연결했나」만 묻는다(POK-291). 판정은 resolve와 같고
     * <b>토큰을 돌려주지 않고 갱신도 하지 않는다.</b> 늘 200인 이유도 resolve와 같다.
     * 거절 로그를 안 남기는 이유: 렌더 주문마다 불려 미연동 스트리머면 같은 줄이 계속 쌓인다.
     */
    @PostMapping("/status")
    public YoutubeLinkCheckResponse status(@Valid @RequestBody YoutubeResolveRequest request) {
        return YoutubeLinkCheckResponse.from(service.check(request.userId()));
    }
}
