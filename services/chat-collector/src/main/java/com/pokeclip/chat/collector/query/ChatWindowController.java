package com.pokeclip.chat.collector.query;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 방송 한 구간의 채팅·후원 목록 창구(POK-234).
 *
 * <p>{@code GET /internal/streams/{streamId}/chat-messages?from&to&limit&cursor&kinds&channelId}
 *
 * <p>문은 {@code /internal/*}의 내부 토큰 필터({@code status/InternalApiConfiguration})가 잠근다 —
 * 경로만 맞추면 새 창구도 자동으로 잠긴다. 부르는 쪽은 clip뿐이고, <b>자격 판정은 clip이 한다</b>
 * (인가 재료가 여기 없다 — 수집 상태 창구와 같은 결정).
 *
 * <p><b>DB가 죽으면 500을 그대로 낸다.</b> 삼켜서 빈 목록을 주면 clip이 「그 구간에 채팅이
 * 없었다」로 읽고 되감기 화면이 조용히 빈다 — 「장애」와 「0건」은 완전히 다른 상태다.
 *
 * <p>시각 축은 {@link ChatWindowQuery} 머리의 표가 정본이다. 여기 복사하지 않는다 — 한쪽만 낡는다.
 */
@RestController
public class ChatWindowController {

    private static final Set<String> ALL_KINDS = Set.of(ChatWindowQuery.CHAT, ChatWindowQuery.DONATION);

    private final ChatWindowQuery query;
    private final QueryProperties properties;

    public ChatWindowController(ChatWindowQuery query, QueryProperties properties) {
        this.query = query;
        this.properties = properties;
    }

    /**
     * @param limit {@code String}으로 받는다 — {@code Integer}로 받으면 {@code limit=abc}에서
     *              스프링이 자기 본문으로 400을 내고 우리 사유 낱말이 안 실린다. 같은 실수인데
     *              답이 둘로 갈리면 부르는 쪽이 창구마다 다시 배운다
     */
    @GetMapping("/internal/streams/{streamId}/chat-messages")
    public ChatWindowPage get(@PathVariable String streamId,
                              @RequestParam(required = false) String from,
                              @RequestParam(required = false) String to,
                              @RequestParam(required = false) String limit,
                              @RequestParam(required = false) String cursor,
                              @RequestParam(required = false) String kinds,
                              @RequestParam(required = false) String channelId) {
        WindowRequest window = WindowRequest.parse(from, to, properties.windowMax());
        return query.find(streamId, channelId, window, parseKinds(kinds), resolveLimit(limit),
                ChatWindowCursor.decodeOrFirstPage(cursor));
    }

    /**
     * 안 주면 기본값, 상한을 넘으면 <b>잘라 준다</b>(400이 아니다 — 많이 달라는 것은 오류가
     * 아니고, 400으로 만들면 부르는 쪽이 상한을 알아야만 창구를 쓸 수 있다).
     * 0 이하와 숫자가 아닌 값은 400이다 — 「0건을 달라」는 실수 말고는 뜻이 없다.
     */
    private int resolveLimit(String raw) {
        if (raw == null || raw.isBlank()) {
            return properties.pageDefault();
        }
        int requested;
        try {
            requested = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new InvalidWindowException("limit");
        }
        if (requested <= 0) {
            throw new InvalidWindowException("limit");
        }
        return Math.min(requested, properties.pageMax());
    }

    /**
     * 안 주면 둘 다. <b>모르는 값은 400이다</b> — 조용히 버리면 {@code kinds=donaton} 오타가
     * 「후원이 하나도 없었다」로 보인다.
     */
    private static Set<String> parseKinds(String raw) {
        if (raw == null || raw.isBlank()) {
            return ALL_KINDS;
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String kind = part.trim();
            if (!ALL_KINDS.contains(kind)) {
                throw new InvalidWindowException("kinds");
            }
            wanted.add(kind);
        }
        if (wanted.isEmpty()) {
            throw new InvalidWindowException("kinds");
        }
        return wanted;
    }
}
