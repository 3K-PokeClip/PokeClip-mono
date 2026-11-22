package com.pokeclip.clip.demo;

import com.pokeclip.clip.collector.CollectorClient;
import com.pokeclip.clip.collector.CollectorResponse;
import com.pokeclip.clip.delegation.BroadcastAccessGuard;
import com.pokeclip.clip.support.NotFoundFloor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 발표 데모의 「채팅 이벤트」 문. 단축키를 누르면 수집기가 그 방송에 임시 시청자 채팅을 몰아 넣는다
 * (수집기 {@code DemoChatBurst}). 운영 사이트에 들어가므로 둘을 막는다.
 * <ul>
 *   <li><b>데모 계정만</b>({@link DemoProperties}). 목록 밖이면 문이 없는 것처럼 빈 404다. 화면은
 *       {@code GET /api/clip/demo/chat-event}가 204일 때만 단축키를 붙인다
 *   <li><b>그 방송을 볼 자격</b>(스트리머 본인 · 편집자). 데모 계정이어도 남의 방송에는 못 넣는다
 * </ul>
 */
@RestController
public class DemoChatEventController {

    private final DemoProperties demo;
    private final BroadcastAccessGuard guard;
    private final CollectorClient collector;

    DemoChatEventController(DemoProperties demo, BroadcastAccessGuard guard, CollectorClient collector) {
        this.demo = demo;
        this.guard = guard;
        this.collector = collector;
    }

    @GetMapping("/api/clip/demo/chat-event")
    ResponseEntity<Void> available(@AuthenticationPrincipal Jwt jwt) {
        return demo.allows(jwt.getSubject()) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /**
     * 🔴 순서: 데모 계정 → 볼 자격 → 수집기. 자격 판정이 경로 탈출도 막는다({@code BroadcastChatController}와 같은 이유):
     * broadcasts 명부에 있는 방송 번호만 수집기 경로에 들어간다.
     */
    @PostMapping("/api/clip/broadcasts/{streamId}/demo/chat-event")
    ResponseEntity<String> start(@PathVariable String streamId, @AuthenticationPrincipal Jwt jwt,
                                 HttpServletRequest request) {
        if (!demo.allows(jwt.getSubject())) {
            return ResponseEntity.notFound().build();
        }
        NotFoundFloor.mark(request);
        guard.requireViewable(jwt.getSubject(), streamId);
        CollectorResponse response = collector.postJson("/internal/streams/" + streamId + "/demo-chat-burst", "{}");
        return ResponseEntity.status(response.status()).contentType(MediaType.APPLICATION_JSON).body(response.body());
    }
}
