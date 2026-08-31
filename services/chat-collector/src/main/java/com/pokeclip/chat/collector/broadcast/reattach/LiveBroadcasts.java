package com.pokeclip.chat.collector.broadcast.reattach;

import java.time.Instant;
import java.util.List;

/**
 * clip의 {@code GET /internal/broadcasts/live} 응답. 정본은 clip의
 * {@code broadcast/api/LiveBroadcastsResponse}이고 <b>칸 이름을 마음대로 바꾸지 않는다</b> —
 * 2번(web)이 아니라 우리 서버끼리의 계약이다.
 *
 * <p><b>{@code streamerId}가 문자열이다.</b> 명부의 그 칸이 문자열이고 숫자로 못 읽는 줄도
 * 온다 — clip이 그런 줄을 빼면 그 방송은 영영 안 걷히고 우리는 그런 줄이 있었다는 것조차
 * 모른다. 숫자로 읽는 것은 {@code StreamerId.parse}의 몫이고, 못 읽으면 세어서 드러낸다.
 *
 * <p><b>{@code startedAt}은 {@code null}일 수 있다.</b> clip이 그 칸을 지우지 않고
 * {@code null}로 싣는다(그쪽 record 주석이 못박았다). 그런 줄을 버리지 않는다.
 *
 * <p>{@code truncated}는 clip이 상한(500)에서 잘랐다는 뜻이다. <b>개수 제한이 아니라
 * 「명부가 이상하다」의 눈금이다</b> — 종료 알림을 놓친 방송이 영원히 {@code live}로 남고
 * 치우는 장치가 없다(POK-218이 찾아 clip 쪽 별도 카드가 났다).
 */
public record LiveBroadcasts(List<Item> broadcasts, boolean truncated) {

    public record Item(String streamId, String streamerId, Instant startedAt) {
    }
}
