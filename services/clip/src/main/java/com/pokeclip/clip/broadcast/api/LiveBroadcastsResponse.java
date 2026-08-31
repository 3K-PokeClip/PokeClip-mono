package com.pokeclip.clip.broadcast.api;

import com.pokeclip.clip.broadcast.LiveBroadcastPage;
import com.pokeclip.clip.broadcast.LiveBroadcastRow;

import java.time.Instant;
import java.util.List;

/**
 * 수집기가 받는 모양(POK-219). <b>2번(web)이 아니라 우리 서버끼리의 계약</b>이라
 * 칸 이름을 바꾸지 않는다.
 *
 * <p><b>{@code streamerId}가 문자열이다.</b> 명부의 그 칸이 문자열이고 수집기가 숫자로 읽는다
 * ({@code StreamerId.parse}). 여기서 숫자로 바꾸면 못 읽는 줄을 clip이 빼야 하는데, 그러면
 * 그 방송은 영영 안 걷히고 수집기는 그런 줄이 있었다는 것조차 모른다.
 *
 * <p><b>방송 상태를 안 싣는다</b> — 이 창구는 방송 중인 것만 주므로 항상 같은 값이다.
 *
 * <p><b>{@link LiveBroadcastRow}를 그대로 안 돌려준다.</b> 그것은 Spring Data가 만드는
 * 프록시라 직렬화 결과가 우리 손 밖이고(칸 이름이 게터에서 파생된다), 무엇보다 <b>계약이
 * 조회 쿼리의 별칭에 매달리게 된다</b> — 별칭을 고치는 순간 수집기가 읽는 칸 이름이 바뀐다.
 */
public record LiveBroadcastsResponse(List<Item> broadcasts, boolean truncated) {

    /**
     * 한 줄.
     *
     * <p>🔴 <b>{@code startedAt}이 {@code null}이면 칸을 지우지 않고 {@code null}로 싣는다</b>
     * (그래서 {@code @JsonInclude}를 안 건다). 그런 줄은 <b>운영 경로로는 도달 불가</b>이고
     * ({@code LiveStartedAtNeverNullTest}), 막는 것은 수신 러너의 봉투 검증 한 줄뿐이다.
     * 그 줄이 사라지는 날 수집기가 받는 것이 「칸이 없는 줄」이면 파싱이 통째로 깨지지만,
     * 「{@code null}인 칸」이면 그 줄 하나만 판단하면 된다.
     */
    public record Item(String streamId, String streamerId, Instant startedAt) {
    }

    public static LiveBroadcastsResponse from(LiveBroadcastPage page) {
        List<Item> items = page.rows().stream()
                .map(row -> new Item(row.getStreamId(), row.getStreamerId(), row.getStartedAt()))
                .toList();
        return new LiveBroadcastsResponse(items, page.truncated());
    }
}
