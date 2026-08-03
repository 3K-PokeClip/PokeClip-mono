package com.pokeclip.auth.streamkey.api;

import com.pokeclip.auth.streamkey.StreamKeyService;
import com.pokeclip.auth.streamkey.api.dto.StreamKeyStatusResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stream-keys")
@RequiredArgsConstructor
public class StreamKeyController {

    private final StreamKeyService streamKeyService;

    /**
     * 키 유무만 알려준다. 웹이 재발급 버튼을 보여줄지 정하는 데 쓴다 —
     * rotate가 키 없을 때 404라, 이것이 없으면 오류로 상태를 조회하게 된다.
     */
    @GetMapping
    public StreamKeyStatusResponse status(@AuthenticationPrincipal Jwt jwt) {
        return streamKeyService.findAlive(Long.valueOf(jwt.getSubject()))
                .map(StreamKeyStatusResponse::of)
                .orElseGet(StreamKeyStatusResponse::none);
    }
}
