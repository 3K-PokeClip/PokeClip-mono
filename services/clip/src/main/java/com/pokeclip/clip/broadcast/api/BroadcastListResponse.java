package com.pokeclip.clip.broadcast.api;

import com.pokeclip.clip.broadcast.Broadcast;
import com.pokeclip.clip.broadcast.BroadcastPage;
import com.pokeclip.clip.delegation.ResolveResult;

import java.time.Instant;
import java.util.List;

/**
 * 방송 목록 화면이 받는 모양. 2번(web)과의 계약이라 칸 이름을 바꾸지 않는다.
 *
 * <p><b>{@code nextCursor}는 불투명하다</b> — 웹은 이 문자열을 풀어 보거나 만들지 않고,
 * 받은 것을 그대로 되돌려 넣기만 한다. 마지막 장이면 {@code null}이다.
 */
public record BroadcastListResponse(List<Item> broadcasts, String nextCursor) {

    /**
     * 한 줄.
     *
     * <p>🔴 <b>{@code startedAt}이 {@code null}로 나갈 수 있다</b> — 종료 선도착 placeholder는
     * 시작을 못 본 줄이다(ADR-016). <b>감추거나 지어내지 않는다</b>(PRD 성공 기준). 화면은
     * 그것을 「시작 시각 미상」으로 그린다.
     *
     * <p>{@code vodExpiresAt}을 싣는 것은 <b>기한이 지난 방송도 목록에 그대로 두기</b>
     * 때문이다 — 영상은 못 봐도 방송 기록은 남고, 화면이 「보관 만료」를 그릴 재료가 필요하다.
     *
     * <p>{@code status}가 {@code String}인 것은 계약이 소문자여서다.
     * {@code BroadcastStatus}를 그대로 실으면 {@code "LIVE"}로 나간다({@code @JsonValue}가 없다).
     * <b>{@code state}로 접지 않고 실제 상태 값을 그대로 싣는다</b> — 나중에
     * {@code ended}와 {@code vod_ready}를 화면이 구분하고 싶어질 여지를 남긴다(PRD 결정).
     *
     * <p>줄 번호는 안 싣는다 — 이어받기가 표시로 끝나므로 웹이 쓸 데가 없고,
     * 방송을 가리키는 이름은 {@code streamId}다(카드 목록 문이 그 값을 받는다).
     */
    public record Item(String streamId,
                       String status,
                       ResolveResult relation,
                       Instant startedAt,
                       Instant endedAt,
                       Instant vodExpiresAt) {
    }

    public static BroadcastListResponse from(BroadcastPage page) {
        List<Item> items = page.rows().stream().map(row -> toItem(row, page)).toList();
        return new BroadcastListResponse(items, page.nextCursor());
    }

    /**
     * 관계는 못 찾을 수 없다 — 줄을 뽑은 조건이 {@code streamer_id IN (이 맵의 키 전부)}라
     * 모든 줄의 번호가 키에 있다. 그래도 {@code get}이 {@code null}을 낼 자리라 <b>그 사실을
     * 여기 적어 둔다</b>: 조회 조건과 이 맵이 <b>같은 목록에서</b> 나오는 한 성립하고,
     * 둘의 출처가 갈리는 날 이 칸이 조용히 비게 된다.
     */
    private static Item toItem(Broadcast row, BroadcastPage page) {
        return new Item(row.getStreamId(),
                row.getStatus().dbValue(),
                page.relations().get(row.getStreamerId()),
                row.getStartedAt(),
                row.getEndedAt(),
                row.getVodExpiresAt());
    }
}
