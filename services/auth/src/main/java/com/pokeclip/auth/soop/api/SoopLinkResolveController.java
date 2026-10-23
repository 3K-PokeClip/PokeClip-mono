package com.pokeclip.auth.soop.api;

import com.pokeclip.auth.soop.SoopLinkService;
import com.pokeclip.auth.soop.SoopResolveResult;
import com.pokeclip.auth.soop.api.dto.SoopResolveRequest;
import com.pokeclip.auth.soop.api.dto.SoopResolveResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 수집기(chat-collector, POK-397)가 부른다. {@code /internal/**} 체인이 X-Internal-Token을 본다(설정 변경 없음).
 * 항상 200: 「연동 안 됨·끊김」(수집 안 함)과 「auth 장애」(판단 불가)는 조치가 정반대라 둘 다 4xx면 못 가른다
 * (치지직 {@code ChzzkLinkResolveController}와 같은 이유).
 */
@RestController
@RequestMapping("/internal/soop-link")
@RequiredArgsConstructor
public class SoopLinkResolveController {

    private static final Logger log = LoggerFactory.getLogger(SoopLinkResolveController.class);

    private final SoopLinkService service;

    @PostMapping("/resolve")
    public SoopResolveResponse resolve(@Valid @RequestBody SoopResolveRequest request) {
        SoopResolveResult result = service.resolve(request.userId());
        if (!result.valid()) {
            // WARN이 아닌 이유: 미연동 회원의 방송 시작은 정상 트래픽이다.
            log.info("auth.soop.link.resolve_rejected userId={} reason={}", request.userId(), result.reason());
        }
        return SoopResolveResponse.from(result);
    }
}
