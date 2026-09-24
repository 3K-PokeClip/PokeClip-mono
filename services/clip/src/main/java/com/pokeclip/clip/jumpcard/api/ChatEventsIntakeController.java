package com.pokeclip.clip.jumpcard.api;

import com.pokeclip.clip.broadcast.BroadcastRepository;
import com.pokeclip.clip.jumpcard.JumpCardErrors.BroadcastNotFoundException;
import com.pokeclip.clip.jumpcard.stream.CardStreamRegistry;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 수집기가 받은 채팅·후원을 <b>저장과 따로</b> 바로 미는 문(POK-234 PR-B). {@code /internal/**}이라
 * {@code X-Internal-Token}으로만 들어온다. 받은 것은 그 방송의 SSE 연결에 뿌리고 <b>저장하지 않는다</b> —
 * 정본은 수집기의 표이고 놓친 구간은 화면이 수집기 범위 창구로 메운다.
 *
 * <p><b>순서가 계약이다</b>: 구조 검증(400) → 그 방송에 연결이 없으면 <b>DB를 안 치고</b> 200 {@code 0/0}(F11) →
 * 연결이 있는데 명부에 방송이 없으면 404 {@code broadcast_not_found} → 뿌림. 수집기는 묶음마다 부르므로
 * 연결 없는 방송(아무도 안 보는 방송)마다 DB를 치면 채팅 유량이 곧 clip의 DB 부하가 된다.
 * 그래서 수집기의 404(UNKNOWN_BROADCAST) 셈은 「보는 사람이 있는데 방송이 없다」로 좁다.
 *
 * @return {@code accepted}는 연결에 뿌리려고 넣은 이벤트 수(아는 kind), {@code dropped}는 모르는 kind라
 *         건너뛴 수. 연결 큐가 차서 버린 것은 여기 안 실린다 — 연결마다 다르므로 clip 쪽 셈이다
 */
@RestController
public class ChatEventsIntakeController {

    private final CardStreamRegistry streams;
    private final BroadcastRepository broadcasts;

    ChatEventsIntakeController(CardStreamRegistry streams, BroadcastRepository broadcasts) {
        this.streams = streams;
        this.broadcasts = broadcasts;
    }

    @PostMapping("/internal/broadcasts/{streamId}/chat-events")
    public Map<String, Integer> receive(@PathVariable String streamId, @RequestBody JsonNode body) {
        ChatEventsRequest request = ChatEventsRequest.parse(body);
        if (!streams.hasConnections(streamId)) {
            return Map.of("accepted", 0, "dropped", 0);
        }
        if (!broadcasts.existsByStreamId(streamId)) {
            throw new BroadcastNotFoundException(streamId);
        }
        CardStreamRegistry.ChatPublishResult result = streams.publishChatEvents(streamId, request.events());
        return Map.of("accepted", result.accepted(), "dropped", result.dropped());
    }
}
