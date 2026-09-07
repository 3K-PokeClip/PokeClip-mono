package com.pokeclip.clip.broadcast.api;

import com.pokeclip.clip.collector.CollectorClient;
import com.pokeclip.clip.collector.CollectorResponse;
import com.pokeclip.clip.delegation.BroadcastAccessGuard;
import com.pokeclip.clip.support.NotFoundFloor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 되감기 화면이 채팅을 읽는 문 셋. 수집기(chat-collector)의 같은 이름 창구로 넘기고
 * <b>상태와 본문을 그대로</b> 돌려준다.
 *
 * <p><b>여기서 본문을 조립하지 않는다.</b> 수집기가 칸을 늘릴 때마다 clip을 고쳐야 하는 구조를
 * 만들지 않으려는 것이고, 오류 본문도 마찬가지다 — 400의 사유 낱말({@code inverted_window}·
 * {@code bucket}…)은 수집기가 정본이다.
 *
 * <p><b>이 문이 하는 일은 셋이다</b> — 404의 기준 시각을 찍고, 자격을 판정하고, 넘겨도 되는
 * 쿼리만 골라 넘긴다.
 *
 * <p>🔴 <b>순서가 계약이다: {@code mark} → {@code requireViewable} → {@code collector.get}.</b>
 * 판정을 수집기 호출보다 뒤로 옮기면 두 가지가 한꺼번에 무너진다 — 남남이 남의 방송 채팅을
 * 읽고, 아래 「경로 탈출」 방어도 같이 사라진다.
 */
@RestController
@RequestMapping("/api/clip/broadcasts/{streamId}")
public class BroadcastChatController {

    /**
     * 🔴 <b>브라우저가 준 쿼리를 통째로 넘기지 않는다.</b> 통째로 넘기면 프론트가
     * {@code channelId}를 정하게 되는데, 그 값은 수집기에서 <b>시차 보정값을 고르는 열쇠</b>다
     * (채널별 덮어쓰기가 생기는 날 「그럴듯하게 틀린 시각」이 열린다 — 지금은 덮어쓰기가
     * 0개라 아직 안 열려 있고, 그래서 <b>지금 막아 두는 것</b>이 싸다).
     * 수집기가 채널을 알아야 하면 자기 세션 정보에서 찾는다.
     *
     * <p>문마다 다른 이유는 창구마다 받는 칸이 다르기 때문이다 — 한 목록으로 합치면 차트에
     * {@code cursor}를, 목록에 {@code bucket}을 넘기게 되고 수집기가 400으로 답한다.
     */
    private static final Set<String> 채팅_목록_허용 = Set.of("from", "to", "limit", "cursor", "kinds");

    private static final Set<String> 차트_허용 = Set.of("from", "to", "bucket");

    private static final Set<String> 방송_정보_허용 = Set.of("since");

    private final BroadcastAccessGuard guard;
    private final CollectorClient collector;

    BroadcastChatController(BroadcastAccessGuard guard, CollectorClient collector) {
        this.guard = guard;
        this.collector = collector;
    }

    @GetMapping("/chat-messages")
    ResponseEntity<String> chatMessages(@PathVariable String streamId,
                                        @RequestParam MultiValueMap<String, String> query,
                                        @AuthenticationPrincipal Jwt jwt,
                                        HttpServletRequest request) {
        return 넘긴다(streamId, "chat-messages", 채팅_목록_허용, query, jwt, request);
    }

    @GetMapping("/chat-chart")
    ResponseEntity<String> chatChart(@PathVariable String streamId,
                                     @RequestParam MultiValueMap<String, String> query,
                                     @AuthenticationPrincipal Jwt jwt,
                                     HttpServletRequest request) {
        return 넘긴다(streamId, "chat-chart", 차트_허용, query, jwt, request);
    }

    @GetMapping("/broadcast-info")
    ResponseEntity<String> broadcastInfo(@PathVariable String streamId,
                                         @RequestParam MultiValueMap<String, String> query,
                                         @AuthenticationPrincipal Jwt jwt,
                                         HttpServletRequest request) {
        return 넘긴다(streamId, "broadcast-info", 방송_정보_허용, query, jwt, request);
    }

    private ResponseEntity<String> 넘긴다(String streamId, String 창구, Set<String> 허용,
                                       MultiValueMap<String, String> query, Jwt jwt,
                                       HttpServletRequest request) {
        // 404 두 갈래(「없는 방송」·「자격 없음」)가 갈리기 전에 기준 시각을 찍는다 — NotFoundFloor.
        NotFoundFloor.mark(request);

        // 🔴 판정이 먼저다. 이 줄이 경로 탈출도 같이 막는다.
        //
        // 아래 조립은 막지 않는다 — DefaultUriBuilderFactory.path는 공백만 인코딩하고
        // `/`·`..`는 글자 그대로 통과시킨다(실측). 실제로 막는 것은 이 판정이다:
        // broadcasts 명부에 있는 방송 번호만 통과하므로 그런 값은 여기서 404로 끝난다.
        // 그래서 이 줄을 수집기 호출 뒤로 옮기면 그 방어가 사라진다.
        guard.requireViewable(jwt.getSubject(), streamId);

        return 그대로(collector.get("/internal/streams/" + streamId + "/" + 창구, 고른다(허용, query)));
    }

    /**
     * 같은 칸이 여러 번 오면 <b>첫 값만</b> 넘긴다. 뒤엣것을 쓰면 브라우저가
     * {@code ?limit=10&limit=99999}로 앞 값을 덮을 수 있고, 어느 쪽이 쓰이는지가
     * 프레임워크 구현에 달리게 된다.
     */
    private static Map<String, String> 고른다(Set<String> 허용, MultiValueMap<String, String> query) {
        Map<String, String> 넘길_것 = new LinkedHashMap<>();
        query.forEach((name, values) -> {
            if (허용.contains(name) && !values.isEmpty() && values.get(0) != null) {
                넘길_것.put(name, values.get(0));
            }
        });
        return 넘길_것;
    }

    /**
     * <b>Content-Type을 명시한다.</b> 안 하면 문자열 본문이 {@code text/plain}으로 나가고
     * 브라우저가 JSON으로 안 읽는다.
     */
    private static ResponseEntity<String> 그대로(CollectorResponse response) {
        return ResponseEntity.status(response.status())
                .contentType(MediaType.APPLICATION_JSON).body(response.body());
    }
}
