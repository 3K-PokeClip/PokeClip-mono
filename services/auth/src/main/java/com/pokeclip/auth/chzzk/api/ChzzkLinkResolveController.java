package com.pokeclip.auth.chzzk.api;

import com.pokeclip.auth.chzzk.ChzzkLinkService;
import com.pokeclip.auth.chzzk.ChzzkResolveResult;
import com.pokeclip.auth.chzzk.api.dto.ChzzkResolveRequest;
import com.pokeclip.auth.chzzk.api.dto.ChzzkResolveResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 수집기(chat-collector)가 부른다. /internal/** 체인이 X-Internal-Token을 본다 — SecurityConfig 변경 없음.
 * 항상 200 — "연동 안 됨/끊김"(수집 안 함)과 "Auth 장애"(판단 불가)는 조치가 정반대라
 * 둘 다 4xx면 구분이 안 된다(StreamKeyResolveController와 같은 이유).
 */
@RestController
@RequestMapping("/internal/chzzk-link")
@RequiredArgsConstructor
public class ChzzkLinkResolveController {

    private static final Logger log = LoggerFactory.getLogger(ChzzkLinkResolveController.class);

    private final ChzzkLinkService service;

    @PostMapping("/resolve")
    public ChzzkResolveResponse resolve(@Valid @RequestBody ChzzkResolveRequest request) {
        ChzzkResolveResult result = service.resolve(request.userId());
        if (!result.valid()) {
            // WARN이 아닌 이유는 미연동 회원의 방송 시작이 정상 트래픽이기 때문이다.
            log.info("auth.chzzk.link.resolve_rejected userId={} reason={}", request.userId(), result.reason());
        }
        return ChzzkResolveResponse.from(result);
    }
}
