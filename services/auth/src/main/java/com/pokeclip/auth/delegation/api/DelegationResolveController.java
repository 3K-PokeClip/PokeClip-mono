package com.pokeclip.auth.delegation.api;

import com.pokeclip.auth.delegation.DelegationRelation;
import com.pokeclip.auth.delegation.DelegationService;
import com.pokeclip.auth.delegation.api.dto.AccessibleStreamersRequest;
import com.pokeclip.auth.delegation.api.dto.AccessibleStreamersResponse;
import com.pokeclip.auth.delegation.api.dto.DelegationResolveRequest;
import com.pokeclip.auth.delegation.api.dto.DelegationResolveResponse;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * clip이 부른다. /internal/** 체인이 X-Internal-Token을 본다 — SecurityConfig 변경 없음
 * (StreamKeyResolveController·ChzzkLinkResolveController와 같은 문).
 *
 * <p>판정 결과는 항상 200이다. 요청 형식이 틀리면(번호 누락·숫자 아님) Spring 기본 400 —
 * 기존 두 창구와 같다.
 *
 * <p>DelegationExceptionHandler의 assignableTypes에 넣지 않는다. 그 핸들러 주석이 「새 컨트롤러는
 * 반드시 더한다」지만, 이 컨트롤러는 DelegationException을 던지는 경로가 없다(읽기 전용,
 * 예외 없는 서비스 메서드만 부른다). 넣으면 쓰기 없는 곳에 제약 위반 핸들러가 딸려 온다.
 */
@RestController
@RequestMapping("/internal/editor-delegations")
@RequiredArgsConstructor
public class DelegationResolveController {

    private static final Logger log = LoggerFactory.getLogger(DelegationResolveController.class);

    private final DelegationService service;
    private final MeterRegistry meterRegistry;

    @PostMapping("/resolve")
    public DelegationResolveResponse resolve(@Valid @RequestBody DelegationResolveRequest request) {
        DelegationRelation relation = service.relationOf(request.userId(), request.streamerUserId());
        if (relation == DelegationRelation.NONE) {
            // 회원 표를 안 읽으므로 「없는 번호가 왔다」를 모른다. NONE 자체를 세서 튀면 조사한다(PRD 결정).
            meterRegistry.counter("pokeclip.delegation.resolve.none").increment();
            // WARN이 아닌 이유는 남의 방송 링크를 열어보는 것이 정상 트래픽이기 때문이다.
            log.info("auth.delegation.resolve_none userId={} streamerUserId={}",
                    request.userId(), request.streamerUserId());
        }
        return new DelegationResolveResponse(relation);
    }

    @PostMapping("/accessible")
    public AccessibleStreamersResponse accessible(@Valid @RequestBody AccessibleStreamersRequest request) {
        return new AccessibleStreamersResponse(service.accessibleStreamers(request.userId()));
    }
}
