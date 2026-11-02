package com.pokeclip.chat.collector.status;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 방송 하나의 수집 상태 창구(POK-128). <b>이 서버의 첫 HTTP 컨트롤러다.</b>
 *
 * <p>부르는 쪽은 clip뿐이다 — 웹은 직접 안 부른다(인가 재료가 여기 없다). 그래서 문은
 * {@code /internal/*}의 내부 토큰 필터({@link InternalApiConfiguration})가 잠근다.
 * {@code /actuator/health}와 경로가 갈려 있어야 한다 — 그쪽은 재연결 중 DOWN이 정상이라
 * liveness에 걸면 안 되는 물건이고(ADR-035), 이쪽은 방송 하나의 상태다.
 *
 * <p><b>항상 200.</b> 모르는 방송도 {@code unknown}으로 답한다 — 404면 clip이 「그런 방송 없음」과
 * 「수집 서버 장애」를 못 가른다(auth 계약4C와 같은 이유).
 */
@RestController
public class ChatCollectionStatusController {

    private final ChatCollectionStatusResolver resolver;

    public ChatCollectionStatusController(ChatCollectionStatusResolver resolver) {
        this.resolver = resolver;
    }

    @GetMapping("/internal/streams/{streamId}/chat-collection")
    public ChatCollectionStatus get(@PathVariable String streamId) {
        return resolver.resolve(streamId);
    }
}
