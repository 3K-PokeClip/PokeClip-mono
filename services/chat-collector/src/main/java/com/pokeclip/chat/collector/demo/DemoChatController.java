package com.pokeclip.chat.collector.demo;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 발표 데모의 「채팅 이벤트」 창구. clip만 부른다(내부 토큰). 데모 계정인지 · 그 방송을 볼 자격이 있는지는
 * clip이 판정한 뒤에 온다.
 *
 * <p>이미 흐르는 중이면 409가 아니라 200 {@code started:false}다. clip이 수집기의 400 말고 4xx를 전부
 * 「수집기가 아프다」(503)로 접기 때문이다({@code CollectorClient}).
 */
@RestController
public class DemoChatController {

    private final DemoChatBurst burst;

    public DemoChatController(DemoChatBurst burst) {
        this.burst = burst;
    }

    @PostMapping("/internal/streams/{streamId}/demo-chat-burst")
    public ResponseEntity<Map<String, Object>> start(@PathVariable String streamId) {
        if (burst.start(streamId)) {
            return ResponseEntity.accepted().body(Map.of("started", true));
        }
        return ResponseEntity.ok(Map.of("started", false));
    }
}
