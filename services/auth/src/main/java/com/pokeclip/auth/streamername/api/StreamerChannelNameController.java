package com.pokeclip.auth.streamername.api;

import com.pokeclip.auth.streamername.StreamerChannelNameService;
import com.pokeclip.auth.streamername.api.dto.ChannelNamesRequest;
import com.pokeclip.auth.streamername.api.dto.ChannelNamesResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * clip이 방송 목록 한 장을 만들 때 부른다(POK-430). /internal/** 체인이 X-Internal-Token을 본다:
 * SecurityConfig 변경 없음(DelegationResolveController와 같은 문).
 *
 * <p>요청 형식이 틀리면(목록 없음 · null 섞임 · 100개 초과) Spring 기본 400이다. 이름은 로그에 싣지 않는다
 * (SOOP 닉네임은 약관 제11조 ⑰의 파기 대상이다).
 */
@RestController
@RequestMapping("/internal/streamer-channel-names")
@RequiredArgsConstructor
public class StreamerChannelNameController {

    private final StreamerChannelNameService service;

    @PostMapping
    public ChannelNamesResponse names(@Valid @RequestBody ChannelNamesRequest request) {
        return new ChannelNamesResponse(service.namesOf(request.streamerUserIds()));
    }
}
