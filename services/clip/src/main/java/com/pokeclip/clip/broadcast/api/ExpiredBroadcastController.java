package com.pokeclip.clip.broadcast.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pokeclip.clip.broadcast.ExpiredBroadcastRow;
import com.pokeclip.clip.broadcast.ExpiredBroadcastService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * 보관 기한(60일)이 지난 방송 목록을 1번(영상 서버)에게 주는 문(POK-335, 계약-세그먼트인덱스 6-2). 1번이 10분마다 부른다.
 *
 * <p>방송 중 목록 창구(POK-218)와 같은 자리다: 자격 판정이 없고 이 파일에 시큐리티 설정이 없다.
 * 문은 둘이다 — 공용 {@code X-Internal-Token}은 {@code InternalSecurityConfig}가 {@code /internal/**}
 * 접두로 받고, 1번 전용 헤더 {@code X-Expired-Broadcasts-Token}은
 * {@code ExpiredBroadcastSecurityConfig}가 출발지 잠금과 함께 받는다(POK-196 M6).
 * 400은 {@code before} · {@code cursor} 형식뿐이다.
 */
@RestController
public class ExpiredBroadcastController {

    private final ExpiredBroadcastService service;

    ExpiredBroadcastController(ExpiredBroadcastService service) {
        this.service = service;
    }

    @GetMapping("/internal/broadcasts/expired")
    public ExpiredBroadcastsResponse expired(@RequestParam(required = false) String before,
                                             @RequestParam(required = false) String cursor) {
        ExpiredBroadcastService.ExpiredBroadcastPage page = service.list(before, cursor);
        return new ExpiredBroadcastsResponse(page.rows().stream().map(Item::from).toList(), page.nextCursor(),
                page.hasMore());
    }

    /**
     * {@code nextCursor}는 이 장 마지막 줄의 자리라 1번이 저장해 두고 다음 폴링에 넘긴다(빈 장이면 받은 표시 그대로, 첫 장이
     * 비었으면 null). {@code hasMore}가 참이면 지금 바로 그 표시로 이어 부른다. 칸은 늘 있다.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ExpiredBroadcastsResponse(List<Item> broadcasts, String nextCursor, boolean hasMore) {
    }

    /**
     * 칸 일곱. {@code segmentsFrom}이 null이면 처음부터다. 구간은 [segmentsFrom, segmentsUntil)이고 조각 장부의 벽시계 칸으로 잰다.
     * 시작 시각을 모르는 방송(자리표시)은 {@code startedAt}이 null이다.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Item(String streamId, String ingestStreamId, Instant startedAt, Instant endedAt, Instant vodExpiresAt,
                       Instant segmentsFrom, Instant segmentsUntil) {

        static Item from(ExpiredBroadcastRow row) {
            return new Item(row.streamId(), row.ingestStreamId(), row.startedAt(), row.endedAt(), row.vodExpiresAt(),
                    row.segmentsFrom(), row.segmentsUntil());
        }
    }
}
