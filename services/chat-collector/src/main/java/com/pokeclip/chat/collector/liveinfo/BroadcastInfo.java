package com.pokeclip.chat.collector.liveinfo;

import java.time.Instant;
import java.util.List;

/**
 * 방송 정보 한 시점(POK-234).
 *
 * @param observedAt <b>우리 시계다.</b> 치지직이 관측 시각을 안 주므로 물어본 시각을 우리가 찍는다 —
 *                   {@code chat_donations.received_at}과 같은 축이고
 *                   {@code chat_messages.message_time}(치지직 시계)과는 다른 축이다
 * @param tags       빈 목록과 「모른다」를 안 가른다. 치지직이 태그 없음을 빈 배열로 준다
 * @param viewers    동시 시청자 수. <b>{@code null}이 정상값이다</b> — 전체 라이브 목록에서
 *                   그 방송을 못 찾은 회차가 있다(채널 하나를 묻는 공식 창구가 없다).
 *                   0으로 접으면 「아무도 안 봤다」가 되어 그럴듯하게 틀린다
 */
public record BroadcastInfo(String streamId, String channelId, Instant observedAt,
                            String title, List<String> tags, String category, Integer viewers) {
}
