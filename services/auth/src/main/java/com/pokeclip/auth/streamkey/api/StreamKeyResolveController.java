package com.pokeclip.auth.streamkey.api;

import com.pokeclip.auth.streamkey.StreamKeyService;
import com.pokeclip.auth.streamkey.StreamKeyService.ResolveResult;
import com.pokeclip.auth.streamkey.api.dto.ResolveRequest;
import com.pokeclip.auth.streamkey.api.dto.ResolveResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/stream-keys")
@RequiredArgsConstructor
public class StreamKeyResolveController {

    private static final Logger log = LoggerFactory.getLogger(StreamKeyResolveController.class);

    private final StreamKeyService streamKeyService;

    @PostMapping("/resolve")
    public ResolveResponse resolve(@Valid @RequestBody ResolveRequest request) {
        ResolveResult result = streamKeyService.resolve(request.streamid());

        if (!result.valid()) {
            // userId가 없다 — 없는 키라 붙일 것이 없다. streamid도 안 찍는다.
            // WARN이 아닌 이유는 Media 재시도가 정상 트래픽이기 때문이다.
            log.info("auth.streamkey.resolve_rejected reason={}", result.reason());
        }
        return ResolveResponse.from(result);
    }
}
