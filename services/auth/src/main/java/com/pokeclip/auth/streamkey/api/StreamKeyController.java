package com.pokeclip.auth.streamkey.api;

import com.pokeclip.auth.streamkey.StreamKeyService;
import com.pokeclip.auth.streamkey.api.dto.RotateResponse;
import com.pokeclip.auth.streamkey.api.dto.StreamKeyStatusResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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

    /**
     * 유출 대응 수단이다. 계정당 키가 하나라 기기별 해제 같은 선택지가 없고,
     * 이것이 스트리머가 스스로 막을 수 있는 유일한 방법이다.
     */
    @PostMapping("/rotate")
    public RotateResponse rotate(@AuthenticationPrincipal Jwt jwt) {
        return new RotateResponse(streamKeyService.rotate(Long.valueOf(jwt.getSubject())));
    }
}
